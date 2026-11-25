// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser.engine

import mozilla.components.feature.session.SessionFeature
import mozilla.components.concept.engine.EngineView

/** Activity/Compose hosts must pair start/stop with their Lifecycle and release on disposal. */
class EngineViewBinding internal constructor(private val feature: SessionFeature, private val view: EngineView,
    private val onRelease: () -> Unit = {}) : BrowserEngineBinding {
    private var started = false
    private var lifecycleStarted = false
    private var renderingPaused = false

    override fun start() {
        lifecycleStarted = true
        startRendering()
    }

    private fun startRendering() {
        if (!started && lifecycleStarted && !renderingPaused) {
            view.onStart()
            view.onResume()
            feature.start()
            started = true
        }
    }

    override fun stop() {
        lifecycleStarted = false
        stopRendering()
    }

    private fun stopRendering() {
        if (started) {
            feature.stop()
            view.onPause()
            view.onStop()
            started = false
        }
    }

    internal fun suspendRendering() { renderingPaused = true; stopRendering() }
    internal fun resumeRendering() { renderingPaused = false; startRendering() }

    override fun release() { stop(); onRelease() }

    override fun onBackPressed(): Boolean = feature.onBackPressed()
}
