#include "core/art_inline_hook_cleanup.h"

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

    LOGI("Running deferred ART inline-hook cleanup after initial package lifecycle callbacks.");

    const size_t total = g_art_inline_hook_targets.size();
    if (total == 0) {
        g_art_cleanup_completed = true;
        g_art_cleanup_enabled = false;
        LOGI("No tracked LSPlant ART inline hooks require cleanup.");
        return true;
    }

    std::vector<void *> failed;
    failed.reserve(total);
    for (auto it = g_art_inline_hook_targets.rbegin(); it != g_art_inline_hook_targets.rend(); ++it) {
        if (UnhookInline(*it) != 0) {
            LOGW("Failed to unregister tracked LSPlant ART inline hook at {}.", *it);
            failed.push_back(*it);
        }
    }

    // Preserve installation order for any failed entries so a later retry still tears them down in
    // reverse order. DobbyDestroy restores the original target prologue; we deliberately do not
    // rewrite unrelated libart.so executable pages from the backing file.
    std::reverse(failed.begin(), failed.end());
    g_art_inline_hook_targets = std::move(failed);

    const size_t removed = total - g_art_inline_hook_targets.size();
    if (g_art_inline_hook_targets.empty()) {
        g_art_cleanup_completed = true;
        g_art_cleanup_enabled = false;
        LOGI("Deferred ART inline-hook cleanup removed all {} tracked LSPlant Dobby hook(s).",
             removed);
        return true;
    }

    LOGW("Deferred ART inline-hook cleanup removed {} of {} tracked LSPlant Dobby hook(s); {} "
         "remain registered.",
         removed, total, g_art_inline_hook_targets.size());
    return false;
}

}  // namespace vector::native
