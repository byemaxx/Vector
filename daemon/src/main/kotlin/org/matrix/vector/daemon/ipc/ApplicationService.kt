package org.matrix.vector.daemon.ipc

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IXposedService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.lsposed.lspd.models.HotReloadOutcome
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadOutcomeReceiver
import org.lsposed.lspd.service.IHotReloadTarget
import org.lsposed.lspd.service.ILSPApplicationService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.InlineHookProcessPolicy
import org.matrix.vector.daemon.data.ModuleCodeIdentity
import org.matrix.vector.daemon.data.NativeLibraryStager
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.ProcessFreezer
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorAppService"
private const val FIRST_APPLICATION_UID = 10000
private const val RELOAD_TIMEOUT_SECONDS = 30L

// Hardcoded transaction code from BridgeService
const val BRIDGE_TRANSACTION_CODE =
    ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code
const val DEX_TRANSACTION_CODE =
    ('_'.code shl 24) or ('D'.code shl 16) or ('E'.code shl 8) or 'X'.code
const val OBFUSCATION_MAP_TRANSACTION_CODE =
    ('_'.code shl 24) or ('O'.code shl 16) or ('B'.code shl 8) or 'F'.code
const val INVALIDATE_ART_INLINE_HOOKS_TRANSACTION_CODE =
    ('_'.code shl 24) or ('I'.code shl 16) or ('N'.code shl 8) or 'L'.code

internal class HotReloadUnsupportedException(message: String) : IllegalStateException(message)

object ApplicationService : ILSPApplicationService.Stub() {

  data class ProcessKey(val uid: Int, val pid: Int)

