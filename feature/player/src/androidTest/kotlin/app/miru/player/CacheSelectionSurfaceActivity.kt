// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView

/** A real compositor consumer for tests of the renderer's current HLS format. */
class CacheSelectionSurfaceActivity : Activity() {
    lateinit var video: SurfaceView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = SurfaceView(this)
        setContentView(video)
    }
}
