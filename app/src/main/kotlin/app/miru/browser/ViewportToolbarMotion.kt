// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import app.miru.design.MiruMotion
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Consumes Gecko compositor positions in screen pixels. */
@Stable
internal class ViewportToolbarMotion(
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    var hidden by mutableFloatStateOf(0f)
        private set
    private var height = 0f
    private var forced = true
    private var reducedMotion = false
    private var touching = false
    private var releasedTouch = false
    private var previousPosition: Float? = null
    private var previousTime = 0L
    private var velocity = 0f
    private var settle: Job? = null

    fun configure(height: Float, forced: Boolean, reducedMotion: Boolean) {
        val changed = this.height != height || this.forced != forced || this.reducedMotion != reducedMotion
        this.height = height
        this.forced = forced
        this.reducedMotion = reducedMotion
        if (forced || height == 0f) show()
        else if (changed) {
            hidden = hidden.coerceIn(0f, height)
            settleToNearest()
        }
    }

    fun reset() {
        touching = false
        releasedTouch = false
        previousPosition = null
        previousTime = 0L
        show()
    }

    fun show() {
        settle?.cancel()
        hidden = 0f
        velocity = 0f
    }

    fun touch(active: Boolean) {
        if (active == touching) return
        touching = active
        val time = now()
        if (active) {
            settle?.cancel()
            releasedTouch = false
            velocity = 0f
        } else {
            releasedTouch = true
            val releaseVelocity = velocity.takeIf { time - previousTime <= QuietMillis } ?: 0f
            settleToNearest(initialVelocity = releaseVelocity)
        }
        previousTime = time
    }

    fun scroll(position: Float) {
        if (!position.isFinite()) return
        val time = now()
        val oldPosition = previousPosition
        previousPosition = position
        if (forced || position <= 1f) {
            previousTime = time
            show()
            return
        }
        if (oldPosition == null) {
            previousTime = time
            return
        }
        val delta = position - oldPosition
        if (abs(delta) < 0.01f) return
        val elapsed = (time - previousTime).coerceAtLeast(1L)
        previousTime = time

        // APZ may keep scrolling the document after ACTION_UP. That tail cannot
        // change the endpoint chosen when the finger left the screen.
        if (releasedTouch) {
            if (elapsed <= QuietMillis || settle?.isActive == true) return
            releasedTouch = false
        }
        settle?.cancel()
        val oldHidden = hidden
        hidden = (hidden + delta).coerceIn(0f, height)
        velocity = ((hidden - oldHidden) * 1000f / elapsed).coerceIn(-6000f, 6000f)
        if (!touching) settleToNearest(delayMillis = QuietMillis, initialVelocity = velocity)
    }

    private fun settleToNearest(delayMillis: Long = 0, initialVelocity: Float = 0f) {
        settle?.cancel()
        if (touching || forced || hidden == 0f || hidden == height) return
        val target = if (hidden >= height / 2f) height else 0f
        settle = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (reducedMotion) hidden = target
            else Animatable(hidden).animateTo(
                target, spring(MiruMotion.springDamping, MiruMotion.springStiffness), initialVelocity = initialVelocity,
            ) { hidden = value.coerceIn(0f, height) }
        }
    }

    fun release() { settle?.cancel() }

    private companion object { const val QuietMillis = 120L }
}
