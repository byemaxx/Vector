package org.matrix.vector.daemon.data

import android.util.Log
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

private const val TAG = "VectorStaticScope"

/**
 * Reads a libxposed module's fixed scope declaration without coupling the policy to FileSystem.
 *
 * Semantics are adapted from JingMatrix/Vector commit
 * 04093fdbf4cff2426cd1b320d5e568282d0e2a77. Vector-SR keeps this parser separate because its
 * FileSystem also owns soft-restart/reinjection state that should not be rewritten for scope policy.
 *
 * @return null when staticScope is not enabled, otherwise the claimed package set (possibly empty).
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
              val entry = zip.getEntry("META-INF/xposed/scope.list") ?: return@use emptySet()
              zip.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
              }
            }
          }
          .onFailure { Log.w(TAG, "Cannot read the scope list of $apkPath", it) }
          .getOrNull()
}
