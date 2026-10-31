// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser.capability

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.TrackSampleTable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Public Media3 1.11.1 API capability only, NOT a P11 implementation/acceptance.
 *
 * Exact selector: app.miru.browser.capability.CachedRangeMp4IndexCapabilityTest
 * Required argument: miruCachedRangeCapability=true
 * Complex generated fixtures additionally require miruCachedRangeComplex=true.
 *
 * No decoder, player, HTTP, disk cache, UI, byte/time interpolation or reflection.
 * Assets are parsed in memory; expected samples come from an independent fixed
 * ISO-BMFF export (baseline) or recorded ffprobe packet export (complex fixtures).
 */
@RunWith(AndroidJUnit4::class)
class CachedRangeMp4IndexCapabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets

    @Before fun explicitlyEnabled() {
        assumeTrue("Independent P11 capability probe requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("miruCachedRangeCapability") == "true")
    }

    @Test fun headMoovPublicTablesMatchAllFixedSourceSamples() {
        val reference = reference("colors.samples.json")
        val bytes = fixedMedia(reference)
        val boxes = scan(bytes, 0, bytes.size)
        assertTrue("Existing colors.mp4 must contain moov before mdat",
            boxes.single { it.type == fourcc("moov") }.start < boxes.first { it.type == fourcc("mdat") }.start)
        val tables = parse(bytes, reference.getBoolean("ignoreEditLists"))
        assertTables(bytes, tables, reference)
        assertEquals("Fixed baseline has one complete video track", 1, tables.size)
        assertEquals(120, tables.single().sampleCount)
        assertTrue("Exact sample positions must be non-uniform even at uniform 100ms times",
            tables.single().sizes.toSet().size > 10)
        Log.i(TAG, "head moov: all 120 source offsets/sizes/PTS/flags/payload hashes matched")
    }

    @Test fun relocatedTailMoovPublicTablesMatchEveryRelocatedSourceSample() {
        val reference = reference("colors.samples.json")
        val original = fixedMedia(reference)
        val relocated = relocateMoovToTail(original)
        val boxes = scan(relocated.bytes, 0, relocated.bytes.size)
        val moov = boxes.single { it.type == fourcc("moov") }
        assertEquals("Relocated moov must be the final complete box", relocated.bytes.size, moov.end)
        assertTrue(moov.start > boxes.single { it.type == fourcc("mdat") }.start)
        assertEquals("Relocation preserves the complete file size", original.size, relocated.bytes.size)
        assertTables(relocated.bytes, parse(relocated.bytes, reference.getBoolean("ignoreEditLists")),
            reference, sourceOffsetDelta = relocated.sourceOffsetDelta)
        Log.i(TAG, "tail moov: 120 fixed samples preserved; exact byte delta=" + relocated.sourceOffsetDelta)
    }

    @Test fun omittingSampleArraysCannotSupplyAByteTimeIndex() {
        val reference = reference("colors.samples.json")
        val bytes = fixedMedia(reference)
        val tables = parse(bytes, ignoreEditLists = false, omitSampleTable = true)
        assertEquals(1, tables.size)
        val table = tables.single()
        assertEquals("A nonzero count alone must not be mistaken for mapping data", 120, table.sampleCount)
        assertFalse(table.hasSampleTableData())
        assertEquals(0, table.offsets.size)
        assertEquals(0, table.sizes.size)
        assertEquals(0, table.timestampsUs.size)
        assertEquals(0, table.flags.size)
    }

    @Test fun generatedStrongVbrBFramesHeadMoovMatchesFfprobeSamples() =
        complexFixture("head-vbr-bframes.samples.json", "head", expectReordering = true)

    @Test fun generatedStrongVbrBFramesTailMoovMatchesFfprobeSamples() =
        complexFixture("tail-vbr-bframes.samples.json", "tail", expectReordering = true)

    @Test fun generatedNonIdentityEditListMatchesFfprobePresentedSamples() =
        complexFixture("edit-list-vbr.samples.json", "head", expectReordering = true, requireAppliedEdit = true)

    private fun complexFixture(name: String, placement: String, expectReordering: Boolean,
        requireAppliedEdit: Boolean = false) {
        assumeTrue("Complex fixtures have not been generated/validated by this source-only change",
            InstrumentationRegistry.getArguments().getString("miruCachedRangeComplex") == "true")
        // Once explicitly enabled, a missing generated reference is a failure, not a skip.
        val reference = reference(name)
        assertEquals(placement, reference.getString("moovPlacement"))
        val bytes = fixedMedia(reference)
        val boxes = scan(bytes, 0, bytes.size)
        val moov = boxes.single { it.type == fourcc("moov") }
        val mdat = boxes.first { it.type == fourcc("mdat") }
        assertEquals("Reference placement must match the actual file layout", placement == "head", moov.start < mdat.start)
        if(requireAppliedEdit) {
            assertFalse("Edit-list gate must enable Media3 edit processing", reference.getBoolean("ignoreEditLists"))
            assertTrue("Generated fixture must declare an independently checked non-identity edit",
                reference.getJSONObject("provenance").getBoolean("nonIdentityEditList"))
        }
        val tables = parse(bytes, reference.getBoolean("ignoreEditLists"))
        if(requireAppliedEdit && InstrumentationRegistry.getArguments().getString("miruCachedRangeTrace") == "true")
            traceTableDiff(bytes, tables, reference)
        // ffprobe stream duration and raw sample-table duration can differ in
        // composition-offset/edit semantics. Compare every actual sample exactly;
        // retain the separate duration definitions as diagnostics.
        assertTables(bytes, tables, reference, checkTableDuration = false)
        val samples = reference.getJSONArray("tracks").getJSONObject(0).getJSONArray("samples")
        val sizes = (0 until samples.length()).map { samples.getJSONObject(it).getInt("size") }
        assertTrue("Strong VBR gate requires genuinely different source sample sizes", sizes.toSet().size >= 20)
        assertTrue("Strong VBR gate requires at least 10:1 sample-size contrast",
            sizes.max().toLong() >= sizes.min().coerceAtLeast(1) * 10L)
        if(expectReordering) {
            assertTrue("B-frame gate requires real composition/decode time reordering",
                (0 until samples.length()).any { samples.getJSONObject(it).getLong("ptsUs") != samples.getJSONObject(it).getLong("dtsUs") })
            assertTrue("B-frame gate requires non-monotonic PTS in source/decode sample order",
                (1 until samples.length()).any { samples.getJSONObject(it).getLong("ptsUs") <
                    samples.getJSONObject(it - 1).getLong("ptsUs") })
        }
        Log.i(TAG, "complex public-table gate passed: " + name + " samples=" + samples.length())
    }

    private fun reference(name: String): JSONObject =
        JSONObject(assets.open("media/cached-ranges/" + name).bufferedReader().use { it.readText() }).also {
            assertEquals("Only the fixed reference schema is supported", 1, it.getInt("schemaVersion"))
        }

    /** Optional diagnostic only: emits the complete table BEFORE the unchanged assertions. */
    private fun traceTableDiff(bytes: ByteArray, tables: List<TrackSampleTable>, reference: JSONObject) {
        val tracks = reference.getJSONArray("tracks")
        for(trackIndex in 0 until tracks.length()) {
            val expected = tracks.getJSONObject(trackIndex)
            val table = tables.singleOrNull { it.track.id == expected.getInt("id") } ?: continue
            val samples = expected.getJSONArray("samples")
            Log.i(TAG, "table-diff-count expected=" + samples.length() + " actual=" + table.sampleCount)
            for(index in 0 until minOf(samples.length(), table.sampleCount)) {
                val sample = samples.getJSONObject(index)
                val actualHash = runCatching { digest(bytes.copyOfRange(table.offsets[index].toInt(),
                    table.offsets[index].toInt() + table.sizes[index])) }.getOrNull()
                val expectedFlags = (if(sample.getBoolean("isSync")) C.BUFFER_FLAG_KEY_FRAME else 0) or
                    (if(index == samples.length() - 1) C.BUFFER_FLAG_LAST_SAMPLE else 0)
                val row = JSONObject().put("index", index).put("track", table.track.id)
                    .put("expectedOffset", sample.getLong("offset")).put("actualOffset", table.offsets[index])
                    .put("expectedSize", sample.getInt("size")).put("actualSize", table.sizes[index])
                    .put("expectedPtsUs", sample.getLong("ptsUs")).put("actualPtsUs", table.timestampsUs[index])
                    .put("expectedFlags", expectedFlags).put("actualFlags", table.flags[index])
                    .put("payloadHashMatches", actualHash == sample.getString("sha256"))
                Log.i(TAG, "table-diff " + row)
            }
        }
    }

    private fun fixedMedia(reference: JSONObject): ByteArray {
        val bytes = assets.open(reference.getString("asset")).use { it.readBytes() }
        assertTrue("Capability fixture must remain small and bounded", bytes.size <= 32 * 1024 * 1024)
        assertEquals("Reference must bind the exact complete media bytes", reference.getLong("bytes"), bytes.size.toLong())
        assertEquals(reference.getString("sha256").lowercase(), digest(bytes))
        return bytes
    }

    private fun parse(bytes: ByteArray, ignoreEditLists: Boolean,
        omitSampleTable: Boolean = false): List<TrackSampleTable> {
        val moov = scan(bytes, 0, bytes.size).single { it.type == fourcc("moov") }
        return BoxParser.parseTraks(
            tree(bytes, moov), GaplessInfoHolder(), C.TIME_UNSET,
            /* drmInitData= */ null, ignoreEditLists,
            /* isQuickTime= */ false,
            /* modifyTrackFunction= */ { track -> track },
            omitSampleTable,
        )
    }

    private fun assertTables(bytes: ByteArray, tables: List<TrackSampleTable>, reference: JSONObject,
        sourceOffsetDelta: Long = 0, checkTableDuration: Boolean = true) {
        val tracks = reference.getJSONArray("tracks")
        assertEquals("No reference track may silently disappear", tracks.length(), tables.size)
        val mediaBoxes = scan(bytes, 0, bytes.size).filter { it.type == fourcc("mdat") }
        for(trackIndex in 0 until tracks.length()) {
            val expected = tracks.getJSONObject(trackIndex)
            val table = tables.single { it.track.id == expected.getInt("id") }
            assertEquals(expected.getLong("timescale"), table.track.timescale)
            assertEquals("Reference track must be actual video", C.TRACK_TYPE_VIDEO, table.track.type)
            if(checkTableDuration) assertEquals(expected.getLong("durationUs"), table.durationUs)
            else Log.i(TAG, "duration definitions: packet-reference stream durationUs=" +
                expected.getLong("durationUs") + " public table durationUs=" + table.durationUs)
            val samples = expected.getJSONArray("samples")
            assertEquals(expected.getInt("sampleCount"), samples.length())
            assertEquals(samples.length(), table.sampleCount)
            assertTrue("Per-sample arrays must be populated", table.hasSampleTableData())
            assertEquals(table.sampleCount, table.offsets.size)
            assertEquals(table.sampleCount, table.sizes.size)
            assertEquals(table.sampleCount, table.timestampsUs.size)
            assertEquals(table.sampleCount, table.flags.size)
            for(index in 0 until samples.length()) {
                val sample = samples.getJSONObject(index)
                val label = "track=" + table.track.id + " sample=" + index
                val offset = sample.getLong("offset") + sourceOffsetDelta
                val size = sample.getInt("size")
                assertEquals(label + " exact source byte offset", offset, table.offsets[index])
                assertEquals(label + " exact encoded sample byte size", size, table.sizes[index])
                assertEquals(label + " actual presentation timestamp", sample.getLong("ptsUs"), table.timestampsUs[index])
                val sync = sample.getBoolean("isSync")
                val flags = (if(sync) C.BUFFER_FLAG_KEY_FRAME else 0) or
                    (if(index == samples.length() - 1) C.BUFFER_FLAG_LAST_SAMPLE else 0)
                assertEquals(label + " sync/final flags", flags, table.flags[index])
                assertTrue(label + " must address actual mdat payload",
                    mediaBoxes.any { offset >= it.start + it.headerBytes && offset + size <= it.end })
                assertTrue(label + " source byte span must be bounded", offset >= 0 && size > 0 && offset + size <= bytes.size)
                val actualHash = digest(bytes.copyOfRange(offset.toInt(), offset.toInt() + size))
                assertEquals(label + " independently fixed encoded payload", sample.getString("sha256").lowercase(), actualHash)
            }
        }
    }

    /** Build only the complete structural tree required by the public BoxParser. */
    private fun tree(bytes: ByteArray, box: Box, depth: Int = 0): Mp4Box.ContainerBox {
        require(depth < 16) { "Capability fixture exceeds the structural depth bound" }
        val container = Mp4Box.ContainerBox(box.type, box.end.toLong())
        for(child in scan(bytes, box.start + box.headerBytes, box.end)) {
            if(child.type in CONTAINERS) container.add(tree(bytes, child, depth + 1))
            else {
                require(child.headerBytes == 8) { "Extended leaf headers require a separate capability gate" }
                container.add(Mp4Box.LeafBox(child.type, ParsableByteArray(bytes.copyOfRange(child.start, child.end))))
            }
        }
        return container
    }

    private fun scan(bytes: ByteArray, start: Int, end: Int): List<Box> {
        require(start in 0..end && end <= bytes.size)
        val result = mutableListOf<Box>()
        var cursor = start
        while(cursor < end) {
            require(end - cursor >= 8) { "Incomplete box header; no partial index is allowed" }
            val rawSize = uint32(bytes, cursor)
            val type = ByteBuffer.wrap(bytes, cursor + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            val header = if(rawSize == 1L) 16 else 8
            require(end - cursor >= header)
            val length = when(rawSize) {
                0L -> (end - cursor).toLong()
                1L -> ByteBuffer.wrap(bytes, cursor + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                else -> rawSize
            }
            require(length >= header && length <= end.toLong() - cursor) { "Incomplete/oversized box; require the entire moov" }
            val box = Box(type, cursor, cursor + length.toInt(), header)
            result += box
            cursor = box.end
        }
        return result
    }

    /**
     * Deterministic lossless baseline layout transform, not a new FFmpeg fixture.
     * Move the complete head moov after mdat and rewrite its absolute stco/co64.
     * Expected samples remain the independently fixed original samples plus one
     * known byte relocation delta; every encoded payload hash must remain equal.
     */
    private fun relocateMoovToTail(original: ByteArray): Relocated {
        val top = scan(original, 0, original.size)
        val moov = top.single { it.type == fourcc("moov") }
        val mdat = top.single { it.type == fourcc("mdat") }
        require(moov.start < mdat.start)
        require(top.last() == mdat) { "Baseline relocation intentionally supports the fixed colors layout only" }
        val length = moov.end - moov.start
        val delta = -length.toLong()
        val rewritten = original.copyOf()
        fun updateOffsets(box: Box) {
            for(child in scan(rewritten, box.start + box.headerBytes, box.end)) {
                if(child.type in CONTAINERS) updateOffsets(child)
                else if(child.type == fourcc("stco") || child.type == fourcc("co64")) {
                    require(child.headerBytes == 8)
                    val width = if(child.type == fourcc("stco")) 4 else 8
                    val count = uint32(rewritten, child.start + 12)
                    require(count <= 10_000 && child.start + 16L + count * width == child.end.toLong())
                    for(index in 0 until count.toInt()) {
                        val position = child.start + 16 + index * width
                        val buffer = ByteBuffer.wrap(rewritten).order(ByteOrder.BIG_ENDIAN)
                        val previous = if(width == 4) uint32(rewritten, position) else buffer.getLong(position)
                        require(previous >= mdat.start + mdat.headerBytes && previous < mdat.end)
                        val moved = previous + delta
                        require(moved >= 0 && (width == 8 || moved <= 0xffffffffL))
                        if(width == 4) buffer.putInt(position, moved.toInt()) else buffer.putLong(position, moved)
                    }
                }
            }
        }
        updateOffsets(moov)
        val output = ByteArray(original.size)
        var cursor = 0
        for(box in top.filter { it !== moov }) {
            rewritten.copyInto(output, cursor, box.start, box.end)
            cursor += box.end - box.start
        }
        rewritten.copyInto(output, cursor, moov.start, moov.end)
        return Relocated(output, delta)
    }

    private fun uint32(bytes: ByteArray, position: Int): Long =
        ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Box(val type: Int, val start: Int, val end: Int, val headerBytes: Int)
    private data class Relocated(val bytes: ByteArray, val sourceOffsetDelta: Long)
    private companion object {
        const val TAG = "MiruCachedRangeProbe"
        fun fourcc(value: String): Int {
            require(value.length == 4)
            return value.fold(0) { result, char -> (result shl 8) or char.code }
        }
        val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts").map(::fourcc).toSet()
    }
}
