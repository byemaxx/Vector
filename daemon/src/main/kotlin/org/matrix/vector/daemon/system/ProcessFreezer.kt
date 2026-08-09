package org.matrix.vector.daemon.system

import android.util.Log
import java.io.File

private const val TAG = "VectorFreezer"

/**
 * Thaws a frozen process for the duration of a daemon-initiated hot-reload transaction.
 *
 * Android may freeze cached application processes. A oneway Binder transaction is then not
 * delivered until the process is thawed, which would otherwise turn a healthy background target
 * into a framework timeout without ever running the module's lifecycle callback.
 */
object ProcessFreezer {

  /** Resolve the process-level cgroup v2 freezer file without guessing an OEM cgroup layout. */
  private fun freezeFile(pid: Int): File? {
    val path =
        runCatching {
              File("/proc/$pid/cgroup")
                  .readLines()
                  .firstOrNull { it.startsWith("0::") }
                  ?.removePrefix("0::")
                  ?.trim()
                  ?.takeIf { it.isNotEmpty() && it != "/" }
            }
            .getOrNull() ?: return null

    // Never thaw a uid-wide cgroup: only the addressed target process belongs to this reload.
    if (!path.contains("/pid_")) return null

    return File("/sys/fs/cgroup$path/cgroup.freeze").takeIf { it.exists() }
  }

  fun isFrozen(pid: Int): Boolean =
      runCatching { freezeFile(pid)?.readText()?.trim() == "1" }.getOrDefault(false)

  /**
   * Thaw [pid] if necessary and return a best-effort action that restores its previous frozen state.
   * Null means either that the platform has no usable process freezer or that the process was
   * already running.
   */
  fun thaw(pid: Int): (() -> Unit)? {
    val file = freezeFile(pid) ?: return null
    val wasFrozen = runCatching { file.readText().trim() == "1" }.getOrDefault(false)
    if (!wasFrozen) return null

    if (runCatching { file.writeText("0") }.isFailure) {
      Log.w(TAG, "Cannot thaw pid=$pid through ${file.path}")
      return null
    }

    Log.d(TAG, "Thawed pid=$pid for API102 hot reload")
    return {
      runCatching {
            if (file.readText().trim() == "0") {
              file.writeText("1")
            } else {
              Log.d(TAG, "Left pid=$pid alone: another owner changed its freezer state")
            }
          }
          .onFailure { Log.w(TAG, "Cannot re-freeze pid=$pid", it) }
    }
  }
}
