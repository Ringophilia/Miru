// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import app.miru.superres.*

@Composable
internal fun PlayerVideoOutput(controller: PlayerController, state: PlayerSnapshot,
    preferences: PlayerPreferences, pip: Boolean, fit: SuperResFit, zoom: VideoZoom, compareSplit: Float,
    onStatus: (SuperResViewState) -> Unit, modifier: Modifier = Modifier) {
    val statusCallback = rememberUpdatedState(onStatus)
    AndroidView(factory = { context -> RoutedVideoView(context).apply {
        keepScreenOn = true
        setDecoderSurfaceListener(controller::setVideoSurface)
        onStatusChanged = { statusCallback.value(it) }
        onError = { message -> Toast.makeText(context, context.getString(R.string.player_superres_failed, message), Toast.LENGTH_LONG).show() }
        onProtection = { action ->
            val text = when(action) {
                SuperResProtection.None -> null
                is SuperResProtection.Reduce -> {
                    if(action.persistDeviceCeiling) controller.recordSuperResCeiling(action.tier)
                    context.getString(R.string.player_superres_reduced, context.getString(superResTierLabel(action.tier)))
                }
                is SuperResProtection.Disable -> context.getString(if(action.reason == SuperResBypass.THERMAL)
                    R.string.player_superres_disabled_heat else R.string.player_superres_disabled_load)
            }
            if(text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    } }, modifier = modifier.testTag("player_video_output"), update = { view ->
        view.configure(SuperResViewConfig(mode = preferences.superResMode, tier = preferences.superResTier,
            title = controller.request.title, pageUrl = controller.request.pageUrl,
            sourceWidth = state.width, sourceHeight = state.height, pixelWidthHeightRatio = state.format.pixelWidthHeightRatio,
            frameRate = state.format.frameRate, hdr = state.format.hdr, protectedContent = state.format.protectedContent,
            speed = state.speed, pip = pip, fit = fit,
            compareSplit = compareSplit, deviceCeiling = preferences.superResDeviceCeiling))
        val layout = controller.videoLayoutSize
        view.setVideoLayoutSize(layout.width, layout.height, layout.pixelWidthHeightRatio)
        view.setVideoZoom(zoom)
    }, onRelease = { it.dispose() })
}
