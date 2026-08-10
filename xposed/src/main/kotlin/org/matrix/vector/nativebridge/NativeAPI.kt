package org.matrix.vector.nativebridge

object NativeAPI {
    @JvmStatic external fun recordNativeEntrypoint(library_name: String)

    /**
     * Complete the opt-in libart inline-hook cleanup after the initial package lifecycle callbacks.
     * Disabled or already-cleaned processes return true as a no-op.
     */
    @JvmStatic external fun cleanupArtInlineHooks(): Boolean
}
