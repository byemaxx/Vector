#pragma once

namespace vector::native {

/**
 * Configure per-process ART inline-hook cleanup state before LSPlant initialization.
 *
 * This is reset for every specialized process. system_server and normal apps that are not on the
 * compatibility list pass false, making the later Java lifecycle callback a no-op.
 */
void ConfigureArtInlineHookCleanup(bool enabled);

/** Record/forget native libart.so targets installed through LSPlant's InitInfo hook handler. */
void RecordArtInlineHookTarget(void *target);
void ForgetArtInlineHookTarget(void *target);

/**
 * Run the one-shot compatibility cleanup after the initial package lifecycle callbacks finish.
 *
 * Tracked Dobby hooks are unregistered first, then libart.so executable PT_LOAD bytes are checked
 * against the backing file and restored when they still differ. Disabled/already-cleaned states are
 * successful no-ops.
 */
bool CleanupArtInlineHooksIfEnabled();

}  // namespace vector::native
