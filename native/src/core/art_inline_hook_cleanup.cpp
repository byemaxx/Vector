#include "core/art_inline_hook_cleanup.h"

#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "common/logging.h"

namespace vector::native {
namespace {

std::mutex g_art_cleanup_mutex;
std::vector<void *> g_art_inline_hook_targets;
bool g_art_cleanup_enabled = false;
bool g_art_cleanup_completed = false;

struct LibArtRestoreResult {
    bool found = false;
    bool success = false;
    size_t executable_segments = 0;
    size_t modified_pages = 0;
    size_t restored_bytes = 0;
};

bool IsLibArtPath(const char *path) {
    if (!path || *path == '\0') return false;
    const char *name = std::strrchr(path, '/');
    name = name ? name + 1 : path;
    return std::strcmp(name, "libart.so") == 0;
}

int ProtectionFromFlags(ElfW(Word) flags) {
    int protection = 0;
    if ((flags & PF_R) != 0) protection |= PROT_READ;
    if ((flags & PF_W) != 0) protection |= PROT_WRITE;
    if ((flags & PF_X) != 0) protection |= PROT_EXEC;
    return protection;
}

bool RestoreExecutableSegments(const dl_phdr_info *info, LibArtRestoreResult &result) {
    const char *path = info->dlpi_name;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        PLOGE("Failed to open libart backing file '{}'", path);
        return false;
    }

    struct stat file_stat {};
    if (fstat(fd, &file_stat) != 0 || file_stat.st_size <= 0) {
        PLOGE("Failed to stat libart backing file '{}'", path);
        close(fd);
        return false;
    }

    const size_t file_size = static_cast<size_t>(file_stat.st_size);
    void *file_map = mmap(nullptr, file_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (file_map == MAP_FAILED) {
        PLOGE("Failed to map libart backing file '{}'", path);
        close(fd);
        return false;
    }

    const long page_size_value = sysconf(_SC_PAGESIZE);
    if (page_size_value <= 0) {
        LOGE("Failed to determine page size while invalidating libart.so");
        munmap(file_map, file_size);
        close(fd);
        return false;
    }
    const size_t page_size = static_cast<size_t>(page_size_value);
    const uintptr_t page_mask = static_cast<uintptr_t>(page_size - 1);

    bool success = true;
    const auto *clean_file = static_cast<const uint8_t *>(file_map);

    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD || (phdr.p_flags & PF_X) == 0 || phdr.p_filesz == 0) {
            continue;
        }

        ++result.executable_segments;

        const size_t file_offset = static_cast<size_t>(phdr.p_offset);
        const size_t segment_size = static_cast<size_t>(phdr.p_filesz);
        if (file_offset > file_size || segment_size > file_size - file_offset) {
            LOGE("Executable libart segment {} exceeds backing file bounds", i);
            success = false;
            continue;
        }

        const uintptr_t segment_start =
            static_cast<uintptr_t>(info->dlpi_addr) + static_cast<uintptr_t>(phdr.p_vaddr);
        if (segment_size > UINTPTR_MAX - segment_start) {
            LOGE("Executable libart segment {} address range overflows", i);
            success = false;
            continue;
        }
        const uintptr_t segment_end = segment_start + segment_size;
        const auto *clean_segment = clean_file + file_offset;
        const int original_protection = ProtectionFromFlags(phdr.p_flags);
        const size_t first_page_prefix = static_cast<size_t>(segment_start & page_mask);
        if (file_offset < first_page_prefix) {
            LOGE("Executable libart segment {} has an invalid page-aligned file offset", i);
            success = false;
            continue;
        }
        const size_t first_page_file_offset = file_offset - first_page_prefix;

        // Count dirty pages first, then replace the complete executable PT_LOAD in one mmap. Mapping
        // individual dirty pages leaves visible VMA boundaries around every former trampoline;
        // protection libraries can treat that non-standard libart layout as tampering even when all
        // instruction bytes have been restored. A segment-wide file mapping recreates the loader's
        // normal contiguous VMA shape and also avoids a transient writable executable mapping.
        const uintptr_t mapping_start = segment_start & ~page_mask;
        uintptr_t page_start = mapping_start;
        size_t segment_modified_pages = 0;
        size_t segment_restored_bytes = 0;
        while (page_start < segment_end) {
            const uintptr_t next_page = page_start + page_size;
            if (next_page < page_start) {
                LOGE("Page range overflow while invalidating libart.so");
                success = false;
                break;
            }

            const uintptr_t copy_start = std::max(page_start, segment_start);
            const uintptr_t copy_end = std::min(next_page, segment_end);
            const size_t copy_size = static_cast<size_t>(copy_end - copy_start);
            const size_t segment_offset = static_cast<size_t>(copy_start - segment_start);
            auto *live = reinterpret_cast<uint8_t *>(copy_start);
            const auto *clean = clean_segment + segment_offset;

            if (std::memcmp(live, clean, copy_size) != 0) {
                ++segment_modified_pages;
                segment_restored_bytes += copy_size;
            }

            page_start = next_page;
        }

