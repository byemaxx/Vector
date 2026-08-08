#include <fcntl.h>
#include <errno.h>
#include <jni.h>
#include <limits.h>
#include <sched.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

#include "logging.h"

// Lightweight RAII wrapper to prevent FD leaks
struct UniqueFd {
    int fd;
    explicit UniqueFd(int fd) : fd(fd) {}
    ~UniqueFd() {
        if (fd >= 0) close(fd);
    }
    operator int() const { return fd; }
};

static constexpr int kMaxUnmountLayers = 32;

static bool unmount_all(const char *target) {
    if (!target) return true;
    for (int layer = 0; layer < kMaxUnmountLayers; ++layer) {
        if (umount2(target, MNT_DETACH) == 0) continue;
        if (errno == EINVAL || errno == ENOENT) return true;
        PLOGE("umount %s", target);
        return false;
    }
    LOGE("refusing to unmount more than %d stacked dex2oat mounts at %s", kMaxUnmountLayers, target);
    return false;
}

static bool bind_mount_wrapper(const char *source, const char *target) {
    if (mount(source, target, nullptr, MS_BIND, nullptr) != 0) {
        PLOGE("mount %s to %s", source, target);
        return false;
    }
    if (mount(nullptr, target, nullptr, MS_BIND | MS_REMOUNT | MS_RDONLY, nullptr) != 0) {
        PLOGE("remount %s readonly", target);
        return false;
    }
    return true;
}

static bool apply_mounts(bool enabled, const char *dex2oat32, bool has32, const char *dex2oat64,
                         bool has64, const char *r32p, const char *d32p, const char *r64p,
                         const char *d64p) {
    bool success = true;
    if (!enabled) {
        LOGI("Disable dex2oat wrapper");
        success &= unmount_all(r32p);
        success &= unmount_all(d32p);
        success &= unmount_all(r64p);
        success &= unmount_all(d64p);
        return success;
    }
    LOGI("Enable dex2oat wrapper");
    const auto mount_target = [&success](const char *source, bool source_exists, const char *target) {
        if (!target) return;
        success &= unmount_all(target);
        if (!source_exists) {
            LOGE("missing dex2oat wrapper for %s", target);
            success = false;
            return;
        }
        success &= bind_mount_wrapper(source, target);
    };
    mount_target(dex2oat32, has32, r32p);
    mount_target(dex2oat32, has32, d32p);
    mount_target(dex2oat64, has64, r64p);
    mount_target(dex2oat64, has64, d64p);
    return success;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_matrix_vector_daemon_env_Dex2OatServer_doMountNative(
    JNIEnv *env, jobject, jboolean enabled, jstring r32, jstring d32, jstring r64, jstring d64) {
    char dex2oat32[PATH_MAX] = {};
    char dex2oat64[PATH_MAX] = {};
    const bool has32 = realpath("bin/dex2oat32", dex2oat32) != nullptr;
    const bool has64 = realpath("bin/dex2oat64", dex2oat64) != nullptr;
    if (!has32) PLOGE("resolve realpath for bin/dex2oat32");
    if (!has64) PLOGE("resolve realpath for bin/dex2oat64");

    const char *r32p = r32 ? env->GetStringUTFChars(r32, nullptr) : nullptr;
    const char *d32p = d32 ? env->GetStringUTFChars(d32, nullptr) : nullptr;
    const char *r64p = r64 ? env->GetStringUTFChars(r64, nullptr) : nullptr;
    const char *d64p = d64 ? env->GetStringUTFChars(d64, nullptr) : nullptr;
    auto release_strings = [&]() {
        if (r32p) env->ReleaseStringUTFChars(r32, r32p);
        if (d32p) env->ReleaseStringUTFChars(d32, d32p);
        if (r64p) env->ReleaseStringUTFChars(r64, r64p);
        if (d64p) env->ReleaseStringUTFChars(d64, d64p);
    };

    // The daemon and init mount namespaces can both retain stale binds after soft restart.
    const bool daemon_namespace_ok =
        apply_mounts(enabled, dex2oat32, has32, dex2oat64, has64, r32p, d32p, r64p, d64p);
    const pid_t pid = fork();
    if (pid > 0) {
        int status = 0;
        int waited = -1;
        while ((waited = waitpid(pid, &status, 0)) < 0 && errno == EINTR) {
        }
        release_strings();
        return daemon_namespace_ok && waited >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0
                   ? JNI_TRUE
                   : JNI_FALSE;
    }
    if (pid < 0) {
        PLOGE("fork dex2oat mount namespace child");
        release_strings();
        return JNI_FALSE;
    }

    UniqueFd ns(open("/proc/1/ns/mnt", O_RDONLY));
    if (ns < 0 || setns(ns, CLONE_NEWNS) != 0) {
        PLOGE("enter init mount namespace");
        _exit(1);
    }
    _exit(apply_mounts(enabled, dex2oat32, has32, dex2oat64, has64, r32p, d32p, r64p, d64p) ? 0 : 1);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_enableDex2OatPropertyFallbackNative(JNIEnv *, jobject) {
    const pid_t pid = fork();
    if (pid < 0) {
        PLOGE("fork dex2oat property fallback");
        return JNI_FALSE;
    }
    if (pid == 0) {
        execlp("resetprop", "resetprop", "dalvik.vm.dex2oat-flags", "--inline-max-code-units=0", nullptr);
        PLOGE("set dex2oat property fallback");
        _exit(1);
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        LOGE("dex2oat property fallback failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static int setsockcreatecon_raw(const char *context) {
    std::string path = "/proc/self/task/" + std::to_string(gettid()) + "/attr/sockcreate";
    UniqueFd fd(open(path.c_str(), O_RDWR | O_CLOEXEC));
    if (fd < 0) return -1;

    int ret;
    if (context) {
        do {
            ret = write(fd, context, strlen(context) + 1);
        } while (ret < 0 && errno == EINTR);
    } else {
        do {
            ret = write(fd, nullptr, 0);  // clear
        } while (ret < 0 && errno == EINTR);
    }
    return ret < 0 ? -1 : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_setSockCreateContext(JNIEnv *env, jclass,
                                                                     jstring contextStr) {
    const char *context = contextStr ? env->GetStringUTFChars(contextStr, nullptr) : nullptr;
    if (contextStr && !context) {
        // Only OutOfMemoryError puts us here, and it is pending: returning into Java with it still
        // set would surface it at the next unrelated call.
        env->ExceptionClear();
        return false;
    }
    int ret = setsockcreatecon_raw(context);
    if (context) env->ReleaseStringUTFChars(contextStr, context);
    return ret == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_getSockPath(JNIEnv *env, jobject) {
    return env->NewStringUTF("5291374ceda0aef7c5d86cd2a4f6a3ac\0");
}
