package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/**
 * Builder for configuring and registering hooks.
 *
 * [frozen] belongs to the module generation that created this builder. Registration is checked both
 * optimistically and again under the module lock that hot reload takes while retiring a generation,
 * so an id-less hook cannot slip in after the old generation has been frozen.
 */
class VectorHookBuilder(
    private val modulePackageName: String,
    private val origin: Executable,
    private val frozen: (() -> Boolean)? = null,
    private val defaultExceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
) : HookBuilder {

    constructor(origin: Executable) :
        this(FRAMEWORK_HOOK_OWNER, origin, null, ExceptionMode.PROTECTIVE)

    // Retain the pre-freeze constructor shape for framework-internal/test call sites.
    constructor(
        modulePackageName: String,
        origin: Executable,
        defaultExceptionMode: ExceptionMode,
    ) : this(modulePackageName, origin, null, defaultExceptionMode)

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT
    private var id: String? = null

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun setId(id: String?): HookBuilder = apply { this.id = id }

    override fun intercept(hooker: Hooker): HookHandle {
        validateHookTarget()
        ensureNotFrozen()

        val hookKey = id?.let { HookKey(modulePackageName, origin, it) }
        val record = createRecord(hooker)

        // Every module-owned registration, including hooks without setId(), is serialized with the
        // lock hot reload uses to freeze the generation. Concurrent maps alone are insufficient:
        // the correctness requirement is ordering between "generation retired" and native install.
        synchronized(HookRegistry.lockOf(modulePackageName)) {
            ensureNotFrozen()

            if (hookKey != null) {
                val existing = HookRegistry.records[hookKey]
                if (existing != null && existing.isActive()) {
                    return replaceRecordLocked(existing, record, hookKey)
                }
            }

            installRecord(record)
            hookKey?.let { HookRegistry.records[it] = record }
            return VectorHookHandle(record, hookKey)
        }
    }

    private fun ensureNotFrozen() {
        if (frozen?.invoke() == true) {
            throw IllegalStateException(
                "This module generation has been retired by a hot reload and cannot register hooks"
            )
        }
    }

    private fun createRecord(hooker: Hooker): VectorHookRecord {
        // Resolve DEFAULT before the record is stored natively: callback dispatch has no route back
        // to the module.prop that defines this module's default exception policy.
        val resolvedMode =
            if (exceptionMode == ExceptionMode.DEFAULT) defaultExceptionMode else exceptionMode
        return VectorHookRecord(
            modulePackageName = modulePackageName,
            executable = origin,
            id = id,
            priority = priority,
            hooker = hooker,
            exceptionMode = resolvedMode,
        )
    }

    private fun validateHookTarget() {
        if (Modifier.isAbstract(origin.modifiers)) {
            throw IllegalArgumentException("Cannot hook abstract methods: $origin")
        } else if (origin.declaringClass.classLoader == VectorHookBuilder::class.java.classLoader) {
            throw IllegalArgumentException("Do not allow hooking inner methods")
        } else if (
            origin is Method &&
                origin.declaringClass == Method::class.java &&
                origin.name == "invoke"
        ) {
            throw IllegalArgumentException("Cannot hook Method.invoke")
        } else if (
            origin is Method &&
                origin.declaringClass == Constructor::class.java &&
                origin.name == "newInstance"
        ) {
            throw IllegalArgumentException(
                "Constructor.newInstance cannot be hooked: Vector reflects through it the same way it does Method.invoke, so a hook here would recurse."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Any::class.java &&
                origin.name == "getClass"
        ) {
            throw IllegalArgumentException(
                "Object.getClass cannot be hooked: Vector's dispatch calls it entering every hooked method, so a hook here would call itself forever."
            )
        }
    }
}

private const val FRAMEWORK_HOOK_OWNER = "org.matrix.vector.framework"

private data class HookKey(
    val modulePackageName: String,
    val executable: Executable,
    val id: String,
)

private object HookRegistry {
    val records = ConcurrentHashMap<HookKey, VectorHookRecord>()
    val allRecords = ConcurrentHashMap.newKeySet<VectorHookRecord>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** One ownership lock per module; hot reload and every registration/handle mutation share it. */
    fun lockOf(modulePackageName: String): Any =
        locks.computeIfAbsent(modulePackageName) { Any() }

    fun handlesForModule(modulePackageName: String): List<HookHandle> =
        synchronized(lockOf(modulePackageName)) {
            allRecords
                .filter { it.modulePackageName == modulePackageName && it.isActive() }
                .map {
                    VectorHookHandle(
                        it,
                        it.id?.let { id -> HookKey(modulePackageName, it.executable, id) },
                    )
                }
        }
}

internal fun getActiveHookHandles(modulePackageName: String): List<HookHandle> {
    return HookRegistry.handlesForModule(modulePackageName)
}

/** The same lock API102 hot reload must hold while freezing a module generation. */
internal fun hookLockOf(modulePackageName: String): Any = HookRegistry.lockOf(modulePackageName)

internal fun unhookAllModuleHooks(modulePackageName: String, except: Set<HookHandle> = emptySet()) {
    val excludedRecords = except.mapNotNull { (it as? VectorHookHandle)?.record }.toSet()
    synchronized(HookRegistry.lockOf(modulePackageName)) {
        HookRegistry.allRecords
            .filter { it.modulePackageName == modulePackageName && it !in excludedRecords }
            .toList()
            .forEach(::uninstallRecordLocked)
    }
}

private class VectorHookHandle(val record: VectorHookRecord, private val hookKey: HookKey?) :
    HookHandle {
    override fun getExecutable(): Executable = record.executable

    override fun getId(): String? = record.id

    override fun unhook() {
        synchronized(HookRegistry.lockOf(record.modulePackageName)) {
            uninstallRecordLocked(record)
        }
    }

    override fun replaceHook(hooker: Hooker): HookHandle {
        val replacement =
            VectorHookRecord(
                modulePackageName = record.modulePackageName,
                executable = record.executable,
                id = record.id,
                priority = record.priority,
                hooker = hooker,
                exceptionMode = record.exceptionMode,
            )

        synchronized(HookRegistry.lockOf(record.modulePackageName)) {
            if (!record.isActive()) {
                throw IllegalStateException("Hook handle is no longer valid")
            }
            return replaceRecordLocked(record, replacement, hookKey)
        }
    }
}

private fun installRecord(record: VectorHookRecord) {
    if (
        !HookBridge.hookMethod(
            true,
            record.executable,
            VectorNativeHooker::class.java,
            record.priority,
            record,
        )
    ) {
        throw HookFailedError("Cannot hook ${record.executable}")
    }
    HookRegistry.allRecords.add(record)
}

/**
 * Replaces one modern callback using API102's native atomic primitive.
 *
 * The native operation is performed before Java bookkeeping changes. If allocation or lookup fails,
 * replaceCallback() leaves the old callback installed and this function throws without changing the
 * registry. A callbackSnapshot taken before the swap owns a Java reference to [oldRecord], so it may
 * finish the old generation even after the handle is invalidated here; new snapshots see only
 * [replacement].
 */
private fun replaceRecordLocked(
    oldRecord: VectorHookRecord,
    replacement: VectorHookRecord,
    hookKey: HookKey?,
): HookHandle {
    if (!oldRecord.isActive()) {
        throw IllegalStateException("Hook handle is no longer valid")
    }
    if (
        !HookBridge.replaceCallback(
            true,
            oldRecord.executable,
            oldRecord,
            replacement,
            replacement.priority,
        )
    ) {
        throw HookFailedError("Cannot replace the hook on ${oldRecord.executable}")
    }

    // Native state is committed at this point. Invalidate the old handle without suppressing an
    // already-snapshotted callback; dispatch deliberately does not consult this bookkeeping flag.
    oldRecord.deactivate()
    HookRegistry.allRecords.remove(oldRecord)
    HookRegistry.allRecords.add(replacement)
    hookKey?.let { HookRegistry.records[it] = replacement }
    return VectorHookHandle(replacement, hookKey)
}

private fun uninstallRecordLocked(record: VectorHookRecord): Boolean {
    if (!record.deactivate()) return false
    HookBridge.unhookMethod(true, record.executable, record)
    record.id?.let { id ->
        HookRegistry.records.remove(
            HookKey(record.modulePackageName, record.executable, id),
            record,
        )
    }
    HookRegistry.allRecords.remove(record)
    return true
}

/** The native callback entrypoint instantiated by [HookBridge] for a hooked executable. */
class VectorNativeHooker<T : Executable>(private val method: T) {

    private val isStatic = Modifier.isStatic(method.modifiers)
    private val returnType = if (method is Method) method.returnType else null

    /** Invoked by C++ via JNI. */
    fun callback(args: Array<Any?>): Any? {
        val thisObject = if (isStatic) null else args[0]
        val actualArgs = if (isStatic) args else args.sliceArray(1 until args.size)

        // Null means every hook was removed after this trampoline was entered. Once a snapshot is
        // returned, its callbacks remain authoritative for this invocation even if a concurrent
        // API102 replacement invalidates their handles; the Java array holds strong references.
        val snapshots =
            HookBridge.callbackSnapshot(VectorHookRecord::class.java, method)
                ?: return invokeOriginalSafely(thisObject, actualArgs)

        @Suppress("UNCHECKED_CAST")
        val modernHooks = snapshots[0] as Array<VectorHookRecord>
        val legacyHooks = snapshots[1]

        if (modernHooks.isEmpty() && legacyHooks.isEmpty()) {
            return invokeOriginalSafely(thisObject, actualArgs)
        }

        val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
            val delegate = VectorBootstrap.delegate
            if (legacyHooks.isNotEmpty() && delegate != null) {
                delegate.processLegacyHook(method, tObj, tArgs, legacyHooks) {
                    invokeOriginalSafely(tObj, tArgs)
                }
            } else {
                invokeOriginalSafely(tObj, tArgs)
            }
        }

        val rootChain = VectorChain(method, thisObject, actualArgs, modernHooks, 0, terminal)
        val result = rootChain.proceed()

        if (returnType != null && returnType != Void.TYPE) {
            if (result == null) {
                if (returnType.isPrimitive) {
                    throw NullPointerException(
                        "Hook returned null for a primitive return type: $method"
                    )
                }
            } else if (
                !HookBridge.instanceOf(result, returnType) &&
                    !isBoxingCompatible(result, returnType)
            ) {
                Utils.logD(
                    "Hook return type mismatch. Expected ${returnType.name}, got ${result.javaClass.name}"
                )
            }
        }

        return result
    }

    private fun isBoxingCompatible(obj: Any, targetType: Class<*>): Boolean {
        if (!targetType.isPrimitive) return false
        return when (targetType) {
            Int::class.javaPrimitiveType -> obj is Int
            Long::class.javaPrimitiveType -> obj is Long
            Boolean::class.javaPrimitiveType -> obj is Boolean
            Double::class.javaPrimitiveType -> obj is Double
            Float::class.javaPrimitiveType -> obj is Float
            Byte::class.javaPrimitiveType -> obj is Byte
            Char::class.javaPrimitiveType -> obj is Char
            Short::class.javaPrimitiveType -> obj is Short
            else -> false
        }
    }

    /** Safely invokes the original method, unwrapping InvocationTargetExceptions for a chain. */
    private fun invokeOriginalSafely(tObj: Any?, tArgs: Array<Any?>): Any? {
        return try {
            HookBridge.invokeOriginalMethod(method, tObj, *tArgs)
        } catch (ite: InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }
}