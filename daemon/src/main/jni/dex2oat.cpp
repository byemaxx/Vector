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

struct JStringUtfChars {
    JNIEnv *env;
    jstring value;
    const char *chars;

    JStringUtfChars(JNIEnv *env, jstring value)
        : env(env), value(value), chars(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}

    ~JStringUtfChars() {
        if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    }

    operator const char *() const { return chars; }
};

struct MountTarget {
    const char *source;
    const char *target;
};

static const char *resolve_wrapper_path(const char *relative_path, char *resolved_path, bool needed) {
    if (!needed) return nullptr;
    if (realpath(relative_path, resolved_path) == nullptr) {
        PLOGE("resolve realpath for %s", relative_path);
        return nullptr;
    }
    return resolved_path;
}

static void bind_mount_readonly(const char *source, const char *target) {
    if (source == nullptr || target == nullptr) return;
    if (mount(source, target, nullptr, MS_BIND, nullptr) != 0) {
        PLOGE("mount %s to %s", source, target);
        return;
    }
    if (mount(nullptr, target, nullptr, MS_BIND | MS_REMOUNT | MS_RDONLY, nullptr) != 0) {
        PLOGE("remount %s readonly", target);
    }
}

static void unmount_target(const char *target) {
    if (target == nullptr) return;
    while (umount2(target, MNT_DETACH) == 0) {
    }
    if (errno != EINVAL && errno != ENOENT) {
        PLOGE("umount %s", target);
    }
}

extern "C" JNIEXPORT void JNICALL Java_org_matrix_vector_daemon_env_Dex2OatServer_doMountNative(
    JNIEnv *env, jobject, jboolean enabled, jstring r32, jstring d32, jstring r64, jstring d64) {
    JStringUtfChars r32p(env, r32);
    JStringUtfChars d32p(env, d32);
    JStringUtfChars r64p(env, r64);
    JStringUtfChars d64p(env, d64);

    char dex2oat32[PATH_MAX], dex2oat64[PATH_MAX];
    const char *dex2oat32p = resolve_wrapper_path("bin/dex2oat32", dex2oat32, r32p || d32p);
    const char *dex2oat64p = resolve_wrapper_path("bin/dex2oat64", dex2oat64, r64p || d64p);

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
    } else if (pid < 0) {
        PLOGE("fork dex2oat mount namespace child");
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

        if (enabled) {
            LOGI("Enable dex2oat wrapper");
            const MountTarget targets[] = {
                {dex2oat32p, r32p},
                {dex2oat32p, d32p},
                {dex2oat64p, r64p},
                {dex2oat64p, d64p},
            };
            for (const auto &target : targets) {
                unmount_target(target.target); // Ensure clean slate before mounting
                bind_mount_readonly(target.source, target.target);
            }
        } else {
            LOGI("Disable dex2oat wrapper");
            const char *targets[] = {r32p, d32p, r64p, d64p};
            for (const auto *target : targets) {
                unmount_target(target);
            }
        }

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
    int ret = setsockcreatecon_raw(context);
    if (context) env->ReleaseStringUTFChars(contextStr, context);
    return ret == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_getSockPath(JNIEnv *env, jobject) {
    return env->NewStringUTF("5291374ceda0aef7c5d86cd2a4f6a3ac\0");
}