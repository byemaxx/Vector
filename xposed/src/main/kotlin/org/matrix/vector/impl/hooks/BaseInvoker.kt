package org.matrix.vector.impl.hooks

import android.util.Log
import io.github.libxposed.api.XposedInterface.CtorInvoker
import io.github.libxposed.api.XposedInterface.Invoker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

private const val TAG = "VectorBaseInvoker"

/**
 * Base implementation of the Invoker system. Handles the resolution of [Invoker.Type] to determine
 * whether to execute the original method directly or to construct a partial interceptor chain.
 *
 * The vararg entry points the interface declares are not here but in [InvokerEntry], which is Java
 * for the one reason given there; what arrives here is the array they normalised.
 */
internal abstract class BaseInvoker<T : Invoker<T, U>, U : Executable>(
    protected val executable: U
) : InvokerEntry<T, U> {

    protected var type: Invoker.Type = Invoker.Type.Chain.FULL

    // An invoker names one executable for its whole life, and each of these would otherwise be
    // rebuilt per call: getParameterTypes clones its array every time it is asked, and the shorty
    // is derived from that array.
    protected val parameterTypes: Array<Class<*>> = executable.parameterTypes
    private val shorty: CharArray = VectorInvocation.shortyOf(executable, parameterTypes)
    protected val declaringClass: Class<*> = executable.declaringClass
    private val isStatic: Boolean = Modifier.isStatic(executable.modifiers)

    @Suppress("UNCHECKED_CAST")
    override fun setType(type: Invoker.Type): T {
        this.type = type
        return this as T
    }

    /**
     * Resolves [type] and runs the executable, non-virtually when [nonVirtual].
     *
     * The receiver and the arguments are checked before the chain is entered, because Method#invoke
     * reports its own refusals unwrapped and reserves InvocationTargetException for what the call
     * threw - and everything thrown inside the chain is what the call threw. [onReceiver] reports
     * the receiver each dispatch actually ran against, which a hooker may have redirected.
     *
     * [type] defaults to this invoker's own, and is a parameter for the one caller that has to
     * decide it from outside: a virtual invocation that resolved to an override runs the override's
     * chain, under the type asked of the invoker the module actually holds.
     */
    protected fun proceedInvocation(
        thisObject: Any?,
        args: Array<out Any?>,
        nonVirtual: Boolean,
        type: Invoker.Type = this.type,
        onReceiver: (Any?) -> Unit = {},
    ): Any? {
        val receiver = VectorInvocation.checkReceiver(executable, isStatic, thisObject)
        val actualArgs = VectorInvocation.coerceArguments(executable, parameterTypes, args)

        // Reaches the body this invoker names, never through the trampoline.
        fun dispatch(tObj: Any?, tArgs: Array<Any?>): Any? {
            onReceiver(tObj)
            return HookBridge.invokeOriginal(
                executable,
                shorty,
                parameterTypes,
                declaringClass,
                isStatic,
                nonVirtual,
                tObj,
                tArgs,
            )
        }

        return when (val currentType = type) {
            is Invoker.Type.Origin -> dispatch(receiver, actualArgs)
            is Invoker.Type.Chain -> {
                val snapshots =
                    HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable)
                        // The executable carries no hooks, so there is no chain to enter. Invokers
                        // default to Type.Chain.FULL, so this is the ordinary case for a module
                        // that obtains an invoker for a method it has not hooked.
                        ?: return dispatch(receiver, actualArgs)

                @Suppress("UNCHECKED_CAST")
                val allModernHooks = snapshots[0] as Array<VectorHookRecord>
                val legacyHooks = snapshots[1]

                // Filter hooks to respect the maxPriority requested by the module
                val filteredHooks =
                    allModernHooks.filter { it.priority <= currentType.maxPriority }.toTypedArray()

                // Chain#proceed is documented to throw whatever the original executable threw, so
                // the reflective wrapper comes off here rather than at the public boundary.
                val runOriginal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                    try {
                        dispatch(tObj, tArgs)
                    } catch (e: InvocationTargetException) {
                        throw e.cause ?: e
                    }
                }

                val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                    val delegate = VectorBootstrap.delegate
                    if (legacyHooks.isNotEmpty() && delegate != null) {
                        delegate.processLegacyHook(executable, tObj, tArgs, legacyHooks) {
                            runOriginal(tObj, tArgs)
                        }
                    } else {
                        runOriginal(tObj, tArgs)
                    }
                }

                val chain =
                    VectorChain(executable, receiver, actualArgs, filteredHooks, 0, terminal)
                try {
                    chain.proceed()
                } catch (t: Throwable) {
                    // The terminal took the wrapper off, so whatever arrives here is what the call
                    // produced - the executable's exception or a hooker's - and Method#invoke
                    // reports that wrapped, including an InvocationTargetException of its own.
                    throw InvocationTargetException(t)
                }
            }
        }
    }
}

