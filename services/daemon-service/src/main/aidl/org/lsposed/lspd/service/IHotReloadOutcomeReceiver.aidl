package org.lsposed.lspd.service;

import org.lsposed.lspd.models.HotReloadOutcome;

/** Out-of-band response for a potentially long-running API 102 hot reload. */
interface IHotReloadOutcomeReceiver {
    oneway void onOutcome(in HotReloadOutcome outcome);
}
