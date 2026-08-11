package org.matrix.vector.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.util.Utils.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object VectorServiceClient : ILSPApplicationService, IBinder.DeathRecipient {

    private const val TAG = "VectorServiceClient"

    private var service: ILSPApplicationService? = null
    private val pendingHotReloadTargets = ConcurrentHashMap<String, PendingHotReloadTarget>()
    private val registeredTargetIds = ConcurrentHashMap<String, Long>()
    var processName: String = ""
        private set

    private data class PendingHotReloadTarget(
        val modulePackageName: String,
        val loadedVersionCode: Long,
        val target: IHotReloadTarget,
    )

    @Synchronized
    fun init(appService: ILSPApplicationService?, niceName: String) {
        val binder = appService?.asBinder()
        if (binder == null) return

        val oldService = service
        val oldBinder = oldService?.asBinder()
        if (oldBinder === binder || oldBinder == binder) {
            processName = niceName
            return
        }

        runCatching { oldBinder?.unlinkToDeath(this, 0) }
            .onFailure { Log.w(TAG, "Failed to unlink old service death recipient", it) }

        registeredTargetIds.clear()
        service = appService
        processName = niceName

        // The parasitic manager is initialized while the app is still being specialized from the
        // zygote. On some Android 16 vendor builds the Binder thread pool has not started yet, so
        // linkToDeath can fail even though synchronous transactions on this Binder work normally.
        // Death notification is a recovery aid, not a prerequisite for using a live service.
        runCatching { binder.linkToDeath(this, 0) }
            .onFailure {
                Log.w(
                    TAG,
                    "Service death monitoring is unavailable in process $niceName; retaining live service",
                    it,
                )
            }

        pendingHotReloadTargets.values.forEach(::registerHotReloadTargetLocked)
    }

    override fun isLogMuted(): Boolean {
        return runCatching { service?.isLogMuted == true }.getOrDefault(false)
    }

    override fun getLegacyModulesList(): List<Module> {
        return runCatching { service?.legacyModulesList }.getOrNull() ?: emptyList()
    }

    override fun getModulesList(): List<Module> {
        return runCatching { service?.modulesList }.getOrNull() ?: emptyList()
    }

    override fun getPrefsPath(packageName: String): String? {
        return runCatching { service?.getPrefsPath(packageName) }.getOrNull()
    }

    override fun requestInjectedManagerBinder(binder: List<IBinder>): ParcelFileDescriptor? {
        val currentService = service
        if (currentService == null) {
            Log.e(TAG, "Cannot request injected manager: application service is unavailable")
            return null
        }
        return runCatching { currentService.requestInjectedManagerBinder(binder) }
            .onFailure { Log.e(TAG, "Failed to request injected manager resources", it) }
            .getOrNull()
    }

    override fun registerHotReloadTarget(
        modulePackageName: String,
        loadedVersionCode: Long,
        target: IHotReloadTarget,
    ): Long {
        val pending = PendingHotReloadTarget(modulePackageName, loadedVersionCode, target)
        pendingHotReloadTargets[modulePackageName] = pending
        return registerHotReloadTargetLocked(pending)
    }

    @Synchronized
    private fun registerHotReloadTargetLocked(pending: PendingHotReloadTarget): Long {
        val currentService = service
        if (currentService == null) {
            Log.w(
                TAG,
                "Cannot register hot reload target for ${pending.modulePackageName} in $processName: service unavailable",
            )
            return -1L
        }
        return runCatching {
                currentService.registerHotReloadTarget(
                    pending.modulePackageName,
                    pending.loadedVersionCode,
                    pending.target,
                )
            }
            .onSuccess { registeredTargetIds[pending.modulePackageName] = it }
            .onFailure {
                registeredTargetIds.remove(pending.modulePackageName)
                Log.e(
                    TAG,
                    "Failed to register hot reload target package=${pending.modulePackageName} process=$processName versionCode=${pending.loadedVersionCode}: ${it.message}",
                    it,
                )
            }
            .getOrDefault(-1L)
    }

    fun updatePendingHotReloadVersion(modulePackageName: String, loadedVersionCode: Long) {
        pendingHotReloadTargets.computeIfPresent(modulePackageName) { _, pending ->
            pending.copy(loadedVersionCode = loadedVersionCode)
        }
    }

    override fun asBinder(): IBinder? {
        return service?.asBinder()
    }

    @Synchronized
    override fun binderDied() {
        runCatching { service?.asBinder()?.unlinkToDeath(this, 0) }
            .onFailure { Log.w(TAG, "Failed to unlink dead service", it) }
        service = null
        registeredTargetIds.clear()
    }
}
