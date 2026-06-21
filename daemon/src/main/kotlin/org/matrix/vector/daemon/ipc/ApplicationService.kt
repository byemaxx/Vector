package org.matrix.vector.daemon.ipc

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.HookedProcess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.service.IHotReloadTarget
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorAppService"

// Hardcoded transaction code from BridgeService
const val BRIDGE_TRANSACTION_CODE =
    ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code
const val DEX_TRANSACTION_CODE =
    ('_'.code shl 24) or ('D'.code shl 16) or ('E'.code shl 8) or 'X'.code
const val OBFUSCATION_MAP_TRANSACTION_CODE =
    ('_'.code shl 24) or ('O'.code shl 16) or ('B'.code shl 8) or 'F'.code

internal class HotReloadInProgressException(message: String) : IllegalStateException(message)

internal class HotReloadProcessDiedException(message: String) : IllegalStateException(message)

internal class HotReloadUnsupportedException(message: String) : IllegalStateException(message)

object ApplicationService : ILSPApplicationService.Stub() {

  data class ProcessKey(val uid: Int, val pid: Int)

  private val processes = ConcurrentHashMap<ProcessKey, ProcessInfo>()
  private val nextHotReloadTargetId = AtomicLong(1)
  private val hotReloadTargets = ConcurrentHashMap<Long, HotReloadTargetInfo>()

  private class ProcessInfo(val key: ProcessKey, val processName: String, val heartBeat: IBinder) :
      IBinder.DeathRecipient {
    init {
      heartBeat.linkToDeath(this, 0)
      processes[key] = this
    }

    override fun binderDied() {
      heartBeat.unlinkToDeath(this, 0)
      processes.remove(key)
      hotReloadTargets.entries.removeIf { it.value.process === this }
    }
  }

  private class HotReloadTargetInfo(
      val id: Long,
      val modulePackageName: String,
      val process: ProcessInfo,
      @Volatile var loadedVersionCode: Long,
      val target: IHotReloadTarget
  ) : IBinder.DeathRecipient {
    @Volatile var state: Int = HookedProcess.TARGET_STATE_UP_TO_DATE

    init {
      target.asBinder().linkToDeath(this, 0)
      hotReloadTargets[id] = this
    }

    override fun binderDied() {
      target.asBinder().unlinkToDeath(this, 0)
      hotReloadTargets.remove(id)
    }

    fun toHookedProcess(currentVersionCode: Long): HookedProcess {
      val effectiveState =
          if (state == HookedProcess.TARGET_STATE_UP_TO_DATE &&
              loadedVersionCode != currentVersionCode) {
            HookedProcess.TARGET_STATE_STALE
          } else {
            state
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

  private fun getAllModules(): List<Module> {
    val info = ensureRegistered()
    if (info.key.uid == Process.SYSTEM_UID && info.processName == "system") {
      return ConfigCache.getModulesForSystemServer()
    }
    if (ManagerService.isRunningManager(getCallingPid(), info.key.uid)) {
      return emptyList()
    }
    return ConfigCache.getModulesForProcess(info.processName, info.key.uid)
  }

  override fun getModulesList() = getAllModules().filter { !it.file.legacy }

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
          // Verify the APK signature before serving it
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
    val module =
        ConfigCache.getModuleByPackage(modulePackageName)
            ?: throw RemoteException("Unknown module: $modulePackageName")
    if (!getAllModules().any { it.packageName == module.packageName }) {
      throw RemoteException("Module $modulePackageName is not active in ${info.processName}")
    }

    val existing =
        hotReloadTargets.values.firstOrNull {
          it.modulePackageName == modulePackageName &&
              it.process.key == info.key &&
              it.target.asBinder() == target.asBinder()
        }
    if (existing != null) {
      existing.loadedVersionCode = loadedVersionCode
      existing.state = HookedProcess.TARGET_STATE_UP_TO_DATE
      return existing.id
    }

    val id = nextHotReloadTargetId.getAndIncrement()
    HotReloadTargetInfo(id, module.packageName, info, loadedVersionCode, target)
    return id
  }

  fun getRunningTargets(module: Module): List<HookedProcess> {
    return hotReloadTargets.values
        .filter { it.modulePackageName == module.packageName }
        .map { it.toHookedProcess(module.versionCode) }
  }

  fun hotReloadTarget(targetId: Long, module: Module, extras: android.os.Bundle?) {
    val target =
        hotReloadTargets[targetId] ?: throw SecurityException("Invalid hot reload target: $targetId")
    if (target.modulePackageName != module.packageName) {
      throw SecurityException("Target $targetId does not belong to ${module.packageName}")
    }
    if (target.state == HookedProcess.TARGET_STATE_RELOADING) {
      throw HotReloadInProgressException("Target $targetId is already reloading")
    }

    target.state = HookedProcess.TARGET_STATE_RELOADING
    runCatching {
          target.target.hotReloadModule(module, extras)
          target.loadedVersionCode = module.versionCode
          target.state = HookedProcess.TARGET_STATE_UP_TO_DATE
        }
        .onFailure {
          if (!target.target.asBinder().isBinderAlive) {
            hotReloadTargets.remove(target.id, target)
            throw HotReloadProcessDiedException("Target process died before hot reload completed")
          }
          target.state = HookedProcess.TARGET_STATE_FAILED
          throw it
        }
  }
}
