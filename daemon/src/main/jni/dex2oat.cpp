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

static void unmount_all(const char *target) {
    if (target == nullptr) return;

    while (umount2(target, MNT_DETACH) == 0) {
    }

    if (errno != EINVAL && errno != ENOENT) {
        PLOGE("umount %s", target);
    }
}

static void bind_mount_wrapper(const char *source, const char *target) {
    if (mount(source, target, nullptr, MS_BIND, nullptr) != 0) {
        PLOGE("mount %s to %s", source, target);
        return;
    }
    if (mount(nullptr, target, nullptr, MS_BIND | MS_REMOUNT | MS_RDONLY, nullptr) != 0) {
        PLOGE("remount %s readonly", target);
    }
}

static void apply_mounts(bool enabled, const char *dex2oat32, bool has32, const char *dex2oat64,
                         bool has64, const char *r32p, const char *d32p, const char *r64p,
                         const char *d64p) {
    if (enabled) {
        LOGI("Enable dex2oat wrapper");
        if (r32p && has32) {
            unmount_all(r32p);
            bind_mount_wrapper(dex2oat32, r32p);
        }
        if (d32p && has32) {
            unmount_all(d32p);
            bind_mount_wrapper(dex2oat32, d32p);
        }
        if (r64p && has64) {
            unmount_all(r64p);
            bind_mount_wrapper(dex2oat64, r64p);
        }
        if (d64p && has64) {
            unmount_all(d64p);
            bind_mount_wrapper(dex2oat64, d64p);
        }
    } else {
        LOGI("Disable dex2oat wrapper");
        unmount_all(r32p);
        unmount_all(d32p);
        unmount_all(r64p);
        unmount_all(d64p);
    }
}

extern "C" JNIEXPORT void JNICALL Java_org_matrix_vector_daemon_env_Dex2OatServer_doMountNative(
    JNIEnv *env, jobject, jboolean enabled, jstring r32, jstring d32, jstring r64, jstring d64) {
    char dex2oat32[PATH_MAX] = {};
    char dex2oat64[PATH_MAX] = {};
    bool has32 = realpath("bin/dex2oat32", dex2oat32) != nullptr;
    bool has64 = realpath("bin/dex2oat64", dex2oat64) != nullptr;
    if (!has32) {
        PLOGE("resolve realpath for bin/dex2oat32");
    }
    if (!has64) {
        PLOGE("resolve realpath for bin/dex2oat64");
    }

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

    apply_mounts(enabled, dex2oat32, has32, dex2oat64, has64, r32p, d32p, r64p, d64p);

    pid_t pid = fork();
    if (pid > 0) {  // Parent process
        int status = 0;
        pid_t waited;
        do {
            waited = waitpid(pid, &status, 0);
        } while (waited < 0 && errno == EINTR);

        if (waited < 0) {
            PLOGE("waitpid dex2oat mount namespace child");
        } else {
            if (WIFEXITED(status)) {
                if (WEXITSTATUS(status) != 0) {
                    LOGE("dex2oat mount namespace child exited with status %d",
                         WEXITSTATUS(status));
                }
            } else {
                LOGE("dex2oat mount namespace child exited abnormally: %d", status);
            }
        }

        release_strings();
    } else if (pid < 0) {
        PLOGE("fork dex2oat mount namespace child");
        release_strings();
    } else if (pid == 0) {  // Child process
        UniqueFd ns(open("/proc/1/ns/mnt", O_RDONLY));
        if (ns < 0) {
            PLOGE("open /proc/1/ns/mnt");
            _exit(1);
        }
        if (setns(ns, CLONE_NEWNS) != 0) {
            PLOGE("setns /proc/1/ns/mnt");
            _exit(1);
        }

        apply_mounts(enabled, dex2oat32, has32, dex2oat64, has64, r32p, d32p, r64p, d64p);

        // Do not mutate dalvik.vm.dex2oat-flags here.
        // doMount(false) can be a temporary cleanup step during soft restart
        // recovery, not necessarily a final wrapper failure. Runtime set/delete
        // of this property can leave property-area hole traces.
        _exit(0);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_enableDex2OatPropertyFallbackNative(JNIEnv *, jobject) {
    pid_t pid = fork();
    if (pid < 0) {
        PLOGE("Failed to fork for dex2oat property fallback");
        return JNI_FALSE;
    }

    if (pid == 0) {
        execlp("resetprop", "resetprop",
               "dalvik.vm.dex2oat-flags",
               "--inline-max-code-units=0",
               nullptr);
        PLOGE("Failed to set dalvik.vm.dex2oat-flags fallback");
        _exit(1);
    }

    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        PLOGE("Failed to wait for resetprop fallback");
        return JNI_FALSE;
    }

    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        LOGE("resetprop fallback exited abnormally: %d", status);
        return JNI_FALSE;
    }

    LOGI("Enabled dex2oat property fallback");
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
        // Only OutOfMemoryError puts us here, and it is pending. Returning to Java with the
        // exception still set would make it surface at a later, unrelated JNI call.
        env->ExceptionClear();
        return JNI_FALSE;
    }
    int ret = setsockcreatecon_raw(context);
    if (context) env->ReleaseStringUTFChars(contextStr, context);
    return ret == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_getSockPath(JNIEnv *env, jobject) {
    return env->NewStringUTF("5291374ceda0aef7c5d86cd2a4f6a3ac\0");
}