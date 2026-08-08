package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Executable
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.lsposed.lspd.util.Utils

/** Represents a registered hook configuration, stored natively by [HookBridge]. */
class VectorHookRecord(
    val modulePackageName: String,
    val executable: Executable,
    val id: String?,
    val priority: Int,
    val hooker: XposedInterface.Hooker,
    val exceptionMode: ExceptionMode,
) {
    // This is handle/registry liveness, not invocation liveness. A callback already copied by
    // native callbackSnapshot must be allowed to finish after an API102 replacement invalidates
    // the old handle.
    private val active = AtomicBoolean(true)

    fun isActive(): Boolean = active.get()

    fun deactivate(): Boolean = active.compareAndSet(true, false)
}

/**
 * Core interceptor chain engine. Manages recursive hook execution and enforces [ExceptionMode]
 * protections.
 */
class VectorChain(
    private val executable: Executable,
    private val thisObj: Any?,
    private val args: Array<Any?>,
    private val hooks: Array<VectorHookRecord>,
    private val hookIndex: Int,
    private val terminal: (thisObj: Any?, args: Array<Any?>) -> Any?,
) : Chain {

    internal var proceedCalled: Boolean = false
        private set

    internal var downstreamResult: Any? = null
    internal var downstreamThrowable: Throwable? = null

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = thisObj

    override fun getArgs(): List<Any?> = Collections.unmodifiableList(args.toList())

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? = internalProceed(thisObj, args)

    override fun proceed(currentArgs: Array<Any?>): Any? = internalProceed(thisObj, currentArgs)

    override fun proceedWith(thisObject: Any): Any? = internalProceed(thisObject, args)

    override fun proceedWith(thisObject: Any, currentArgs: Array<Any?>): Any? =
        internalProceed(thisObject, currentArgs)

    private fun internalProceed(thisObject: Any?, currentArgs: Array<Any?>): Any? {
        proceedCalled = true

        if (hookIndex >= hooks.size) {
            return executeDownstream { terminal(thisObject, currentArgs) }
        }

        val nextChain =
            VectorChain(executable, thisObject, currentArgs, hooks, hookIndex + 1, terminal)
        val record = hooks[hookIndex]

        // Do not consult record.isActive() here. The native snapshot is the invocation boundary:
        // once a callback was copied into this invocation it belongs to this call even if another
        // thread atomically replaces or unhooks its handle afterwards.
        return try {
            executeDownstream { record.hooker.intercept(nextChain) }
        } catch (t: Throwable) {
            handleInterceptorException(t, record, nextChain, thisObject, currentArgs)
        }
    }

    /**
     * Executes the block and caches only the latest downstream state. Clearing the opposite state
     * prevents a previously suppressed exception from being resurrected after a later success.
     */
    private inline fun executeDownstream(block: () -> Any?): Any? {
        return try {
            val result = block()
            downstreamResult = result
            downstreamThrowable = null
            result
        } catch (t: Throwable) {
            downstreamResult = null
            downstreamThrowable = t
            throw t
        }
    }

    private fun handleInterceptorException(
        t: Throwable,
        record: VectorHookRecord,
        nextChain: VectorChain,
        recoveryThis: Any?,
        recoveryArgs: Array<Any?>,
    ): Any? {
        if (nextChain.proceedCalled && t === nextChain.downstreamThrowable) {
            throw t
        }

        if (record.exceptionMode == ExceptionMode.PASSTHROUGH) {
            throw t
        }

        val hookerName = record.hooker.javaClass.name
        if (!nextChain.proceedCalled) {
            Utils.logD("Hooker [$hookerName] crashed before proceed. Skipping.", t)
            return nextChain.internalProceed(recoveryThis, recoveryArgs)
        } else {
            Utils.logD("Hooker [$hookerName] crashed after proceed. Restoring state.", t)
            nextChain.downstreamThrowable?.let { throw it }
            return nextChain.downstreamResult
        }
    }
}
