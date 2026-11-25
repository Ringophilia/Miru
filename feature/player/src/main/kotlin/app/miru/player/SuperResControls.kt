// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.miru.superres.SuperResBypass
import app.miru.superres.SuperResMode
import app.miru.superres.SuperResTier
import app.miru.superres.SuperResTiming
import app.miru.superres.SuperResViewState
import kotlin.math.roundToInt

internal fun superResModeLabel(mode: SuperResMode) = when (mode) {
    SuperResMode.OFF -> R.string.player_superres_off
    SuperResMode.AUTO -> R.string.player_superres_auto
    SuperResMode.ANIME -> R.string.player_superres_anime
    SuperResMode.GENERAL -> R.string.player_superres_general
}

internal fun superResTierLabel(tier: SuperResTier) = when (tier) {
    SuperResTier.FAST -> R.string.player_superres_fast
    SuperResTier.BALANCED -> R.string.player_superres_balanced
    SuperResTier.QUALITY -> R.string.player_superres_quality
}

@Composable
internal fun SuperResQualityPanel(
    visible: Boolean,
    controller: PlayerController,
    output: SuperResViewState,
    compare: Boolean,
    reducedMotion: Boolean,
    onCompare: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val preferences by controller.preferences.collectAsState()
    val comparisonDescription = stringResource(R.string.player_superres_compare)

    PlayerSidePanel(
        visible = visible,
        title = stringResource(R.string.player_quality),
        onDismiss = onDismiss,
        reducedMotion = reducedMotion,
        footer = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_options_done)) }
        },
    ) {
        val details = listOfNotNull(
            state.format.codec.ifBlank { "—" },
            state.format.bitrate.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000f) },
            state.format.frameRate.takeIf { it > 0 }?.let { "%.3f fps".format(it) },
        ).joinToString(" · ")
        Text(stringResource(R.string.player_superres_source, state.width, state.height, details), style = MaterialTheme.typography.bodyMedium, color = Color(0xE6FFFFFF))
        Text(
            stringResource(
                R.string.player_superres_output,
                output.rendering.outputWidth.takeIf { output.rendering.active } ?: output.imageRect.width.roundToInt(),
                output.rendering.outputHeight.takeIf { output.rendering.active } ?: output.imageRect.height.roundToInt(),
            ),
            color = Color(0xE6FFFFFF),
        )
        if (output.rendering.active && output.rendering.gpuMs > 0) {
            Text(
                if (output.rendering.timing == SuperResTiming.GPU_QUERY) {
                    stringResource(R.string.player_superres_gpu_time, output.rendering.gpuMs, output.rendering.frameIntervalMs)
                } else {
                    stringResource(R.string.player_superres_render_time, output.rendering.gpuMs)
                },
                color = Color(0xCCFFFFFF),
            )
        }
        output.decision.bypass?.let { reason ->
            Text(
                stringResource(
                    when (reason) {
                        SuperResBypass.OFF -> R.string.player_superres_bypass_off
                        SuperResBypass.NATIVE_SIZE -> R.string.player_superres_bypass_native
                        SuperResBypass.HDR -> R.string.player_superres_bypass_hdr
                        SuperResBypass.PROTECTED -> R.string.player_superres_bypass_secure
                        SuperResBypass.SPEED -> R.string.player_superres_bypass_speed
                        SuperResBypass.PICTURE_IN_PICTURE -> R.string.player_superres_bypass_pip
                        SuperResBypass.THERMAL -> R.string.player_superres_disabled_heat
                        SuperResBypass.ERROR -> R.string.player_superres_bypass_error
                        SuperResBypass.UNKNOWN_SIZE -> R.string.player_superres_waiting
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0x99FFFFFF),
            )
        }
        HorizontalDivider(color = Color(0x1FFFFFFF))
        Text(
            stringResource(R.string.player_superres),
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xCCFFFFFF),
            modifier = Modifier.semantics { heading() },
        )
        Column(Modifier.selectableGroup()) {
            SuperResMode.entries.forEach { mode ->
                TextButton(
                    onClick = { controller.superResMode(mode) },
                    modifier = Modifier.fillMaxWidth().testTag("player_superres_${mode.name}").semantics { selected = mode == preferences.superResMode },
                ) {
                    Text(
                        stringResource(superResModeLabel(mode)),
                        color = if (mode == preferences.superResMode) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.player_superres_tier),
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xCCFFFFFF),
            modifier = Modifier.semantics { heading() },
        )
        Column(Modifier.selectableGroup()) {
            SuperResTier.entries.forEach { tier ->
                TextButton(
                    onClick = { controller.superResTier(tier) },
                    modifier = Modifier.fillMaxWidth().testTag("player_superres_tier_${tier.name}").semantics { selected = tier == preferences.superResTier },
                ) {
                    Text(
                        stringResource(superResTierLabel(tier)),
                        color = if (tier == preferences.superResTier) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
        }
        preferences.superResDeviceCeiling?.let {
            Text(stringResource(R.string.player_superres_limit, stringResource(superResTierLabel(it))), style = MaterialTheme.typography.bodySmall, color = Color(0x99FFFFFF))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_superres_compare), Modifier.weight(1f), color = Color.White)
            Switch(
                compare,
                onCheckedChange = onCompare,
                enabled = output.decision.enabled,
                modifier = Modifier.semantics { contentDescription = comparisonDescription },
            )
        }
    }
}

@Composable
internal fun SuperResComparison(output: SuperResViewState, split: Float, onSplit: (Float) -> Unit) {
    val rect = output.imageRect
    if (rect.width <= 0 || rect.height <= 0) return
    val density = LocalDensity.current
    val current = rememberUpdatedState(split)
    val latestCallback = rememberUpdatedState(onSplit)
    val x = rect.splitX(split)
    val halfTouch = with(density) { 24.dp.toPx() }
    val label = stringResource(R.string.player_superres_split)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White, Offset(x, rect.top.coerceAtLeast(0f)), Offset(x, rect.bottom.coerceAtMost(size.height)), 2.dp.toPx())
            drawCircle(Color.White, 9.dp.toPx(), Offset(x, rect.top + rect.height / 2f))
        }
        Box(
            Modifier.offset { IntOffset((x - halfTouch).roundToInt(), 0) }.width(48.dp).fillMaxHeight()
                .testTag("player_superres_comparison").semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(split, 0f..1f)
                    setProgress { value -> latestCallback.value(value.coerceIn(0f, 1f)); true }
                }.pointerInput(rect) {
                    detectHorizontalDragGestures { change, delta ->
                        change.consume()
                        latestCallback.value((current.value + delta / rect.width).coerceIn(.02f, .98f))
                    }
                },
        )
        Row(
            Modifier.offset {
                IntOffset(
                    (x - with(density) { 64.dp.toPx() }).coerceAtLeast(0f).roundToInt(),
                    rect.top.coerceAtLeast(0f).roundToInt() + with(density) { 12.dp.roundToPx() },
                )
            }.background(Color.Black.copy(alpha = .65f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.player_superres_original), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.player_superres_enhanced), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
