package org.matrix.vector.daemon.data

import android.util.Log
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

private const val TAG = "VectorStaticScope"

/**
 * Reads a libxposed module's fixed scope declaration without coupling the policy to FileSystem.
 *
 * Semantics are adapted from JingMatrix/Vector commits 04093fd and abae837. Vector-SR keeps this
 * parser separate because its FileSystem also owns soft-restart/reinjection state that should not
 * be rewritten for scope policy.
 *
 * @return null when staticScope is not enabled or its fixed list is unusably empty; otherwise the
 * claimed package set.
 */
object StaticScopeReader {
  fun read(apkPath: String): Set<String>? =
      runCatching {
            ZipFile(File(apkPath)).use { zip ->
              val props =
                  Properties().apply {
                    zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                      runCatching { zip.getInputStream(entry).use { load(it) } }
                    }
                  }
              if (!props.getProperty("staticScope").toBoolean()) return@use null

              val claimed =
                  zip.getEntry("META-INF/xposed/scope.list")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                      lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                  } ?: emptySet()

              if (claimed.isEmpty()) {
                Log.w(TAG, "$apkPath fixes its scope but names nothing; ignoring staticScope")
                return@use null
              }
              claimed
            }
          }
          .onFailure { Log.w(TAG, "Cannot read the scope list of $apkPath", it) }
          .getOrNull()
}