        if (segment_modified_pages == 0) continue;

        const size_t mapping_span = first_page_prefix + segment_size;
        if (mapping_span > SIZE_MAX - page_mask) {
            LOGE("Executable libart segment {} mapping size overflows", i);
            success = false;
            continue;
        }
        const size_t mapping_size = (mapping_span + page_mask) & ~page_mask;
        if (first_page_file_offset > file_size ||
            mapping_size > file_size - first_page_file_offset) {
            LOGE("Executable libart segment {} aligned mapping exceeds backing file bounds", i);
            success = false;
            continue;
        }

        void *mapped = mmap(reinterpret_cast<void *>(mapping_start), mapping_size,
                            original_protection, MAP_PRIVATE | MAP_FIXED, fd,
                            static_cast<off_t>(first_page_file_offset));
        if (mapped == MAP_FAILED) {
            PLOGE("Failed to invalidate executable libart segment {} at {} from backing offset {}",
                  i, reinterpret_cast<void *>(mapping_start), first_page_file_offset);
            success = false;
            continue;
        }
        __builtin___clear_cache(reinterpret_cast<char *>(mapping_start),
                                reinterpret_cast<char *>(mapping_start + mapping_size));
        result.modified_pages += segment_modified_pages;
        result.restored_bytes += segment_restored_bytes;
    }

    munmap(file_map, file_size);
    close(fd);
    return success && result.executable_segments > 0;
}

int RestoreLibArtCallback(dl_phdr_info *info, size_t, void *data) {
    if (!IsLibArtPath(info->dlpi_name)) return 0;

    auto &result = *static_cast<LibArtRestoreResult *>(data);
    result.found = true;
    result.success = RestoreExecutableSegments(info, result);
    return 1;  // libart.so is unique in an app process; stop after handling it.
}

LibArtRestoreResult RestoreLibArtExecutableBytes() {
    LibArtRestoreResult result;
    dl_iterate_phdr(RestoreLibArtCallback, &result);
    if (!result.found) {
        LOGE("Unable to locate loaded libart.so for executable-byte invalidation");
    }
    return result;
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
    if (!g_art_cleanup_enabled || g_art_cleanup_completed) return;

    if (std::find(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target) ==
        g_art_inline_hook_targets.end()) {
        g_art_inline_hook_targets.push_back(target);
    }
}

void ForgetArtInlineHookTarget(void *target) {
    if (!target) return;

    std::lock_guard<std::mutex> lock(g_art_cleanup_mutex);
    g_art_inline_hook_targets.erase(
        std::remove(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target),
        g_art_inline_hook_targets.end());
}

bool CleanupArtInlineHooksIfEnabled() {
    std::lock_guard<std::mutex> lock(g_art_cleanup_mutex);
    if (!g_art_cleanup_enabled || g_art_cleanup_completed) return true;

    const size_t tracked_targets = g_art_inline_hook_targets.size();
    LOGI("Running libart.so executable-byte invalidation after framework bootstrap "
         "({} tracked LSPlant target(s)).",
         tracked_targets);

    // Deliberately do not call DobbyDestroy/UnhookInline here. LSPosed-style invalidation is a
    // compatibility operation, not normal hook teardown: restore libart.so's file-backed executable
    // image while leaving LSPlant/Dobby trampoline and interceptor metadata intact. Apps opting into
    // this mode accept that ART maintenance hooks no longer execute afterwards.
    const LibArtRestoreResult result = RestoreLibArtExecutableBytes();
    if (!result.success) {
        LOGW("libart.so executable-byte invalidation failed; cleanup remains armed for a "
             "later retry.");
        return false;
    }

    g_art_inline_hook_targets.clear();
    g_art_cleanup_completed = true;
    g_art_cleanup_enabled = false;

    if (result.modified_pages == 0) {
        LOGI("libart.so executable segments already match the backing file ({} segment(s) checked).",
             result.executable_segments);
    } else {
        LOGI("Invalidated libart.so executable pages from backing file: {} modified page(s), {} "
             "file-backed byte(s) restored across {} executable segment(s).",
             result.modified_pages, result.restored_bytes, result.executable_segments);
    }
    return true;
}

}  // namespace vector::native