  private val processes = ConcurrentHashMap<ProcessKey, ProcessInfo>()
  private val hotReloadSessionId = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE).toLong()
  private val nextHotReloadTargetId = AtomicLong(1)
  private val hotReloadTargets = ConcurrentHashMap<Long, HotReloadTargetInfo>()

  private class ProcessInfo(val key: ProcessKey, val processName: String, val heartBeat: IBinder) :
      IBinder.DeathRecipient {
    // Exact modern Module objects most recently handed to this process. This is authoritative for
    // early system_server target registration, before PackageManager-backed cache state exists.
    val offeredModernModules = ConcurrentHashMap<String, Module>()

    init {
      heartBeat.linkToDeath(this, 0)
      processes[key] = this
    }

    override fun binderDied() {
      heartBeat.unlinkToDeath(this, 0)
      processes.remove(key)
      removeHotReloadTargetsForProcess(this, "process binder died")
    }
  }

  private class HotReloadTargetInfo(
      val id: Long,
      val modulePackageName: String,
      val process: ProcessInfo,
      @Volatile var loadedVersionCode: Long,
      @Volatile var registeredCodeIdentity: ModuleCodeIdentity?,
      val hotReloadable: Boolean,
      val target: IHotReloadTarget
  ) : IBinder.DeathRecipient {
    val state = AtomicInteger(HookedProcess.TARGET_STATE_UP_TO_DATE)

    init {
      target.asBinder().linkToDeath(this, 0)
      hotReloadTargets[id] = this
    }

    override fun binderDied() {
      target.asBinder().unlinkToDeath(this, 0)
      hotReloadTargets.remove(id)
    }

    fun toHookedProcess(module: Module): HookedProcess {
      val currentCodeIdentity = ConfigCache.getModuleCodeIdentity(module.packageName)
      val identityStale =
          currentCodeIdentity != null &&
              registeredCodeIdentity != null &&
              registeredCodeIdentity != currentCodeIdentity
      val effectiveState =
          if (state.get() == HookedProcess.TARGET_STATE_UP_TO_DATE &&
              (loadedVersionCode != module.versionCode || identityStale)) {
            HookedProcess.TARGET_STATE_STALE
          } else {
            state.get()
          }
      return HookedProcess().apply {
        targetId = id
        uid = process.key.uid
        pid = process.key.pid
        processName = process.processName
        state = effectiveState
        loadedVersionCode = this@HotReloadTargetInfo.loadedVersionCode
      }
    }
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    when (code) {
      DEX_TRANSACTION_CODE -> {
        val shm = FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) ?: return false
        reply?.writeNoException()
        reply?.let { shm.writeToParcel(it, 0) }
        reply?.writeLong(shm.size.toLong())
        return true
      }
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        val obfuscation = ConfigCache.state.isDexObfuscateEnabled
        val signatures = ObfuscationManager.getSignatures()
        reply?.writeNoException()
        reply?.writeInt(signatures.size * 2)
        for ((key, value) in signatures) {
          reply?.writeString(key)
          reply?.writeString(if (obfuscation) value else key)
        }
        return true
      }
      INVALIDATE_ART_INLINE_HOOKS_TRANSACTION_CODE -> {
        val info = ensureRegistered()
        val invalidate =
            InlineHookProcessPolicy.mayInvalidate(info.processName, info.key.uid) &&
                PreferenceStore.shouldInvalidateArtInlineHooks(info.processName, info.key.uid)
        reply?.writeNoException()
        reply?.writeInt(if (invalidate) 1 else 0)
        return true
      }
    }
    return super.onTransact(code, data, reply, flags)
  }

  fun registerHeartBeat(uid: Int, pid: Int, processName: String, heartBeat: IBinder): Boolean {
    return runCatching {
          ProcessInfo(ProcessKey(uid, pid), processName, heartBeat)
          true
        }
        .getOrDefault(false)
  }

  fun hasRegister(uid: Int, pid: Int): Boolean = processes.containsKey(ProcessKey(uid, pid))

  private fun ensureRegistered(): ProcessInfo {
    val key = ProcessKey(getCallingUid(), getCallingPid())
    val info = processes[key]
    if (info == null) {
      Log.w(TAG, "Unauthorized IPC call from uid=${key.uid} pid=${key.pid}")
      throw RemoteException("Not registered")
    }
    return info
  }

  private fun getAllModules(info: ProcessInfo = ensureRegistered()): List<Module> {
    if (info.key.uid == Process.SYSTEM_UID && info.processName == "system") {
      // Only system_server needs native-library staging: ordinary app domains can execute mapped
      // libraries directly from /data/app. Keep the staging lifecycle separate from SR reinjection.
      return NativeLibraryStager.prepareForSystemServer(ConfigCache.getModulesForSystemServer())
    }
    if (ManagerService.isRunningManager(info.key.pid, info.key.uid)) {
      return emptyList()
    }
    return ConfigCache.getModulesForProcess(info.processName, info.key.uid)
  }

  override fun getModulesList(): List<Module> {
    val info = ensureRegistered()
    val modules = getAllModules(info).filter { !it.file.legacy }
    // Registration must prove the process was actually handed this module, not re-run package/module
    // discovery afterwards. This also avoids repeatedly creating system_server preload SharedMemory
    // merely to authenticate each API102 target registration.
    info.offeredModernModules.clear()
    modules.forEach { info.offeredModernModules[it.packageName] = it }
    return modules
  }

  override fun getLegacyModulesList() = getAllModules().filter { it.file.legacy }

  override fun isLogMuted(): Boolean = !ManagerService.isVerboseLog

  override fun getPrefsPath(packageName: String): String {
    val info = ensureRegistered()
    return ConfigCache.getPrefsPath(packageName, info.key.uid)
  }

  override fun requestInjectedManagerBinder(
      binderList: MutableList<IBinder>
  ): ParcelFileDescriptor? {
    val info = ensureRegistered()
    val pid = info.key.pid
    val uid = info.key.uid

    if (ManagerService.postStartManager(pid) || ConfigCache.isManager(uid)) {
      binderList.add(ManagerService.obtainManagerBinder(info.heartBeat, pid, uid))
    }

    return runCatching {
          InstallerVerifier.verifyInstallerSignature(FileSystem.managerApkPath.toString())
          ParcelFileDescriptor.open(
              FileSystem.managerApkPath.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        .onFailure { Log.e(TAG, "Failed to open or verify manager APK", it) }
        .getOrNull()
  }

  override fun registerHotReloadTarget(
      modulePackageName: String,
      loadedVersionCode: Long,
      target: IHotReloadTarget
  ): Long {
    val info = ensureRegistered()

    // Re-registration against the same daemon keeps the capability/identity of the generation that
    // is actually still resident, instead of replacing it with metadata from a newer installed APK.
    val existing =
        hotReloadTargets.values.firstOrNull {
          it.modulePackageName == modulePackageName &&
              it.process.key == info.key &&
              it.target.asBinder() == target.asBinder()
        }
    if (existing != null) {
      // A service reconnect may race an in-flight reload. Re-registering the same resident target
      // must not make a reserved target look UP_TO_DATE before its worker reports the outcome.
      if (existing.state.get() != HookedProcess.TARGET_STATE_RELOADING) {
        existing.loadedVersionCode = loadedVersionCode
        existing.state.set(HookedProcess.TARGET_STATE_UP_TO_DATE)
      }
      return existing.id
    }

    val activeModule =
        info.offeredModernModules[modulePackageName]
            ?: getAllModules(info).firstOrNull { it.packageName == modulePackageName }
            ?: throw RemoteException("Module $modulePackageName is not active in ${info.processName}")
    val module = ConfigCache.getModuleByPackage(modulePackageName) ?: activeModule
    val registrationIdentity =
        ConfigCache.getModuleCodeIdentity(modulePackageName)
            ?: observedCodeIdentity(activeModule, loadedVersionCode)

    val id =
        (hotReloadSessionId shl 32) or (nextHotReloadTargetId.getAndIncrement() and 0xffffffffL)
    HotReloadTargetInfo(
        id,
        module.packageName,
        info,
        loadedVersionCode,
        registrationIdentity,
        !activeModule.file.legacy && activeModule.file.moduleClassNames.size == 1,
        target)
    return id
  }

  private fun observedCodeIdentity(module: Module, loadedVersionCode: Long): ModuleCodeIdentity {
    val apk = FileSystem.toGlobalNamespace(module.apkPath)
    return ModuleCodeIdentity(
        packageName = module.packageName,
        versionCode = loadedVersionCode,
        apkPath = module.apkPath,
        apkSize = apk.length(),
        apkLastModified = apk.lastModified(),
    )
  }

  private fun addressableBy(target: HotReloadTargetInfo, userId: Int): Boolean =
      target.process.key.uid < FIRST_APPLICATION_UID ||
          target.process.key.uid / PER_USER_RANGE == userId

  private fun sameCodeArtifact(first: ModuleCodeIdentity, second: ModuleCodeIdentity): Boolean =
      first.packageName == second.packageName &&
          first.apkPath == second.apkPath &&
          first.apkSize == second.apkSize &&
          first.apkLastModified == second.apkLastModified

  private fun reconcileUnknownLoadedVersion(
      target: HotReloadTargetInfo,
      module: Module,
      currentIdentity: ModuleCodeIdentity?,
  ) {
    if (target.loadedVersionCode != 0L || currentIdentity == null) return
    val registered = target.registeredCodeIdentity ?: return
    if (!sameCodeArtifact(registered, currentIdentity)) return
    target.loadedVersionCode = module.versionCode
    target.registeredCodeIdentity = currentIdentity
  }

  fun getRunningTargets(module: Module, userId: Int): List<HookedProcess> {
    val currentIdentity = ConfigCache.getModuleCodeIdentity(module.packageName)
    return hotReloadTargets.values
        .filter { it.modulePackageName == module.packageName && addressableBy(it, userId) }
        .onEach { reconcileUnknownLoadedVersion(it, module, currentIdentity) }
        .map { it.toHookedProcess(module) }
  }

  fun getStaleHotReloadTargetIds(module: Module): List<Long> {
    val currentIdentity = ConfigCache.getModuleCodeIdentity(module.packageName)
    return hotReloadTargets.values
        .filter { it.modulePackageName == module.packageName && it.hotReloadable }
        .onEach { reconcileUnknownLoadedVersion(it, module, currentIdentity) }
        .filter { it.state.get() != HookedProcess.TARGET_STATE_RELOADING }
        .filter {
          it.loadedVersionCode != module.versionCode ||
              currentIdentity != null &&
                  it.registeredCodeIdentity != null &&
                  it.registeredCodeIdentity != currentIdentity
        }
        .map { it.id }
  }

  fun validateHotReloadTarget(targetId: Long, modulePackageName: String, userId: Int): Boolean {
    val target =
        hotReloadTargets[targetId]
            ?: throw SecurityException("Invalid hot reload target: $targetId")
    if (target.modulePackageName != modulePackageName || !addressableBy(target, userId)) {
      throw SecurityException(
          "Target $targetId does not belong to $modulePackageName in user $userId")
    }
    return target.hotReloadable
  }

  fun beginHotReloadTarget(
      targetId: Long,
      modulePackageName: String,
      userId: Int?,
  ): HotReloadOutcome? {
    val target =
        hotReloadTargets[targetId]
            ?: if (userId != null) {
              throw SecurityException("Invalid hot reload target: $targetId")
            } else {
              return outcome(
                  IXposedService.HOT_RELOAD_PROCESS_DIED,
                  "Target $targetId is no longer registered",
              )
            }

    if (target.modulePackageName != modulePackageName ||
        userId != null && !addressableBy(target, userId)) {
      if (userId != null) {
        throw SecurityException(
            "Target $targetId does not belong to $modulePackageName in user $userId")
      }
      return outcome(
          IXposedService.HOT_RELOAD_PROCESS_DIED,
          "Target $targetId is no longer owned by $modulePackageName",
      )
    }

    if (!target.target.asBinder().isBinderAlive || !isProcessRegistered(target)) {
      hotReloadTargets.remove(target.id, target)
      return outcome(
          IXposedService.HOT_RELOAD_PROCESS_DIED,
          "Target process died before hot reload started",
      )
    }

    while (true) {
      val currentState = target.state.get()
      if (currentState == HookedProcess.TARGET_STATE_RELOADING) {
        return outcome(
            IXposedService.HOT_RELOAD_IN_PROGRESS,
            "Target $targetId is already reloading",
        )
      }
      if (target.state.compareAndSet(currentState, HookedProcess.TARGET_STATE_RELOADING)) return null
    }
  }

  fun cancelHotReloadReservation(targetId: Long) {
    hotReloadTargets[targetId]
        ?.state
        ?.compareAndSet(HookedProcess.TARGET_STATE_RELOADING, HookedProcess.TARGET_STATE_FAILED)
  }

  private fun isSystemTarget(target: HotReloadTargetInfo): Boolean =
      target.process.key.uid == Process.SYSTEM_UID ||
          target.process.processName == "system" ||
          target.process.processName == "system_server"

  private fun prepareModuleForTarget(target: HotReloadTargetInfo, module: Module): Module? {
    if (!isSystemTarget(target)) return module
    return NativeLibraryStager.prepareAddressedSystemServerModule(module)
  }

  fun runHotReloadTarget(targetId: Long, module: Module, extras: Bundle?): HotReloadOutcome {
    val target =
        hotReloadTargets[targetId]
            ?: return outcome(
                IXposedService.HOT_RELOAD_PROCESS_DIED,
                "Target $targetId is no longer registered",
            )
    if (target.modulePackageName != module.packageName) {
      return complete(
          target,
          outcome(
              IXposedService.HOT_RELOAD_PROCESS_DIED,
              "Target $targetId is no longer owned by ${module.packageName}",
          ),
      )
    }
    if (!target.target.asBinder().isBinderAlive || !isProcessRegistered(target)) {
      hotReloadTargets.remove(target.id, target)
      return outcome(
          IXposedService.HOT_RELOAD_PROCESS_DIED,
          "Target process died before hot reload started",
      )
    }

    val reloadModule =
        prepareModuleForTarget(target, module)
            ?: return complete(
                target,
                outcome(
                    IXposedService.HOT_RELOAD_UNSUPPORTED,
                    "No reloadable generation of ${module.packageName} is available for ${target.process.processName}",
                ),
            )

    val pid = target.process.key.pid
    val refreeze = ProcessFreezer.thaw(pid)
    if (ProcessFreezer.isFrozen(pid)) {
      return complete(
          target,
          outcome(
              IXposedService.HOT_RELOAD_FAILED,
              "Process ${target.process.processName} is frozen and could not be thawed",
          ),
      )
    }

    val answered = CountDownLatch(1)
    var targetOutcome: HotReloadOutcome? = null
    val receiver =
        object : IHotReloadOutcomeReceiver.Stub() {
          override fun onOutcome(result: HotReloadOutcome?) {
            targetOutcome = result
            answered.countDown()
          }
        }

    return try {
      target.target.hotReloadModule(reloadModule, extras, receiver)
      if (!answered.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        val gone = !isProcessRegistered(target) || !target.target.asBinder().isBinderAlive
        if (gone) hotReloadTargets.remove(target.id, target)
        val result =
            if (gone) {
              outcome(
                  IXposedService.HOT_RELOAD_PROCESS_DIED,
                  "Process ${target.process.processName} died during hot reload",
              )
            } else {
              outcome(
                  IXposedService.HOT_RELOAD_FAILED,
                  "Process ${target.process.processName} did not answer within ${RELOAD_TIMEOUT_SECONDS}s",
              )
            }
        complete(target, result)
      } else {
        val answer =
            targetOutcome
                ?: outcome(
                    IXposedService.HOT_RELOAD_FAILED,
                    "Process ${target.process.processName} answered with no outcome",
                )
        val normalized = normalizeOutcome(answer)
        if (normalized.generationChanged) {
          target.loadedVersionCode = reloadModule.versionCode
          target.registeredCodeIdentity = ConfigCache.getModuleCodeIdentity(module.packageName)
        }
        complete(target, normalized)
      }
    } catch (t: Throwable) {
      val gone = !isProcessRegistered(target) || !target.target.asBinder().isBinderAlive
      if (gone) hotReloadTargets.remove(target.id, target)
      val result =
          if (gone) {
            outcome(
                IXposedService.HOT_RELOAD_PROCESS_DIED,
                "Process ${target.process.processName} died during hot reload",
            )
          } else {
            outcome(
                IXposedService.HOT_RELOAD_FAILED,
                "${t.javaClass.name}: ${t.message ?: "no message"}",
            )
          }
      complete(target, result)
    } finally {
      refreeze?.invoke()
    }
  }

  private fun complete(target: HotReloadTargetInfo, result: HotReloadOutcome): HotReloadOutcome {
    target.state.set(stateFor(result.status))
    return result
  }

  private fun isProcessRegistered(target: HotReloadTargetInfo): Boolean =
      processes.containsKey(target.process.key)

  private fun normalizeOutcome(answer: HotReloadOutcome): HotReloadOutcome {
    if (
        answer.status != IXposedService.HOT_RELOAD_FAILED ||
            answer.refused ||
            answer.message != null
    ) {
      return answer
    }
    return HotReloadOutcome().apply {
      status = answer.status
      message = "Hot reload failed without a diagnostic message"
      refused = false
      generationChanged = answer.generationChanged
    }
  }

  private fun stateFor(status: Int): Int =
      when (status) {
        IXposedService.HOT_RELOAD_SUCCEEDED -> HookedProcess.TARGET_STATE_UP_TO_DATE
        IXposedService.HOT_RELOAD_FAILED -> HookedProcess.TARGET_STATE_FAILED
        else -> HookedProcess.TARGET_STATE_UP_TO_DATE
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

  private fun removeHotReloadTargetsForProcess(process: ProcessInfo, reason: String) {
    val removed = hotReloadTargets.entries.removeIf { (_, target) -> target.process === process }
    if (removed) {
      Log.d(TAG, "Removed hot reload targets for ${process.processName}: $reason")
    }
  }
}
