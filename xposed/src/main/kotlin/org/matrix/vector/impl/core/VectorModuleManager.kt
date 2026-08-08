package org.matrix.vector.impl.core

import android.os.Build
import android.os.Bundle
import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.io.File
import java.lang.reflect.Array
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.VectorContext
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.hooks.getActiveHookHandles
import org.matrix.vector.impl.hooks.hookLockOf
import org.matrix.vector.impl.utils.VectorModuleClassLoader
import org.matrix.vector.nativebridge.NativeAPI

/** Loads modern modules and owns their in-process generations. */
object VectorModuleManager {

    private const val TAG = "VectorModuleManager"
    private const val ENABLE_SYSTEM_SERVER_HOT_RELOAD = false

    private val moduleStates = java.util.concurrent.ConcurrentHashMap<String, ModuleState>()
    private val generationCounter = AtomicLong(1)

    private data class ModuleMetadata(
        val targetApiVersion: Int,
        val defaultExceptionMode: ExceptionMode,
    )

    private data class ModuleState(
        val module: Module,
        val packageName: String,
        val processName: String,
        val isSystemServer: Boolean,
        val entries: List<XposedModule>,
        val context: VectorContext,
        val classLoaders: Set<ClassLoader>,
        val codeIdentity: RuntimeModuleCodeIdentity,
        val generationId: Long,
        val targetApiVersion: Int,
    )

    private data class RuntimeModuleCodeIdentity(
        val packageName: String,
        val versionCode: Long,
        val apkPath: String?,
    )

    private class HotReloadCommittedException(cause: Throwable) :
        IllegalStateException("New module generation was committed but onHotReloaded failed", cause)

    fun loadModule(module: Module, isSystemServer: Boolean, processName: String): Boolean {
        val metadata = readModuleMetadata(module)
        val state = buildGeneration(module, isSystemServer, processName, metadata) ?: return false

        state.entries.forEach(VectorLifecycleManager.activeModules::add)
        moduleStates[module.packageName] = state

        val param =
            object : ModuleLoadedParam {
                override fun isSystemServer(): Boolean = isSystemServer

                override fun getProcessName(): String = processName
            }
        state.entries.forEach { entry ->
            runCatching { entry.onModuleLoaded(param) }
                .onFailure { Log.e(TAG, "Error in onModuleLoaded for ${entry.javaClass.name}", it) }
        }

        if (isHotReloadEligible(state)) {
            VectorServiceClient.registerHotReloadTarget(
                module.packageName,
                module.versionCode,
                VectorHotReloadTarget,
            )
        }

        Log.d(TAG, "Loaded module ${module.packageName} generation=${state.generationId}")
        return true
    }

    /**
     * Build a complete successor without publishing it or touching the currently active generation.
     * This ordering is the API102 invariant: a failed class load/constructor can never retire old
     * code first and leave the process with no viable generation.
     */
    private fun buildGeneration(
        module: Module,
        isSystemServer: Boolean,
        processName: String,
        metadata: ModuleMetadata,
    ): ModuleState? {
        return try {
            Log.d(TAG, "Building module ${module.packageName}")
            val librarySearchPath = buildLibrarySearchPath(module, isSystemServer)
            val initLoader = XposedModule::class.java.classLoader
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    module.file.preLoadedDexes,
                    librarySearchPath,
                    initLoader,
                    blockLegacyApi = metadata.targetApiVersion >= 102,
                )

            if (
                moduleClassLoader.loadClass(XposedModule::class.java.name).classLoader !== initLoader
            ) {
                Log.e(TAG, "The Xposed API classes are compiled into ${module.packageName}")
                return null
            }

            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = module.applicationInfo,
                    service = module.service,
                    defaultExceptionMode = metadata.defaultExceptionMode,
                )

