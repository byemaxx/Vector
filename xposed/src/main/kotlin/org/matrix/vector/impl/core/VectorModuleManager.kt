package org.matrix.vector.impl.core

import android.os.Build
import android.os.Bundle
import android.os.Process
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.io.File
import java.lang.reflect.Array
import java.util.Collections
import java.util.IdentityHashMap
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.VectorContext
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.hooks.freezeHooks
import org.matrix.vector.impl.hooks.getActiveHookHandles
import org.matrix.vector.impl.hooks.unhookAllModuleHooks
import org.matrix.vector.impl.hooks.unfreezeHooks
import org.matrix.vector.impl.utils.VectorModuleClassLoader
import org.matrix.vector.nativebridge.NativeAPI

/**
 * Responsible for loading modules into the target process. Handles ClassLoader isolation and
 * injects the framework context into the module instances.
 */
object VectorModuleManager {

    private const val TAG = "VectorModuleManager"
    private val moduleStates = java.util.concurrent.ConcurrentHashMap<String, ModuleState>()

    private data class ModuleState(
        val module: Module,
        val processName: String,
        val isSystemServer: Boolean,
        val entries: List<XposedModule>,
    )

    /**
     * Loads a module APK, instantiates its entry classes, and binds them to the Vector framework.
     */
    fun loadModule(module: Module, isSystemServer: Boolean, processName: String): Boolean {
        try {
            Log.d(TAG, "Loading module ${module.packageName}")

            val librarySearchPath = buildLibrarySearchPath(module)

            // Create the isolated ClassLoader for the module
            val initLoader = XposedModule::class.java.classLoader
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    module.file.preLoadedDexes,
                    librarySearchPath,
                    initLoader,
                )

            // Security/Integrity Check: Ensure the module isn't bundling its own API classes
            if (
                moduleClassLoader.loadClass(XposedModule::class.java.name).classLoader !==
                    initLoader
            ) {
                Log.e(TAG, "The Xposed API classes are compiled into ${module.packageName}")
                return false
            }

            // Create the Context that will be injected into the module
            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = module.applicationInfo,
                    service = module.service, // Our IPC client
                )

            val entries = instantiateEntries(module, moduleClassLoader, vectorContext)
            entries.forEach { moduleInstance ->
                VectorLifecycleManager.activeModules.add(moduleInstance)
                runCatching {
                        moduleInstance.onModuleLoaded(
                            object : ModuleLoadedParam {
                                override fun isSystemServer(): Boolean = isSystemServer

                                override fun getProcessName(): String = processName
                            }
                        )
                    }
                    .onFailure {
                        Log.e(TAG, "Error in onModuleLoaded for ${module.packageName}", it)
                    }
            }
            moduleStates[module.packageName] = ModuleState(module, processName, isSystemServer, entries)
            if (module.file.moduleClassNames.size == 1) {
                VectorServiceClient.registerHotReloadTarget(
                    module.packageName,
                    module.versionCode,
                    VectorHotReloadTarget,
                )
            }

            // Register any native JNI entrypoints declared by the module
            module.file.moduleLibraryNames.forEach { libraryName ->
                NativeAPI.recordNativeEntrypoint(libraryName)
            }

