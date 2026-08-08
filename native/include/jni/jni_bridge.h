#pragma once

#include <cstdint>
#include <string>
#include <string_view>

#include "common/logging.h"
#include "core/config_bridge.h"
#include "core/context.h"

/**
 * @file jni_bridge.h
 * @brief Provides essential macros and helper functions for creating JNI bridges.
 *
 */

namespace vector::native::jni {

/**
 * @brief Returns the number of elements in a statically-allocated C-style array.
 *
 * This is a compile-time constant.
 * Attempting to use this on a pointer will result in a compilation error,
 * preventing common mistakes.
 *
 * @tparam T The type of the array elements.
 * @tparam N The size of the array.
 * @param arr A reference to the array.
 * @return The number of elements in the array.
 */
template <typename T, size_t N>
[[nodiscard]] constexpr inline size_t ArraySize(T (&)[N]) {
    return N;
}

/**
 * @brief A helper function to get the obfuscated native bridge class signature prefix.
 *
 * It reads the obfuscation map to find the correct, potentially obfuscated,
 * package name for the native bridge classes.
 *
 * @return The JNI signature prefix (e.g., "org/matrix/vector/nativebridge/").
 */
inline std::string GetNativeBridgeSignature() {
    auto *bridge = ConfigBridge::GetInstance();
    if (bridge) {
        const auto &obfs_map = bridge->obfuscation_map();
        // The key must match what the Java build script places in the map.
        auto it = obfs_map.find("org.matrix.vector.nativebridge.");
        if (it != obfs_map.end()) {
            return it->second;
        }
    }
    // Fallback or default value if not found.
    return "org/matrix/vector/nativebridge/";
}

/**
 * @brief Internal implementation for registering native methods.
 *
 * Finds the target class using the framework's class loader and calls JNI's RegisterNatives.
 */
[[gnu::always_inline]]
inline bool RegisterNativeMethodsInternal(JNIEnv *env, std::string_view class_name,
                                          const JNINativeMethod *methods, jint method_count) {
    auto *context = Context::GetInstance();
    if (!context) {
        LOGF("Cannot register natives for '{}', Context is null.", class_name.data());
        return false;
    }
    auto clazz = context->FindClassFromCurrentLoader(env, class_name);
    if (clazz.get() == nullptr) {
        LOGF("JNI class not found: {}", class_name.data());
        return false;
    }
    // Wrapped: a failed registration throws NoSuchMethodError, and returning false while that
    // exception is still pending would hand the next JNI call undefined behaviour.
    return lsplant::JNI_RegisterNatives(env, clazz, methods, method_count) == JNI_OK;
}

// A helper cast for the native method function pointers.
#define VECTOR_JNI_CAST(to) reinterpret_cast<to>

/**
 * @def VECTOR_NATIVE_METHOD(className, functionName, signature)
 * @brief Defines a JNINativeMethod entry.
 *
 * This macro constructs a JNINativeMethod struct, automatically
 * creating the mangled C-style function name that JNI expects.
 *
 * @param className The simple name of the Java class (e.g., "HookBridge").
 * @param functionName The name of the Java method (e.g., "hookMethod").
 * @param signature The JNI signature of the method (e.g., "(I)V").
 */
#define VECTOR_NATIVE_METHOD(className, functionName, signature)                                   \
    {#functionName, signature,                                                                     \
     VECTOR_JNI_CAST(void *)(Java_org_matrix_vector_nativebridge_##className##_##functionName)}

/**
 * @def JNI_START
 * @brief Defines the standard first two arguments for any JNI native method implementation.
 */
#define JNI_START [[maybe_unused]] JNIEnv *env, [[maybe_unused]] jclass clazz

/**
 * @def VECTOR_DEF_NATIVE_METHOD(ret, className, functionName, ...)
 * @brief Defines the function signature for a JNI native method implementation.
 *
 * This macro creates the full C++ function definition with
 * the correct JNI name-mangling convention.
 */
#define VECTOR_DEF_NATIVE_METHOD(ret, className, functionName, ...)                                \
    extern "C" JNIEXPORT ret JNICALL                                                               \
        Java_org_matrix_vector_nativebridge_##className##_##functionName(JNI_START, ##__VA_ARGS__)

/**
 * Android 17 refuses reflective writes to static-final fields even when Field is accessible.
 *
 * This is adapted from JingMatrix/Vector commit
 * 44552398db793a6d02b33acbc66978966950ffef. Vector-SR keeps the primitive in the shared JNI
 * registration layer instead of replacing its API102-modified hook_bridge.cpp wholesale.
 */
inline jboolean MakeFieldWritable(JNIEnv *env, [[maybe_unused]] jclass clazz, jobject field,
                                  jint modifiers) {
    // On supported ART releases jfieldID points at ArtField and access_flags_ follows the
    // four-byte compressed declaring_class_ root. Validate the Java-visible flags before writing
    // so an unexpected runtime layout is rejected instead of corrupted.
    auto *art_field = reinterpret_cast<uint32_t *>(env->FromReflectedField(field));
    if (art_field == nullptr) return JNI_FALSE;

    constexpr uint32_t kAccJavaFlagsMask = 0xFFFFu;
    constexpr uint32_t kAccFinal = 0x0010u;

    const uint32_t flags = art_field[1];
    if ((flags & kAccJavaFlagsMask) != static_cast<uint32_t>(modifiers)) return JNI_FALSE;

    art_field[1] = flags & ~kAccFinal;
    return JNI_TRUE;
}

/**
 * Register native methods that are intentionally kept outside a bridge's main translation unit.
 *
 * The supplemental HookBridge registration lets Vector-SR retain its API102-modified
 * hook_bridge.cpp while still carrying the Android 17 static-final fix from upstream.
 */
inline bool RegisterSupplementalNativeMethods(JNIEnv *env, std::string_view class_name) {
    if (!class_name.ends_with("HookBridge")) return true;

    static JNINativeMethod hook_bridge_supplemental[] = {
        {"makeFieldWritable", "(Ljava/lang/reflect/Field;I)Z",
         VECTOR_JNI_CAST(void *)(MakeFieldWritable)},
    };
    return RegisterNativeMethodsInternal(env, class_name, hook_bridge_supplemental,
                                         ArraySize(hook_bridge_supplemental));
}

/**
 * @def REGISTER_VECTOR_NATIVE_METHODS(class_name)
 * @brief Registers all methods defined in the `gMethods` array for a given class.
 *
 * This is the final step in linking the C++ implementations to the Java native methods.
 */
#define REGISTER_VECTOR_NATIVE_METHODS(class_name)                                                 \
    (RegisterNativeMethodsInternal(env, GetNativeBridgeSignature() + #class_name, gMethods,        \
                                   ArraySize(gMethods)) &&                                         \
     RegisterSupplementalNativeMethods(env, GetNativeBridgeSignature() + #class_name))

}  // namespace vector::native::jni
