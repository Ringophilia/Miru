// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser.engine

import mozilla.components.feature.session.SessionFeature
import mozilla.components.concept.engine.EngineView

/** Activity/Compose hosts must pair start/stop with their Lifecycle and release on disposal. */
class EngineViewBinding internal constructor(private val feature: SessionFeature, private val view: EngineView,
    private val onRelease: () -> Unit = {}) {
    private var started = false
    private var lifecycleStarted = false
    private var renderingPaused = false

    fun start() {
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

    fun stop() {
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

    fun release() { stop(); onRelease() }

    fun onBackPressed(): Boolean = feature.onBackPressed()
}
