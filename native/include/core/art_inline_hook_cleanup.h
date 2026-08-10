#pragma once

namespace vector::native {

/**
 * Configure per-process ART inline-hook cleanup state before LSPlant initialization.
 *
 * This is reset for every specialized process. system_server and normal apps that are not on the
 * compatibility list pass false, making the post-bootstrap cleanup call a no-op.
 *
 * Returns whether cleanup is armed. Enabling can fail when the pre-Vector libart executable state
 * cannot be captured safely; in that case cleanup remains disabled so existing native hooks are
 * never overwritten without a recoverable baseline.
 */
bool ConfigureArtInlineHookCleanup(bool enabled);

/** Record/forget native libart.so targets installed through LSPlant's InitInfo hook handler. */
void RecordArtInlineHookTarget(void *target);
void ForgetArtInlineHookTarget(void *target);

/**
 * Run the one-shot compatibility invalidation after framework bootstrap and before app loading.
 *
 * For opted-in apps, replace executable libart.so segments containing recorded Vector/LSPlant
 * targets with clean private mappings, then restore executable pages that were already modified
 * before Vector installed its hooks. This intentionally does not perform normal Dobby hook teardown,
 * so LSPlant/Dobby trampoline and interceptor metadata remain intact while Vector's patched libart
 * entry code is removed.
 * Disabled/already-cleaned states are successful no-ops.
 */
bool CleanupArtInlineHooksIfEnabled();

}  // namespace vector::native
