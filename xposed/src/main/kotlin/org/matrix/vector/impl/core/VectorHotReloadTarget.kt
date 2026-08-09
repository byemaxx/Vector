package org.matrix.vector.impl.core

import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.util.concurrent.Executors
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadOutcomeReceiver
import org.lsposed.lspd.service.IHotReloadTarget
import org.lsposed.lspd.util.Utils.Log

private const val TAG = "VectorHotReloadTarget"

internal object VectorHotReloadTarget : IHotReloadTarget.Stub() {
    /** Serialize reloads in this process without holding the incoming Binder thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vector-hot-reload-target") }

    override fun hotReloadModule(
        module: Module,
        extras: Bundle?,
        receiver: IHotReloadOutcomeReceiver?,
    ) {
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != 0) {
            Log.w(TAG, "Refusing hot reload request from uid $caller")
            return
        }

        worker.execute {
            val outcome = VectorModuleManager.hotReloadModuleWithOutcome(module, extras)
            runCatching { receiver?.onOutcome(outcome) }
                .onFailure { Log.w(TAG, "Cannot report hot reload outcome", it) }
        }
    }
}
