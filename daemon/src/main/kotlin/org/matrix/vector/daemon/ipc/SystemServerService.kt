package org.matrix.vector.daemon.ipc

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IServiceCallback
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.daemon.*
import org.matrix.vector.daemon.system.getSystemServiceManager

private const val TAG = "VectorSystemServer"

/**
 * The daemon's end of the one handshake system_server gets.
 *
 * A plain [Binder] rather than an AIDL stub on purpose. system_server never holds an interface for
 * this - it reaches the daemon by transacting [BRIDGE_TRANSACTION_CODE] on whatever binder the
 * hijacked service name resolves to, which [onTransact] answers directly. An AIDL interface here
 * would generate a dispatch table nothing ever entered, and would have to state a descriptor that
 * nothing ever checks.
 */
object SystemServerService : Binder() {

  private val serviceLock = Any()
  private var proxyServiceName: String? = null
  private var originService: IBinder? = null
  private var originDeathRecipient: IBinder.DeathRecipient? = null
  private var registrationCallback: IServiceCallback? = null

  @Volatile
  var systemServerRequested = false

  @Volatile private var attachedSystemServerPid: Int = -1

  fun prepareForSystemServerRestart(serviceName: String): Boolean {
    synchronized(serviceLock) {
      systemServerRequested = false
      attachedSystemServerPid = -1
      clearOriginServiceLocked()
      proxyServiceName = serviceName
    }
    return claimProxyService(serviceName)
  }

  fun registerProxyService(serviceName: String): Boolean {
    // Register as the service name early to setup an IPC for `system_server`.
    Log.d(TAG, "Registering bridge service for `system_server` with name `$serviceName`.")

    // `IServiceManager.registerForNotifications` is only available since Android R.
    // On older platforms we simply let the real service replace our proxy in servicemanager.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && registrationCallback == null) {
      val callback =
          object : IServiceCallback.Stub() {
            // The IServiceCallback will tell us when the real Android service is ready,
            // allowing us to capture it and then naturally stop intercepting traffic.
            override fun onRegistration(name: String, binder: IBinder?) {
              if (name == serviceName && binder != null && binder !== this@SystemServerService) {
                Log.d(TAG, "Intercepted system service registration with name `$name`")
                synchronized(serviceLock) {
                  if (originService === binder) return
                  clearOriginServiceLocked()
                  val deathRecipient =
                      object : IBinder.DeathRecipient {
                        override fun binderDied() {
                          synchronized(serviceLock) {
                            if (originService === binder) clearOriginServiceLocked()
                          }
                        }
                      }
                  originService = binder
                  originDeathRecipient = deathRecipient
                  runCatching { binder.linkToDeath(deathRecipient, 0) }
                      .onFailure {
                        Log.w(TAG, "Could not watch real `$name` service", it)
                        clearOriginServiceLocked()
                      }
                }
              }
            }

            override fun asBinder(): IBinder = this
          }
      val registered =
          runCatching {
            getSystemServiceManager().registerForNotifications(serviceName, callback)
            true
          }
          .onFailure { Log.e(TAG, "Failed to register IServiceCallback", it) }
          .getOrDefault(false)
      if (registered) {
        synchronized(serviceLock) {
          if (registrationCallback == null) registrationCallback = callback
        }
      }
    }

    // The Zygisk module polls this name during `system_server` specialization,
    // so it must be claimed on every supported platform.
    synchronized(serviceLock) { proxyServiceName = serviceName }
    return claimProxyService(serviceName)
  }

  private fun claimProxyService(serviceName: String): Boolean {
    return runCatching {
          ServiceManager.addService(serviceName, this)
          true
        }
        .onFailure { Log.e(TAG, "Failed to register proxy service `$serviceName`", it) }
        .getOrDefault(false)
  }

  private fun clearOriginServiceLocked() {
    val service = originService
    val deathRecipient = originDeathRecipient
    originService = null
    originDeathRecipient = null
    if (service != null && deathRecipient != null) {
      runCatching { service.unlinkToDeath(deathRecipient, 0) }
    }
  }

  /**
   * Registers system_server and answers with its framework service, or null if this is not
   * system_server. Only ever called from [onTransact] below.
   */
  private fun attachProcess(
      uid: Int,
      pid: Int,
      processName: String,
      processLifeToken: IBinder?
  ): IFrameworkService? {
    if (uid != 1000 || processLifeToken == null || processName != "system") return null

    // Latched only once the registration has actually succeeded, not on the way in. It used to be
    // set immediately after the gate above, so a registration that then failed — registerHeartBeat
    // answers false when the life token cannot be linked to death — still read as attached. The
    // symptom was the worst kind: the manager's status page reported the framework as present in
    // system_server while no module hooking the system ever loaded, which sends a reader looking
    // at their module instead of at the injection.
    if (!FrameworkService.registerHeartBeat(uid, pid, processName, processLifeToken)) return null
    attachedSystemServerPid = pid
    systemServerRequested = true
    SystemServerRecoveryCoordinator.onSystemServerAttached(pid)
    return FrameworkService
  }

  fun currentAttachedSystemServerPid(): Int = attachedSystemServerPid

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    when (code) {
      BRIDGE_TRANSACTION_CODE -> {
        val uid = data.readInt()
        val pid = data.readInt()
        val processName = data.readString() ?: ""
        val processLifeToken = data.readStrongBinder()

        val service = attachProcess(uid, pid, processName, processLifeToken)
        if (service != null) {
          reply?.writeNoException()
          reply?.writeStrongBinder(service.asBinder())
          return true
        }
        return false
      }
      DEX_TRANSACTION_CODE,
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        return FrameworkService.onTransact(code, data, reply, flags)
      }
      else -> {
        val origin = synchronized(serviceLock) { originService }
        origin?.let {
          // Vector transactions must be handled by this proxy. Forward only transactions that
          // belong to the original Android service after the framework handshake is complete.
          Log.d(TAG, "Forwarding request to real `$proxyServiceName` service.")
          return it.transact(code, data, reply, flags)
        }
        return super.onTransact(code, data, reply, flags)
      }
    }
  }

}
