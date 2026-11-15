// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.app.Activity
import android.media.AudioManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/** Gesture thresholds mirror Ringo-Cove; all speed mutations are queued on the player dispatcher. */
internal class PlayerTouch(
    private val activity: Activity,
    private val playback: PlayerController,
    private val controls: () -> Unit,
    private val feedback: (String?) -> Unit,
    private val holdFeedback: (Float?) -> Unit,
    private val transform: (PinchSample, PinchSample, Int, Int) -> Unit,
    private val finishZoom: (Int, Int) -> Unit,
) {
    var locked = false
    private var width = 1f; private var height = 1f
    private var pinch: PinchSample? = null
    private var pinchChangedScale = false
    private var pinchStartDistance = 0f
    private var multiTouch = false
    private val handler = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f; private var lastTap = 0L
    private var streak = 0; private var streakAt = 0L
    private var streakSeconds = 0
    private var mode = 0
    private var position = 0L; private var target = 0L
    private var level = 0f; private var originalSpeed = 1f
    private var holdAnchorScreenX = 0f
    private var initialHoldSpeed = PlayerGestures.HOLD_SPEED
    private var appliedHoldSpeed = PlayerGestures.HOLD_SPEED
    private val single = Runnable { controls() }
    private val clear = Runnable { feedback(null) }
    private val hold = Runnable {
        if(mode == 0 && !locked && playback.state.value.playing) {
            mode = 3; originalSpeed = playback.state.value.speed
            handler.removeCallbacks(single)
            feedback(null)
            initialHoldSpeed = playback.preferences.value.holdSpeed
            applyHoldSpeed(initialHoldSpeed)
            haptic()
        }
    }

    private fun applyHoldSpeed(speed: Float) {
        appliedHoldSpeed = speed
        playback.speed(speed)
        holdFeedback(speed)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth.coerceAtLeast(1).toFloat()
        height = newHeight.coerceAtLeast(1).toFloat()
        pinch = null
        pinchChangedScale = false
    }

    private fun sample(event: MotionEvent): PinchSample {
        val x = event.getX(0)
        val y = event.getY(0)
        val otherX = event.getX(1)
        val otherY = event.getY(1)
        return PinchSample((x + otherX) / 2f, (y + otherY) / 2f, hypot(otherX - x, otherY - y))
    }

    fun cancel() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(single)
        handler.removeCallbacks(clear)
        if (mode == 3) {
            playback.speed(originalSpeed)
            holdFeedback(null)
        }
        mode = 5
        lastTap = 0
        streak = 0
        pinch = null
        pinchChangedScale = false
        feedback(null)
    }

    fun event(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false
            pinch = null
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (!multiTouch) cancel()
            multiTouch = true
            pinch = if (!locked && event.pointerCount == 2) sample(event) else null
            pinchChangedScale = false
            pinchStartDistance = pinch?.distance ?: 0f
            return true
        }
        if (multiTouch) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (!locked && event.pointerCount == 2) {
                    val next = sample(event)
                    val previous = pinch
                    if (previous != null && previous.distance > 0f && next.distance > 0f && next != previous) {
                        pinchChangedScale = pinchChangedScale || abs(next.distance / pinchStartDistance - 1f) > .015f
                        transform(previous, next, width.toInt(), height.toInt())
                    }
                    if (previous == null || pinchStartDistance <= 0f) pinchStartDistance = next.distance
                    pinch = next
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (!locked && pinchChangedScale && event.pointerCount == 2) finishZoom(width.toInt(), height.toInt())
                    pinch = null
                    pinchChangedScale = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinch = null
            }
            return true
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0; downX = event.x; downY = event.y
                holdAnchorScreenX = event.rawX
                position = playback.state.value.position; target = position
                handler.removeCallbacks(clear); handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX; val dy = event.y - downY
                if(locked) return true
                if (mode == 3) {
                    // Moving window insets can shift local coordinates during a hold.
                    val speed = PlayerGestures.holdSpeedAt(initialHoldSpeed, (event.rawX - holdAnchorScreenX) / density)
                    if (speed != appliedHoldSpeed) applyHoldSpeed(speed)
                    return true
                }
                if (mode == 0) holdAnchorScreenX = event.rawX
                if(mode == 0 && hypot(dx, dy) > PlayerGestures.DRAG_SLOP_DP * density) {
                    handler.removeCallbacks(hold); handler.removeCallbacks(single)
                    if(abs(dx) > abs(dy)) mode = 1 else {
                        val options = playback.preferences.value
                        mode = if(downX < width / 2) { if(options.brightnessGesture) 2 else 5 } else { if(options.volumeGesture) 4 else 5 }
                        level = if(mode == 2) activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: (android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                            else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    }
                }
                if(mode == 1 && playback.state.value.duration > 0) {
                    target = (position + dx / width * playback.preferences.value.dragRangeSeconds * 1000).toLong().coerceIn(0, playback.state.value.duration)
                    feedback(playerTime(target))
                } else if(mode == 2 || mode == 4) {
                    val value = (level - dy / (height * PlayerGestures.VERTICAL_HEIGHT_FRACTION)).coerceIn(0f, 1f)
                    if(mode == 2) activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                    else audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
                    feedback(activity.getString(if(mode == 2) R.string.player_brightness else R.string.player_volume, (value * 100).toInt()))
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                when(mode) {
                    1 -> playback.seek(target)
                    3 -> {
                        cancel()
                        return true
                    }
                    0 -> {
                        if(locked) controls() else {
                            val now = event.eventTime
                            val side = if(event.x < width * 0.38f) -1 else if(event.x > width * 0.62f) 1 else 0
                            val continuous = side != 0 && side == streak && now - streakAt <= PlayerGestures.SEEK_STREAK_MS
                            val double = now - lastTap < PlayerGestures.DOUBLE_TAP_MS && hypot(event.x - lastX, event.y - lastY) < PlayerGestures.DOUBLE_TAP_DISTANCE_DP * density
                            if(continuous || double) {
                                handler.removeCallbacks(single); lastTap = 0
                                if(side == 0) { if(playback.preferences.value.centerDoubleTap) { playback.toggle(); haptic() } } else {
                                    val seconds = playback.preferences.value.doubleTapSeconds
                                    playback.seekBy(side * seconds * 1000L)
                                    streakSeconds = if(continuous) streakSeconds + seconds else seconds
                                    streak = side; streakAt = now; feedback(activity.getString(R.string.player_seek_feedback, side * streakSeconds)); haptic()
                                }
                            } else { lastTap = now; lastX = event.x; lastY = event.y; handler.removeCallbacks(single); handler.postDelayed(single, PlayerGestures.SINGLE_TAP_MS) }
                        }
                    }
                }
                mode = 0; handler.postDelayed(clear, 750)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
    private fun haptic() { if(playback.preferences.value.haptics) activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
}

internal data class PinchSample(val x: Float, val y: Float, val distance: Float)

internal fun playerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if(seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
