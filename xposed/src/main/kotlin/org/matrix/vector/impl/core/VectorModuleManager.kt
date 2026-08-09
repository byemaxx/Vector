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
import io.github.libxposed.service.IXposedService
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import org.lsposed.lspd.models.HotReloadOutcome
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

    private class HotReloadUnsupportedException(message: String) : IllegalStateException(message)

    private class HotReloadRefusedException : IllegalStateException("Module refused hot reload")

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

        // getRunningTargets() describes processes that are running the modern module, not only
        // processes whose descriptor is reloadable. Upstream exposes a multi-entry module here and
        // answers HOT_RELOAD_UNSUPPORTED only when a reload is requested. Register every loaded
        // modern module and keep descriptor validation on the actual reload path.
        VectorServiceClient.registerHotReloadTarget(
            module.packageName,
            module.versionCode,
            VectorHotReloadTarget,
        )

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
            // API 102 deliberately does not unload native code on a generation swap; modules that
            // use native state must quiesce old threads/hooks/JNI refs from onHotReloading().
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

    fun hotReloadModuleWithOutcome(module: Module, extras: Bundle?): HotReloadOutcome {
        return try {
            hotReloadModule(module, extras)
            outcome(
                IXposedService.HOT_RELOAD_SUCCEEDED,
                message = null,
                generationChanged = true,
            )
        } catch (_: HotReloadRefusedException) {
            outcome(
                IXposedService.HOT_RELOAD_FAILED,
                message = null,
                refused = true,
            )
        } catch (e: HotReloadUnsupportedException) {
            outcome(IXposedService.HOT_RELOAD_UNSUPPORTED, e.message ?: "Hot reload unsupported")
        } catch (e: HotReloadCommittedException) {
            outcome(
                IXposedService.HOT_RELOAD_FAILED,
                describe(e.cause ?: e),
                generationChanged = true,
            )
        } catch (e: Throwable) {
            outcome(IXposedService.HOT_RELOAD_FAILED, describe(e))
        }
    }

    @Synchronized
    private fun hotReloadModule(module: Module, extras: Bundle?) {
        Log.i(TAG, "RELOAD_REQUESTED package=${module.packageName} version=${module.versionCode}")
        val oldState =
            moduleStates[module.packageName]
                ?: throw HotReloadUnsupportedException(
                    "Module ${module.packageName} is not loaded",
                )
        if (!isHotReloadEligible(oldState)) {
            throw HotReloadUnsupportedException(
                "Hot reload is unsupported for ${module.packageName}",
            )
        }

        // The service Bundle has already crossed Binder before it reaches this process. API102 asks
        // callers to keep it classloader-neutral; the framework does not impose an additional type
        // whitelist here, matching libxposed/upstream behavior.

        // Only entries that have not detached are allowed to participate in the handover. Holding
        // them in this local list keeps the old generation reachable until the new callback ends.
        val oldEntries = oldState.entries.filter(VectorLifecycleManager::isActive)
        if (oldEntries.isEmpty()) {
            throw HotReloadUnsupportedException(
                "Every entry of ${module.packageName} has detached",
            )
        }

        val newMetadata = readModuleMetadata(module)
        if (!isHotReloadDescriptorEligible(module)) {
            throw HotReloadUnsupportedException(
                "The new generation is not API102 hot-reload compatible",
            )
        }

        // Build the complete successor before freezing or calling any old-generation module code.
        val newState =
            buildGeneration(module, oldState.isSystemServer, oldState.processName, newMetadata)
                ?: throw HotReloadUnsupportedException(
                    "Cannot build a new generation of ${module.packageName}",
                )
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
                        rejectOldGenerationState(outState, oldClassLoaders)
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
                throw HotReloadRefusedException()
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

    private fun outcome(
        status: Int,
        message: String?,
        refused: Boolean = false,
        generationChanged: Boolean = false,
    ) =
        HotReloadOutcome().apply {
            this.status = status
            this.message = message
            this.refused = refused
            this.generationChanged = generationChanged
        }

    private fun describe(t: Throwable): String =
        "${t.javaClass.name}: ${t.message ?: "no message"}"

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
        isHotReloadDescriptorEligible(state.module)

    // The libxposed API defines hot-reloadability by descriptor, not by targetApiVersion: a modern
    // module with exactly one Java entry is a valid target. targetApiVersion still controls API102
    // runtime behavior such as legacy-API isolation for the generation itself.
    private fun isHotReloadDescriptorEligible(module: Module): Boolean =
        !module.file.legacy && module.file.moduleClassNames.size == 1

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

    /**
     * API102's saved-state check is deliberately shallow: it is a diagnostic aid, not an object
     * graph verifier. Reject the value itself plus direct array/collection/map members when they are
     * defined by the retiring module classloader, matching libxposed/upstream semantics.
     */
    private fun rejectOldGenerationState(state: Any?, oldClassLoaders: Set<ClassLoader>) {
        if (state == null) return
        rejectOldGenerationValue(state, oldClassLoaders)
        when (state) {
            is Array<*> ->
                state.forEach { value ->
                    value?.let { rejectOldGenerationValue(it, oldClassLoaders) }
                }
            is Collection<*> ->
                state.forEach { value ->
                    value?.let { rejectOldGenerationValue(it, oldClassLoaders) }
                }
            is Map<*, *> ->
                state.forEach { (key, value) ->
                    key?.let { rejectOldGenerationValue(it, oldClassLoaders) }
                    value?.let { rejectOldGenerationValue(it, oldClassLoaders) }
                }
        }
    }

    private fun rejectOldGenerationValue(value: Any, oldClassLoaders: Set<ClassLoader>) {
        if (definedByOldGeneration(value.javaClass, oldClassLoaders)) {
            throw IllegalArgumentException(
                "Saved instance state contains ${value.javaClass.name}, which was created under " +
                    "the old module classloader"
            )
        }
    }

    private fun definedByOldGeneration(
        clazz: Class<*>,
        oldClassLoaders: Set<ClassLoader>,
    ): Boolean {
        var loader: ClassLoader? =
            (if (clazz.isArray) clazz.componentType else clazz)?.classLoader
        while (loader != null) {
            if (loader in oldClassLoaders) return true
            loader = loader.parent
        }
        return false
    }
}