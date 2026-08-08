package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface.CtorInvoker
import io.github.libxposed.api.XposedInterface.Invoker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/**
 * Base implementation of the Invoker system. Handles the resolution of [Invoker.Type] to determine
 * whether to execute the original method directly or to construct a partial interceptor chain.
 */
internal abstract class BaseInvoker<T : Invoker<T, U>, U : Executable>(
    protected val executable: U
) : Invoker<T, U> {

    protected var type: Invoker.Type = Invoker.Type.Chain.FULL

    @Suppress("UNCHECKED_CAST")
    override fun setType(type: Invoker.Type): T {
        this.type = type
        return this as T
    }

    /** Resolves the current [type] and executes the underlying method. */
    protected fun proceedInvocation(thisObject: Any?, args: Array<out Any?>): Any? {
        return when (val currentType = type) {
            // Both paths below report a target exception wrapped and their own argument failures
            // unwrapped, matching Invoker#invoke / Method#invoke semantics.
            is Invoker.Type.Origin -> dispatchOriginal(thisObject, args)
            is Invoker.Type.Chain -> {
                val snapshots =
                    HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable)
                        ?: return dispatchOriginal(thisObject, args)

                @Suppress("UNCHECKED_CAST")
                val allModernHooks = snapshots[0] as Array<VectorHookRecord>
                val legacyHooks = snapshots[1]

                val filteredHooks =
                    allModernHooks
                        .filter { it.isActive() && it.priority <= currentType.maxPriority }
                        .toTypedArray()

                val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                    val delegate = VectorBootstrap.delegate
                    if (legacyHooks.isNotEmpty() && delegate != null) {
                        delegate.processLegacyHook(executable, tObj, tArgs, legacyHooks) {
                            invokeOriginal(tObj, tArgs)
                        }
                    } else {
                        invokeOriginal(tObj, tArgs)
                    }
                }

                val chain =
                    VectorChain(executable, thisObject, arrayOf(*args), filteredHooks, 0, terminal)
                try {
                    chain.proceed()
                } catch (e: InvocationTargetException) {
                    throw e
                } catch (e: Throwable) {
                    throw InvocationTargetException(e)
                }
            }
        }
    }

    /** Invokes the original executable, reporting a target exception as Method#invoke does. */
    private fun dispatchOriginal(thisObject: Any?, args: Array<out Any?>): Any? {
        // invokeOriginalMethod dispatches through a cached Method.invoke id. For an unhooked
        // Constructor the reflected receiver has a different class, so use the non-virtual path.
        if (
            executable is Constructor<*> &&
                HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable) == null
        ) {
            requireNotNull(thisObject) {
                "A constructor invoked as a method needs a receiver: $executable"
            }
            return HookBridge.invokeSpecialMethod(
                executable,
                getExecutableShorty(),
                executable.declaringClass,
                thisObject,
                *args,
            )
        }
        return HookBridge.invokeOriginalMethod(executable, thisObject, *args)
    }

    /** Chain#proceed exposes the target exception itself; remove reflection's wrapper here. */
    private fun invokeOriginal(thisObject: Any?, args: Array<out Any?>): Any? =
        try {
            dispatchOriginal(thisObject, args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }

    /** Helper to generate the JNI shorty for non-virtual special invocations. */
    protected fun getExecutableShorty(): CharArray {
        val parameterTypes = executable.parameterTypes
        val shorty = CharArray(parameterTypes.size + 1)
        shorty[0] = getTypeShorty(if (executable is Method) executable.returnType else Void.TYPE)
        for (i in 1..shorty.lastIndex) {
            shorty[i] = getTypeShorty(parameterTypes[i - 1])
        }
        return shorty
    }

    private fun getTypeShorty(type: Class<*>): Char =
        when (type) {
            Int::class.javaPrimitiveType -> 'I'
            Long::class.javaPrimitiveType -> 'J'
            Float::class.javaPrimitiveType -> 'F'
            Double::class.javaPrimitiveType -> 'D'
            Boolean::class.javaPrimitiveType -> 'Z'
            Byte::class.javaPrimitiveType -> 'B'
            Char::class.javaPrimitiveType -> 'C'
            Short::class.javaPrimitiveType -> 'S'
            Void.TYPE -> 'V'
            else -> 'L'
        }
}

/** Invoker implementation specifically for [Method] types. */
internal class VectorMethodInvoker(method: Method) :
    BaseInvoker<VectorMethodInvoker, Method>(method) {

    override fun invoke(thisObject: Any?, vararg args: Any?): Any? {
        return proceedInvocation(thisObject, args)
    }

    override fun invokeSpecial(thisObject: Any, vararg args: Any?): Any? {
        return HookBridge.invokeSpecialMethod(
            executable,
            getExecutableShorty(),
            executable.declaringClass,
            thisObject,
            *args,
        )
    }
}

/** Invoker implementation specifically for [Constructor] types. */
internal class VectorCtorInvoker<T : Any>(constructor: Constructor<T>) :
    BaseInvoker<CtorInvoker<T>, Constructor<T>>(constructor), CtorInvoker<T> {

    override fun invoke(thisObject: Any?, vararg args: Any?): Any? {
        proceedInvocation(thisObject, args)
        return null
    }

    override fun invokeSpecial(thisObject: Any, vararg args: Any?): Any? {
        HookBridge.invokeSpecialMethod(
            executable,
            getExecutableShorty(),
            executable.declaringClass,
            thisObject,
            *args,
        )
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun newInstance(vararg args: Any?): T {
        val obj = HookBridge.allocateObject(executable.declaringClass)
        proceedInvocation(obj, args)
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    override fun <U : Any> newInstanceSpecial(subClass: Class<U>, vararg args: Any?): U {
        if (!executable.declaringClass.isAssignableFrom(subClass)) {
            throw IllegalArgumentException(
                "$subClass is not inherited from ${executable.declaringClass}"
            )
        }
        val obj = HookBridge.allocateObject(subClass)
        HookBridge.invokeSpecialMethod(
            executable,
            getExecutableShorty(),
            executable.declaringClass,
            obj,
            *args,
        )
        return obj
    }
}
