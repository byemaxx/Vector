package org.matrix.vector.daemon.ipc

import android.os.Build
import android.os.IBinder
import android.os.IServiceCallback
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.VectorService
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.ReinjectionOwnerStore
import org.matrix.vector.daemon.ReinjectionLease
import org.matrix.vector.daemon.LeaseState
import org.matrix.vector.daemon.OwnerLiveness
import org.matrix.vector.daemon.OwnerStatus
import org.matrix.vector.daemon.system.getSystemServiceManager

private const val TAG = "VectorRecoveryCoord"
private const val RECOVERY_TIMEOUT_MS = 90_000L
private const val ATTACH_TIMEOUT_MS = 20_000L
private const val MAX_FAILED_RECOVERY_RETRIES = 5
private const val ACTION_SEND_BINDER = 1

enum class RecoveryState {
    IDLE,
    ACQUIRING,
    RECOVERING,
    WAITING_FOR_SYSTEM_SERVER,
    INJECTING,
    WAITING_FOR_ATTACH,
    ATTACHED,
    FAILED
}

private enum class FailureKind {
    ACTIVITY_UNAVAILABLE,
    ATTACH_TIMEOUT,
    INJECTION_FAILED,
    PERMANENT,
}

class RecoveryExpiredException(message: String) : Exception(message)
class LeaseUnavailableException(message: String) : Exception(message)
class InjectionFailedException(message: String) : Exception(message)

class RecoveryContext(
    val generation: Long,
    val deadlineElapsedRealtime: Long
) {
    fun throwIfExpired(stage: String) {
        if (SystemClock.elapsedRealtime() > deadlineElapsedRealtime) {
            throw RecoveryExpiredException("Recovery generation $generation expired at stage $stage")
        }
    }
}

/**
 * Serialises system_server injection and recovery behind a single-thread executor.
 *
 * Every state transition runs on [recoveryExecutor]. External events - death callbacks,
 * attach notifications - post into that executor rather than touching state directly.
 *
 * The two-phase lease protocol ensures that the reinjection_lease.lock is never held
 * across the wait for system_server to become available:
 *
 *   Phase A: acquire lease -> write owner -> cleanup -> release lease
 *   (wait for bridge service)
 *   Phase B: acquire lease -> validate owner -> transact -> release lease
 */
