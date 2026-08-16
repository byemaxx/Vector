package org.matrix.vector.nativebridge

import dalvik.annotation.optimization.FastNative
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object HookBridge {
    @JvmStatic
    external fun hookMethod(
        useModernApi: Boolean,
        hookMethod: Executable,
        hooker: Class<*>,
        priority: Int,
        callback: Any?,
    ): Boolean

    @JvmStatic
    external fun unhookMethod(
        useModernApi: Boolean,
        hookMethod: Executable,
        callback: Any?,
    ): Boolean

    /**
     * Swaps [oldCallback] for [newCallback] on [hookMethod] under the lock [callbackSnapshot]
     * takes, so no snapshot can observe both or neither.
     *
     * A snapshot taken before this returns keeps running [oldCallback]: it copied the reference
     * into an array of its own. That is what makes a replacement invisible to a call already in
     * flight, which is what `HookHandle#replaceHook` promises.
     *
     * Returns false when [oldCallback] is no longer registered, which is the caller's cue that the
     * handle it holds has already been replaced or unhooked.
     */
    @JvmStatic
    external fun replaceCallback(
        useModernApi: Boolean,
        hookMethod: Executable,
        oldCallback: Any?,
        newCallback: Any?,
        newPriority: Int,
    ): Boolean

    @JvmStatic external fun deoptimizeMethod(method: Executable): Boolean

    /**
     * Allocates an instance of [clazz] without running any constructor.
     *
     * Throws InstantiationException for a class that has no instances to allocate - an interface,
     * an array class, a primitive type or an abstract class - which is what
     * `Constructor#newInstance` reports and what the runtime would otherwise only catch in a debug
     * build.
     */
    @JvmStatic
    @Throws(InstantiationException::class)
    external fun <T> allocateObject(clazz: Class<T>): T

    @JvmStatic
    @Throws(
        IllegalAccessException::class,
        IllegalArgumentException::class,
        InvocationTargetException::class,
    )
    external fun invokeOriginalMethod(method: Executable, thisObject: Any?, vararg args: Any?): Any?

    /**
     * Runs [executable]'s own body, skipping the trampoline of every hook installed on it.
     *
     * The one dispatch primitive behind the whole invoker family, and behind the legacy bridge.
     * JNI performs no access control, which is what lets an invocation through an invoker bypass
     * access checks - and no receiver or argument check either, so this refuses against
     * [parameterTypes] everything reflection would refuse. Callers refuse it earlier still, so that
     * their own refusal is not reported as something the call produced.
     *
     * [shorty] carries the return type first, then one character per parameter. [declaringClass] is
     * the class to dispatch against, which is the superclass for a `newInstanceSpecial`.
     * [isStatic] and [nonVirtual] pick the JNI call form; a null [args] means no arguments.
     *
     * Only what the call itself throws is reported wrapped in [InvocationTargetException]; a
     * refusal of the receiver or of an argument propagates raw, as `Method#invoke` reports it.
     * IllegalAccessException is not among them, because neither branch runs an access check.
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class, InvocationTargetException::class)
    external fun invokeOriginal(
        executable: Executable,
        shorty: CharArray,
        parameterTypes: Array<Class<*>>,
        declaringClass: Class<*>,
        isStatic: Boolean,
        nonVirtual: Boolean,
        thisObject: Any?,
        args: Array<Any?>?,
    ): Any?

    @JvmStatic @FastNative external fun instanceOf(obj: Any?, clazz: Class<*>): Boolean

    @JvmStatic @FastNative external fun setTrusted(cookie: Any?): Boolean

    /**
     * Clears the final flag ART reads, so that reflection will write [field] again.
     *
     * Android 17 refuses every reflective write to a static final field however accessible the
     * [Field] is, and clearing the reflective copy's flag does not help because the check reads
     * the ArtField. The write itself stays with reflection, which keeps its conversions and its
     * exceptions; this only stops it being refused.
     *
     * [modifiers] must be `field.modifiers`. It is checked against the flags about to be written,
     * so a runtime that lays an ArtField out differently is left alone rather than corrupted.
     *
     * Returns false when the field is still final, which is the caller's cue to report the
     * failure it already had. The field stays writable afterwards.
     */
    @JvmStatic external fun makeFieldWritable(field: Field, modifiers: Int): Boolean

    /** Returns null when [method] carries no hooks at all. */
    @JvmStatic
    external fun callbackSnapshot(
        hooker_callback: Class<*>,
        method: Executable,
    ): Array<Array<Any?>>?

    /**
     * Locates a class's static initializer without initializing it.
     * [artMethods] must be the ArtMethod addresses of the class's declared constructors and
     * methods, which reflection can supply without triggering initialization, and [artMethodSize]
     * the size of one ArtMethod. One member is enough, which matters because a class whose only
     * members are the static initializer and an implicit constructor shows just one to reflection.
     *
     * Returns null when the class has no static initializer or the method layout is not the one
     * this relies on.
     */
    @JvmStatic
    external fun findStaticInitializer(
        clazz: Class<*>,
        artMethods: LongArray,
        artMethodSize: Long,
    ): Executable?

    /**
     * The class name prefixes of the legacy `de.robv` API as this process will actually be asked
     * for them, which is not the same as what they are called in source: dex obfuscation rewrites
     * them, in the framework and in every module, to a different random string on every boot.
     *
     * A module targeting API 102 is not allowed to reach any of them, and the module class loader
     * is the only place that can be enforced.
     */
    @JvmStatic external fun legacyApiPrefixes(): Array<String>
}
