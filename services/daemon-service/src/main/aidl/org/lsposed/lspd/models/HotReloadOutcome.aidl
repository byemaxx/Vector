package org.lsposed.lspd.models;

/** Result reported by an injected process after attempting an API 102 hot reload. */
parcelable HotReloadOutcome {
    /** One of IXposedService.HOT_RELOAD_*. */
    int status;

    /** Framework diagnostic, or null for success and a genuine module refusal. */
    @nullable String message;

    /** True only when onHotReloading returned false. */
    boolean refused;

    /** True once the target has committed the successor generation, even if its callback failed. */
    boolean generationChanged;
}
