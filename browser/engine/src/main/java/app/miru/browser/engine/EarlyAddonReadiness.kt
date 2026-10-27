// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.os.SystemClock
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Observe only startup lifecycle before permission migration. In particular, this does not
 * register action/tab handlers, install an add-on, open a session, or approve permissions.
 * GeckoEngine replaces this delegate after migration. Its normal installed-list path installs
 * action/tab handlers; the retained real onReady event then supplies Miru's missing readiness.
 */
internal class EarlyAddonReadiness(runtime: GeckoRuntime, private val expectedId: String) :
    WebExtensionController.AddonManagerDelegate, WebExtensionController.ExtensionProcessDelegate {
    data class Handoff(val ready: WebExtension?, val processUnavailable: Boolean)
    private val pending = DeferredReadyEvent<WebExtension>(expectedId)

    init {
        runtime.webExtensionController.apply {
            setExtensionProcessDelegate(this@EarlyAddonReadiness)
            setAddonManagerDelegate(this@EarlyAddonReadiness)
        }
        Log.d("MiruAdblock", "event=early-listener-attached elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    override fun onReady(extension: WebExtension) {
        val retained = pending.ready(extension.id, extension)
        if(extension.id == expectedId)
            Log.d("MiruAdblock", "event=early-ready-received retained=$retained elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    override fun onInstalling(extension: WebExtension) = invalidate(extension)
    override fun onEnabling(extension: WebExtension) = invalidate(extension)
    override fun onDisabling(extension: WebExtension) = invalidate(extension)
    override fun onDisabled(extension: WebExtension) = invalidate(extension)
    override fun onUninstalling(extension: WebExtension) = invalidate(extension)
    override fun onUninstalled(extension: WebExtension) = invalidate(extension)
    override fun onInstallationFailed(extension: WebExtension?, exception: WebExtension.InstallException) {
        extension?.let(::invalidate)
    }

    override fun onDisabledProcessSpawning() {
        val retained = pending.processDisabled()
        Log.d("MiruAdblock", "event=early-process-unavailable retained=$retained elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    private fun invalidate(extension: WebExtension) {
        pending.invalidated(extension.id)
        if(extension.id == expectedId)
            Log.d("MiruAdblock", "event=early-ready-invalidated elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    fun take(): Handoff = Handoff(pending.take(), pending.processUnavailable).also {
        Log.d("MiruAdblock", "event=early-ready-handoff present=${it.ready != null} processUnavailable=${it.processUnavailable} elapsedMs=${SystemClock.elapsedRealtime()}")
    }
}
