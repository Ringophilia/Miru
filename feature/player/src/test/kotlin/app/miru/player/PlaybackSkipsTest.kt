// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSkipsTest {
    private val rule = PlaybackSkipRule(introSeconds = 10, outroSeconds = 5)

    @Test fun directoryRulesMatchWholeSegmentsWithoutQueryOrFragment() {
        val scopes = PlaybackSkipScope.forPage("https://Media.Example/show/a/episode1?token=private#play")
        assertEquals(listOf("/show/a/episode1", "/show/a/", "/show/", "/"), scopes.map { it.path })
        val directory = PlaybackSkipScope.suggested(scopes)
        assertEquals("/show/a/", directory.path)
        assertTrue(directory in PlaybackSkipScope.forPage("http://media.example:8080/show/a/episode2"))
        assertFalse(directory in PlaybackSkipScope.forPage("https://media.example/show/ab/episode2"))
        assertFalse(directory in PlaybackSkipScope.forPage("https://other.media.example/show/a/episode2"))
        assertTrue(PlaybackSkipScope.suggested(PlaybackSkipScope.forPage("https://media.example/film.html")).exact)
        assertTrue(PlaybackSkipScope.forPage("content://media/item").isEmpty())
        assertTrue(PlaybackSkipScope.forPage("https://user@media.example/show/a/episode2").isEmpty())
    }

    @Test fun introOnlySkipsOnceAndKeepsResumeBeyondTheIntro() {
        val tracker = PlaybackSkipTracker()
        assertEquals(PlaybackSkipSection.INTRO, tracker.next(rule, 3_000, 30_000))
        tracker.positionDiscontinuity(userSeek = true)
        assertNull(tracker.next(rule, 0, 30_000))
        assertNull(PlaybackSkipTracker().next(rule, 12_000, 30_000))
        assertNull(PlaybackSkipTracker().next(rule, 0, 15_000))
    }

    @Test fun creditsSkipOnlyOnNaturalCrossingAndRemainAccessibleAfterward() {
        val tracker = PlaybackSkipTracker()
        assertNull(tracker.next(rule, 17_000, 30_000))
        tracker.positionDiscontinuity(userSeek = true)
        assertNull(tracker.next(rule, 26_000, 30_000))
        tracker.positionDiscontinuity(userSeek = true)
        assertNull(tracker.next(rule, 24_500, 30_000))
        assertEquals(PlaybackSkipSection.OUTRO, tracker.next(rule, 25_001, 30_000))
        assertNull(tracker.next(rule, 25_400, 30_000))
    }
}