            // Native entrypoints must be known before an entry constructor or callback can dlopen.
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint)

            val entries = instantiateEntries(module, moduleClassLoader, vectorContext)
            if (entries.isEmpty()) {
                Log.e(TAG, "No entry class of ${module.packageName} could be instantiated")
                return null
            }

            ModuleState(
                module = module,
                packageName = module.packageName,
                processName = processName,
                isSystemServer = isSystemServer,
                entries = entries,
                context = vectorContext,
                classLoaders = setOf(moduleClassLoader),
                codeIdentity =
                    RuntimeModuleCodeIdentity(
                        module.packageName,
                        module.versionCode,
                        module.apkPath,
                    ),
                generationId = generationCounter.getAndIncrement(),
                targetApiVersion = metadata.targetApiVersion,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error building module ${module.packageName}", e)
            null
        }
    }

    @Synchronized
    fun hotReloadModule(module: Module, extras: Bundle?) {
        Log.i(TAG, "RELOAD_REQUESTED package=${module.packageName} version=${module.versionCode}")
        val oldState =
            moduleStates[module.packageName]
                ?: throw IllegalStateException("Module ${module.packageName} is not loaded")
        if (!isHotReloadEligible(oldState)) {
            throw IllegalArgumentException("Hot reload is unsupported for ${module.packageName}")
        }

        extras?.let { validateClassLoaderNeutralValue(it, oldState.classLoaders, "extras") }

        // Only entries that have not detached are allowed to participate in the handover. Holding
        // them in this local list keeps the old generation reachable until the new callback ends.
        val oldEntries = oldState.entries.filter(VectorLifecycleManager::isActive)
        if (oldEntries.isEmpty()) {
            throw IllegalStateException("Every entry of ${module.packageName} has detached")
        }

        val newMetadata = readModuleMetadata(module)
        if (!isHotReloadDescriptorEligible(module, newMetadata, oldState.isSystemServer)) {
            throw IllegalArgumentException("The new generation is not API102 hot-reload compatible")
        }

        // Build the complete successor before freezing or calling any old-generation module code.
        val newState =
            buildGeneration(module, oldState.isSystemServer, oldState.processName, newMetadata)
                ?: throw IllegalStateException("Cannot build a new generation of ${module.packageName}")
        val newEntries = newState.entries
        val oldClassLoaders = oldState.classLoaders

        Log.i(TAG, "NEW_INSTANTIATED package=${module.packageName} generation=${newState.generationId}")

        // The flag write and all module-owned hook registrations use exactly this lock. A hook
        // registration that passed its optimistic check before this point must therefore either
        // finish before the freeze, or acquire the lock afterwards and observe the frozen context.
        synchronized(hookLockOf(module.packageName)) { oldState.context.freeze() }
        Log.i(TAG, "FREEZE_HOOKS package=${module.packageName} generation=${oldState.generationId}")

        var committed = false
        try {
            var savedInstanceState: Any? = null
            val reloadingParam =
                object : HotReloadingParam {
                    override fun getExtras(): Bundle? = extras

                    override fun setSavedInstanceState(outState: Any?) {
                        validateClassLoaderNeutralValue(
                            outState,
                            oldClassLoaders,
                            "savedInstanceState",
                        )
                        savedInstanceState = outState
                    }
                }

            val accepted =
                oldEntries.all { entry ->
                    runCatching { entry.onHotReloading(reloadingParam) }
                        .onFailure {
                            Log.e(TAG, "Error in onHotReloading for ${entry.javaClass.name}", it)
                        }
                        .getOrThrow()
                }
            if (!accepted) {
                Log.i(TAG, "OLD_REJECTED package=${module.packageName}")
                throw IllegalStateException("Module refused hot reload")
            }

            // Snapshot after the freeze and after old code had its opportunity to unhook. The new
            // generation can migrate these handles with native replaceCallback(), atomically.
            val oldHandles = getActiveHookHandles(module.packageName)
            val param =
                object : HotReloadedParam {
                    override fun isSystemServer(): Boolean = oldState.isSystemServer

                    override fun getProcessName(): String = oldState.processName

                    override fun getExtras(): Bundle? = extras

                    override fun getSavedInstanceState(): Any? = savedInstanceState

                    override fun getOldHookHandles(): List<XposedInterface.HookHandle> = oldHandles
                }

            // This map assignment is the commit point. Once it happens there is intentionally no
            // rollback: onHotReloaded may already replace native callbacks, and restoring old Java
            // state could not undo those swaps. Mark committed immediately so no later bookkeeping
            // failure can be misclassified as a pre-commit failure and re-enable the retired context.
            moduleStates[module.packageName] = newState
            committed = true

            oldEntries.forEach(VectorLifecycleManager::detach)
            newEntries.forEach(VectorLifecycleManager.activeModules::add)
            VectorServiceClient.updatePendingHotReloadVersion(
                module.packageName,
                module.versionCode,
            )
            Log.i(TAG, "COMMITTED package=${module.packageName} generation=${newState.generationId}")

            var callbackFailure: Throwable? = null
            for (entry in newEntries) {
                if (!VectorLifecycleManager.isActive(entry)) continue
                runCatching { entry.onHotReloaded(param) }
                    .onFailure { t ->
                        if (callbackFailure == null) callbackFailure = t
                        Log.e(TAG, "HOT_RELOADED_CALLBACK_FAILED ${entry.javaClass.name}", t)
                    }
                if (callbackFailure != null) break
            }

            callbackFailure?.let { throw HotReloadCommittedException(it) }
        } finally {
            if (!committed) {
                // No generation swap happened. Restore the old generation's ability to register
                // hooks; candidate entries were never published and can become unreachable.
                synchronized(hookLockOf(module.packageName)) { oldState.context.unfreeze() }
                newEntries.forEach(VectorLifecycleManager::detach)
                Log.i(TAG, "PRECOMMIT_FAILED package=${module.packageName}")
                Log.i(TAG, "UNFROZEN package=${module.packageName} generation=${oldState.generationId}")
            } else {
                // A committed generation is retired permanently. Do not unfreeze its VectorContext:
                // old module objects/threads may remain reachable after lifecycle detach, and must
                // never be able to install ghost hooks into the new generation.
                oldEntries.forEach(VectorLifecycleManager::detach)
                Log.i(
                    TAG,
                    "RETIRED_FROZEN package=${module.packageName} generation=${oldState.generationId}",
                )
            }
        }
    }

    private fun readModuleMetadata(module: Module): ModuleMetadata =
        runCatching {
                ZipFile(module.apkPath).use { zip ->
                    val props = Properties()
                    zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                        runCatching { zip.getInputStream(entry).use { props.load(it) } }
                            .onFailure {
                                Log.w(TAG, "Malformed module.prop in ${module.apkPath}", it)
                            }
                    }

                    fun readLeadingInt(name: String): Int? =
                        props.getProperty(name)
                            ?.trim()
                            ?.takeWhile { it.isDigit() }
                            ?.toIntOrNull()

                    val canonicalTarget = readLeadingInt("targetApiVersion")
                    val targetApi =
                        canonicalTarget
                            ?: readLeadingInt("api")
                            ?: readLeadingInt("minApiVersion")
                            ?: readLeadingInt("minApi")
                            ?: 0
                    if (canonicalTarget == null && targetApi >= 101) {
                        Log.w(
                            TAG,
                            "${module.packageName} uses legacy Vector-SR API metadata; " +
                                "prefer targetApiVersion=$targetApi in module.prop",
                        )
                    }

                    val mode =
                        if (
                            props.getProperty("exceptionMode")
                                ?.trim()
                                .equals("passthrough", ignoreCase = true)
                        ) {
                            ExceptionMode.PASSTHROUGH
                        } else {
                            ExceptionMode.PROTECTIVE
                        }
                    ModuleMetadata(targetApi, mode)
                }
            }
            .onFailure {
                Log.w(TAG, "Cannot read module metadata for ${module.packageName}", it)
            }
            .getOrDefault(ModuleMetadata(0, ExceptionMode.PROTECTIVE))

    private fun isHotReloadEligible(state: ModuleState): Boolean =
        isHotReloadDescriptorEligible(
            state.module,
            ModuleMetadata(state.targetApiVersion, ExceptionMode.PROTECTIVE),
            state.isSystemServer,
        )

    private fun isHotReloadDescriptorEligible(
        module: Module,
        metadata: ModuleMetadata,
        isSystemServer: Boolean,
    ): Boolean {
        return metadata.targetApiVersion >= 102 &&
            !module.file.legacy &&
            module.file.moduleClassNames.size == 1 &&
            module.file.moduleLibraryNames.isEmpty() &&
            (!isSystemServer || ENABLE_SYSTEM_SERVER_HOT_RELOAD)
    }

    private fun buildLibrarySearchPath(module: Module, isSystemServer: Boolean): String = buildString {
        if (isSystemServer) {
            module.file.nativeLibraryDir?.let { append(it).append(File.pathSeparator) }
        }
        val abis =
            if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
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

                    val constructor =
                        runCatching { moduleClass.getDeclaredConstructor() }
                            .onFailure { e ->
                                if (e is NoSuchMethodException) {
                                    Log.i(
                                        TAG,
                                        "Skipping incompatible modern entry $className (missing no-arg constructor)",
                                    )
                                } else {
                                    Log.e(TAG, "Failed to get constructor for $className", e)
                                }
                            }
                            .getOrNull() ?: return@runCatching

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
    private fun validateClassLoaderNeutralValue(
        value: Any?,
        oldClassLoaders: Set<ClassLoader>,
        path: String,
        seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
    ) {
        if (value == null) return
        if (containsOldClassLoaderObject(value, oldClassLoaders)) {
            throw IllegalArgumentException("$path must not contain old module classloader objects")
        }
        if (!seen.add(value)) return
        val classLoader = value.javaClass.classLoader
        if (
            classLoader != null &&
                classLoader !== String::class.java.classLoader &&
                classLoader !in oldClassLoaders &&
                !value.javaClass.name.startsWith("android.") &&
                !value.javaClass.name.startsWith("java.") &&
                !value.javaClass.name.startsWith("kotlin.")
        ) {
            throw IllegalArgumentException("$path contains custom class ${value.javaClass.name}")
        }
        if (value is Bundle) {
            value.classLoader = XposedModule::class.java.classLoader
            value.keySet().forEach { key ->
                validateClassLoaderNeutralValue(value.get(key), oldClassLoaders, "$path.$key", seen)
            }
        } else if (value is Map<*, *>) {
            value.entries.forEachIndexed { index, entry ->
                validateClassLoaderNeutralValue(
                    entry.key,
                    oldClassLoaders,
                    "$path.mapKey[$index]",
                    seen,
                )
                validateClassLoaderNeutralValue(
                    entry.value,
                    oldClassLoaders,
                    "$path.mapValue[$index]",
                    seen,
                )
            }
        } else if (value is Iterable<*>) {
            value.forEachIndexed { index, item ->
                validateClassLoaderNeutralValue(item, oldClassLoaders, "$path[$index]", seen)
            }
        } else if (value.javaClass.isArray) {
            for (index in 0 until Array.getLength(value)) {
                validateClassLoaderNeutralValue(
                    Array.get(value, index),
                    oldClassLoaders,
                    "$path[$index]",
                    seen,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun containsOldClassLoaderObject(
        value: Any?,
        oldClassLoaders: Set<ClassLoader>,
        seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
    ): Boolean {
        if (value == null || !seen.add(value)) return false
        if (value is ClassLoader && value in oldClassLoaders) return true
        if (value is Class<*> && value.classLoader?.let { it in oldClassLoaders } == true) return true
        if (value.javaClass.classLoader?.let { it in oldClassLoaders } == true) return true
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