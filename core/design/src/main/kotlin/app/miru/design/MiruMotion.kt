package app.miru.design

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
