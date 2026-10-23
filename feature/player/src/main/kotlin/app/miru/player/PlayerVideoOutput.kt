// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import app.miru.superres.*

@Composable
internal fun PlayerVideoOutput(controller: PlayerController, state: PlayerSnapshot,
    preferences: PlayerPreferences, pip: Boolean, fit: Int, compareSplit: Float,
    onStatus: (SuperResViewState) -> Unit, modifier: Modifier = Modifier) {
    val currentPlayer = rememberUpdatedState(controller.player)
    val statusCallback = rememberUpdatedState(onStatus)
    val binding = remember { arrayOfNulls<Player>(1) }
    AndroidView(factory = { context -> RoutedVideoView(context).apply {
        keepScreenOn = true
        setDecoderSurfaceListener { surface -> currentPlayer.value?.setVideoSurface(surface) }
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
        if(binding[0] !== currentPlayer.value) { binding[0] = currentPlayer.value; view.rebindDecoderSurface() }
        view.configure(SuperResViewConfig(mode = preferences.superResMode, tier = preferences.superResTier,
            title = controller.request.title, pageUrl = controller.request.pageUrl,
            sourceWidth = state.width, sourceHeight = state.height, pixelWidthHeightRatio = state.format.pixelWidthHeightRatio,
            frameRate = state.format.frameRate, hdr = state.format.hdr, protectedContent = state.format.protectedContent,
            speed = state.speed, pip = pip, fit = when(fit) { 1 -> SuperResFit.COVER; 2 -> SuperResFit.FILL; else -> SuperResFit.CONTAIN },
            compareSplit = compareSplit, deviceCeiling = preferences.superResDeviceCeiling))
    }, onRelease = { it.dispose(); binding[0] = null })
}
