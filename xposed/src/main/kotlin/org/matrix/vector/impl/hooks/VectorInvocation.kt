package org.matrix.vector.impl.hooks

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.matrix.vector.nativebridge.HookBridge

/**
 * What java.lang.reflect does to a receiver and an argument list before it calls anything, for the
 * callers that reach the executable through JNI instead.
 *
 * JNI reports none of it. A foreign receiver or a mistyped argument is not refused, it is executed,
 * with the callee reading fields at offsets that belong to a different layout; a boxed value of the
 * wrong width is not refused either, it is silently truncated. Method#invoke reports all of that as
 * IllegalArgumentException, and the invoker interface is specified against Method#invoke.
 *
 * The checks live here rather than in the JNI backend because a shorty cannot name the declared
 * class of a reference parameter, and they run before the hook chain is entered because everything
 * thrown inside the chain is reported wrapped - a refusal of ours is not something the call
 * produced.
 *
 * The messages are ART's own, from `art/runtime/reflection.cc`, so a module that reads one - or a
 * conformance suite that asserts on it - gets the same text here as from Method#invoke.
 * `Class#getTypeName` is the Java side of ART's PrettyDescriptor: dotted, and `int[]` for an array.
 *
 * The receiver decides one more thing reflection settles before it calls anything, and that is
 * *which* method it calls: Method#invoke dispatches virtually, so a Method taken from a superclass
 * reaches the receiver's override. A caller that already holds the target - which is what every
 * dispatch through a hook backup is - reaches no override on its own, so the answer is computed
 * here instead.
 */
object VectorInvocation {

    /**
     * The JNI shorty of [executable]: the return type first, then one character per parameter.
     * Reference types and arrays are both 'L', which is ART's own convention.
     */
    fun shortyOf(executable: Executable, parameterTypes: Array<Class<*>>): CharArray {
        val shorty = CharArray(parameterTypes.size + 1)
        shorty[0] = shortyOf(if (executable is Method) executable.returnType else Void.TYPE)
        for (i in parameterTypes.indices) {
            shorty[i + 1] = shortyOf(parameterTypes[i])
        }
        return shorty
    }

    private fun shortyOf(type: Class<*>): Char =
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

    /**
     * The receiver Method#invoke would call with: a static executable ignores it, a missing one is
     * a NullPointerException and one of a foreign class an IllegalArgumentException.
     */
    fun checkReceiver(executable: Executable, isStatic: Boolean, thisObject: Any?): Any? {
        if (isStatic) return null
        if (thisObject == null) throw NullPointerException("null receiver")
        if (!executable.declaringClass.isInstance(thisObject)) {
            throw IllegalArgumentException(
                "Expected receiver of type ${executable.declaringClass.typeName}, " +
                    "but got ${thisObject.javaClass.typeName}"
            )
        }
        return thisObject
    }

