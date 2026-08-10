#include "core/art_inline_hook_cleanup.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <vector>

#include "common/logging.h"
#include "core/native_api.h"

namespace vector::native {
namespace {

std::mutex g_art_cleanup_mutex;
std::vector<void *> g_art_inline_hook_targets;
bool g_art_cleanup_enabled = false;
bool g_art_cleanup_completed = false;

struct LibraryRestoreState {
    const char *soname;
    bool found = false;
    bool success = true;
    size_t executable_segments = 0;
    size_t restored_segments = 0;
    size_t restored_bytes = 0;
};

bool IsLibrary(const char *path, const char *soname) {
    if (!path || !*path || !soname || !*soname) return false;
    const char *base = std::strrchr(path, '/');
    base = base ? base + 1 : path;
    return std::strcmp(base, soname) == 0;
}

int ProtectionFromFlags(ElfW(Word) flags) {
    int protection = 0;
    if ((flags & PF_R) != 0) protection |= PROT_READ;
    if ((flags & PF_W) != 0) protection |= PROT_WRITE;
    if ((flags & PF_X) != 0) protection |= PROT_EXEC;
    return protection;
}

bool ReadFullyAt(int fd, void *buffer, size_t size, off_t offset) {
    auto *out = static_cast<unsigned char *>(buffer);
    size_t done = 0;
    while (done < size) {
        const ssize_t count = pread(fd, out + done, size - done, offset + done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        done += static_cast<size_t>(count);
    }
    return true;
}

int RestoreLibraryCallback(dl_phdr_info *info, size_t, void *opaque) {
    auto *state = static_cast<LibraryRestoreState *>(opaque);
    if (!info || !IsLibrary(info->dlpi_name, state->soname)) return 0;

    state->found = true;
    const int fd = open(info->dlpi_name, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        const int error = errno;
        LOGE("Failed to open {} while cleaning ART inline hooks: {}", info->dlpi_name,
             std::strerror(error));
        state->success = false;
        return 1;
    }

    const long page_size_long = sysconf(_SC_PAGESIZE);
    if (page_size_long <= 0) {
        LOGE("Failed to determine page size while cleaning {}.", info->dlpi_name);
        close(fd);
        state->success = false;
        return 1;
    }
    const uintptr_t page_size = static_cast<uintptr_t>(page_size_long);

    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD || (phdr.p_flags & PF_X) == 0 || phdr.p_filesz == 0) continue;

        ++state->executable_segments;
        const size_t segment_size = static_cast<size_t>(phdr.p_filesz);
        const uintptr_t segment_begin =
            static_cast<uintptr_t>(info->dlpi_addr) + static_cast<uintptr_t>(phdr.p_vaddr);
        const uintptr_t segment_end = segment_begin + segment_size;
        if (segment_end < segment_begin) {
            LOGE("Executable segment address overflow while cleaning {}.", info->dlpi_name);
            state->success = false;
            continue;
        }

        std::vector<unsigned char> clean_bytes(segment_size);
        if (!ReadFullyAt(fd, clean_bytes.data(), segment_size, static_cast<off_t>(phdr.p_offset))) {
            const int error = errno;
            LOGE("Failed to read executable segment {} from {}: {}", i, info->dlpi_name,
                 std::strerror(error));
            state->success = false;
            continue;
        }

        // DobbyDestroy normally restores the target prologues. Comparing the complete executable
        // segment catches any remaining inline patch that was not represented in our target list.
        if (std::memcmp(reinterpret_cast<const void *>(segment_begin), clean_bytes.data(),
                        segment_size) == 0) {
            LOGD("{} executable segment {} already matches its backing file.", state->soname, i);
            continue;
        }

        const uintptr_t protection_begin = segment_begin - (segment_begin % page_size);
        const uintptr_t protection_end =
            ((segment_end + page_size - 1) / page_size) * page_size;
        if (protection_end < segment_end || protection_end < protection_begin) {
            LOGE("Executable segment protection range overflow while cleaning {}.", info->dlpi_name);
            state->success = false;
            continue;
        }

        const size_t protection_size = static_cast<size_t>(protection_end - protection_begin);
        const int original_protection = ProtectionFromFlags(phdr.p_flags);
        const int writable_protection = original_protection | PROT_WRITE;
        if (mprotect(reinterpret_cast<void *>(protection_begin), protection_size,
                     writable_protection) != 0) {
            const int error = errno;
            LOGE("Failed to make {} executable segment {} writable: {}", state->soname, i,
                 std::strerror(error));
            state->success = false;
            continue;
        }

        std::memcpy(reinterpret_cast<void *>(segment_begin), clean_bytes.data(), segment_size);
        __builtin___clear_cache(reinterpret_cast<char *>(segment_begin),
                                reinterpret_cast<char *>(segment_end));

        if (mprotect(reinterpret_cast<void *>(protection_begin), protection_size,
                     original_protection) != 0) {
            const int error = errno;
            LOGE("Failed to restore protection for {} executable segment {}: {}", state->soname, i,
                 std::strerror(error));
            state->success = false;
        }

        ++state->restored_segments;
        state->restored_bytes += segment_size;
    }

