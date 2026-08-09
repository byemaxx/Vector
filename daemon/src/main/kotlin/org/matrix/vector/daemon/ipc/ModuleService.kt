package org.matrix.vector.daemon.ipc

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import java.util.Collections
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.NotificationManager
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.activityManager

private const val TAG = "VectorModuleService"

class ModuleService(private val loadedModule: Module) : IXposedService.Stub() {

  companion object {
    /** UIDs whose current module-app process successfully received this service binder. */
    private val uidSet = ConcurrentHashMap.newKeySet<Int>()

    /** UIDs with a delivery running so ACTIVE/CACHED/IDLE observer events cannot schedule duplicates. */
    private val sending = ConcurrentHashMap.newKeySet<Int>()

    /** Keeps the provider proxy and DeathRecipient alive until the recipient process dies. */
    private val deliveries = ConcurrentHashMap<Int, Pair<IBinder, IBinder.DeathRecipient>>()

    private val serviceMap = Collections.synchronizedMap(WeakHashMap<Module, ModuleService>())

    private data class FailureRun(val count: Int, val atElapsed: Long)

    private val binderFailures = ConcurrentHashMap<Int, FailureRun>()
    private const val MAX_CONSECUTIVE_BINDER_FAILURES = 3
    private const val BINDER_RETRY_COOLDOWN_MS = 60_000L
    private const val BINDER_FAILURE_RUN_MS = 10 * BINDER_RETRY_COOLDOWN_MS

    /**
     * getContentProviderExternal may block while AMS starts/waits for the module app. Never perform
     * that work on the IUidObserver binder thread, where one broken module would delay every UID.
     */
    private val binderExecutor =
        Executors.newCachedThreadPool { r -> Thread(r, "vector-module-binder") }

    /** API102 reload work never runs inline on the module app's Binder thread. */
    private val hotReloadExecutor =
        Executors.newCachedThreadPool { r -> Thread(r, "vector-hot-reload") }

    fun uidClear() {
      uidSet.clear()
    }

    fun uidStarts(uid: Int) {
      if (uid in uidSet || !sending.add(uid)) return

      val module = ConfigCache.getModuleByUid(uid)
      if (module?.file?.legacy != false) {
        sending.remove(uid)
        return
      }
      if (isThrottled(uid)) {
        sending.remove(uid)
        return
      }

      val service = serviceMap.getOrPut(module) { ModuleService(module) }
      runCatching {
            binderExecutor.execute {
              try {
                val delivered = service.sendBinder(uid)
                if (delivered != null) {
                  uidSet.add(uid)
                  binderFailures.remove(uid)
                  linkDelivery(uid, delivered)
                } else {
                  recordFailure(uid, module.packageName)
                }
              } finally {
                sending.remove(uid)
              }
            }
          }
          .onFailure {
            sending.remove(uid)
            Log.w(TAG, "Could not schedule binder delivery for ${module.packageName}", it)
          }
    }

    /**
     * UID_GONE is not enough: another process under the same UID may survive while the process that
     * received this binder dies. Watching the provider binder allows the restarted module process
     * to become eligible for delivery again without holding an external provider reference.
     */
    private fun linkDelivery(uid: Int, provider: IBinder) {
      val recipient = IBinder.DeathRecipient { uidSet.remove(uid) }
      runCatching {
            provider.linkToDeath(recipient, 0)
            deliveries.put(uid, provider to recipient)?.let { (old, previous) ->
              runCatching { old.unlinkToDeath(previous, 0) }
            }
          }
          .onFailure { uidSet.remove(uid) }
    }

    private fun isThrottled(uid: Int): Boolean {
      val run = binderFailures[uid] ?: return false
      if (run.count < MAX_CONSECUTIVE_BINDER_FAILURES) return false
      return SystemClock.elapsedRealtime() - run.atElapsed < BINDER_RETRY_COOLDOWN_MS
    }

    private fun recordFailure(uid: Int, modulePkg: String) {
      var crossed = false
      binderFailures.compute(uid) { _, previous ->
        val now = SystemClock.elapsedRealtime()
        val count =
            if (previous == null || now - previous.atElapsed >= BINDER_FAILURE_RUN_MS) {
              1
            } else {
              minOf(previous.count + 1, MAX_CONSECUTIVE_BINDER_FAILURES)
            }
        crossed = count == MAX_CONSECUTIVE_BINDER_FAILURES && (previous?.count ?: 0) < count
        FailureRun(count, now)
      }
      if (crossed) {
        Log.w(
            TAG,
            "$modulePkg/$uid failed to take its binder $MAX_CONSECUTIVE_BINDER_FAILURES times in " +
                "a row; retrying at most once every ${BINDER_RETRY_COOLDOWN_MS / 1000}s",
        )
      }
    }

    fun uidGone(uid: Int) {
      uidSet.remove(uid)
      sending.remove(uid)
      deliveries.remove(uid)?.let { (binder, recipient) ->
        runCatching { binder.unlinkToDeath(recipient, 0) }
      }
    }

    /** Trigger an app-update hot reload only when module.prop explicitly opts in. */
    fun autoHotReload(module: Module) {
      if (!isAutoHotReloadEnabled(module)) return
      val targets = ApplicationService.getStaleHotReloadTargetIds(module)
      targets.forEach { targetId ->
        val reservation =
            ApplicationService.beginHotReloadTarget(targetId, module.packageName, userId = null)
        if (reservation != null) {
          if (reservation.status != IXposedService.HOT_RELOAD_IN_PROGRESS) {
            Log.w(
                TAG,
                "Auto hot reload of ${module.packageName} target=$targetId could not start: " +
                    "${reservation.status}: ${reservation.message}",
            )
          }
          return@forEach
        }

        runCatching {
              hotReloadExecutor.execute {
                val result = ApplicationService.runHotReloadTarget(targetId, module, null)
                if (result.status != IXposedService.HOT_RELOAD_SUCCEEDED) {
                  Log.w(
                      TAG,
                      "Auto hot reload of ${module.packageName} target=$targetId returned " +
                          "${result.status}: ${result.message}",
                  )
                }
              }
            }
            .onFailure {
              ApplicationService.cancelHotReloadReservation(targetId)
              Log.w(TAG, "Could not schedule auto hot reload for ${module.packageName}", it)
            }
      }
    }

    private fun isAutoHotReloadEnabled(module: Module): Boolean =
        runCatching {
              ZipFile(module.apkPath).use { zip ->
                val entry = zip.getEntry("META-INF/xposed/module.prop") ?: return@use false
                val props = Properties()
                zip.getInputStream(entry).use { input -> props.load(input) }
                props.getProperty("autoHotReload")?.trim().equals("true", ignoreCase = true)
              }
            }
            .onFailure { Log.w(TAG, "Cannot read autoHotReload for ${module.packageName}", it) }
            .getOrDefault(false)
  }

