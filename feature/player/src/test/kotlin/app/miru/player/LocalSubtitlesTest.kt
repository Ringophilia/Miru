// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class LocalSubtitlesTest {
    @Test fun srtSupportsMultilineAndOverlappingExclusiveEnds() {
        val document = parse("sample.srt", "1\r\n00:00:01,000 --> 00:00:03,000\r\n<i>First</i>\r\nline &amp; next\r\n\r\n2\r\n00:00:02,000 --> 00:00:04,000\r\nSecond")
        assertEquals("", document.textAt(999))
        assertEquals("First\nline & next", document.textAt(1000))
        assertEquals("First\nline & next\nSecond", document.textAt(2000))
        assertEquals("Second", document.textAt(3000))
        assertEquals("", document.textAt(4000))
    }

    @Test fun vttSkipsMetadataAndAcceptsShortTimestampAndSettings() {
        val document = parse("sample.vtt", "WEBVTT\n\nSTYLE\n::cue { color: red; }\n\nNOTE an ignored cue\n00:00.000 --> 00:10.000\nNo\n\nidentifier\n00:01.250 --> 00:02.500 align:center\n<v Speaker>Hello</v>")
        assertEquals(1, document.cues.size)
        assertEquals("Hello", document.textAt(1250))
    }

    @Test fun assRespectsFormatAndPreservesCommaAndNewline() {
        val document = parse("sample.ass", "[Script Info]\nTitle: Test\n[Events]\nFormat: Start, End, Style, Text\nDialogue: 0:00:01.50,0:00:03.00,Default,{\\b1}Hello, world{\\b0}\\Nsecond\\hline\nComment: 0:00:00.00,0:00:05.00,Default,Not shown")
        assertEquals("Hello, world\nsecond line", document.textAt(1500))
        assertEquals("", document.textAt(3000))
    }

    @Test fun utf16BomIsDecodedWithoutDiskFiles() {
        val data = "\uFEFF1\n00:00:00,000 --> 00:00:01,000\n字幕".toByteArray(Charsets.UTF_16LE)
        assertEquals("字幕", LocalSubtitles.parse("utf16.srt", data).textAt(0))
    }

    @Test fun invalidAndReversedTimesDoNotBecomeVisible() {
        val document = parse("sample.srt", "1\n00:90:00,000 --> 02:00:00,000\nBad\n\n2\n00:00:02,000 --> 00:00:01,000\nReverse\n\n3\n00:00:01,000 --> 00:00:02,000\nValid")
        assertEquals(1, document.cues.size)
        assertEquals("Valid", document.textAt(1000))
    }

    @Test fun rejectsOversizedOrUnsupportedSubtitle() {
        assertThrows(IllegalArgumentException::class.java) { LocalSubtitles.parse("large.srt", ByteArray(LocalSubtitles.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { parse("invalid.srt", "Not a subtitle") }
    }

    private fun parse(name: String, text: String) = LocalSubtitles.parse(name, text.toByteArray())
}
