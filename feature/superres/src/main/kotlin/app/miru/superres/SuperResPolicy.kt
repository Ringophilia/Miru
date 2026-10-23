// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.superres

import java.net.URI
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class SuperResMode { OFF, AUTO, ANIME, GENERAL }
enum class SuperResTier(val network: String) {
    FAST("S"), BALANCED("M"), QUALITY("L");
    val lower: SuperResTier? get() = entries.getOrNull(ordinal - 1)
    fun limitedBy(ceiling: SuperResTier?) = if(ceiling != null && ceiling.ordinal < ordinal) ceiling else this
}
enum class SuperResFit { CONTAIN, COVER, FILL }
enum class SuperResBypass { OFF, UNKNOWN_SIZE, NATIVE_SIZE, HDR, PROTECTED, SPEED, PICTURE_IN_PICTURE, THERMAL, ERROR }
enum class SuperResTiming { NONE, GPU_QUERY, SYNCHRONIZED_RENDER }

data class SuperResConfig(
    val shaders: List<String>,
    val fit: SuperResFit = SuperResFit.CONTAIN,
    val compareSplit: Float = 0f,
    val playbackSpeed: Float = 1f,
    val frameRateHint: Float = 0f,
)

/** Top-left origin rectangle in physical SurfaceView pixels, including crop outside its bounds. */
data class SuperResImageRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    fun splitX(split: Float) = left + width * split.coerceIn(0f, 1f)
    /** A UI handle at screenX maps to this source-relative split, including contain/cover offsets. */
    fun splitAt(screenX: Float) = if(width > 0f) ((screenX - left) / width).coerceIn(0f, 1f) else 0f
}

data class SuperResStatus(
    val initialized: Boolean = false,
    val active: Boolean = false,
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val imageRect: SuperResImageRect = SuperResImageRect(0f, 0f, 0f, 0f),
    val gpuMs: Double = 0.0,
    val averageMs: Double = 0.0,
    val frameIntervalMs: Double = 0.0,
    val passes: Int = 0,
    val frames: Long = 0,
    val presentations: Long = 0,
    val generation: Long = 0,
    val timing: SuperResTiming = SuperResTiming.NONE,
    val consecutiveOverloadedFrames: Int = 0,
    val overloadDetected: Boolean = false,
    val error: String? = null,
) {
    val overloaded get() = overloadDetected || consecutiveOverloadedFrames >= 30
}

data class SuperResDecision(
    val mode: SuperResMode,
    val tier: SuperResTier,
    val shaders: List<String> = emptyList(),
    val bypass: SuperResBypass? = null,
) { val enabled get() = bypass == null && shaders.isNotEmpty() }

object SuperResPolicy {
    private val animeKeywords = Regex("番剧|动漫|动画|国漫|日漫|anime|アニメ|bangumi", RegexOption.IGNORE_CASE)
    // Domains only classify user-selected content; they are never used to discover or fetch sources.
    val animationDomains: Set<String> = setOf("bangumi.tv", "bgm.tv", "anilist.co", "myanimelist.net", "crunchyroll.com")