    /**
     * Whether any class could put another body in front of [method] for some receiver.
     *
     * A static or private method has no vtable slot to replace - the runtime dispatches both
     * directly - and a final method, or any method of a final class, has one that nothing is
     * allowed to replace. None of that depends on the receiver, so an invoker settles it once
     * instead of walking a hierarchy per call.
     */
    fun canBeOverridden(method: Method): Boolean {
        val modifiers = method.modifiers
        if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) return false
        if (Modifier.isFinal(modifiers)) return false
        // An interface is never final, and a method declared by one is overridden by definition.
        return !Modifier.isFinal(method.declaringClass.modifiers)
    }

    /**
     * The declaration a virtual call on [receiverClass] enters, when that is not [method] itself.
     *
     * The runtime answers this out of the vtable, which Java cannot read, so the answer is rebuilt
     * the way the vtable is: starting at the declaring class and coming down towards the receiver,
     * each class's own declaration replacing the one it overrides. Coming down rather than up from
     * the receiver is what makes overriding transitive, which it is - a package-private method may
     * be overridden inside its own package by a declaration that widens it to public, and a
     * subclass in another package then overrides that one, and so this one too, though it could
     * never have overridden it directly.
     *
     * [parameterTypes] is the caller's copy of what `method.getParameterTypes()` returns, which is
     * cloned on every call and does not change for the life of an invoker.
     *
     * Asking the same question again about the answer settles it: a second walk for the same
     * receiver starts at the class the first one stopped in and covers exactly the classes it had
     * already rejected, so it finds nothing. Dispatch resolves once and reaches a fixed point.
     *
     * @return the override, or null when [method] is what the call reaches. A receiver that is not
     *   an instance of the declaring class answers null too: its own hierarchy says nothing about a
     *   method it does not have, and the refusal is [checkReceiver]'s to report.
     */
    fun virtualTargetOf(
        method: Method,
        parameterTypes: Array<Class<*>>,
        receiverClass: Class<*>,
    ): Method? {
        val declaringClass = method.declaringClass
        if (!declaringClass.isAssignableFrom(receiverClass)) return null

        // The receiver's superclasses down to the declaring class, most derived first. An interface
        // is not on that chain, so a method declared by one ends the walk at Object instead, which
        // finds the implementing class's declaration - what Method#invoke reaches for the shape that
        // matters. It does not find a more specific default: where a sub-interface overrides a
        // default and no class declares it, the walk answers nothing and the call reaches the
        // declared interface's default. Resolving that needs a walk of the interface graph as well,
        // and no caller has wanted one yet.
        val classes = ArrayList<Class<*>>(4)
        var clazz: Class<*>? = receiverClass
        while (clazz != null && clazz !== declaringClass) {
            classes.add(clazz)
            clazz = clazz.superclass
        }

        var current = method
        for (i in classes.size - 1 downTo 0) {
            current = declaredOverrideIn(classes[i], current, parameterTypes) ?: current
        }
        return if (current === method) null else current
    }

    /** The declaration in [clazz] that takes [inherited]'s slot, or null when it has none. */
    private fun declaredOverrideIn(
        clazz: Class<*>,
        inherited: Method,
        parameterTypes: Array<Class<*>>,
    ): Method? {
        for (candidate in clazz.declaredMethods) {
            // The name is tested first because it is the only test that resolves nothing: reading a
            // return or parameter type of a method this class merely happens to declare would load
            // classes on a path that has no business loading any.
            if (candidate.name != inherited.name) continue
            // The return type is part of what the runtime matches on, and has to be here too. A
            // covariant override compiles to two methods - the narrow one, plus a synthetic bridge
            // carrying the inherited signature - and it is the bridge that takes the slot. Entering
            // the narrow one instead would skip whatever the bridge does and any hook on it.
            if (candidate.returnType !== inherited.returnType) continue
            if (candidate.parameterCount != parameterTypes.size) continue
            if (!candidate.parameterTypes.contentEquals(parameterTypes)) continue

            // No class can declare two methods agreeing on all three, so this one decides whether
            // the class contributes an override or nothing at all - and a private or static
            // declaration contributes nothing, because it is dispatched directly and leaves the
            // inherited slot exactly where it was.
            val modifiers = candidate.modifiers
            if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) return null
            return if (overrides(inherited, clazz)) candidate else null
        }
        return null
    }

    /**
     * Whether a declaration in [subclass] may replace [inherited], which for anything but a
     * package-private method it always may.
     *
     * A package-private method is overridden only from inside its own runtime package, and a
     * runtime package is the package name together with the defining class loader: two loaders each
     * defining a `com.example.Foo` define two packages, and a method in one overrides nothing in
     * the other. Reading this rule as the name alone would silently redirect every package-private
     * call whose receiver was loaded somewhere else - which, in a process hosting an app, a module
     * and the framework at once, is not a rare shape.
     */
    private fun overrides(inherited: Method, subclass: Class<*>): Boolean {
        val modifiers = inherited.modifiers
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) return true
        val owner = inherited.declaringClass
        return owner.classLoader === subclass.classLoader &&
            packageNameOf(owner) == packageNameOf(subclass)
    }

    /**
     * The package a class belongs to, taken from its name because that is where the runtime takes
     * it from - and because Class#getPackageName arrived in API 28, above what this supports.
     */
    private fun packageNameOf(clazz: Class<*>): String {
        val name = clazz.name
        val lastDot = name.lastIndexOf('.')
        return if (lastDot < 0) "" else name.substring(0, lastDot)
    }

    /**
     * The argument list Method#invoke would build: one identity or widening conversion per
     * argument, and IllegalArgumentException for every other pair.
     *
     * Converting here rather than at the dispatch is what lets a hooker see through Chain#getArgs
     * the values the executable will actually receive, which is what a hooked call arriving from
     * real bytecode always carries.
     */
    fun coerceArguments(
        executable: Executable,
        parameterTypes: Array<Class<*>>,
        args: Array<out Any?>,
    ): Array<Any?> {
        if (args.size != parameterTypes.size) {
            throw IllegalArgumentException(
                "Wrong number of arguments; expected ${parameterTypes.size}, got ${args.size}"
            )
        }
        return Array(args.size) { i -> coerce(executable, parameterTypes[i], args[i], i) }
    }

    private fun coerce(executable: Executable, type: Class<*>, value: Any?, index: Int): Any? {
        if (!type.isPrimitive) {
            // Class#isInstance is the whole rule: it answers for interfaces, for arrays and their
            // covariance, and false for null, which is why null is short-circuited first.
            if (value != null && !type.isInstance(value)) {
                throw mismatch(executable, index, type, value)
            }
            return value
        }
        // A null where a primitive is declared is refused with the same message: ART routes it
        // through the same test as a mistyped reference and prints "null" for what it got.
        if (value == null) throw mismatch(executable, index, type, null)
        // Identity plus the widening primitive conversions of JLS 5.1.2, and nothing else. The
        // tests below are exact-wrapper tests, which is what reflection does: a Number that is not
        // one of the eight wrappers converts to nothing, and neither does a Character to a short.
        val widened: Any? =
            when (type) {
                Boolean::class.javaPrimitiveType -> value as? Boolean
                Char::class.javaPrimitiveType -> value as? Char
                Byte::class.javaPrimitiveType -> value as? Byte
                Short::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toShort()
                        is Short -> value
                        else -> null
                    }
                Int::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toInt()
                        is Short -> value.toInt()
                        is Char -> value.code
                        is Int -> value
                        else -> null
                    }
                Long::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toLong()
                        is Short -> value.toLong()
                        is Char -> value.code.toLong()
                        is Int -> value.toLong()
                        is Long -> value
                        else -> null
                    }
                Float::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toFloat()
                        is Short -> value.toFloat()
                        is Char -> value.code.toFloat()
                        is Int -> value.toFloat()
                        is Long -> value.toFloat()
                        is Float -> value
                        else -> null
                    }
                Double::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toDouble()
                        is Short -> value.toDouble()
                        is Char -> value.code.toDouble()
                        is Int -> value.toDouble()
                        is Long -> value.toDouble()
                        is Float -> value.toDouble()
                        is Double -> value
                        else -> null
                    }
                else -> null
            }
        return widened ?: throw mismatch(executable, index, type, value)
    }

    private fun mismatch(
        executable: Executable,
        index: Int,
        type: Class<*>,
        value: Any?,
    ): IllegalArgumentException =
        IllegalArgumentException(
            "method ${prettyMethod(executable)} argument ${index + 1} has type " +
                "${type.typeName}, got ${value?.javaClass?.typeName ?: "null"}"
        )

    /**
     * ART's PrettyMethod without the signature: the declaring class and the name the runtime knows
     * the member by, which for a constructor is `<init>` and not the class name Constructor#getName
     * reports. Arguments are numbered from one for the same reason ART numbers them from one.
     */
    private fun prettyMethod(executable: Executable): String {
        val name = if (executable is Constructor<*>) "<init>" else executable.name
        return "${executable.declaringClass.typeName}.$name"
    }

    /**
     * The whole of the legacy bridge's invocation. `XposedBridge.invokeOriginalMethod` is
     * documented as Method#invoke without the access check and is handed an Executable of either
     * kind, so it needs what an invoker needs; it holds no invoker, so nothing here is cached.
     *
     * A constructor is dispatched non-virtually because it is a direct method either way. A method
     * asks for a virtual dispatch, and gets one while it carries no hook; once it does, it is
     * reached through lsplant's backup, which is private and so dispatched directly, and an
     * overriding receiver reaches this executable's body rather than its override. The modern
     * invoker resolves the override for itself rather than rely on the dispatch - see
     * [VectorMethodInvoker.invokeWith] - and this bridge deliberately does not: `invokeOriginalMethod`
     * is what a legacy hooker calls to run the method it hooked, so the executable it named is the
     * one it means, and resolving an override here would send a hooker's own super call back into
     * its hook.
     *
     * IllegalAccessException is not among the outcomes: neither branch of the dispatch runs an
     * access check, which is the whole point of the legacy bridge's "access permissions are not
     * checked".
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class, InvocationTargetException::class)
    fun invokeOriginal(executable: Executable, thisObject: Any?, args: Array<Any?>): Any? {
        val parameterTypes = executable.parameterTypes
        val isStatic = Modifier.isStatic(executable.modifiers)
        return HookBridge.invokeOriginal(
            executable,
            shortyOf(executable, parameterTypes),
            parameterTypes,
            executable.declaringClass,
            isStatic,
            executable is Constructor<*>,
            checkReceiver(executable, isStatic, thisObject),
            coerceArguments(executable, parameterTypes, args),
        )
    }
}
