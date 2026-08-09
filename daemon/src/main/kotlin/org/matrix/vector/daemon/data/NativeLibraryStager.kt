package org.matrix.vector.daemon.data

import android.os.Build
import android.os.Process
import android.os.SELinux
import android.system.Os
import android.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig

/**
 * Stages native libraries only for modules that will run inside system_server.
 *
 * Adapted from JingMatrix/Vector commit 5ff67a87cb70414d8c52b59f97f56a9de257ee49.
 * Vector-SR keeps this responsibility separate from FileSystem because FileSystem also owns the
 * SR reinjection lease and soft-restart state. The staging lifecycle is intentionally orthogonal to
 * reinjection: it is keyed by module APK identity and framework version, not daemon/restart rounds.
 */
object NativeLibraryStager {
  private const val TAG = "VectorNativeStager"
  private const val XPOSED_DATA_CONTEXT = "u:object_r:xposed_data:s0"

  /** Stage native libraries for all modules being handed to system_server and prune stale copies. */
  fun prepareForSystemServer(modules: List<Module>): List<Module> {
    val root = ensureMiscPath() ?: return modules

    modules.forEach { stageModule(root, it) }
    prune(root, modules.mapTo(mutableSetOf()) { it.packageName })
    return modules
  }

  /**
   * Stage the generation addressed by an already-existing system_server hot-reload target.
   *
   * Target ownership is historical: removing `system` from scope does not make the generation that
   * is already running disappear. Reloading that target therefore must not depend on the module
   * still being present in today's system scope. Do not prune sibling staging directories here.
   */
  fun prepareAddressedSystemServerModule(module: Module): Module? {
    val root = ensureMiscPath() ?: return module
    stageModule(root, module)
    return module
  }

  private fun stageModule(root: Path, module: Module) {
    val file = module.file ?: return
    file.nativeLibraryDir =
        stage(root, module.packageName, module.apkPath, module.versionCode)
  }

  /**
   * Resolve the same misc root used by ConfigCache without mutating ConfigCache's state object.
   *
   * ConfigCache will later read the same persisted path if its cache was not initialized yet. The
   * synchronized block prevents two early system_server queries in this daemon from generating
   * different roots.
   */
  private fun ensureMiscPath(): Path? =
      runCatching {
            ConfigCache.state.miscPath?.let { return@runCatching it }

            synchronized(ConfigCache) {
              ConfigCache.state.miscPath?.let { return@synchronized it }

              val pathStr =
                  PreferenceStore.getModulePrefs("lspd", 0, "config")["misc_path"] as? String
              val path =
                  if (pathStr == null) {
                    val newPath = Paths.get("/data/misc", UUID.randomUUID().toString())
                    PreferenceStore.updateModulePref(
                        "lspd", 0, "config", "misc_path", newPath.toString())
                    newPath
                  } else {
                    Paths.get(pathStr)
                  }

              val perms =
                  PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rwx--x--x"))
              Files.createDirectories(path, perms)
              Os.chmod(path.toString(), "711".toInt(8))
              setSelinuxContextRecursive(path, XPOSED_DATA_CONTEXT)
              path
            }
          }
          .onFailure { Log.e(TAG, "Failed to prepare native staging root", it) }
          .getOrNull()

  /**
   * Copy the current process ABI's .so files out of a module APK into xposed_data.
   *
   * system_server may read /data/app APKs but cannot create executable mappings from apk_data_file.
   * A staged ordinary file under xposed_data can be mapped executable with the framework's existing
   * policy. The stamp prevents stale native code after a module or framework update.
   */
  private fun stage(
      root: Path,
      packageName: String,
      apkPath: String,
      versionCode: Long,
  ): String? =
      runCatching {
            val apk = java.io.File(apkPath)
            val dir = root.resolve("lib").resolve(packageName)
            // versionCode closes the same-size/same-mtime update edge that ModuleCodeIdentity can
            // still distinguish. Early system_server modules may use 0; once PackageManager is up,
            // the real version naturally causes one refresh of the staged generation.
            val stamp =
                "$versionCode:${apk.length()}:${apk.lastModified()}:${BuildConfig.VERSION_CODE}"
            val stampFile = dir.resolve(".stamp").toFile()

            if (stampFile.isFile && stampFile.readText() == stamp) {
              return@runCatching dir.toString()
            }

            val abis =
                if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS

            ZipFile(apk).use { zip ->
              val libraries =
                  zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }
                      .toList()
              val abi =
                  abis.firstOrNull { abi -> libraries.any { it.name.startsWith("lib/$abi/") } }
                      ?: return@runCatching null

              dir.toFile().deleteRecursively()
              Files.createDirectories(dir)

              libraries
                  .filter { it.name.startsWith("lib/$abi/") }
                  .forEach { entry ->
                    val target = dir.resolve(entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                      Files.newOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    Os.chmod(target.toString(), "644".toInt(8))
                  }

              stampFile.writeText(stamp)
              Os.chmod(stampFile.absolutePath, "644".toInt(8))
              Os.chmod(dir.parent.toString(), "711".toInt(8))
              Os.chmod(dir.toString(), "711".toInt(8))
              setSelinuxContextRecursive(dir, XPOSED_DATA_CONTEXT)
              SELinux.setFileContext(dir.parent.toString(), XPOSED_DATA_CONTEXT)
              dir.toString()
            }
          }
          .onFailure { Log.e(TAG, "Failed to stage native libraries of $packageName", it) }
          .getOrNull()

  private fun prune(root: Path, keep: Set<String>) {
    runCatching {
      val libRoot = root.resolve("lib")
      if (!libRoot.isDirectory()) return
      Files.list(libRoot).use { stream ->
        stream
            .filter { it.fileName.toString() !in keep }
            .forEach { it.toFile().deleteRecursively() }
      }
    }.onFailure { Log.e(TAG, "Failed to prune staged native libraries", it) }
  }

  private fun setSelinuxContextRecursive(path: Path, context: String) {
    Files.walk(path).use { stream ->
      stream.forEach { item ->
        if (!SELinux.setFileContext(item.toString(), context)) {
          throw IllegalStateException("Failed to set SELinux context on $item")
        }
      }
    }
  }
}