    fun looksAnime(title: String, pageUrl: String): Boolean {
        if(animeKeywords.containsMatchIn(title) || animeKeywords.containsMatchIn(pageUrl)) return true
        val host = runCatching { URI(pageUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return animationDomains.any { host == it || host.endsWith(".$it") }
    }

    /** Ringo-Cove Mode A. Every name refers to the unchanged, real multipass GLSL assets. */
    fun chain(anime: Boolean, tier: SuperResTier): List<String> {
        if(!anime) return listOf("FSR_EASU", "FSR_RCAS")
        val second = if(tier == SuperResTier.QUALITY) "M" else "S"
        return listOf("Clamp_Highlights", "Restore_CNN_${tier.network}", "Upscale_CNN_x2_${tier.network}",
            "AutoDownscalePre_x2", "AutoDownscalePre_x4", "Upscale_CNN_x2_$second")
    }

    fun imageRect(width: Int, height: Int, boxWidth: Int, boxHeight: Int,
                  fit: SuperResFit = SuperResFit.CONTAIN, pixelWidthHeightRatio: Float = 1f): SuperResImageRect {
        if(width <= 0 || height <= 0 || boxWidth <= 0 || boxHeight <= 0) return SuperResImageRect(0f, 0f, 0f, 0f)
        if(fit == SuperResFit.FILL) return SuperResImageRect(0f, 0f, boxWidth.toFloat(), boxHeight.toFloat())
        val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val w = width * ratio
        val scale = if(fit == SuperResFit.COVER) max(boxWidth / w, boxHeight.toFloat() / height)
                    else min(boxWidth / w, boxHeight.toFloat() / height)
        val outW = w * scale
        val outH = height * scale
        return SuperResImageRect((boxWidth - outW) / 2f, (boxHeight - outH) / 2f, outW, outH)
    }

    /** User preference remains untouched. Protection only changes this session's effective decision. */
    fun decide(mode: SuperResMode, tier: SuperResTier, sourceWidth: Int, sourceHeight: Int,
               outputWidth: Float, outputHeight: Float, title: String = "", pageUrl: String = "",
               speed: Float = 1f, hdr: Boolean = false, protectedContent: Boolean = false,
               pip: Boolean = false, thermalStatus: Int = 0, deviceCeiling: SuperResTier? = null,
               sessionCeiling: SuperResTier? = null, failed: Boolean = false): SuperResDecision {
        val effectiveTier = tier.limitedBy(deviceCeiling).limitedBy(sessionCeiling)
        val reason = when {
            mode == SuperResMode.OFF -> SuperResBypass.OFF
            failed -> SuperResBypass.ERROR
            protectedContent -> SuperResBypass.PROTECTED
            hdr -> SuperResBypass.HDR
            pip -> SuperResBypass.PICTURE_IN_PICTURE
            !speed.isFinite() || speed > 1.5f -> SuperResBypass.SPEED
            thermalStatus >= 4 -> SuperResBypass.THERMAL
            sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 -> SuperResBypass.UNKNOWN_SIZE
            mode == SuperResMode.AUTO && sourceWidth >= outputWidth * .9f && sourceHeight >= outputHeight * .9f -> SuperResBypass.NATIVE_SIZE
            else -> null
        }
        val selectedMode = if(mode == SuperResMode.AUTO) {
            if(looksAnime(title, pageUrl)) SuperResMode.ANIME else SuperResMode.GENERAL
        } else mode
        return SuperResDecision(selectedMode, effectiveTier,
            if(reason == null) chain(selectedMode == SuperResMode.ANIME, effectiveTier) else emptyList(), reason)
    }
}

sealed interface SuperResProtection {
    data object None : SuperResProtection
    data class Reduce(val tier: SuperResTier, val persistDeviceCeiling: Boolean) : SuperResProtection
    data class Disable(val reason: SuperResBypass) : SuperResProtection
}

/** Session guard. A hot episode reduces once; sustained measured overload records a device ceiling. */
class SuperResGuard {
    private var thermalReduced = false
    private var actionGeneration: Long? = null
    fun evaluate(status: SuperResStatus, tier: SuperResTier, thermalStatus: Int): SuperResProtection {
        if(thermalStatus < 3) thermalReduced = false
        if(thermalStatus >= 4) return SuperResProtection.Disable(SuperResBypass.THERMAL)
        val heat = thermalStatus >= 3 && !thermalReduced
        val overload = status.overloaded && actionGeneration != status.generation
        if(!heat && !overload) return SuperResProtection.None
        if(heat) thermalReduced = true
        actionGeneration = status.generation
        return tier.lower?.let { SuperResProtection.Reduce(it, persistDeviceCeiling = !heat) }
            ?: SuperResProtection.Disable(if(heat) SuperResBypass.THERMAL else SuperResBypass.ERROR)
    }
}

/** Counts contiguous measured frames; missing or invalid measurements break the streak. */
class SuperResFrameLoad {
    var consecutive: Int = 0; private set
    var overloadDetected: Boolean = false; private set
    private var lastFrame = -1L
    fun clear() { consecutive = 0; overloadDetected = false; lastFrame = -1L }
    fun record(frame: Long, elapsedMs: Double, frameIntervalMs: Double) {
        if(frame != lastFrame + 1) consecutive = 0
        consecutive = if(elapsedMs.isFinite() && elapsedMs > 0 && frameIntervalMs.isFinite() &&
            frameIntervalMs > 0 && elapsedMs > frameIntervalMs * .85) consecutive + 1 else 0
        lastFrame = frame
        if(consecutive >= 30) overloadDetected = true
    }
}