object SystemServerRecoveryCoordinator {
    private val mainHandler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    // All state mutations happen on this single thread.
    private val recoveryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vector-recovery").apply { isDaemon = true }
    }

    // Timeout scheduling - separate from recoveryExecutor so it cannot be blocked.
    private val timeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "vector-recovery-timeout").apply { isDaemon = true }
        }

    @Volatile
    private var state: RecoveryState = RecoveryState.IDLE

    private var recoveryGeneration = 0L
    private var currentContext: RecoveryContext? = null
    private var retryWatchGeneration = -1L
    private var activityCallbackRegistered = false
    private var failureEpisodeActive = false
    private var failureEpisodeRetries = 0
    private var failureEpisodePolls = 0
    private var currentFailureKind: FailureKind? = null
    private var retryScheduledOrRunning = false
    private var pendingAttachedPid = -1
    private var pendingAttachGeneration = -1L
    private var phaseBWasInitial = false
    private var expectedPrimaryRestartGeneration = -1L

    private val activityServiceCallback = object : IServiceCallback.Stub() {
        override fun onRegistration(name: String, binder: IBinder?) {
            if (name == VectorDaemon.bridgeServiceName && binder?.pingBinder() == true) {
                recoveryExecutor.execute { handleActivityServiceAvailable() }
            }
        }

        override fun asBinder(): IBinder = this
    }

    fun getState(): RecoveryState = state

    /** Must only be called on recoveryExecutor. */
    private fun setState(newState: RecoveryState) {
        val oldState = state
        state = newState
        Log.i(TAG, "Recovery state: $oldState -> $newState (gen=${currentContext?.generation})")
    }

    // -- Public entry points --------------------------------------------------

    /**
     * Called once from VectorDaemon.main() on the main thread.
     * Posts work to the recovery executor.
     */
    fun requestInitialInjection() {
        recoveryExecutor.execute {
            val gen = ++recoveryGeneration
            val deadline = SystemClock.elapsedRealtime() + RECOVERY_TIMEOUT_MS
            val ctx = RecoveryContext(gen, deadline)
            currentContext = ctx

            Log.i(TAG, "Starting initial injection (gen=$gen)")
            try {
                executeInitialInjection(ctx)
            } catch (e: RecoveryExpiredException) {
                Log.e(TAG, "Initial injection timed out", e)
                failRecovery(ctx, classifyExpiry(e))
            } catch (e: Exception) {
                Log.e(TAG, "Initial injection failed", e)
                failRecovery(ctx, FailureKind.PERMANENT)
            }
        }
    }

    /**
     * Called when system_server dies. Posts to recoveryExecutor with state gate.
     *
     * If a recovery is already in progress the request is merged (ignored).
     * Generation is only incremented inside the executor.
     */
    fun requestRecovery(reason: String, sourceGeneration: Long? = null) {
        recoveryExecutor.execute {
            val currentGeneration = currentContext?.generation ?: -1L
            if (sourceGeneration != null && sourceGeneration < currentGeneration) {
                Log.i(TAG, "Ignoring stale bridge death from generation $sourceGeneration")
                return@execute
            }
            if (failureEpisodeActive &&
                retryScheduledOrRunning &&
                sourceGeneration == expectedPrimaryRestartGeneration) {
                Log.i(TAG, "Ignoring expected bridge death during controlled primary restart")
                return@execute
            }
            when (state) {
                RecoveryState.IDLE,
                RecoveryState.ATTACHED -> startNewRecovery(reason)

                RecoveryState.FAILED -> startNewRecovery(reason)

                // Recovery already in progress - merge / ignore.
                RecoveryState.ACQUIRING,
                RecoveryState.RECOVERING,
                RecoveryState.WAITING_FOR_SYSTEM_SERVER,
                RecoveryState.INJECTING,
                RecoveryState.WAITING_FOR_ATTACH -> {
                    Log.i(TAG, "Recovery in progress (state=$state), ignoring: $reason")
                }
            }
        }
    }

    /**
     * Called from SystemServerService.attachProcess() when system_server registers.
     * Posts to recoveryExecutor so it is never blocked by polling.
     */
    fun onSystemServerAttached(pid: Int) {
        recoveryExecutor.execute {
            if (!SystemServerService.systemServerRequested ||
                SystemServerService.currentAttachedSystemServerPid() != pid ||
                !FrameworkService.hasRegister(Process.SYSTEM_UID, pid)) return@execute
            when (state) {
                RecoveryState.WAITING_FOR_ATTACH,
                RecoveryState.IDLE -> {
                    Log.i(TAG, "system_server attached (pid=$pid)")
                    setState(RecoveryState.ATTACHED)
                    resetFailureEpisode()
                }
                RecoveryState.ACQUIRING,
                RecoveryState.RECOVERING,
                RecoveryState.WAITING_FOR_SYSTEM_SERVER,
                RecoveryState.INJECTING -> {
                    pendingAttachedPid = pid
                    pendingAttachGeneration = currentContext?.generation ?: -1L
                    Log.i(TAG, "Recorded early system_server attach pid=$pid")
                }
                RecoveryState.ATTACHED,
                RecoveryState.FAILED -> Unit
            }
        }
    }

    // -- Initial injection (two-phase lease) ----------------------------------

    private fun executeInitialInjection(ctx: RecoveryContext) {
        // -- Phase A: acquire lease, write owner, release --
        setState(RecoveryState.ACQUIRING)
        ctx.throwIfExpired("ACQUIRING")

        val lease = acquireRecoveryLease(ctx, "initial injection")

        try {
            setState(RecoveryState.RECOVERING)
            validateAndWriteOwner(ctx)
        } finally {
            lease.close() // Release first lease before waiting for bridge
        }

        // -- Phase B: wait for bridge, re-acquire, transact --
        executePhaseB(ctx, isInitial = true)
    }

    // -- Recovery (two-phase lease) -------------------------------------------

    private fun startNewRecovery(
        reason: String,
        preserveFailureEpisode: Boolean = false,
        restartPrimaryAfterPreparation: Boolean = false,
    ) {
        if (!preserveFailureEpisode) resetFailureEpisode()
        if (!restartPrimaryAfterPreparation) retryScheduledOrRunning = false
        val gen = ++recoveryGeneration
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_TIMEOUT_MS
        val ctx = RecoveryContext(gen, deadline)
        currentContext = ctx

        Log.w(TAG, "Starting recovery gen=$gen, reason: $reason")
        try {
            executeRecoveryPhaseA(ctx, restartPrimaryAfterPreparation)
        } catch (e: RecoveryExpiredException) {
            Log.e(TAG, "Recovery gen=$gen timed out", e)
            failRecovery(ctx, classifyExpiry(e))
        } catch (e: Exception) {
            Log.e(TAG, "Recovery gen=$gen failed in Phase A", e)
            failRecovery(ctx, FailureKind.PERMANENT)
        }
    }

    private fun executeRecoveryPhaseA(ctx: RecoveryContext, restartPrimaryAfterPreparation: Boolean) {
        setState(RecoveryState.ACQUIRING)
        ctx.throwIfExpired("ACQUIRING")

        val lease = acquireRecoveryLease(ctx, "recovery Phase A")
        var oldBridge: IBinder? = null

        if (restartPrimaryAfterPreparation) {
            oldBridge = ServiceManager.getService(VectorDaemon.bridgeServiceName)
        }

        try {
            setState(RecoveryState.RECOVERING)
            ctx.throwIfExpired("RECOVERING")

            validateAndWriteOwner(ctx)

            withRootIdentity {
                Log.w(TAG, "Owner pid=${Process.myPid()} performing recovery cleanup")

                // All heavy cleanup happens here, inside Phase A, not in death callbacks.
                clearSystemCaches()
                FrameworkService.detachSystemServerForRestart(ctx.generation)
                ManagerService.invalidateForSystemServerRestart()
                if (!SystemServerService.prepareForSystemServerRestart(VectorDaemon.proxyServiceName)) {
                    throw IllegalStateException("Failed to claim system_server proxy service")
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    when (val refreshResult = Dex2OatServer.refreshMount()) {
                        Dex2OatServer.RefreshResult.ALREADY_HEALTHY,
                        Dex2OatServer.RefreshResult.RECOVERED,
                        Dex2OatServer.RefreshResult.FALLBACK_ENABLED ->
                            Log.i(TAG, "Dex2Oat recovery: $refreshResult")

                        Dex2OatServer.RefreshResult.FAILED ->
                            throw IllegalStateException("Dex2Oat recovery failed")
                    }
                }
            }
        } finally {
            lease.close() // Release first lease before waiting
        }

        if (restartPrimaryAfterPreparation) {
            VectorDaemon.restartPrimaryZygoteForSystemServerRecovery()
        }

        // Phase B runs after lease is released
        try {
            executePhaseB(ctx, isInitial = false, oldBridge = oldBridge)
        } catch (e: RecoveryExpiredException) {
            Log.e(TAG, "Recovery gen=${ctx.generation} timed out in Phase B", e)
            failRecovery(ctx, classifyExpiry(e))
        } catch (e: InjectionFailedException) {
            Log.e(TAG, "Recovery gen=${ctx.generation} failed to inject", e)
            failRecovery(ctx, FailureKind.INJECTION_FAILED)
        } catch (e: Exception) {
            Log.e(TAG, "Recovery gen=${ctx.generation} failed in Phase B", e)
            failRecovery(ctx, FailureKind.PERMANENT)
        }
    }

    // -- Phase B: wait for bridge, re-acquire lease, transact -----------------

    private fun executePhaseB(
        ctx: RecoveryContext,
        isInitial: Boolean,
        allowExistingAttach: Boolean = false,
        oldBridge: IBinder? = null,
    ) {
        if (currentContext?.generation != ctx.generation) return
        phaseBWasInitial = isInitial

        setState(RecoveryState.WAITING_FOR_SYSTEM_SERVER)
        ctx.throwIfExpired("WAITING_FOR_SYSTEM_SERVER")

        if (oldBridge != null) {
            Log.i(TAG, "Waiting for old system_server to exit...")
            while (oldBridge.pingBinder() && SystemClock.elapsedRealtime() <= ctx.deadlineElapsedRealtime) {
                if (currentContext?.generation != ctx.generation) return
                Thread.sleep(100)
            }
            ctx.throwIfExpired("WAITING_FOR_OLD_SYSTEM_SERVER_EXIT")
            Log.i(TAG, "Old system_server has exited")
            
            withRootIdentity {
                clearSystemCaches() // Clear again after death to ensure ServiceManager has dropped it
            }
        }

        // Poll for bridge service availability
        val bridgeServiceName = VectorDaemon.bridgeServiceName
        var bridgeService: IBinder? = null

        while (SystemClock.elapsedRealtime() <= ctx.deadlineElapsedRealtime) {
            if (currentContext?.generation != ctx.generation) return
            bridgeService = ServiceManager.getService(bridgeServiceName)
            // Ensure we don't accidentally grab the old dead binder
            if (bridgeService != null && bridgeService.pingBinder() && bridgeService !== oldBridge) {
                break
            }
            Thread.sleep(1000)
        }
        ctx.throwIfExpired("WAITING_FOR_SYSTEM_SERVER (after poll)")

        if (bridgeService == null || !bridgeService.pingBinder()) {
            throw RecoveryExpiredException("Bridge service not found")
        }

        // Re-acquire lease for transact phase
        val lease = acquireRecoveryLease(ctx, "recovery Phase B")

        try {
            // Revalidate we are still the owner
            val owner = ReinjectionOwnerStore.readOwner()
            if (owner.status != OwnerStatus.VALID || owner.pid != Process.myPid()) {
                terminateStaleDaemon("Owner changed during Phase B (pid=${owner.pid})")
            }

            setState(RecoveryState.INJECTING)
            ctx.throwIfExpired("INJECTING")

            // Death recipient: ONLY unlink + post recovery. No heavy work.
            val deathRecipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    runCatching { bridgeService.unlinkToDeath(this, 0) }
                    requestRecovery("system_server died", ctx.generation)
                }
            }
            bridgeService.linkToDeath(deathRecipient, 0)

            // Transact to inject VectorService binder on the main thread
            val injected = transactVectorServiceOnMainThread(bridgeService, ctx)

            if (!injected) {
                if (isInitial) {
                    throw IllegalStateException("Failed to inject on boot")
                } else {
                    throw InjectionFailedException("Failed to inject during recovery")
                }
            }
            Log.i(TAG, "Successfully injected Vector IPC binder")
        } finally {
            lease.close() // Release second lease
        }

        // Initial injection follows an existing system_server attach. Only a restart needs a new
        // attach callback; waiting here would turn a healthy boot into a timeout failure.
        val currentAttached =
            SystemServerService.systemServerRequested &&
                FrameworkService.hasRegister(
                    Process.SYSTEM_UID,
                    SystemServerService.currentAttachedSystemServerPid(),
                )
        val pendingAttached =
            pendingAttachGeneration == ctx.generation &&
                pendingAttachedPid > 0 &&
                SystemServerService.currentAttachedSystemServerPid() == pendingAttachedPid &&
                FrameworkService.hasRegister(Process.SYSTEM_UID, pendingAttachedPid)
        if (((isInitial || allowExistingAttach) && currentAttached) || pendingAttached) {
            setState(RecoveryState.ATTACHED)
            resetFailureEpisode()
            pendingAttachedPid = -1
            pendingAttachGeneration = -1L
        } else {
            setState(RecoveryState.WAITING_FOR_ATTACH)
            scheduleAttachTimeout(ctx.generation)
        }
    }

    private fun transactVectorServiceOnMainThread(
        bridgeService: IBinder,
        ctx: RecoveryContext,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return transactVectorServiceAsRoot(bridgeService, ctx)
        }

        val future = CompletableFuture<Boolean>()

        val posted = mainHandler.post {
            try {
                future.complete(transactVectorServiceAsRoot(bridgeService, ctx))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }

        if (!posted) {
            throw IllegalStateException("Unable to post bridge injection to daemon main thread")
        }

        val remaining = ctx.deadlineElapsedRealtime - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            throw RecoveryExpiredException("Recovery expired before main-thread bridge injection")
        }

        return try {
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw RecoveryExpiredException("Timed out waiting for main-thread bridge injection")
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            when (cause) {
                is RecoveryExpiredException -> throw cause
                is Exception -> throw cause
                else -> throw RuntimeException("Main-thread bridge injection failed", cause)
            }
        }
    }

    private fun transactVectorServiceAsRoot(
        bridgeService: IBinder,
        ctx: RecoveryContext,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "VectorService SEND_BINDER must run on daemon main thread"
        }

        for (attempt in 0 until 3) {
            ctx.throwIfExpired("INJECTING")

            val success = transactSingleAttemptAsRoot(bridgeService, ctx, attempt)
            if (success) {
                return true
            }

            if (attempt < 2) {
                Thread.sleep(500)
            }
        }

        return false
    }

    private fun transactSingleAttemptAsRoot(
        bridgeService: IBinder,
        ctx: RecoveryContext,
        attempt: Int,
    ): Boolean {
        val originalEuid = android.system.Os.geteuid()

        try {
            if (originalEuid != 0) {
                android.system.Os.seteuid(0)
            }

            val effectiveEuid = android.system.Os.geteuid()

            Log.i(
                TAG,
                "Main-thread VectorService injection: attempt=${attempt + 1} " +
                    "pid=${Process.myPid()} " +
                    "tid=${Process.myTid()} " +
                    "euid=$effectiveEuid " +
                    "generation=${ctx.generation}"
            )

            if (effectiveEuid != 0) {
                throw IllegalStateException("Failed to obtain root euid for SEND_BINDER")
            }

            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeInt(ACTION_SEND_BINDER)
                data.writeStrongBinder(VectorService.asBinder())

                val transacted = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0)

                if (!transacted) {
                    Log.w(TAG, "Bridge transact attempt ${attempt + 1} returned false")
                    return false
                }

                reply.readException()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Bridge transact attempt ${attempt + 1} failed", e)
                return false
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            val currentEuid = runCatching { android.system.Os.geteuid() }.getOrDefault(originalEuid)
            if (currentEuid != originalEuid) {
                try {
                    android.system.Os.seteuid(originalEuid)
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "CRITICAL: failed to restore daemon main-thread euid from $currentEuid to $originalEuid",
                        e
                    )
                    throw e
                }
            }
        }
    }

    // -- Non-blocking attach timeout ------------------------------------------

    /**
     * Schedules a timeout check on the separate [timeoutScheduler].
     * When it fires, it posts a state check back to [recoveryExecutor].
     * This way [onSystemServerAttached] is never blocked.
     */
    private fun scheduleAttachTimeout(generation: Long) {
        timeoutScheduler.schedule({
            recoveryExecutor.execute {
                if (currentContext?.generation == generation &&
                    state == RecoveryState.WAITING_FOR_ATTACH) {
                    Log.e(TAG, "Timed out waiting for system_server attach (gen=$generation)")
                    currentContext?.takeIf { it.generation == generation }?.let {
                        failRecovery(it, FailureKind.ATTACH_TIMEOUT)
                    }
                }
            }
        }, ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    // -- Helpers --------------------------------------------------------------

    /** Validates existing owner before overwriting. */
    private fun validateAndWriteOwner(ctx: RecoveryContext) {
        var existing = ReinjectionOwnerStore.readOwner()
        for (attempt in 0 until 3) {
            if (existing.status != OwnerStatus.CORRUPT && existing.status != OwnerStatus.IO_ERROR) break
            Log.w(TAG, "Owner metadata ${existing.status}; retrying read (${attempt + 1}/3)")
            Thread.sleep(100L shl attempt)
            ctx.throwIfExpired("owner metadata retry")
            existing = ReinjectionOwnerStore.readOwner()
        }

        when (existing.status) {
            OwnerStatus.MISSING -> Unit
            OwnerStatus.VALID -> {
                when (ReinjectionOwnerStore.getOwnerLiveness(existing)) {
                    OwnerLiveness.ALIVE -> {
                        if (existing.pid != Process.myPid()) {
                            terminateStaleDaemon("Active owner exists (pid=${existing.pid})")
                        }
                    }
                    OwnerLiveness.DEAD -> Unit
                    OwnerLiveness.INDETERMINATE ->
                        throw IllegalStateException("Owner liveness is indeterminate; refusing overwrite")
                }
            }
            OwnerStatus.CORRUPT,
            OwnerStatus.IO_ERROR ->
                throw IllegalStateException("Owner metadata remains ${existing.status}; refusing overwrite")
        }

        if (!ReinjectionOwnerStore.writeOwner()) {
            throw IllegalStateException("Failed to write owner metadata")
        }
    }

    private fun acquireRecoveryLease(ctx: RecoveryContext, stage: String): ReinjectionLease {
        val delays = longArrayOf(100L, 300L, 1_000L)
        for (attempt in delays.indices) {
            ctx.throwIfExpired("$stage lease attempt ${attempt + 1}")
            val (state, lease) = ReinjectionOwnerStore.acquireLease()
            if (state == LeaseState.ACQUIRED && lease != null) return lease
            lease?.close()

            val owner = ReinjectionOwnerStore.readOwner()
            val liveness = ReinjectionOwnerStore.getOwnerLiveness(owner)
            if (state == LeaseState.BUSY &&
                owner.status == OwnerStatus.VALID &&
                liveness == OwnerLiveness.ALIVE &&
                owner.pid != Process.myPid()) {
                terminateStaleDaemon("Lost recovery authority at $stage to pid=${owner.pid}")
            }

            Log.w(TAG, "$stage lease unavailable: state=$state owner=${owner.status} " +
                "liveness=$liveness attempt=${attempt + 1}/${delays.size}")
            if (attempt < delays.lastIndex) {
                Thread.sleep(delays[attempt])
            }
        }
        throw LeaseUnavailableException("Recovery lease unavailable at $stage after bounded retries")
    }

    /** A failed recovery retains no lease and selects a recovery path by the failed stage. */
    private fun failRecovery(ctx: RecoveryContext, kind: FailureKind) {
        if (currentContext?.generation != ctx.generation) return
        currentFailureKind = kind
        setState(RecoveryState.FAILED)
        if (kind == FailureKind.PERMANENT) {
            clearExpectedPrimaryRestart()
            Log.e(TAG, "Recovery gen=${ctx.generation} stopped after permanent failure")
            return
        }
        if (!failureEpisodeActive) {
            failureEpisodeActive = true
            failureEpisodeRetries = 0
        }
        when (kind) {
            FailureKind.ACTIVITY_UNAVAILABLE -> {
                if (failureEpisodeRetries >= MAX_FAILED_RECOVERY_RETRIES) {
                    Log.e(TAG, "Recovery failure episode exhausted retry budget")
                    return
                }
                scheduleFailedRecoveryWatch(ctx.generation)
            }
            FailureKind.ATTACH_TIMEOUT ->
                scheduleFullRecoveryAfterFailure(ctx, "system_server attach timed out")
            FailureKind.INJECTION_FAILED ->
                scheduleFullRecoveryAfterFailure(ctx, "system_server injection failed")
            FailureKind.PERMANENT -> Unit
        }
    }

    /**
     * Start a complete generation whose Phase A reclaims the proxy before restarting the primary
     * zygote. This guarantees the next system_server specialization sees Vector's proxy service.
     */
    private fun scheduleFullRecoveryAfterFailure(ctx: RecoveryContext, reason: String) {
        if (failureEpisodeRetries >= MAX_FAILED_RECOVERY_RETRIES) {
            clearExpectedPrimaryRestart()
            Log.e(TAG, "Recovery failure episode exhausted retry budget: $reason")
            return
        }
        failureEpisodeRetries++
        expectedPrimaryRestartGeneration = ctx.generation
        retryScheduledOrRunning = true
        timeoutScheduler.schedule({
            recoveryExecutor.execute {
                startNewRecovery(reason, preserveFailureEpisode = true, restartPrimaryAfterPreparation = true)
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    private fun scheduleFailedRecoveryWatch(generation: Long) {
        if (retryWatchGeneration != generation) {
            retryWatchGeneration = generation
            registerActivityServiceCallback()
        }
        scheduleActivityPoll(generation)
    }

    private fun registerActivityServiceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || activityCallbackRegistered) return
        val registered =
            runCatching {
                getSystemServiceManager()
                    .registerForNotifications(VectorDaemon.bridgeServiceName, activityServiceCallback)
                true
            }
            .onFailure { Log.w(TAG, "Unable to watch activity bridge registration", it) }
            .getOrDefault(false)
        if (registered) activityCallbackRegistered = true
    }

    private fun scheduleActivityPoll(generation: Long) {
        if (failureEpisodeRetries >= MAX_FAILED_RECOVERY_RETRIES ||
            failureEpisodePolls >= MAX_FAILED_RECOVERY_RETRIES) {
            Log.e(TAG, "Recovery gen=$generation exhausted activity-service retry budget")
            return
        }
        val attempt = ++failureEpisodePolls
        val delayMs = 1_000L shl (attempt - 1)
        timeoutScheduler.schedule({
            recoveryExecutor.execute {
                if (currentContext?.generation != generation || state != RecoveryState.FAILED) return@execute
                val activity = ServiceManager.getService(VectorDaemon.bridgeServiceName)
                if (activity?.pingBinder() == true) {
                    startPhaseBRetry("activity bridge appeared during failed recovery watch")
                } else {
                    scheduleActivityPoll(generation)
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun handleActivityServiceAvailable() {
        if (state != RecoveryState.FAILED || !failureEpisodeActive) return
        if (currentFailureKind != FailureKind.ACTIVITY_UNAVAILABLE) return
        startPhaseBRetry("activity bridge registered after failed recovery")
    }

    /** Retries only Phase B after the bridge service returns; Phase A remains intact. */
    private fun startPhaseBRetry(reason: String) {
        if (state != RecoveryState.FAILED || !failureEpisodeActive ||
            (retryScheduledOrRunning && expectedPrimaryRestartGeneration < 0)) return
        if (failureEpisodeRetries >= MAX_FAILED_RECOVERY_RETRIES) {
            Log.e(TAG, "Recovery failure episode exhausted retry budget")
            return
        }
        failureEpisodeRetries++
        retryScheduledOrRunning = true
        timeoutScheduler.schedule({
            recoveryExecutor.execute {
                startPhaseBRecovery(reason)
            }
        }, 0, TimeUnit.MILLISECONDS)
    }

    private fun startPhaseBRecovery(reason: String) {
        retryScheduledOrRunning = false
        val gen = ++recoveryGeneration
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_TIMEOUT_MS
        val ctx = RecoveryContext(gen, deadline)
        currentContext = ctx

        Log.w(TAG, "Retrying recovery Phase B only (gen=$gen), reason: $reason")
        try {
            executePhaseB(ctx, phaseBWasInitial, allowExistingAttach = true)
        } catch (e: RecoveryExpiredException) {
            Log.e(TAG, "Recovery gen=$gen timed out in retried Phase B", e)
            failRecovery(ctx, classifyExpiry(e))
        } catch (e: InjectionFailedException) {
            Log.e(TAG, "Recovery gen=$gen failed to inject in retried Phase B", e)
            failRecovery(ctx, FailureKind.INJECTION_FAILED)
        } catch (e: Exception) {
            Log.e(TAG, "Recovery gen=$gen failed in retried Phase B", e)
            failRecovery(ctx, FailureKind.PERMANENT)
        }
    }

    private fun classifyExpiry(error: RecoveryExpiredException): FailureKind =
        if (error.message?.contains("WAITING_FOR_SYSTEM_SERVER") == true) {
            FailureKind.ACTIVITY_UNAVAILABLE
        } else if (error.message?.contains("WAITING_FOR_ATTACH") == true) {
            FailureKind.ATTACH_TIMEOUT
        } else {
            FailureKind.PERMANENT
        }

    private fun resetFailureEpisode() {
        failureEpisodeActive = false
        failureEpisodeRetries = 0
        failureEpisodePolls = 0
        currentFailureKind = null
        retryScheduledOrRunning = false
        retryWatchGeneration = -1L
        expectedPrimaryRestartGeneration = -1L
        pendingAttachedPid = -1
        pendingAttachGeneration = -1L
    }

    private fun clearExpectedPrimaryRestart() {
        expectedPrimaryRestartGeneration = -1L
        retryScheduledOrRunning = false
    }

    private fun terminateStaleDaemon(reason: String): Nothing {
        Log.w(TAG, "$reason. Terminating stale daemon.")
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(0)
    }

    private fun clearSystemCaches() {
        Log.i(TAG, "Clearing ServiceManager and ActivityManager caches")
        runCatching {
            var field = ServiceManager::class.java.getDeclaredField("sServiceManager")
            field.isAccessible = true
            field.set(null, null)

            field = ServiceManager::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            val sCache = field.get(null)
            if (sCache is MutableMap<*, *>) {
                sCache.clear()
            }

            field = android.app.ActivityManager::class.java
                .getDeclaredField("IActivityManagerSingleton")
            field.isAccessible = true
            val singleton = field.get(null)
            if (singleton != null) {
                val mInstanceField =
                    Class.forName("android.util.Singleton").getDeclaredField("mInstance")
                mInstanceField.isAccessible = true
                synchronized(singleton) { mInstanceField.set(singleton, null) }
            }
        }.onFailure { Log.w(TAG, "Failed to clear system caches", it) }
    }

    private inline fun <T> withRootIdentity(block: () -> T): T {
        val originalEuid = runCatching { android.system.Os.geteuid() }.getOrDefault(0)
        val switched = originalEuid != 0 && runCatching { android.system.Os.seteuid(0) }.isSuccess
        try {
            return block()
        } finally {
            if (switched) {
                runCatching { android.system.Os.seteuid(originalEuid) }
                    .onFailure { Log.w(TAG, "Failed to restore euid to $originalEuid", it) }
            }
        }
    }
}
