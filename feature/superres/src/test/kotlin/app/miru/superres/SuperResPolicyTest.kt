// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.superres

import org.junit.Assert.*
import org.junit.Test

class SuperResPolicyTest {
    @Test fun realAnimeNetworksAndFsrChains() {
        SuperResTier.entries.forEach { tier ->
            assertEquals(listOf("FSR_EASU", "FSR_RCAS"), SuperResPolicy.chain(false, tier))
            val chain = SuperResPolicy.chain(true, tier)
            assertEquals("Restore_CNN_${tier.network}", chain[1])
            assertEquals("Upscale_CNN_x2_${tier.network}", chain[2])
            assertEquals(if(tier == SuperResTier.QUALITY) "Upscale_CNN_x2_M" else "Upscale_CNN_x2_S", chain.last())
            assertEquals(6, chain.size)
        }
    }

    @Test fun containCoverFillAndNonSquarePixels() {
        val contain = SuperResPolicy.imageRect(1920, 1080, 1000, 1000)
        assertEquals(1000f, contain.width, .01f)
        assertEquals(562.5f, contain.height, .01f)
        assertEquals(218.75f, contain.top, .01f)
        val cover = SuperResPolicy.imageRect(1920, 1080, 1000, 1000, SuperResFit.COVER)
        assertTrue(cover.left < 0)
        assertEquals(1000f, cover.height, .01f)
        assertEquals(.5f, cover.splitAt(500f), .001f)
        assertEquals(500f, cover.splitX(.5f), .001f)
        assertEquals(SuperResImageRect(0f, 0f, 1000f, 1000f), SuperResPolicy.imageRect(1920, 1080, 1000, 1000, SuperResFit.FILL))
        assertEquals(1000f, SuperResPolicy.imageRect(720, 576, 1000, 1000, pixelWidthHeightRatio = 16f/15f).width, .01f)
    }

    @Test fun autoBypassesAtExactNinetyPercentButExplicitModeRemainsAvailable() {
        fun decide(w: Int, mode: SuperResMode = SuperResMode.AUTO) = SuperResPolicy.decide(mode, SuperResTier.BALANCED,
            w, w, 1000f, 1000f)
        assertEquals(SuperResBypass.NATIVE_SIZE, decide(900).bypass)
        assertTrue(decide(899).enabled)
        assertTrue(decide(1000, SuperResMode.ANIME).enabled)
        assertEquals(SuperResBypass.OFF, decide(500, SuperResMode.OFF).bypass)
    }

    @Test fun animationMetadataAndHostAreClassifiedWithoutSuffixSpoof() {
        assertTrue(SuperResPolicy.looksAnime("春季番剧", "https://example.test/watch"))
        assertTrue(SuperResPolicy.looksAnime("episode", "https://example.test/anime/42"))
        assertTrue(SuperResPolicy.looksAnime("episode", "https://www.crunchyroll.com/watch/42"))
        assertFalse(SuperResPolicy.looksAnime("film", "https://crunchyroll.com.evil.test/42"))
        assertFalse(SuperResPolicy.looksAnime("film", "https://example.test/42"))
    }

    @Test fun safetyGatesDoNotMutateRequestedModeAndRestoreOnSpeedRecovery() {
        fun decide(speed: Float = 1f, hdr: Boolean = false, pip: Boolean = false, secure: Boolean = false) =
            SuperResPolicy.decide(SuperResMode.AUTO, SuperResTier.QUALITY, 540, 540, 1080f, 1080f,
                title = "anime", speed = speed, hdr = hdr, pip = pip, protectedContent = secure)
        assertEquals(SuperResBypass.SPEED, decide(speed = 1.5001f).bypass)
        assertTrue(decide(speed = 1.5f).enabled)
        assertEquals(SuperResMode.ANIME, decide().mode)
        assertEquals(SuperResBypass.HDR, decide(hdr = true).bypass)
        assertEquals(SuperResBypass.PROTECTED, decide(secure = true).bypass)
        assertEquals(SuperResBypass.PICTURE_IN_PICTURE, decide(pip = true).bypass)
        assertTrue(decide().enabled)
    }

    @Test fun thermalDropsOncePerHeatEpisodeAndDoesNotPersistDeviceCeiling() {
        val guard = SuperResGuard()
        val status = SuperResStatus(generation = 1)
        assertEquals(SuperResProtection.Reduce(SuperResTier.BALANCED, false), guard.evaluate(status, SuperResTier.QUALITY, 3))
        assertEquals(SuperResProtection.None, guard.evaluate(status, SuperResTier.BALANCED, 3))
        assertEquals(SuperResProtection.Disable(SuperResBypass.THERMAL), guard.evaluate(status, SuperResTier.BALANCED, 4))
        guard.evaluate(status, SuperResTier.BALANCED, 0)
        assertEquals(SuperResProtection.Reduce(SuperResTier.FAST, false), guard.evaluate(status, SuperResTier.BALANCED, 3))
    }

    @Test fun thirtyConsecutiveFramesRequiredAndOverloadEventSurvivesPollGap() {
        val load = SuperResFrameLoad()
        repeat(29) { load.record(it.toLong(), 35.0, 40.0) }
        assertEquals(29, load.consecutive)
        assertFalse(load.overloadDetected)
        load.record(29, 35.0, 40.0)
        assertTrue(load.overloadDetected)
        load.record(30, 1.0, 40.0)
        assertEquals(0, load.consecutive)
        assertTrue(load.overloadDetected)
        load.clear()
        repeat(20) { load.record(it.toLong(), 35.0, 40.0) }
        load.record(21, 35.0, 40.0)
        assertEquals(1, load.consecutive)
        load.record(22, 34.0, 40.0) // exactly 85% is not greater than 85%
        assertEquals(0, load.consecutive)
        assertFalse(load.overloadDetected)
    }

    @Test fun overloadDegradesOncePerGenerationAndRemembersCeiling() {
        val guard = SuperResGuard()
        val slow = SuperResStatus(generation = 4, overloadDetected = true)
        assertEquals(SuperResProtection.Reduce(SuperResTier.BALANCED, true), guard.evaluate(slow, SuperResTier.QUALITY, 0))
        assertEquals(SuperResProtection.None, guard.evaluate(slow, SuperResTier.BALANCED, 0))
        assertEquals(SuperResProtection.Reduce(SuperResTier.FAST, true), guard.evaluate(slow.copy(generation = 5), SuperResTier.BALANCED, 0))
        val decision = SuperResPolicy.decide(SuperResMode.ANIME, SuperResTier.QUALITY, 540, 540, 1080f, 1080f,
            deviceCeiling = SuperResTier.BALANCED, sessionCeiling = SuperResTier.FAST)
        assertEquals(SuperResTier.FAST, decision.tier)
    }
}
