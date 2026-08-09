package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadOutcomeReceiver;

interface IHotReloadTarget {
    /**
     * Run the module lifecycle handover asynchronously and answer through receiver. The daemon owns
     * the timeout, so arbitrary module code cannot pin a daemon Binder thread indefinitely.
     */
    oneway void hotReloadModule(in Module module, in Bundle extras, IHotReloadOutcomeReceiver receiver);
}
