// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object MiruMotion {
    const val instant = 50
    const val short1 = 100
    const val short2 = 150
    const val short3 = 200
    const val medium1 = 250
    const val medium2 = 300
    const val medium3 = 350
    const val long1 = 450
    const val long2 = 500
    const val suggestionStagger = 20
    const val staggeredSuggestions = 6
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val standardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val emphasized = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val springDamping = 0.8f
    const val springStiffness = 380f
    const val bouncyDamping = 0.6f
    const val bouncyStiffness = 500f
}

@Composable
fun animationsReduced(): Boolean {
    val context = LocalContext.current
    val reduced by remember(context.applicationContext) {
        val resolver = context.applicationContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
            trySend(Unit)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate().map { Settings.Global.getFloat(resolver, setting, 1f) == 0f }.flowOn(Dispatchers.IO)
    }.collectAsState(initial = remember { !ValueAnimator.areAnimatorsEnabled() })
    return reduced
}

private object FadeDurationScale : MotionDurationScale { override val scaleFactor = 1f }

/** Preserve the short opacity change when Android has disabled spatial animation. */
suspend fun withReducedMotionFade(reduced: Boolean, animate: suspend () -> Unit) {
    if (reduced) withContext(FadeDurationScale) { animate() } else animate()
}
