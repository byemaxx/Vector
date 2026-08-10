#include "core/art_inline_hook_cleanup.h"

#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "Interceptor.h"
#include "common/logging.h"
#include "core/native_api.h"

namespace vector::native {
namespace {

std::mutex g_art_cleanup_mutex;
std::vector<void *> g_art_inline_hook_targets;
bool g_art_cleanup_enabled = false;
bool g_art_cleanup_completed = false;

struct MappingInfo {
    uintptr_t start = 0;
    uintptr_t end = 0;
    bool readable = false;
    bool writable = false;
    bool executable = false;
    bool private_mapping = false;
    std::string path;
};

uintptr_t PageForAddress(uintptr_t address, size_t page_size) {
    return address - (address % page_size);
}

void TrimMappingPath(std::string &path) {
    while (!path.empty() &&
           (path.front() == ' ' || path.front() == '\t')) {
        path.erase(path.begin());
    }
    while (!path.empty() &&
           (path.back() == '\n' || path.back() == '\r' || path.back() == ' ' ||
            path.back() == '\t')) {
        path.pop_back();
    }
}

bool FindMappingForAddress(uintptr_t address, MappingInfo &result) {
    FILE *maps = std::fopen("/proc/self/maps", "r");
    if (!maps) {
        PLOGE("Failed to open /proc/self/maps while cleaning ART inline hooks");
        return false;
    }

    char line[4096];
    bool found = false;
    while (std::fgets(line, sizeof(line), maps)) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        unsigned long long offset = 0;
        unsigned long long inode = 0;
        char permissions[5] = {};
        char device[32] = {};
        int consumed = 0;

        if (std::sscanf(line, "%llx-%llx %4s %llx %31s %llu %n", &start, &end,
                        permissions, &offset, device, &inode, &consumed) < 6) {
            continue;
        }

        if (address < start || address >= end) continue;

        result.start = static_cast<uintptr_t>(start);
        result.end = static_cast<uintptr_t>(end);
        result.readable = permissions[0] == 'r';
        result.writable = permissions[1] == 'w';
        result.executable = permissions[2] == 'x';
        result.private_mapping = permissions[3] == 'p';
        if (consumed > 0 && static_cast<size_t>(consumed) < sizeof(line)) {
            result.path.assign(line + consumed);
            TrimMappingPath(result.path);
        }
        found = true;
        break;
    }

