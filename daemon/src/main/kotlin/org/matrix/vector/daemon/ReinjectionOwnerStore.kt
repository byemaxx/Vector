package org.matrix.vector.daemon

import android.os.Process
import android.os.SELinux
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Log
import org.json.JSONObject
import org.matrix.vector.daemon.data.FileSystem
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ReinjectionOwnerStore"
private const val METADATA_LOCK_MAX_ATTEMPTS = 3
private val METADATA_LOCK_BACKOFF_MS = longArrayOf(100L, 300L, 1_000L)

enum class OwnerStatus {
    VALID,
    MISSING,
    CORRUPT,
    IO_ERROR
}

data class ReinjectionOwner(
    val pid: Int,
    val bootId: String,
    val startTime: Long,
    val cmdline: String,
    val status: OwnerStatus = OwnerStatus.VALID
)

enum class LeaseState {
    ACQUIRED,
    BUSY,
    INDETERMINATE
}

enum class OwnerLiveness {
    ALIVE,
    DEAD,
    INDETERMINATE
}

class ReinjectionLease(
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }
}

object ReinjectionOwnerStore {
    private val storeFile: File
        get() = File(FileSystem.basePath.toFile(), "reinjection_owner.json")
    private val lockFile: File
        get() = File(FileSystem.basePath.toFile(), "reinjection_lease.lock")
    private val metadataLockFile: File
        get() = File(FileSystem.basePath.toFile(), "reinjection_owner_metadata.lock")

