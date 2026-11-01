// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** RLE timing compared with independent packet export, not neighboring timestamps. */
@RunWith(AndroidJUnit4::class)
class Mp4RawSampleTimingTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private val profile = Mp4ParserProfile(true, true, true, C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265)

    @Test fun allBaselineAndStrongVbrRawTimesMatchIndependentPacketTruth() {
        for(name in listOf("colors.samples.json", "head-vbr-bframes.samples.json", "tail-vbr-bframes.samples.json", "edit-list-vbr.raw.samples.json")) {
            val ref = JSONObject(assets.open("mp4-cache/$name").bufferedReader().use { it.readText() })
            val file = ref.getString("asset").substringAfterLast('/')
            val bytes = assets.open(if(file == "colors.mp4") file else "mp4-cache/$file").use { it.readBytes() }
            val source = Mp4MetadataBytes { p, n -> CachedMp4Block(bytes.copyOfRange(p.toInt(), p.toInt() + n), CachedResourceRange("fixed-original", p, n.toLong())) }
            val metadata = requireNotNull(CachedMp4MetadataReader.read(source, bytes.size.toLong(), profile))
            val expected = ref.getJSONArray("tracks").getJSONObject(0)
            val timing = requireNotNull(Mp4RawSampleTiming.from(metadata, expected.getInt("id")))
            val samples = expected.getJSONArray("samples")
            assertEquals(samples.length(), timing.sampleCount)
            repeat(samples.length()) { index ->
                val sample = samples.getJSONObject(index)
                assertEquals("$name raw PTS $index", sample.getLong("ptsUs"), timing.presentationUs(index))
                assertEquals("$name DTS $index", sample.getLong("dtsUs"), timing.decodeUs(index))
                assertEquals("$name duration $index", sample.getLong("durationUs"), timing.durationUs(index))
            }
            if(name.contains("vbr")) {
                assertTrue((0 until timing.sampleCount).any { timing.presentationTicks(it) != timing.decodeTicks(it) })
                assertNotEquals("B-frame adjacent PTS is not a sample duration", timing.presentationUs(1) - timing.presentationUs(0), timing.durationUs(0))
            }
        }
    }

    @Test fun runsPreserveSignedCompositionOffsetsAndExactDecodeBoundaries() {
        val decode = box("stts", 0, listOf(2 to 4, 1 to 9))
        val composition = box("ctts", 1, listOf(1 to -10, 2 to 3))
        val timing = requireNotNull(Mp4RawSampleTiming.parse(3, 1000, decode, composition))
        assertEquals(listOf(0L, 4L, 8L), (0..2).map(timing::decodeTicks))
        assertEquals(listOf(4L, 4L, 9L), (0..2).map(timing::durationTicks))
        assertEquals(listOf(-10_000L, 7_000L, 11_000L), (0..2).map(timing::presentationUs))
        assertEquals(1_000_000_000_000_000L, timing.micros(1_000_000_000_000L))
    }

    @Test fun invalidRunsAndUnsupportedCountsDoNotInventTimings() {
        assertNull(Mp4RawSampleTiming.parse(3, 0, box("stts", 0, listOf(3 to 1)), null))
        assertNull(Mp4RawSampleTiming.parse(3, 1000, box("stts", 0, listOf(2 to 1)), null))
        assertNull(Mp4RawSampleTiming.parse(3, 1000, box("stts", 0, listOf(0 to 1, 3 to 2)), null))
        assertNull(Mp4RawSampleTiming.parse(Int.MAX_VALUE.toLong(), 1, box("stts", 0, listOf(Int.MAX_VALUE to 1)), null))
        assertNull(Mp4RawSampleTiming.parse(3, 1000, box("stts", 0, listOf(3 to 1)), box("ctts", 2, listOf(3 to 1))))
    }
    private fun box(type: String, version: Int, runs: List<Pair<Int, Int>>) = ByteBuffer.allocate(16 + runs.size * 8).order(ByteOrder.BIG_ENDIAN)
        .putInt(16 + runs.size * 8).put(type.toByteArray()).putInt(version shl 24).putInt(runs.size).apply {
            for((count, value) in runs) putInt(count).putInt(value)
        }.array()
}