  /**
   * Forces the module app to receive this service through its ContentProvider and immediately gives
   * AMS's external provider reference back. This is adapted from JingMatrix/Vector@e8bec6b.
   *
   * @return the provider binder if the module app acknowledged delivery; null otherwise.
   */
  private fun sendBinder(uid: Int): IBinder? {
    val name = loadedModule.packageName
    val userId = uid / PER_USER_RANGE
    val authority = name + AUTHORITY_SUFFIX
    val token = Binder()

    return runCatching {
          // Q replaced the three-argument hidden API with a tagged four-argument form. Calling the
          // wrong signature is a NoSuchMethodError on the other side of that API boundary.
          val provider =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityManager?.getContentProviderExternal(authority, userId, token, "vector")
              } else {
                activityManager?.getContentProviderExternal(authority, userId, token)
              }?.provider

          if (provider == null) {
            Log.d(TAG, "No service provider for $name")
            return@runCatching null
          }

          val extra = Bundle().apply { putBinder("binder", asBinder()) }
          val reply: Bundle? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                provider.call(
                    AttributionSource.Builder(1000).setPackageName("android").build(),
                    authority,
                    SEND_BINDER,
                    null,
                    extra,
                )
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                provider.call("android", null, authority, SEND_BINDER, null, extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                provider.call("android", authority, SEND_BINDER, null, extra)
              } else {
                provider.call("android", SEND_BINDER, null, extra)
              }

          if (reply != null) {
            Log.d(TAG, "Sent module binder to $name")
            provider.asBinder()
          } else {
            Log.w(TAG, "Failed to send module binder to $name")
            null
          }
        }
        .onFailure { Log.w(TAG, "Failed to send module binder for uid $uid", it) }
        // AMS records the external handle before waiting for provider publication, so release on
        // success, crash and timeout alike. Otherwise module apps remain at ext-provider foreground
        // priority and failed launches can be restarted repeatedly.
        .also { releaseProvider(authority, token, userId) }
        .getOrNull()
  }

  /** Gives back the exact external provider handle acquired in [sendBinder]. */
  private fun releaseProvider(authority: String, token: Binder, userId: Int) {
    // API 27/28 cannot name the user when releasing. Releasing a secondary user's authority through
    // the user-0 form could decrement the wrong provider record; the token remains the safety net.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && userId != 0) {
      Log.d(TAG, "Cannot explicitly release $authority in user $userId before Android Q")
      return
    }

    runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityManager?.removeContentProviderExternalAsUser(authority, token, userId)
          } else {
            activityManager?.removeContentProviderExternal(authority, token)
          }
        }
        .onFailure { Log.w(TAG, "Failed to release provider reference for $authority", it) }
  }

  private fun ensureModule(): Int {
    val appId = Binder.getCallingUid() % PER_USER_RANGE
    if (loadedModule.appId != appId) {
      throw RemoteException(
          "Module ${loadedModule.packageName} is not for uid ${Binder.getCallingUid()}")
    }
    return Binder.getCallingUid() / PER_USER_RANGE
  }

  override fun getApiVersion() = ensureModule().let { IXposedService.LIB_API }

  override fun getFrameworkName() = ensureModule().let { BuildConfig.FRAMEWORK_NAME }

  override fun getFrameworkVersion() = ensureModule().let { BuildConfig.VERSION_NAME }

  override fun getFrameworkVersionCode() = ensureModule().let { BuildConfig.VERSION_CODE }

  override fun getFrameworkProperties(): Long {
    ensureModule()
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled)
        prop = prop or IXposedService.PROP_RT_API_PROTECTION
    return prop
  }

  override fun getScope(): List<String> {
    val userId = ensureModule()
    return ConfigCache.getModuleScope(loadedModule.packageName)
        ?.filter { it.userId == userId || it.packageName == "system" }
        ?.map { it.packageName }
        ?.distinct() ?: emptyList()
  }

  override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
    val userId = ensureModule()
    val requested = packages.distinct().sorted()
    if (requested.isEmpty()) {
      callback.onScopeRequestApproved(emptyList())
      return
    }

    ConfigCache.staticScopeOf(loadedModule.packageName)?.let { claimed ->
      val beyond = requested.filterNot(claimed::contains)
      if (beyond.isNotEmpty()) {
        callback.onScopeRequestFailed(
            "This module declares a static scope, so ${beyond.joinToString()} cannot be added")
        return
      }
    }

    if (!PreferenceStore.isScopeRequestBlocked(loadedModule.packageName)) {
      NotificationManager.requestModuleScope(
          loadedModule.packageName,
          userId,
          requested,
          callback,
      )
    } else {
      callback.onScopeRequestFailed("Scope request blocked by user configuration")
    }
  }

  override fun removeScope(packages: List<String>) {
    val userId = ensureModule()
    packages.distinct().forEach { pkg ->
      runCatching { ModuleDatabase.removeModuleScope(loadedModule.packageName, pkg, userId) }
          .onFailure { Log.e(TAG, "Error removing scope for $pkg", it) }
    }
  }

  override fun getRunningTargets(): List<HookedProcess> {
    val userId = ensureModule()
    val current = ConfigCache.getModuleByPackage(loadedModule.packageName) ?: loadedModule
    return ApplicationService.getRunningTargets(current, userId)
  }

  override fun hotReloadModule(targetId: Long, data: Bundle?, callback: IHotReloadCallback?) {
    val userId = ensureModule()

    // Invalid or cross-user target IDs take precedence over every ordinary unsupported condition,
    // matching IXposedService's SecurityException contract and upstream's lookup order.
    val targetHotReloadable =
        ApplicationService.validateHotReloadTarget(targetId, loadedModule.packageName, userId)
    if (!targetHotReloadable) {
      report(
          callback,
          IXposedService.HOT_RELOAD_UNSUPPORTED,
          "Module has no single Java entry class",
      )
      return
    }

    val latest =
        ConfigCache.getModuleByPackage(loadedModule.packageName)
            ?: run {
              report(
                  callback,
                  IXposedService.HOT_RELOAD_UNSUPPORTED,
                  "Module ${loadedModule.packageName} is not enabled",
              )
              return
            }

    // The Bundle has already crossed Binder into the framework process. API 102 only requires the
    // caller to keep it classloader-neutral; adding a second framework-side type whitelist would
    // incorrectly reject valid boot/framework Parcelable or Serializable values that upstream
    // accepts. Invalid module-defined parcelables fail naturally while unmarshalling.

    val reservation =
        ApplicationService.beginHotReloadTarget(targetId, loadedModule.packageName, userId)
    if (reservation != null) {
      report(callback, reservation.status, reservation.message)
      return
    }

    runCatching {
          hotReloadExecutor.execute {
            val result = ApplicationService.runHotReloadTarget(targetId, latest, data)
            report(callback, result.status, result.message)
          }
        }
        .onFailure {
          ApplicationService.cancelHotReloadReservation(targetId)
          report(
              callback,
              IXposedService.HOT_RELOAD_FAILED,
              "Could not enqueue hot reload: ${it.message ?: it.javaClass.name}",
          )
        }
  }

  private fun report(callback: IHotReloadCallback?, status: Int, message: String?) {
    runCatching { callback?.onHotReloadResult(status, message) }
        .onFailure { Log.w(TAG, "Cannot deliver hot reload result to ${loadedModule.packageName}", it) }
  }

  override fun requestRemotePreferences(group: String): Bundle {
    val userId = ensureModule()
    return Bundle().apply {
      putSerializable(
          "map",
          PreferenceStore.getModulePrefs(loadedModule.packageName, userId, group) as Serializable)
    }
  }

  @Suppress("DEPRECATION")
  override fun updateRemotePreferences(group: String, diff: Bundle) {
    val userId = ensureModule()
    val values = mutableMapOf<String, Any?>()

    if (diff.getBoolean("clear", false)) {
      PreferenceStore.deleteModulePrefs(loadedModule.packageName, userId, group)
    }
    diff.getSerializable("delete")?.let { deletes ->
      (deletes as Set<*>).forEach { values[it as String] = null }
    }
    diff.getSerializable("put")?.let { puts ->
      (puts as Map<*, *>).forEach { (k, v) -> values[k as String] = v }
    }

    runCatching {
          PreferenceStore.updateModulePrefs(loadedModule.packageName, userId, group, values)
          (loadedModule.service as? InjectedModuleService)
              ?.onUpdateRemotePreferences(group, userId, diff)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemotePreferences(group: String) {
    val userId = ensureModule()
    PreferenceStore.deleteModulePrefs(loadedModule.packageName, userId, group)
    (loadedModule.service as? InjectedModuleService)
        ?.onUpdateRemotePreferences(group, userId, Bundle().apply { putBoolean("clear", true) })
  }

  override fun listRemoteFiles(): Array<String> {
    val userId = ensureModule()
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .toFile()
              .list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          val file =
              FileSystem.resolveModuleDir(
                      loadedModule.packageName, "files", userId, Binder.getCallingUid())
                  .resolve(path)
                  .toFile()
          ParcelFileDescriptor.open(
              file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemoteFile(path: String): Boolean {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .resolve(path)
              .toFile()
              .delete()
        }
        .getOrElse { throw RemoteException(it.message) }
  }
}