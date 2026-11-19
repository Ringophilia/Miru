// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // Keep Material's compact-sheet behavior while it migrates to enabledValues.
@Composable
fun MiruBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.9f,
    skipPartiallyExpanded: Boolean = false,
    reducedMotion: Boolean = animationsReduced(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = reducedMotion
    val currentReduced = rememberUpdatedState(reduced)
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    val closing = remember { mutableStateOf(false) }
    val exiting = remember { mutableStateOf(false) }
    val dismissDelivered = remember { mutableStateOf(false) }
    val opacity = remember { Animatable(0f) }
    val finishDismiss = remember {
        {
            if (!dismissDelivered.value) {
                dismissDelivered.value = true
                currentDismiss.value()
            }
        }
    }
    val confirmChange = remember {
        { target: SheetValue ->
            if (target == SheetValue.Hidden) exiting.value = true
            if (currentReduced.value && target == SheetValue.Hidden) {
                closing.value = true
                false
            } else true
        }
    }
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded || reduced,
        confirmValueChange = confirmChange,
    )
    val fading = reduced || closing.value
    LaunchedEffect(fading, exiting.value) {
        val duration = when {
            fading -> MiruMotion.short2
            exiting.value -> MiruMotion.short3
            else -> MiruMotion.medium2
        }
        val easing = when {
            fading -> LinearEasing
            exiting.value -> MiruMotion.emphasizedAccelerate
            else -> MiruMotion.emphasizedDecelerate
        }
        withReducedMotionFade(fading) {
            opacity.animateTo(if (exiting.value) 0f else 1f, tween(duration, easing = easing))
        }
        if (exiting.value && fading) finishDismiss()
    }
    val appearing = sheet.currentValue == SheetValue.Hidden
    val parentMotion = MaterialTheme.motionScheme
    val motion = remember(reduced, appearing) { SheetMotionScheme(reduced, appearing) }
    MaterialTheme(motionScheme = motion) {
        ModalBottomSheet(
            onDismissRequest = {
                if (currentReduced.value || closing.value) {
                    exiting.value = true
                    closing.value = true
                } else finishDismiss()
            },
            modifier = modifier.testTag("miru-bottom-sheet").graphicsLayer { alpha = if (fading) opacity.value else 1f },
            sheetState = sheet,
            sheetGesturesEnabled = !reduced,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            scrimColor = MiruTheme.colors.sheetScrim.let { it.copy(alpha = it.alpha * opacity.value) },
            dragHandle = { BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp) },
        ) {
            val windowHeight = LocalWindowInfo.current.containerSize.height
            val ime = WindowInsets.ime
            val density = LocalDensity.current
            MaterialTheme(motionScheme = parentMotion) {
                Column(
                    // Material measures the handle and insets first. Cap the remaining body space
                    // here so its drag anchors still use the full window height.
                    Modifier.fillMaxWidth().layout { measurable, constraints ->
                        val availableHeight = (windowHeight - ime.getBottom(density)).coerceAtLeast(0)
                        val reservedHeight = (availableHeight * (1f - maxHeightFraction)).roundToInt()
                        val bodyHeight = (constraints.maxHeight - reservedHeight).coerceAtLeast(constraints.minHeight)
                        val body = measurable.measure(constraints.copy(maxHeight = bodyHeight))
                        layout(body.width, body.height) { body.placeRelative(0, 0) }
                    },
                    content = content,
                )
            }
        }
    }
}

private class SheetMotionScheme(
    private val reduced: Boolean,
    private val appearing: Boolean,
) : MotionScheme by MotionScheme.standard() {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = when {
        reduced -> snap()
        appearing -> tween(MiruMotion.medium2, easing = MiruMotion.emphasizedDecelerate)
        else -> spring(dampingRatio = MiruMotion.springDamping, stiffness = MiruMotion.springStiffness)
    }

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(MiruMotion.short3, easing = MiruMotion.emphasizedAccelerate)

    // The wrapper owns the scrim's opacity from its first frame, including scale-zero fades.
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