    std::fclose(maps);
    return found;
}

bool IsDisposableDobbyMapping(const MappingInfo &mapping) {
    // Dobby's relocated-code arena is a private anonymous RX mapping after DobbyCodePatch returns.
    if (!mapping.readable || mapping.writable || !mapping.executable || !mapping.private_mapping) {
        return false;
    }

    if (mapping.path.empty()) return true;

    // Some Android builds may name anonymous VMAs. Permit only anonymous labels and explicitly
    // reject ART/JIT/ashmem-style mappings so LSPlant Java trampolines can never be neutralized here.
    if (mapping.path.rfind("[anon:", 0) != 0) return false;
    if (mapping.path.find("dalvik") != std::string::npos ||
        mapping.path.find("jit") != std::string::npos ||
        mapping.path.find("ashmem") != std::string::npos) {
        return false;
    }
    return true;
}

bool IsTrackedTarget(uintptr_t address) {
    const auto *target = reinterpret_cast<void *>(address);
    return std::find(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target) !=
           g_art_inline_hook_targets.end();
}

bool CollectAndValidateRelocatedPages(size_t page_size, std::vector<uintptr_t> &pages) {
    auto *interceptor = Interceptor::SharedInstance();
    if (!interceptor) {
        LOGE("Dobby interceptor is unavailable while cleaning ART inline hooks.");
        return false;
    }

    for (void *target : g_art_inline_hook_targets) {
        auto *entry = interceptor->find(reinterpret_cast<addr_t>(target));
        if (!entry || entry->type != kFunctionInlineHook || entry->relocated_addr == 0) {
            LOGE("Tracked ART inline hook at {} has no live Dobby relocated entry.", target);
            return false;
        }

        const uintptr_t page = PageForAddress(entry->relocated_addr, page_size);
        if (std::find(pages.begin(), pages.end(), page) == pages.end()) {
            pages.push_back(page);
        }
    }

    for (uintptr_t page : pages) {
        MappingInfo mapping;
        if (!FindMappingForAddress(page, mapping)) {
            LOGE("Unable to resolve Dobby relocated-code mapping at {}.",
                 reinterpret_cast<void *>(page));
            return false;
        }

        if (page > UINTPTR_MAX - page_size || page < mapping.start ||
            page + page_size > mapping.end || !IsDisposableDobbyMapping(mapping)) {
            LOGE("Refusing to neutralize relocated page {}: mapping [{}-{}] '{}' is not a "
                 "private anonymous RX Dobby arena.",
                 reinterpret_cast<void *>(page), reinterpret_cast<void *>(mapping.start),
                 reinterpret_cast<void *>(mapping.end),
                 mapping.path.empty() ? "<anonymous>" : mapping.path.c_str());
            return false;
        }

        Dl_info dl_info{};
        if (dladdr(reinterpret_cast<void *>(page), &dl_info) != 0) {
            LOGE("Refusing to neutralize relocated page {} because dladdr resolves it to '{}'.",
                 reinterpret_cast<void *>(page), dl_info.dli_fname ? dl_info.dli_fname : "<unknown>");
            return false;
        }

        // A Dobby arena can serve more than one hook. Never neutralize a page while an unrelated
        // active interceptor entry still relies on relocated code from that same page.
        for (int i = 0; i < interceptor->count(); ++i) {
            const auto *entry = interceptor->getEntry(i);
            if (!entry || entry->relocated_addr == 0) continue;
            if (PageForAddress(entry->relocated_addr, page_size) != page) continue;
            if (IsTrackedTarget(entry->patched_addr)) continue;

            LOGE("Refusing to neutralize relocated page {} because unrelated Dobby hook {} shares "
                 "the arena.",
                 reinterpret_cast<void *>(page), reinterpret_cast<void *>(entry->patched_addr));
            return false;
        }
    }

    return !pages.empty();
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

    if (g_art_inline_hook_targets.empty()) {
        LOGW("ART inline-hook cleanup was requested but no LSPlant Dobby targets were tracked.");
        return false;
    }

    const long page_size_value = sysconf(_SC_PAGESIZE);
    if (page_size_value <= 0) {
        LOGE("Failed to determine page size while cleaning ART inline hooks.");
        return false;
    }
    const size_t page_size = static_cast<size_t>(page_size_value);

    std::vector<uintptr_t> relocated_pages;
    relocated_pages.reserve(g_art_inline_hook_targets.size());
    if (!CollectAndValidateRelocatedPages(page_size, relocated_pages)) {
        LOGW("ART inline-hook cleanup aborted before teardown because the Dobby relocated-code "
             "arena could not be isolated safely.");
        return false;
    }

    const size_t tracked_count = g_art_inline_hook_targets.size();
    LOGI("Running deferred ART inline-hook cleanup for {} tracked LSPlant Dobby hook(s) across {} "
         "relocated-code page(s).",
         tracked_count, relocated_pages.size());

    bool unhook_success = true;
    for (auto it = g_art_inline_hook_targets.rbegin(); it != g_art_inline_hook_targets.rend(); ++it) {
        if (UnhookInline(*it) != 0) {
            LOGE("Failed to destroy tracked LSPlant Dobby hook at {}.", *it);
            unhook_success = false;
        }
    }

    if (!unhook_success) {
        // Some Dobby entries may already have been removed. Do not touch their shared allocator
        // pages after a partial teardown, and do not retry with stale target bookkeeping.
        g_art_inline_hook_targets.clear();
        g_art_cleanup_enabled = false;
        g_art_cleanup_completed = true;
        LOGW("ART inline-hook cleanup stopped after a partial Dobby teardown; relocated pages were "
             "left mapped.");
        return false;
    }

    bool neutralize_success = true;
    size_t neutralized_pages = 0;
    for (uintptr_t page : relocated_pages) {
        void *page_address = reinterpret_cast<void *>(page);

        // Discard stale relocated instructions first. The mapping itself is deliberately retained:
        // Dobby's allocator keeps process-lifetime arena metadata and may reuse this page later.
        if (madvise(page_address, page_size, MADV_DONTNEED) != 0) {
            PLOGE("Failed to discard stale Dobby relocated code at {}", page_address);
        }

        if (mprotect(page_address, page_size, PROT_NONE) != 0) {
            PLOGE("Failed to make Dobby relocated-code page {} inaccessible", page_address);
            neutralize_success = false;
            continue;
        }
        ++neutralized_pages;
    }

    g_art_inline_hook_targets.clear();
    g_art_cleanup_enabled = false;
    g_art_cleanup_completed = true;

    if (!neutralize_success) {
        LOGW("Destroyed all {} tracked LSPlant Dobby hooks, but neutralized only {}/{} relocated "
             "page(s).",
             tracked_count, neutralized_pages, relocated_pages.size());
        return false;
    }

    LOGI("Destroyed all {} tracked LSPlant Dobby hooks and neutralized {} relocated-code page(s); "
         "LSPlant Java-hook trampolines were left intact.",
         tracked_count, neutralized_pages);
    return true;
}

}  // namespace vector::native
