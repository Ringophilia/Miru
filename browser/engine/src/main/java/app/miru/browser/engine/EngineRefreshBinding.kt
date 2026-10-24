// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.feature.session.SwipeRefreshFeature

/** Owns the AC feature's lifecycle without exposing BrowserStore or AC types to the UI module. */
class EngineRefreshBinding internal constructor(private var feature: SwipeRefreshFeature?) {
    private var started = false

    fun start() {
        if (started) return
        feature?.let { it.start(); started = true }
    }

    fun stop() {
        if (!started) return
        feature?.stop()
        started = false
    }

    fun release() {
        stop()
        feature = null
    }
}
