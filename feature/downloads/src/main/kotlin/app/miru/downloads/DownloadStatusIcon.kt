// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.downloads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.miru.design.MiruTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun DownloadStatusIcon(
    progress: Float,
    completed: Boolean,
    completion: Float,
    reducedMotion: Boolean,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val success = MiruTheme.colors.success
    val tag = when {
        !completed -> "download-progress-ring"
        completion < 1f -> "download-completing-icon"
        else -> "download-completed-icon"
    }
    Canvas(Modifier.size(24.dp).testTag(tag)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val radius = size.minDimension * 0.35f
        val arcTopLeft = center - Offset(radius, radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        if (!completed || reducedMotion && completion < 1f) {
            val sweep = if (completed) 360f else if (progress < 0f) 270f else 360f * progress.coerceIn(0f, 1f)
            val alpha = if (completed) 1f - completion else 1f
            drawArc(activeColor.copy(alpha = alpha * 0.16f), 0f, 360f, false, arcTopLeft, arcSize, style = stroke)
            drawArc(activeColor.copy(alpha = alpha), -90f, sweep, false, arcTopLeft, arcSize, style = stroke)
        }
        if (completed) {
            val first = Offset(size.width * 0.19f, size.height * 0.51f)
            val corner = Offset(size.width * 0.42f, size.height * 0.72f)
            val last = Offset(size.width * 0.81f, size.height * 0.32f)
            val path = Path()
            if (reducedMotion || completion == 1f) {
                path.moveTo(first.x, first.y)
                path.lineTo(corner.x, corner.y)
                path.lineTo(last.x, last.y)
            } else {
                // Use corresponding points along an open circle and the two strokes of the check.
                for (index in 0..24) {
                    val along = index / 24f
                    val angle = 2.0 * PI * along - PI / 2
                    val ring = center + Offset(radius * cos(angle).toFloat(), radius * sin(angle).toFloat())
                    val check = if (along < 0.375f) lerp(first, corner, along / 0.375f)
                        else lerp(corner, last, (along - 0.375f) / 0.625f)
                    val point = lerp(ring, check, completion)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
            }
            val color = if (reducedMotion) success.copy(alpha = completion) else lerp(activeColor, success, completion)
            drawPath(path, color, style = stroke)
        }
    }
}
