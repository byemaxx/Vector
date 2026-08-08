package org.matrix.vector.daemon

import android.app.ActivityManager
import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import android.os.SystemProperties
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.CliSocketServer
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.ipc.SystemServerRecoveryCoordinator
import org.matrix.vector.daemon.ipc.BRIDGE_TRANSACTION_CODE
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService
import org.matrix.vector.daemon.utils.applyNotificationWorkaround

private const val TAG = "VectorDaemon"
private const val ACTION_SEND_BINDER = 1

object VectorDaemon {
  private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
    Log.e(TAG, "Caught fatal coroutine exception in background task!", throwable)
  }

  // Dispatchers.IO: Uses the shared background thread pool.
  // SupervisorJob(): Ensures one failing task doesn't kill the whole daemon.
  val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
  val bridgeServiceName = "activity"

  var isLateInject = false
  var proxyServiceName = "serial"

  @JvmStatic
  fun main(args: Array<String>) {
    if (!FileSystem.tryLock()) kotlin.system.exitProcess(0)

    var systemServerMaxRetry = 1
    for (arg in args) {
      if (arg.startsWith("--system-server-max-retry=")) {
        systemServerMaxRetry = arg.substringAfter('=').toIntOrNull() ?: 1
      } else if (arg == "--late-inject") {
        isLateInject = true
        proxyServiceName = "serial_vector"
      }
    }

    Log.i(TAG, "Vector daemon started: lateInject=$isLateInject, proxy=$proxyServiceName")
    // The hash is here rather than in the version the manager prints: Home should stay readable,
    // but every saved bug report should say exactly which commit produced the daemon that wrote it.
    Log.i(
        TAG,
        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "commit ${BuildConfig.VERSION_HASH}")

    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      Log.e(TAG, "Uncaught exception in Daemon", e)
      kotlin.system.exitProcess(1)
    }

    // Setup Main Looper
    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    @Suppress("DEPRECATION") Looper.prepareMainLooper()

    // Squat on the proxy service name immediately, which creates the early IPC channel of
    // FrameworkService for our Zygisk module during system_server specialization.
    if (!SystemServerService.registerProxyService(proxyServiceName)) {
      Log.e(TAG, "Unable to claim proxy service `$proxyServiceName`")
      kotlin.system.exitProcess(1)
    }

    // Start Environmental Daemons
    LogcatMonitor.start()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.start()
    CliSocketServer.start()

    // Preload Framework DEX in the background
    scope.launch { FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) }

    // Initializes system frameworks inside the daemon process
    ActivityThread.systemMain()
    DdmHandleAppName.setAppName("org.matrix.vector.daemon", 0)

    // Wait for Android core services
    waitForSystemService("package")
    waitForSystemService("activity") // current bridgeServiceName
    waitForSystemService(Context.USER_SERVICE)
    waitForSystemService(Context.APP_OPS_SERVICE)

    applyNotificationWorkaround()

    // Read this before `sendToBridge`, which leaves the main thread at euid 1000: the config
    // database lives under a directory only root can enter, so the first process to open it has
    // to do so while we still have root. On a successful injection a binder thread opens it for
    // us during specialization, but when the injection fails nothing else has, and the daemon
    // used to die here on an unreadable preference.
    val isVerboseLog = ManagerService.isVerboseLogEnabled()

    // Setup IPC channel for applications by injecting DaemonService binder
    SystemServerRecoveryCoordinator.requestInitialInjection()

    if (!isVerboseLog) {
      LogcatMonitor.stopVerbose()
    }

    runCatching { Os.seteuid(1000) }
        .onFailure { Log.e(TAG, "Failed to drop main thread euid to 1000", it) }

    Looper.loop()
    throw RuntimeException("Main thread loop unexpectedly exited")
  }

  private fun waitForSystemService(name: String) = runBlocking {
    while (ServiceManager.getService(name) == null) {
      Log.i(TAG, "Waiting system service: $name for 1s")
      delay(1000)
    }
  }

  // The bridge is setup via SystemServerRecoveryCoordinator


  /**
   * Brings the framework down and up without rebooting the device.
   *
   * `system_server` is forked from the *primary* zygote, so restarting that is what restarts the
   * framework — on a 64/32 device the primary init service is still called `zygote` (it runs
   * app_process64) and `zygote_secondary` is the 32-bit one. Restarting the secondary leaves
   * system_server running, so it cannot be used for a framework restart.
   *
   * Everything on screen dies with it. The caller is expected to have said so first.
   */
  fun softReboot() {
    Log.w(TAG, "Soft reboot: restarting the primary zygote")
    SystemProperties.set("ctl.restart", "zygote")
  }

  /** Recreates system_server, which is always forked from the primary zygote. */
  fun restartPrimaryZygoteForSystemServerRecovery() {
    Log.w(TAG, "Restarting primary zygote to recreate system_server")
    SystemProperties.set("ctl.restart", "zygote")
  }
}
