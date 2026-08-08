package org.matrix.vector.daemon.env

import android.net.LocalServerSocket
import android.os.Build
import android.os.FileObserver
import android.os.SELinux
import android.os.SystemProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.daemon.VectorDaemon

private const val TAG = "VectorDex2Oat"

// Wrapper states mirrored directly from the IManagerService AIDL contract.
val DEX2OAT_OK = IManagerService.DEX2OAT_OK
val DEX2OAT_MOUNT_FAILED = IManagerService.DEX2OAT_MOUNT_FAILED
val DEX2OAT_SEPOLICY_INCORRECT = IManagerService.DEX2OAT_SEPOLICY_INCORRECT
val DEX2OAT_SELINUX_PERMISSIVE = IManagerService.DEX2OAT_SELINUX_PERMISSIVE
val DEX2OAT_CRASHED = IManagerService.DEX2OAT_CRASHED

object Dex2OatServer {
  enum class RefreshResult {
    ALREADY_HEALTHY,
    RECOVERED,
    FALLBACK_ENABLED,
    FAILED,
  }

  private const val WRAPPER32 = "bin/dex2oat32"
  private const val WRAPPER64 = "bin/dex2oat64"
  private const val HOOKER32 = "bin/liboat_hook32.so"
  private const val HOOKER64 = "bin/liboat_hook64.so"

  private val dex2oatArray = arrayOfNulls<String>(6)
  private val fdArray = arrayOfNulls<FileDescriptor>(6)
  private var serverJob: Job? = null
  private var serverSocket: LocalServerSocket? = null
  private val running = AtomicBoolean(false)

  @Volatile
  var compatibility = DEX2OAT_OK
    private set

  private val stateLock = Any()

  private external fun doMountNative(
      enabled: Boolean,
      r32: String?,
      d32: String?,
      r64: String?,
      d64: String?
  ): Boolean

  private external fun enableDex2OatPropertyFallbackNative(): Boolean

  private var dex2OatPropertyFallbackEnabled =
      runCatching {
        hasDex2OatPropertyFallbackFlag()
      }.getOrDefault(false)

  private fun hasDex2OatPropertyFallbackFlag(): Boolean =
      SystemProperties.get("dalvik.vm.dex2oat-flags", "")
          .contains("--inline-max-code-units=0")

  private fun enableDex2OatPropertyFallback(reason: String): Boolean {
    if (dex2OatPropertyFallbackEnabled) return true
    if (!enableDex2OatPropertyFallbackNative()) {
      Log.e(TAG, "Failed to enable dex2oat property fallback: $reason")
      return false
    }
    // Native success only means the property write was attempted. Verify the value visible to
    // this namespace before advertising fallback to the recovery coordinator.
    dex2OatPropertyFallbackEnabled = runCatching { hasDex2OatPropertyFallbackFlag() }
        .getOrDefault(false)
    if (dex2OatPropertyFallbackEnabled) {
      Log.w(TAG, "Enabled dex2oat property fallback: $reason")
    } else {
      Log.e(TAG, "Failed to enable dex2oat property fallback: $reason")
    }
    return dex2OatPropertyFallbackEnabled
  }

  private external fun setSockCreateContext(context: String?): Boolean

  private external fun getSockPath(): String

  /** The nodes whose writes mean the SELinux state this daemon depends on may have moved. */
  private val SELINUX_NODES = listOf("/sys/fs/selinux/enforce", "/sys/fs/selinux/policy")

