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
 * For opted-in apps, perform normal Dobby teardown for the tracked LSPlant libart.so inline hooks,
 * then discard and make non-executable only the anonymous Dobby relocated-code page(s) that held
 * those hooks' backup trampolines. Pages shared by any unrelated active Dobby hook are rejected.
 * LSPlant's Java-hook trampoline mappings are not touched. Disabled/already-cleaned states are
 * successful no-ops.
 */
bool CleanupArtInlineHooksIfEnabled();

}  // namespace vector::native
