// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min
import org.w3c.dom.Element

/** Bounded, defensive container parsers. No decoder, network or Android state is needed. */
internal object ResolutionParsers {
    fun attributes(line: String): Map<String, String> = Regex("([A-Za-z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        .findAll(line.substringAfter(':', line)).associate { it.groupValues[1].uppercase() to it.groupValues[2].trim().removeSurrounding("\"") }

    fun hls(text: String): MediaResolution? = text.lineSequence().map(String::trim)
        .filter { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF:") }
        .mapNotNull { line ->
            val sizes = attributes(line)["RESOLUTION"]?.split('x', 'X') ?: return@mapNotNull null
            if (sizes.size != 2) null else MediaResolution.valid(sizes[0].toIntOrNull() ?: 0, sizes[1].toIntOrNull() ?: 0)
        }.maxByOrNull { it.pixels }

    fun dash(bytes: ByteArray): MediaResolution? = runCatching {
        val text = bytes.toString(Charsets.UTF_8)
        // Android's XML implementation varies by API. Reject declarations before parsing as
        // well as disabling external entities, so no fallback can ever resolve remote entities.
        // NUL also rejects UTF-16/32 before their declaration can bypass the UTF-8
        // preflight. These uncommon MPDs remain "unknown" rather than parsing a DTD.
        if (bytes.any { it == 0.toByte() } || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")
                .forEach { feature -> runCatching { setFeature(feature, false) } }
        }
        val root = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
            setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
        }
            .parse(ByteArrayInputStream(bytes)).documentElement
        if ((root.localName ?: root.tagName) != "MPD") return null
        var best: MediaResolution? = null
        val sets = root.getElementsByTagNameNS("*", "AdaptationSet")
        for (i in 0 until min(sets.length, 4096)) {
            val set = sets.item(i) as? Element ?: continue
            if (set.getAttribute("contentType") == "audio" || set.getAttribute("mimeType").startsWith("audio/")) continue
            val reps = set.getElementsByTagNameNS("*", "Representation")
            for (j in 0 until min(reps.length, 4096)) {
                val rep = reps.item(j) as? Element ?: continue
                val width = (rep.getAttribute("width").toIntOrNull() ?: set.getAttribute("width").toIntOrNull()) ?: continue
                val height = (rep.getAttribute("height").toIntOrNull() ?: set.getAttribute("height").toIntOrNull()) ?: continue
                val size = MediaResolution.valid(width, height) ?: continue
                if (size.pixels > (best?.pixels ?: 0)) best = size
            }
        }
        best
    }.getOrNull()

    private data class Box(val type: String, val content: Int, val end: Int, val complete: Boolean)
    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val output = ArrayList<Box>()
        var offset = start
        while (offset >= 0 && offset + 8 <= end && output.size < 8192) {
            var size = u32(data, offset)
            var header = 8
            if (size == 1L) {
                if (offset + 16 > end) break
                val high = u32(data, offset + 8)
                if (high > 0) break // Header probes cannot contain a multi-gigabyte box in full.
                size = u32(data, offset + 12)
                header = 16
            } else if (size == 0L) size = (end - offset).toLong()
            if (size < header || size > Int.MAX_VALUE - offset) break
            val boxEnd = offset + size.toInt()
            output += Box(type(data, offset + 4), offset + header, min(boxEnd, end), boxEnd <= end)
            if (boxEnd > end) break
            offset = boxEnd
        }
        return output
    }
    private fun child(data: ByteArray, parent: Box, name: String): Box? = boxes(data, parent.content, parent.end).firstOrNull { it.type == name }

    fun mp4(data: ByteArray): MediaResolution? = runCatching {
        val movies = boxes(data, 0, data.size).filter { it.type == "moov" }.toMutableList()
        // A suffix request starts in mdat; accept only a complete moov and validate its
        // video handler/sample hierarchy, rather than trusting an arbitrary stsd string.
        if (movies.isEmpty()) for (i in 4 until data.size - 3) {
            if (data[i] != 0x6d.toByte() || data[i + 1] != 0x6f.toByte() || data[i + 2] != 0x6f.toByte() || data[i + 3] != 0x76.toByte()) continue
            val size = u32(data, i - 4)
            if (size >= 8 && size <= data.size.toLong() - i + 4) movies += Box("moov", i + 4, i - 4 + size.toInt(), true)
        }
        var best: MediaResolution? = null
        for (movie in movies) for (track in boxes(data, movie.content, movie.end).filter { it.type == "trak" }) {
            val media = child(data, track, "mdia") ?: continue
            val handler = child(data, media, "hdlr") ?: continue
            if (handler.content + 12 > handler.end || type(data, handler.content + 8) != "vide") continue
            val table = child(data, media, "minf")?.let { child(data, it, "stbl") }
            val description = table?.let { child(data, it, "stsd") }
            var measured: MediaResolution? = null
            if (description != null && description.content + 8 <= description.end) {
                val count = u32(data, description.content + 4).coerceAtMost(256).toInt()
                for (entry in boxes(data, description.content + 8, description.end).take(count)) {
                    if (entry.type !in setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp09", "encv", "mp4v") || entry.content + 28 > entry.end) continue
                    measured = MediaResolution.valid(u16(data, entry.content + 24), u16(data, entry.content + 26))
                    if (measured != null) break
                }
            }
            if (measured == null) child(data, track, "tkhd")?.let { header ->
                if (header.complete && header.end - header.content >= 84) measured = MediaResolution.valid((u32(data, header.end - 8) shr 16).toInt(), (u32(data, header.end - 4) shr 16).toInt())
            }
            if ((measured?.pixels ?: 0) > (best?.pixels ?: 0)) best = measured
        }
        best
    }.getOrNull()

    fun media(data: ByteArray): MediaResolution? {
        mp4(data)?.let { return it }
        val start = tsStart(data) ?: return null
        return ts(data, start)
    }

    private fun tsStart(data: ByteArray): Int? {
        for (i in 0 until min(data.size - 376, 4096)) if (u8(data, i) == 0x47 && u8(data, i + 188) == 0x47 && u8(data, i + 376) == 0x47) return i
        return null
    }

    private fun ts(data: ByteArray, start: Int): MediaResolution? = runCatching {
        var pmt = -1
        var video = -1
        var hevc = false
        val stream = ByteArrayOutputStream()
        var p = start
        while (p + 188 <= data.size) {
            if (u8(data, p) != 0x47) break
            val pid = ((u8(data, p + 1) and 0x1f) shl 8) or u8(data, p + 2)
            val first = u8(data, p + 1) and 0x40 != 0
            val adaptation = (u8(data, p + 3) shr 4) and 3
            var offset = p + 4
            if (adaptation and 2 != 0) offset += 1 + u8(data, p + 4)
            if (adaptation and 1 != 0 && offset < p + 188) {
                if (pid == 0 && first) {
                    val section = offset + 1 + u8(data, offset)
                    if (section + 12 <= p + 188 && u8(data, section) == 0) {
                        val end = min(section + 3 + (u16(data, section + 1) and 0xfff) - 4, p + 188)
                        var i = section + 8
                        while (i + 4 <= end) {
                            if (u16(data, i) != 0) { pmt = u16(data, i + 2) and 0x1fff; break }
                            i += 4
                        }
                    }
                } else if (pid == pmt && first && video < 0) {
                    val section = offset + 1 + u8(data, offset)
                    if (section + 12 <= p + 188 && u8(data, section) == 2) {
                        val end = min(section + 3 + (u16(data, section + 1) and 0xfff) - 4, p + 188)
                        var i = section + 12 + (u16(data, section + 10) and 0xfff)
                        while (i + 5 <= end) {
                            val type = u8(data, i)
                            if (type == 0x1b || type == 0x24) { video = u16(data, i + 1) and 0x1fff; hevc = type == 0x24; break }
                            i += 5 + (u16(data, i + 3) and 0xfff)
                        }
                    }
                } else if (pid == video) {
                    // Exclude the PES header and its optional fields from Annex-B scanning.
                    if (first && offset + 9 <= p + 188 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()) offset += 9 + u8(data, offset + 8)
                    if (offset < p + 188) stream.write(data, offset, p + 188 - offset)
                }
            }
            p += 188
        }
        val annex = stream.toByteArray()
        for (i in 0 until annex.size - 5) {
            if (annex[i] != 0.toByte() || annex[i + 1] != 0.toByte() || annex[i + 2] != 1.toByte()) continue
            val header = u8(annex, i + 3)
            val skip = if (hevc) 5 else 4
            if ((!hevc && header and 0x1f == 7) || (hevc && (header shr 1) and 0x3f == 33)) {
                val raw = annex.copyOfRange(i + skip, min(annex.size, i + 3 + 1024))
                val size = if (hevc) hevcSps(raw) else avcSps(raw)
                if (size != null) return size
            }
        }
        null
    }.getOrNull()

    internal fun avcSps(raw: ByteArray): MediaResolution? = runCatching {
        val b = Bits(raw)
        val profile = b.read(8)
        b.skip(16); b.ue()
        var chroma = 1
        var separatePlane = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = b.ue()
            require(chroma in 0..3)
            if (chroma == 3) separatePlane = b.bit() == 1
            b.ue(); b.ue(); b.skip(1)
            if (b.bit() == 1) repeat(if (chroma != 3) 8 else 12) { index ->
                if (b.bit() == 1) {
                    var last = 8; var next = 8
                    repeat(if (index < 6) 16 else 64) { if (next != 0) next = (last + b.se() + 256) % 256; if (next != 0) last = next }
                }
            }
        }
        b.ue()
        when (b.ue()) {
            0 -> b.ue()
            1 -> { b.skip(1); b.se(); b.se(); val count = b.ue(); require(count <= 256); repeat(count) { b.se() } }
        }
        b.ue(); b.skip(1)
        val widthMbs = b.ue() + 1
        val heightUnits = b.ue() + 1
        require(widthMbs in 1..1024 && heightUnits in 1..1024)
        val frame = b.bit()
        if (frame == 0) b.skip(1)
        b.skip(1)
        var width = widthMbs * 16
        var height = (2 - frame) * heightUnits * 16
        if (b.bit() == 1) {
            val chromaArray = if (separatePlane) 0 else chroma
            val cropX = if (chromaArray == 1 || chromaArray == 2) 2 else 1
            val cropY = (if (chromaArray == 1) 2 else 1) * (2 - frame)
            width -= (b.ue() + b.ue()) * cropX
            height -= (b.ue() + b.ue()) * cropY
        }
        MediaResolution.valid(width, height)
    }.getOrNull()

    internal fun hevcSps(raw: ByteArray): MediaResolution? = runCatching {
        val b = Bits(raw)
        b.skip(4)
        val layers = b.read(3)
        b.skip(1); b.skip(96)
        val profiles = BooleanArray(layers)
        val levels = BooleanArray(layers)
        repeat(layers) { profiles[it] = b.bit() == 1; levels[it] = b.bit() == 1 }
        if (layers > 0) b.skip(2 * (8 - layers))
        repeat(layers) { if (profiles[it]) b.skip(88); if (levels[it]) b.skip(8) }
        b.ue()
        val chroma = b.ue(); require(chroma in 0..3)
        if (chroma == 3) b.skip(1)
        var width = b.ue(); var height = b.ue()
        if (b.bit() == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            width -= (b.ue() + b.ue()) * subW
            height -= (b.ue() + b.ue()) * subH
        }
        MediaResolution.valid(width, height)
    }.getOrNull()

    private class Bits(raw: ByteArray) {
        private val bytes = raw.filterIndexed { i, value -> !(i >= 2 && value == 3.toByte() && raw[i - 1] == 0.toByte() && raw[i - 2] == 0.toByte()) }.toByteArray()
        private var position = 0
        fun bit(): Int { require(position < bytes.size * 8); return (u8(bytes, position / 8) shr (7 - position++ % 8)) and 1 }
        fun read(count: Int): Int { var value = 0; repeat(count) { value = (value shl 1) or bit() }; return value }
        fun skip(count: Int) { require(count >= 0 && count <= bytes.size * 8 - position); position += count }
        fun ue(): Int { var zeros = 0; while (bit() == 0) { require(++zeros <= 20) }; return (1 shl zeros) - 1 + read(zeros) }
        fun se(): Int { val value = ue(); return if (value and 1 != 0) (value + 1) / 2 else -(value / 2) }
    }
    private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 255
    private fun u16(bytes: ByteArray, index: Int) = (u8(bytes, index) shl 8) or u8(bytes, index + 1)
    private fun u32(bytes: ByteArray, index: Int): Long = (u16(bytes, index).toLong() shl 16) or u16(bytes, index + 2).toLong()
    private fun type(bytes: ByteArray, index: Int) = String(bytes, index, 4, Charsets.ISO_8859_1)
}