  /**
   * Watches [SELINUX_NODES] on every release this daemon runs on.
   *
   * `FileObserver(List<File>, int)` is API 29 and the minimum here is 27, where the only
   * constructors are the single-path ones -- deprecated in 29 precisely because they were replaced
   * by these. So below 29 this is one observer per node, and `stopWatching` has to reach all of
   * them, which is why the two live behind an object rather than being a `FileObserver` itself.
   */
  private object SelinuxObserver {
    private val observers: List<FileObserver> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          listOf(
              object : FileObserver(SELINUX_NODES.map(::File), FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) = onSelinuxEvent()
              })
        } else {
          SELINUX_NODES.map { node ->
            @Suppress("DEPRECATION")
            object : FileObserver(node, FileObserver.CLOSE_WRITE) {
              override fun onEvent(event: Int, path: String?) = onSelinuxEvent()
            }
          }
        }

    fun startWatching() = observers.forEach(FileObserver::startWatching)

    fun stopWatching() = observers.forEach(FileObserver::stopWatching)
  }

  private fun onSelinuxEvent() {
    synchronized(stateLock) {
      if (compatibility == DEX2OAT_CRASHED) {
        SelinuxObserver.stopWatching()
        return
      }

      val enforcing =
          runCatching {
                Files.newInputStream(Paths.get("/sys/fs/selinux/enforce")).use {
                  it.read() == '1'.code
                }
              }
              .getOrDefault(false)

      when {
        !enforcing -> {
          if (compatibility == DEX2OAT_OK) doMount(false)
          compatibility = DEX2OAT_SELINUX_PERMISSIVE
        }
        hasSePolicyErrors() -> {
          if (compatibility == DEX2OAT_OK) doMount(false)
          compatibility = DEX2OAT_SEPOLICY_INCORRECT
        }
        compatibility != DEX2OAT_OK -> {
          if (!doMount(true) || notMounted()) {
            doMount(false)
            compatibility = DEX2OAT_MOUNT_FAILED
            enableDex2OatPropertyFallback("wrapper mount failed after SELinux recovery")
            SelinuxObserver.stopWatching()
          } else {
            compatibility = DEX2OAT_OK
          }
        }
      }
    }
  }

  init {
    // Android 10 vs 11+ path differences
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      checkAndAddDex2Oat("/apex/com.android.runtime/bin/dex2oat")
      checkAndAddDex2Oat("/apex/com.android.runtime/bin/dex2oatd")
      checkAndAddDex2Oat("/apex/com.android.runtime/bin/dex2oat64")
      checkAndAddDex2Oat("/apex/com.android.runtime/bin/dex2oatd64")
    } else {
      checkAndAddDex2Oat("/apex/com.android.art/bin/dex2oat32")
      checkAndAddDex2Oat("/apex/com.android.art/bin/dex2oatd32")
      checkAndAddDex2Oat("/apex/com.android.art/bin/dex2oat64")
      checkAndAddDex2Oat("/apex/com.android.art/bin/dex2oatd64")
    }

    openDex2oat(4, "/data/adb/modules/zygisk_vector/bin/liboat_hook32.so")
    openDex2oat(5, "/data/adb/modules/zygisk_vector/bin/liboat_hook64.so")
  }

  private fun hasSePolicyErrors(): Boolean {
    return SELinux.checkSELinuxAccess(
        "u:r:untrusted_app:s0", "u:object_r:dex2oat_exec:s0", "file", "execute") ||
        SELinux.checkSELinuxAccess(
            "u:r:untrusted_app:s0", "u:object_r:dex2oat_exec:s0", "file", "execute_no_trans")
  }

  private fun openDex2oat(id: Int, path: String): Boolean {
    return runCatching {
      fdArray[id]?.let { runCatching { Os.close(it) } }
      fdArray[id] = Os.open(path, OsConstants.O_RDONLY, 0)
      dex2oatArray[id] = path
    }
        .onFailure { Log.w(TAG, "Failed to open dex2oat resource: $path", it) }
        .isSuccess
  }

  private fun checkAndAddDex2Oat(path: String) {
    val file = File(path)
    if (!file.exists()) return

    runCatching {
          FileInputStream(file).use { fis ->
            val header = ByteArray(5)
            if (fis.read(header) != 5) return
            // Verify ELF Magic: 0x7F 'E' 'L' 'F'
            if (header[0] != 0x7F.toByte() ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte())
                return

            val is32Bit = header[4] == 1.toByte()
            val is64Bit = header[4] == 2.toByte()
            val isDebug = path.contains("dex2oatd")

            val index =
                when {
                  is32Bit -> if (isDebug) 1 else 0
                  is64Bit -> if (isDebug) 3 else 2
                  else -> -1
                }

            if (index != -1 && dex2oatArray[index] == null) {
              dex2oatArray[index] = path
              fdArray[index] = Os.open(path, OsConstants.O_RDONLY, 0)
              Log.i(TAG, "Detected $path -> Assigned Index $index")
            }
          }
        }
        .onFailure { Log.w(TAG, "Failed to open dex2oat: $path", it) }
  }

  private fun dex2oatCandidates(): List<String> {
    return if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      listOf(
          "/apex/com.android.runtime/bin/dex2oat",
          "/apex/com.android.runtime/bin/dex2oatd",
          "/apex/com.android.runtime/bin/dex2oat64",
          "/apex/com.android.runtime/bin/dex2oatd64")
    } else {
      listOf(
          "/apex/com.android.art/bin/dex2oat32",
          "/apex/com.android.art/bin/dex2oatd32",
          "/apex/com.android.art/bin/dex2oat64",
          "/apex/com.android.art/bin/dex2oatd64")
    }
  }

  private fun clearDex2OatMountsLocked(): Boolean {
    val paths = dex2oatCandidates()
    return doMountNative(
        false,
        mountPath(0, paths.getOrNull(0)),
        mountPath(1, paths.getOrNull(1)),
        mountPath(2, paths.getOrNull(2)),
        mountPath(3, paths.getOrNull(3)))
  }

  private fun mountPath(index: Int, path: String?): String? =
      path?.takeUnless { (index == 1 || index == 3) && !File(it).exists() }

  private fun closeDex2OatStateLocked() {
    for (i in fdArray.indices) {
      fdArray[i]?.let { fd ->
        runCatching { Os.close(fd) }.onFailure { Log.w(TAG, "Failed to close dex2oat fd[$i]", it) }
      }
      fdArray[i] = null
      dex2oatArray[i] = null
    }
  }

  private fun reopenDex2OatStateLocked() {
    dex2oatCandidates().forEach { checkAndAddDex2Oat(it) }
    openDex2oat(4, "/data/adb/modules/zygisk_vector/$HOOKER32")
    openDex2oat(5, "/data/adb/modules/zygisk_vector/$HOOKER64")
  }

  private fun sameFile(fd: FileDescriptor, path: String): Boolean {
    return runCatching {
      val fdStat = Os.fstat(fd)
      val pathStat = Os.stat(path)
      fdStat.st_dev == pathStat.st_dev && fdStat.st_ino == pathStat.st_ino
    }.getOrDefault(false)
  }

  private fun validateOriginalDex2OatFdLocked(): Boolean {
    val required = requiredDex2OatIndices()
    if (required.isEmpty()) return false

    for (index in required) {
      val path = dex2oatArray[index] ?: return false
      val fd = fdArray[index] ?: return false
      val wrapperPath = if (index < 2) WRAPPER32 else WRAPPER64
      if (!sameFile(fd, path)) {
        Log.w(TAG, "dex2oat fd[$index] no longer matches $path")
        return false
      }
      if (sameFile(fd, wrapperPath)) {
        Log.w(TAG, "dex2oat fd[$index] points to Vector wrapper")
        return false
      }
    }
    return true
  }

  private fun requiredDex2OatIndices(): Set<Int> = buildSet {
    if (Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()) add(0)
    if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) add(2)
    // dex2oatd is a debug-only optional binary. If the release image does not ship it, it must
    // not prevent the release wrapper from starting.
    if (Build.SUPPORTED_32_BIT_ABIS.isNotEmpty() && dex2oatArray[1] != null) add(1)
    if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && dex2oatArray[3] != null) add(3)
  }

  private fun resetDex2OatStateLocked(clearMounts: Boolean = false): Boolean {
    if (clearMounts && !clearDex2OatMountsLocked()) return false
    closeDex2OatStateLocked()
    reopenDex2OatStateLocked()
    if (validateOriginalDex2OatFdLocked()) return true

    closeDex2OatStateLocked()
    if (!clearMounts) {
      Log.w(TAG, "Detected stale dex2oat wrapper mount; clearing mounts and retrying")
      if (!clearDex2OatMountsLocked()) {
        compatibility = DEX2OAT_MOUNT_FAILED
        enableDex2OatPropertyFallback("failed to clear stale dex2oat mounts")
        return false
      }
      reopenDex2OatStateLocked()
      if (validateOriginalDex2OatFdLocked()) return true
      closeDex2OatStateLocked()
    }

    compatibility = DEX2OAT_MOUNT_FAILED
    enableDex2OatPropertyFallback("wrapper mount failed after soft restart recovery")
    return false
  }

  private fun notMounted(): Boolean {
    for (i in requiredDex2OatIndices()) {
      val bin = dex2oatArray[i] ?: return true
      if (fdArray[i] == null) return true
      try {
        val wrapper = Os.stat(if (i < 2) WRAPPER32 else WRAPPER64)
        val current = Os.stat(bin)
        val init = Os.stat("/proc/1/root$bin")
        if (current.st_dev != wrapper.st_dev || current.st_ino != wrapper.st_ino ||
            init.st_dev != wrapper.st_dev || init.st_ino != wrapper.st_ino) {
          return true
        }
      } catch (e: ErrnoException) {
        return true
      }
    }
    return false
  }

  private fun doMount(enabled: Boolean): Boolean {
    return doMountNative(
        enabled,
        mountPath(0, dex2oatArray[0]),
        mountPath(1, dex2oatArray[1]),
        mountPath(2, dex2oatArray[2]),
        mountPath(3, dex2oatArray[3]))
  }

  private fun ensureMountedLocked(): Boolean {
    if (!notMounted()) return true
    if (!doMount(true) || notMounted()) {
      doMount(false)
      compatibility = DEX2OAT_MOUNT_FAILED
      enableDex2OatPropertyFallback("wrapper mount failed after final retry")
      return false
    }
    return true
  }

  fun start() {
    synchronized(stateLock) {
      if (running.get()) {
        Log.d(TAG, "Dex2oat wrapper daemon already running")
        return
      }
      cleanupSocketStateLocked()
      if (!resetDex2OatStateLocked()) return
      if (!ensureMountedLocked()) return
      compatibility = DEX2OAT_OK
      SelinuxObserver.startWatching()
      onSelinuxEvent()
      running.set(true)
      serverJob = VectorDaemon.scope.launch { runSocketLoop() }
    }
  }

  fun stop(disableMount: Boolean = false) {
    synchronized(stateLock) {
      running.set(false)
      serverJob?.cancel()
      serverJob = null
      cleanupSocketStateLocked()
      SelinuxObserver.stopWatching()
      if (disableMount && compatibility == DEX2OAT_OK) doMount(false)
    }
  }

  fun restart() {
    stop()
    start()
  }

  fun refreshMount(): RefreshResult {
    var restartSocket = false
    var startSocket = false
    synchronized(stateLock) {
      when {
        compatibility == DEX2OAT_CRASHED -> restartSocket = true
        running.get() && compatibility == DEX2OAT_OK -> {
          if (!resetDex2OatStateLocked() || !ensureMountedLocked()) return mountFailureResult()
          return RefreshResult.RECOVERED
        }
        !running.get() && compatibility != DEX2OAT_MOUNT_FAILED -> startSocket = true
      }
    }
    if (restartSocket) {
      restart()
      return if (compatibility == DEX2OAT_OK) RefreshResult.RECOVERED else mountFailureResult()
    }
    if (startSocket) {
      start()
      return if (compatibility == DEX2OAT_OK) RefreshResult.RECOVERED else mountFailureResult()
    }
    return if (compatibility == DEX2OAT_OK) RefreshResult.ALREADY_HEALTHY else mountFailureResult()
  }

  private fun mountFailureResult(): RefreshResult =
      if (dex2OatPropertyFallbackEnabled) RefreshResult.FALLBACK_ENABLED else RefreshResult.FAILED

  private fun runSocketLoop() {
    Log.i(TAG, "Dex2oat wrapper daemon start")
    val sockPath = getSockPath()
    Log.d(TAG, "wrapper path: $sockPath")

    val xposedFile = "u:object_r:xposed_file:s0"
    val dex2oatExec = "u:object_r:dex2oat_exec:s0"

    if (SELinux.checkSELinuxAccess("u:r:dex2oat:s0", dex2oatExec, "file", "execute_no_trans")) {
      SELinux.setFileContext(WRAPPER32, dex2oatExec)
      SELinux.setFileContext(WRAPPER64, dex2oatExec)
      setSockCreateContext("u:r:dex2oat:s0")
    } else {
      SELinux.setFileContext(WRAPPER32, xposedFile)
      SELinux.setFileContext(WRAPPER64, xposedFile)
      setSockCreateContext("u:r:installd:s0")
    }
    SELinux.setFileContext(HOOKER32, xposedFile)
    SELinux.setFileContext(HOOKER64, xposedFile)

    runCatching {
          serverSocket = LocalServerSocket(sockPath)
          try {
            setSockCreateContext(null)
            while (running.get()) {
              try {
                // This blocks until the C++ wrapper connects.
                serverSocket!!.accept().use { client ->
                  val input = client.inputStream
                  val output = client.outputStream
                  val id = input.read()
                  var dupFd: FileDescriptor? = null
                  synchronized(stateLock) {
                    if (id in fdArray.indices && fdArray[id] != null) {
                      val sourceFd = fdArray[id]
                      if (sourceFd != null) runCatching { dupFd = Os.dup(sourceFd) }
                    }
                  }
                  dupFd?.let { responseFd ->
                    try {
                      client.setFileDescriptorsForSend(arrayOf(responseFd))
                      output.write(1)
                      output.flush()
                    } finally {
                      runCatching { Os.close(responseFd) }
                    }
                  } ?: runCatching {
                    // The wrapper must receive an explicit failure response for stale/invalid ids;
                    // otherwise it can block waiting for an FD that will never arrive.
                    output.write(0)
                    output.flush()
                  }
                }
              } catch (e: IOException) {
                if (!running.get()) break
                throw e
              }
            }
          } finally {
            serverSocket?.close()
            serverSocket = null
          }
        }
        .onFailure {
          Log.e(TAG, "Dex2oat wrapper daemon crashed", it)
          setSockCreateContext(null)
          synchronized(stateLock) {
            running.set(false)
            cleanupSocketStateLocked()
          }
          if (compatibility == DEX2OAT_OK) {
            doMount(false)
            compatibility = DEX2OAT_CRASHED
          }
        }
        .onSuccess {
          synchronized(stateLock) {
            running.set(false)
            cleanupSocketStateLocked()
          }
        }
  }

  private fun cleanupSocketStateLocked() {
    runCatching { serverSocket?.close() }
    serverSocket = null
    val sockPath = runCatching { getSockPath() }.getOrNull()
    if (!sockPath.isNullOrBlank() && sockPath.startsWith("/")) {
      runCatching {
            val socketFile = File(sockPath)
            if (socketFile.exists()) socketFile.delete()
          }
          .onFailure { Log.w(TAG, "Failed to clean stale dex2oat socket file: $sockPath", it) }
    }
  }
}