/** Invoker implementation specifically for [Method] types. */
internal class VectorMethodInvoker(method: Method) :
    BaseInvoker<VectorMethodInvoker, Method>(method) {

    /**
     * One resolution, kept whole so that no reader can pair one call's class with another's target.
     */
    private class Resolution(val receiverClass: Class<*>, val target: VectorMethodInvoker)

    // Whether any receiver can move this call elsewhere at all. A property of the executable alone,
    // so it is settled once and short-circuits every call on a method nothing can override.
    private val overridable: Boolean = VectorInvocation.canBeOverridden(method)

    // What the executable alone cannot settle is which override a call reaches, because that
    // depends on the receiver's class - so that is what this is keyed by. One entry rather than a
    // map: a call site sees one receiver class almost always, which is what makes an inline cache
    // worth having, and a map would pin every class it ever saw - and through each, its whole
    // loader - for as long as the module holds the invoker.
    @Volatile private var resolved: Resolution? = null

    /**
     * `invoke` is documented "@see Method#invoke", and Method#invoke dispatches virtually: a Method
     * taken from a superclass reaches the receiver's override. That does not happen by itself here,
     * because a hooked executable is reached through lsplant's backup, which is private and so
     * dispatched directly - the override has to be resolved and entered explicitly.
     *
     * The override is a different executable carrying a chain of its own, and Method#invoke would
     * run it hooks and all, so what is entered is that chain and not this one's. Its invoker is
     * asked to run it directly rather than through this entry point, which is what makes the
     * redirection exactly one hop deep whatever the hierarchy looks like.
     */
    override fun invokeWith(thisObject: Any?, args: Array<Any?>): Any? =
        virtualTarget(thisObject).runChain(thisObject, args, type)

    override fun invokeSpecialWith(thisObject: Any?, args: Array<Any?>): Any? =
        proceedInvocation(thisObject, args, nonVirtual = true)

    /** Runs this invoker's own chain under a [type] its caller owns, resolving nothing further. */
    private fun runChain(thisObject: Any?, args: Array<Any?>, type: Invoker.Type): Any? =
        proceedInvocation(thisObject, args, nonVirtual = false, type = type)

    /**
     * The invoker whose executable this call reaches, which is this one unless the receiver's class
     * overrides.
     *
     * Type.Origin is answered with this invoker and never resolves, which is the one place the type
     * decides the dispatch. "Invokes the original executable, skipping all hooks" reads most simply
     * as the executable this invoker names, and the alternative breaks the idiom the whole type
     * exists for: a hooker on `Base.name` asking for the original with an overriding receiver in
     * hand would reach `Derived.name`, whose body calls `super.name()`, which is the hooked method
     * again - the hook would call itself until the stack ran out. Skipping all hooks cannot mean
     * entering one.
     *
     * A Chain type carries no such hazard, because the chain it enters is the override's own and
     * the override's `super` call reaches this executable's body once, not its hook.
     */
    private fun virtualTarget(thisObject: Any?): VectorMethodInvoker {
        if (!overridable || thisObject == null || type is Invoker.Type.Origin) return this
        val receiverClass = thisObject.javaClass
        // The commonest receiver of all, and the one a walk could say nothing about.
        if (receiverClass === declaringClass) return this

        val cached = resolved
        if (cached != null && cached.receiverClass === receiverClass) return cached.target

        // Resolution reads declared members of classes this call would otherwise never touch, so a
        // signature naming a class that is not there raises where the invocation would have
        // succeeded. Only that is caught: anything else is a bug in the walk and has to surface.
        // The answer is not cached either, because it is the wrong one - on a hooked executable it
        // reinstates the very defect this resolves, so a later call has to be free to try again.
        val override =
            try {
                VectorInvocation.virtualTargetOf(executable, parameterTypes, receiverClass)
            } catch (e: LinkageError) {
                Log.w(TAG, "Cannot resolve the override of $executable for $receiverClass", e)
                return this
            }

        val target = override?.let(::VectorMethodInvoker) ?: this
        resolved = Resolution(receiverClass, target)
        return target
    }
}

/**
 * Invoker implementation specifically for [Constructor] types. Extends capabilities to allocate and
 * initialize objects safely.
 */
internal class VectorCtorInvoker<T : Any>(constructor: Constructor<T>) :
    BaseInvoker<CtorInvoker<T>, Constructor<T>>(constructor), InvokerEntry.Ctor<T> {

    // A constructor is a direct method: it has no vtable slot for a receiver's class to override,
    // so every way of calling one is non-virtual.
    override fun invokeWith(thisObject: Any?, args: Array<Any?>): Any? {
        // Invoking a constructor as a method returns nothing (void/null)
        proceedInvocation(thisObject, args, nonVirtual = true)
        return null
    }

    override fun invokeSpecialWith(thisObject: Any?, args: Array<Any?>): Any? {
        proceedInvocation(thisObject, args, nonVirtual = true)
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun newInstanceWith(args: Array<Any?>): T {
        // Allocate memory without invoking <init>
        val allocated = HookBridge.allocateObject(executable.declaringClass)
        // A hooker may redirect the construction with Chain#proceedWith, and newInstance is
        // documented to return the instance the constructor initialized, not the one allocated.
        // Whichever object the chain settled on is a T: the declaring class here is the type asked
        // for, and no receiver that is not an instance of it reaches the constructor.
        var initialized: Any? = allocated
        proceedInvocation(allocated, args, nonVirtual = true) { initialized = it }
        return initialized as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> newInstanceSpecialWith(subClass: Class<V>, args: Array<Any?>): V {
        if (!executable.declaringClass.isAssignableFrom(subClass)) {
            throw IllegalArgumentException(
                "$subClass is not inherited from ${executable.declaringClass}"
            )
        }
        val allocated = HookBridge.allocateObject(subClass)
        var initialized: Any? = allocated
        proceedInvocation(allocated, args, nonVirtual = true) { initialized = it }
        // Here the type asked for is not the one the chain has to keep: a hooker's proceedWith only
        // owes the constructor an instance of its declaring class, the parent. Handing that back
        // would return something that is not a V, and the caller would find out at its own
        // checkcast, nowhere near the hooker that caused it.
        return (if (subClass.isInstance(initialized)) initialized else allocated) as V
    }
}