            Log.d(TAG, "Loaded module ${module.packageName} successfully.")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error loading module ${module.packageName}", e)
            return false
        }
    }

    @Synchronized
    fun hotReloadModule(module: Module, extras: Bundle?) {
        val oldState =
            moduleStates[module.packageName]
                ?: throw IllegalStateException("Module ${module.packageName} is not loaded")
        if (module.file.moduleClassNames.size != 1) {
            throw IllegalArgumentException("Hot reload requires exactly one Java entry class")
        }

        val oldClassLoaders =
            oldState.entries.mapNotNullTo(mutableSetOf<ClassLoader>()) { it.javaClass.classLoader }
        var savedInstanceState: Any? = null
        val reloadingParam =
            object : HotReloadingParam {
                override fun getExtras(): Bundle? = extras

                override fun setSavedInstanceState(outState: Any?) {
                    if (containsOldClassLoaderObject(outState, oldClassLoaders)) {
                        throw IllegalArgumentException(
                            "Saved state must not be created by the old module classloader"
                        )
                    }
                    savedInstanceState = outState
                }
            }

        var allowReload = true
        oldState.entries.forEach { entry ->
            if (!allowReload) return@forEach
            allowReload =
                runCatching { entry.onHotReloading(reloadingParam) }
                    .onFailure { Log.e(TAG, "Error in onHotReloading for ${module.packageName}", it) }
                    .getOrThrow()
        }
        if (!allowReload) {
            Log.d(TAG, "Module ${module.packageName} rejected hot reload")
            throw IllegalStateException()
        }

        freezeHooks(module.packageName, oldClassLoaders)
        val oldHandles = getActiveHookHandles(module.packageName)
        var newStateCommitted = false
        var newEntries: List<XposedModule> = emptyList()
        try {
            val librarySearchPath = buildLibrarySearchPath(module)
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    module.file.preLoadedDexes,
                    librarySearchPath,
                    XposedModule::class.java.classLoader,
                )
            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = module.applicationInfo,
                    service = module.service,
                )
            newEntries = instantiateEntries(module, moduleClassLoader, vectorContext)
            if (newEntries.size != module.file.moduleClassNames.size) {
                throw IllegalStateException("Failed to instantiate hot reload entry")
            }

            val param =
                object : HotReloadedParam {
                    override fun isSystemServer(): Boolean = oldState.isSystemServer

                    override fun getProcessName(): String = oldState.processName

                    override fun getExtras(): Bundle? = extras

                    override fun getSavedInstanceState(): Any? = savedInstanceState

                    override fun getOldHookHandles(): List<XposedInterface.HookHandle> = oldHandles
                }
            moduleStates[module.packageName] =
                ModuleState(module, oldState.processName, oldState.isSystemServer, newEntries)
            newStateCommitted = true
            // Keep oldState strongly reachable until callbacks finish, but stop lifecycle dispatch.
            oldState.entries.forEach(VectorLifecycleManager::detach)
            newEntries.forEach { entry ->
                VectorLifecycleManager.activeModules.add(entry)
                runCatching { entry.onHotReloaded(param) }
                    .onFailure { Log.e(TAG, "Error in onHotReloaded for ${module.packageName}", it) }
                    .getOrThrow()
            }
        } finally {
            if (newStateCommitted) {
                oldState.entries.forEach(VectorLifecycleManager::detach)
            } else {
                newEntries.forEach(VectorLifecycleManager::detach)
                unhookAllModuleHooks(module.packageName, oldHandles.toSet())
            }
            unfreezeHooks(module.packageName)
        }
    }

    private fun buildLibrarySearchPath(module: Module): String = buildString {
        val abis =
            if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
            else Build.SUPPORTED_32_BIT_ABIS
        for (abi in abis) {
            append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator)
        }
    }

    private fun instantiateEntries(
        module: Module,
        moduleClassLoader: ClassLoader,
        vectorContext: VectorContext,
    ): List<XposedModule> {
        val entries = mutableListOf<XposedModule>()
        for (className in module.file.moduleClassNames) {
            runCatching {
                    val moduleClass = moduleClassLoader.loadClass(className)
                    Log.v(TAG, "Loading class $moduleClass")

                    if (!XposedModule::class.java.isAssignableFrom(moduleClass)) {
                        Log.e(TAG, "Class does not extend XposedModule, skipping.")
                        return@runCatching
                    }

                    val constructor = moduleClass.getDeclaredConstructor()
                    constructor.isAccessible = true
                    val moduleInstance = constructor.newInstance() as XposedModule
                    moduleInstance.attachFramework(vectorContext) {
                        VectorLifecycleManager.detach(moduleInstance)
                    }
                    entries += moduleInstance
                }
                .onFailure { e -> Log.e(TAG, "Failed to instantiate class $className", e) }
        }
        return entries
    }

    @Suppress("DEPRECATION")
    private fun containsOldClassLoaderObject(
        value: Any?,
        oldClassLoaders: Set<ClassLoader>,
        seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
    ): Boolean {
        if (value == null || !seen.add(value)) return false
        if (value is ClassLoader && value in oldClassLoaders) return true
        if (value is Class<*> && value.classLoader in oldClassLoaders) return true
        if (value.javaClass.classLoader in oldClassLoaders) return true
        if (value is Bundle) {
            return value.keySet().any { key ->
                runCatching { containsOldClassLoaderObject(value.get(key), oldClassLoaders, seen) }
                    .getOrDefault(true)
            }
        }
        if (value is Map<*, *>) {
            return value.entries.any {
                containsOldClassLoaderObject(it.key, oldClassLoaders, seen) ||
                    containsOldClassLoaderObject(it.value, oldClassLoaders, seen)
            }
        }
        if (value is Iterable<*>) {
            return value.any { containsOldClassLoaderObject(it, oldClassLoaders, seen) }
        }
        if (value.javaClass.isArray) {
            for (index in 0 until Array.getLength(value)) {
                if (containsOldClassLoaderObject(Array.get(value, index), oldClassLoaders, seen)) {
                    return true
                }
            }
        }
        return false
    }

}