    close(fd);
    return 1;  // libart.so is unique in the process; stop after the matching image.
}

bool RestoreLibArtExecutableSegmentsFromDisk() {
    LibraryRestoreState state{.soname = "libart.so"};
    dl_iterate_phdr(RestoreLibraryCallback, &state);

    if (!state.found) {
        LOGE("Unable to locate loaded libart.so for inline-hook cleanup.");
        return false;
    }
    if (state.executable_segments == 0) {
        LOGE("Loaded libart.so has no executable PT_LOAD segment.");
        return false;
    }

    if (state.success) {
        if (state.restored_segments == 0) {
            LOGI("libart.so executable image is clean after tracked Dobby hooks were removed.");
        } else {
            LOGI("Restored {} libart.so executable segment(s), {} bytes, from the backing file.",
                 state.restored_segments, state.restored_bytes);
        }
    }
    return state.success;
}

}  // namespace

void ConfigureArtInlineHookCleanup(bool enabled) {
    std::lock_guard<std::mutex> lock(g_art_cleanup_mutex);
    g_art_inline_hook_targets.clear();
    g_art_cleanup_enabled = enabled;
    g_art_cleanup_completed = false;
}

void RecordArtInlineHookTarget(void *target) {
    if (!target) return;
    std::lock_guard<std::mutex> lock(g_art_cleanup_mutex);
    if (std::find(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target) ==
        g_art_inline_hook_targets.end()) {
        g_art_inline_hook_targets.push_back(target);
    }
}

void ForgetArtInlineHookTarget(void *target) {
    std::lock_guard<std::mutex> lock(g_art_cleanup_mutex);
    g_art_inline_hook_targets.erase(
        std::remove(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target),
        g_art_inline_hook_targets.end());
}

bool CleanupArtInlineHooksIfEnabled() {
    std::lock_guard<std::mutex> lock(g_art_cleanup_mutex);
    if (!g_art_cleanup_enabled || g_art_cleanup_completed) return true;

    LOGI("Running deferred ART inline-hook cleanup after initial package lifecycle callbacks.");

    const size_t total = g_art_inline_hook_targets.size();
    std::vector<void *> failed;
    failed.reserve(total);
    for (auto it = g_art_inline_hook_targets.rbegin(); it != g_art_inline_hook_targets.rend(); ++it) {
        if (UnhookInline(*it) != 0) {
            LOGW("Failed to unregister tracked LSPlant ART inline hook at {}.", *it);
            failed.push_back(*it);
        }
    }
    std::reverse(failed.begin(), failed.end());
    g_art_inline_hook_targets = std::move(failed);

    const size_t removed = total - g_art_inline_hook_targets.size();
    const bool bookkeeping_clean = g_art_inline_hook_targets.empty();
    if (bookkeeping_clean) {
        LOGI("Removed all {} tracked LSPlant ART Dobby hook(s).", removed);
    } else {
        LOGW("Removed {} of {} tracked LSPlant ART Dobby hook(s); {} remain registered.", removed,
             total, g_art_inline_hook_targets.size());
    }

    const bool image_clean = RestoreLibArtExecutableSegmentsFromDisk();
    g_art_cleanup_completed = bookkeeping_clean && image_clean;
    if (g_art_cleanup_completed) {
        LOGI("Deferred ART inline-hook cleanup completed successfully.");
    } else {
        LOGW("Deferred ART inline-hook cleanup completed with failures.");
    }
    return g_art_cleanup_completed;
}

}  // namespace vector::native
