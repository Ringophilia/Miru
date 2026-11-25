// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import app.miru.browser.engine.api.R
import kotlinx.coroutines.flow.Flow

/** Engine-agnostic rendering surface wrapped by Compose via AndroidView. */
interface BrowserEngineView {
    fun asView(): View
    val verticalScrollPosition: Flow<Float>
    fun canScrollVerticallyUp(): Boolean
    fun canClearSelection(): Boolean
    fun setDynamicToolbarMaxHeight(height: Int)
    fun setVerticalClipping(clipping: Int)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun release()
}

/** Finds or tags the active [BrowserEngineView] in an Android View tree without importing backend SDK types. */
object BrowserEngineViews {
    fun attachTag(view: BrowserEngineView) {
        view.asView().setTag(R.id.miru_engine_view_tag, view)
    }

    fun findIn(root: View): BrowserEngineView? {
        (root.getTag(R.id.miru_engine_view_tag) as? BrowserEngineView)?.let { return it }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findIn(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

/**
 * Engine-owned viewport container combining the web surface, overscroll pull-to-refresh,
 * dynamic toolbar clipping, and lifecycle bindings behind one backend-neutral contract.
 */
interface BrowserSurfaceHost {
    val containerView: View
    val engineView: BrowserEngineView
    val verticalScrollPosition: Flow<Float>
    var onGestureStart: (() -> Unit)?
    var onTouchState: ((Boolean) -> Unit)?
    var onIndicator: ((fraction: Float, refreshing: Boolean) -> Unit)?
    var performThresholdFeedback: (Int) -> Unit

    fun configure(
        webContent: Boolean,
        canRefresh: Boolean,
        loading: Boolean,
        maximumToolbarHeight: Int,
        shownToolbarPixels: Float,
    )

    fun canClearSelection(): Boolean = engineView.canClearSelection()
    fun start()
    fun stop()
    fun release()
    fun captureThumbnail()
}

/** Finds or tags the active [BrowserSurfaceHost] in an Android View tree without importing backend SDK types. */
object BrowserSurfaceHosts {
    fun attachTag(host: BrowserSurfaceHost) {
        host.containerView.setTag(R.id.miru_surface_host_tag, host)
    }

    fun findIn(root: View): BrowserSurfaceHost? {
        (root.getTag(R.id.miru_surface_host_tag) as? BrowserSurfaceHost)?.let { return it }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findIn(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

interface BrowserEngineBinding {
    fun start()
    fun stop()
    fun release()
    fun onBackPressed(): Boolean
}

interface WebsiteIconLoader {
    suspend fun load(url: String): Bitmap?
    fun clearMemory()
}
