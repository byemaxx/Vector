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

    @JvmStatic external fun deoptimizeMethod(method: Executable): Boolean

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

    @JvmStatic
    @Throws(
        IllegalAccessException::class,
        IllegalArgumentException::class,
        InvocationTargetException::class,
    )
    external fun <T> invokeSpecialMethod(
        method: Executable,
        shorty: CharArray,
        clazz: Class<T>,
        thisObject: Any?,
        vararg args: Any?,
    ): Any?

    @JvmStatic @FastNative external fun instanceOf(obj: Any?, clazz: Class<*>): Boolean

    @JvmStatic @FastNative external fun setTrusted(cookie: Any?): Boolean

    /**
     * Clears the final flag ART reads, so that reflection can write [field] again.
     *
     * Android 17 checks the underlying ArtField rather than only the reflective Field object's
     * accessibility state. [modifiers] must be `field.modifiers`; native code verifies those
     * flags before changing ACC_FINAL so an unexpected ART layout is rejected safely.
     *
     * Adapted from JingMatrix/Vector commit 44552398db793a6d02b33acbc66978966950ffef.
     */
    @JvmStatic external fun makeFieldWritable(field: Field, modifiers: Int): Boolean

    @JvmStatic
    external fun callbackSnapshot(hooker_callback: Class<*>, method: Executable): Array<Array<Any?>>

    @JvmStatic external fun getStaticInitializer(clazz: Class<*>): Method?
}
