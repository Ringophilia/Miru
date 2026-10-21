package app.miru.browser.engine

import mozilla.components.feature.session.SessionFeature
import mozilla.components.concept.engine.EngineView

/** Activity/Compose hosts must pair start/stop with their Lifecycle and release on disposal. */
class EngineViewBinding internal constructor(private val feature: SessionFeature, private val view: EngineView) {
    private var started = false

    fun start() {
        if (!started) {
            view.onStart()
            view.onResume()
            feature.start()
            started = true
        }
    }

    fun stop() {
        if (started) {
            feature.stop()
            view.onPause()
            view.onStop()
            started = false
        }
    }

    fun release() = stop()

    fun onBackPressed(): Boolean = feature.onBackPressed()
}