    fun acquireLease(): Pair<LeaseState, ReinjectionLease?> {
        return withRootIdentity {
            runCatching { lockFile.parentFile?.mkdirs() }
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                val raf = RandomAccessFile(lockFile, "rw")
                channel = raf.channel
                secureMetadataFile(lockFile)
                lock = channel.tryLock()
                if (lock != null && lock.isValid) {
                    return@withRootIdentity LeaseState.ACQUIRED to ReinjectionLease(channel, lock)
                }
                return@withRootIdentity LeaseState.BUSY to null
            } catch (e: OverlappingFileLockException) {
                return@withRootIdentity LeaseState.BUSY to null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire lease lock", e)
                return@withRootIdentity LeaseState.INDETERMINATE to null
            } finally {
                if (lock == null || !lock.isValid) {
                    runCatching { channel?.close() }
                }
            }
        }
    }

    fun readOwner(): ReinjectionOwner {
        return runCatching { withRootIdentity { withMetadataLock { readOwnerUnlocked() } } }
            .getOrElse {
                Log.w(TAG, "Failed to lock owner metadata for read", it)
                ReinjectionOwner(-1, "", -1, "", OwnerStatus.IO_ERROR)
            }
    }

    private fun readOwnerUnlocked(): ReinjectionOwner {
        if (!storeFile.exists()) {
            return ReinjectionOwner(-1, "", -1, "", OwnerStatus.MISSING)
        }
        return try {
            val json = JSONObject(storeFile.readText())
            if (!json.has("pid") || !json.has("bootId") || !json.has("startTime") ||
                !json.has("cmdline") || json.optInt("version", -1) != 2) {
                return ReinjectionOwner(-1, "", -1, "", OwnerStatus.CORRUPT)
            }
            val pid = json.getInt("pid")
            val bootId = json.getString("bootId")
            val startTime = json.getLong("startTime")
            val cmdline = json.getString("cmdline")
            if (pid <= 0 || bootId.isBlank() || startTime <= 0L || cmdline.isBlank()) {
                return ReinjectionOwner(-1, "", -1, "", OwnerStatus.CORRUPT)
            }
            ReinjectionOwner(pid, bootId, startTime, cmdline, OwnerStatus.VALID)
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Owner file corrupt", e)
            ReinjectionOwner(-1, "", -1, "", OwnerStatus.CORRUPT)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Owner file IO error", e)
            ReinjectionOwner(-1, "", -1, "", OwnerStatus.IO_ERROR)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown error reading owner file", e)
            ReinjectionOwner(-1, "", -1, "", OwnerStatus.CORRUPT)
        }
    }

    fun writeOwner(): Boolean {
        return runCatching { withRootIdentity { withMetadataLock { writeOwnerUnlocked() } } }
            .onFailure { Log.e(TAG, "Failed to lock owner metadata for write", it) }
            .getOrDefault(false)
    }

    private fun writeOwnerUnlocked(): Boolean {
        return try {
            val parentDir = storeFile.parentFile ?: return false
            parentDir.mkdirs()
            val pid = Process.myPid()
            val bootId = readBootId()
            val startTime = readProcessStartTime(pid)
            val cmdline = readProcessCmdline(pid)
            if (pid <= 0 || bootId.isBlank() || startTime <= 0L || cmdline.isBlank()) {
                Log.e(TAG, "Refusing to write incomplete owner metadata")
                return false
            }

            val json = JSONObject().apply {
                put("pid", pid)
                put("bootId", bootId)
                put("startTime", startTime)
                put("cmdline", cmdline)
                put("version", 2)
            }

            val tempFile = File(parentDir, "${storeFile.name}.tmp")
            try {
                RandomAccessFile(tempFile, "rwd").use { raf ->
                    secureMetadataFile(tempFile)
                    raf.write(json.toString().toByteArray())
                    raf.fd.sync()
                }

                try {
                    Files.move(
                        tempFile.toPath(),
                        storeFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempFile.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                secureMetadataFile(storeFile)
            } finally {
                runCatching { tempFile.delete() }
            }

            val fd = Os.open(parentDir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
            val written = readOwnerUnlocked()
            written.status == OwnerStatus.VALID && written.pid == pid &&
                written.bootId == bootId && written.startTime == startTime && written.cmdline == cmdline
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write owner file", e)
            false
        }
    }

    fun getOwnerLiveness(owner: ReinjectionOwner): OwnerLiveness {
        return withRootIdentity { getOwnerLivenessUnlocked(owner) }
    }

    private fun getOwnerLivenessUnlocked(owner: ReinjectionOwner): OwnerLiveness {
        if (owner.status != OwnerStatus.VALID || owner.pid <= 0) return OwnerLiveness.INDETERMINATE

        // Permission to inspect another process is not proof that it is alive. Ask the kernel
        // first, then validate boot, start time, and command line to reject PID reuse.
        try {
            Os.kill(owner.pid, 0)
        } catch (e: ErrnoException) {
            when (e.errno) {
                OsConstants.ESRCH -> return OwnerLiveness.DEAD
                OsConstants.EPERM -> Unit
                else -> return OwnerLiveness.INDETERMINATE
            }
        } catch (_: Exception) {
            return OwnerLiveness.INDETERMINATE
        }

        val bootId = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
            ?: return OwnerLiveness.INDETERMINATE
        if (owner.bootId != bootId) return OwnerLiveness.DEAD

        val currentStartTime = runCatching { readProcessStartTimeStrict(owner.pid) }.getOrNull()
            ?: return OwnerLiveness.INDETERMINATE
        if (currentStartTime != owner.startTime) return OwnerLiveness.DEAD

        val currentCmdline = runCatching { File("/proc/${owner.pid}/cmdline").readText().trim('\u0000') }.getOrNull()
            ?: return OwnerLiveness.INDETERMINATE
        if (currentCmdline.isEmpty()) return OwnerLiveness.DEAD
        if (currentCmdline != owner.cmdline) return OwnerLiveness.DEAD

        return OwnerLiveness.ALIVE
    }

    private fun readBootId(): String {
        return runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
    }

    private fun readProcessStartTime(pid: Int): Long {
        return runCatching {
            val stat = File("/proc/$pid/stat").readText()
            val lastParen = stat.lastIndexOf(')')
            if (lastParen > 0) {
                val parts = stat.substring(lastParen + 2).trim().split(" ")
                if (parts.size > 19) {
                    parts[19].toLong()
                } else -1L
            } else -1L
        }.getOrDefault(-1L)
    }

    private fun readProcessStartTimeStrict(pid: Int): Long {
        val stat = File("/proc/$pid/stat").readText()
        val lastParen = stat.lastIndexOf(')')
        require(lastParen > 0) { "Invalid proc stat format" }
        val parts = stat.substring(lastParen + 2).trim().split(" ")
        require(parts.size > 19) { "Incomplete proc stat" }
        return parts[19].toLong()
    }

    private fun readProcessCmdline(pid: Int): String {
        return runCatching { File("/proc/$pid/cmdline").readText().trim('\u0000') }.getOrDefault("")
    }

    private inline fun <T> withMetadataLock(block: () -> T): T {
        metadataLockFile.parentFile?.mkdirs()
        RandomAccessFile(metadataLockFile, "rw").use { raf ->
            secureMetadataFile(metadataLockFile)
            val channel = raf.channel
            var heldLock: FileLock? = null
            var lastFailure: Throwable? = null
            for (attempt in 0 until METADATA_LOCK_MAX_ATTEMPTS) {
                heldLock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    lastFailure = e
                    null
                } catch (e: Exception) {
                    lastFailure = e
                    null
                }
                if (heldLock != null) break
                if (attempt < METADATA_LOCK_MAX_ATTEMPTS - 1) {
                    Thread.sleep(METADATA_LOCK_BACKOFF_MS[attempt])
                }
            }
            val lock = heldLock ?: throw java.io.IOException(
                "Timed out waiting for owner metadata lock", lastFailure)
            try {
                return block()
            } finally {
                runCatching { lock.release() }
                    .onFailure { Log.w(TAG, "Failed to release owner metadata lock", it) }
            }
        }
    }

    private fun secureMetadataFile(file: File) {
        Os.chmod(file.absolutePath, "600".toInt(8))
        if (!SELinux.setFileContext(file.absolutePath, "u:object_r:system_file:s0")) {
            throw java.io.IOException("Failed to set SELinux context for ${file.name}")
        }
    }

    private inline fun <T> withRootIdentity(block: () -> T): T {
        val originalEuid = runCatching { Os.geteuid() }.getOrDefault(0)
        val switched = originalEuid != 0 && runCatching { Os.seteuid(0) }.isSuccess
        try {
            return block()
        } finally {
            if (switched) {
                runCatching { Os.seteuid(originalEuid) }
                    .onFailure { Log.w(TAG, "Failed to restore euid to $originalEuid", it) }
            }
        }
    }
}
