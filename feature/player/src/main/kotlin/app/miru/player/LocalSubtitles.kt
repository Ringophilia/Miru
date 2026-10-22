// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

data class LocalSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** In-memory only; neither a content URI nor subtitle contents are persisted. */
class LocalSubtitleDocument(val name: String, cues: List<LocalSubtitleCue>) {
    val cues = cues.sortedBy { it.startMs }
    private val maximumEnd = LongArray(this.cues.size).also { values ->
        var maximum = 0L
        this.cues.forEachIndexed { index, cue -> maximum = maxOf(maximum, cue.endMs); values[index] = maximum }
    }

    fun textAt(positionMs: Long): String {
        var low = 0
        var high = cues.size
        while(low < high) {
            val middle = (low + high) ushr 1
            if(cues[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        val active = ArrayList<String>()
        var index = low - 1
        while(index >= 0 && maximumEnd[index] > positionMs && active.size < 32) {
            val cue = cues[index--]
            if(positionMs < cue.endMs) active.add(cue.text)
        }
        return active.asReversed().distinct().joinToString("\n").take(8_192)
    }
}

object LocalSubtitles {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_CUES = 100_000
    private val blocks = Regex("\n[ \\t]*\n")
    private val timing = Regex("^\\s*(\\S+)\\s*-->\\s*(\\S+).*$")
    private val stamp = Regex("^(?:(\\d{1,6}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})$")

    fun parse(name: String, bytes: ByteArray): LocalSubtitleDocument {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Subtitle size is unsupported" }
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8)
        }.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        require('\u0000' !in text) { "Unsupported subtitle encoding" }
        val cues = if(text.contains("[Events]", ignoreCase = true) || name.substringAfterLast('.').lowercase() in setOf("ass", "ssa")) parseAss(text) else parseTimedText(text)
        require(cues.isNotEmpty()) { "No supported subtitle cues" }
        return LocalSubtitleDocument(name.take(200), cues)
    }

    private fun parseTimedText(text: String): List<LocalSubtitleCue> {
        val output = ArrayList<LocalSubtitleCue>()
        for(block in text.split(blocks)) {
            val lines = block.lines()
            val first = lines.firstOrNull()?.trim().orEmpty()
            if(first.startsWith("NOTE") || first == "STYLE" || first == "REGION") continue
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if(timeIndex < 0) continue
            val match = timing.matchEntire(lines[timeIndex]) ?: continue
            val start = timestamp(match.groupValues[1]) ?: continue
            val end = timestamp(match.groupValues[2]) ?: continue
            val content = plain(lines.drop(timeIndex + 1).joinToString("\n"))
            append(output, start, end, content)
        }
        return output
    }

    private fun parseAss(text: String): List<LocalSubtitleCue> {
        val output = ArrayList<LocalSubtitleCue>()
        var events = false
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        for(line in text.lineSequence()) {
            val trimmed = line.trim()
            if(trimmed.startsWith("[")) { events = trimmed.equals("[Events]", ignoreCase = true); continue }
            if(!events) continue
            if(trimmed.startsWith("Format:", ignoreCase = true)) {
                format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if(!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            // ASS requires Text to be last; that field may itself contain any number of commas.
            if(format.lastOrNull() != "text") continue
            val values = trimmed.substringAfter(':').split(',', limit = format.size)
            if(values.size != format.size) continue
            fun field(name: String) = format.indexOf(name).takeIf { it >= 0 }?.let { values[it].trim() }
            val start = field("start")?.let(::timestamp) ?: continue
            val end = field("end")?.let(::timestamp) ?: continue
            val content = plain(values.last().replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
            append(output, start, end, content)
        }
        return output
    }

    private fun append(output: MutableList<LocalSubtitleCue>, start: Long, end: Long, text: String) {
        if(end <= start || text.isBlank()) return
        require(output.size < MAX_CUES) { "Too many subtitle cues" }
        output.add(LocalSubtitleCue(start, end, text.take(8_192)))
    }

    private fun timestamp(value: String): Long? {
        val match = stamp.matchEntire(value) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if(minutes >= 60 || seconds >= 60) return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + match.groupValues[4].padEnd(3, '0').toLong()
    }

    private fun plain(text: String) = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").trim()
}
