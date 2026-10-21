// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.BrowsingLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory database; the app's own library.db is untouched. */
@RunWith(AndroidJUnit4::class)
class BrowsingLibraryTest {
    private val library = BrowsingLibrary(InstrumentationRegistry.getInstrumentation().targetContext, name = null)

    @Test fun revisitUpdatesOneEntryAndSuggestionsPreferHostPrefix() = runBlocking {
        library.history.recordVisit("https://example.com/github-notes", now = 1_000)
        library.history.recordVisit("https://github.com/", now = 2_000)
        library.history.recordVisit("https://github.com/", now = 3_000)
        library.history.updateTitle("https://github.com/", "GitHub")
        val entries = library.history.search("", 10)
        assertEquals(2, entries.size)
        with(entries.first()) {
            assertEquals("https://github.com/", url); assertEquals("GitHub", title)
            assertEquals(3_000L, lastVisited); assertEquals(2, visitCount)
        }
        assertEquals("https://github.com/", library.history.suggest("git", 3).first().url)
        assertEquals(listOf(true, false), library.history.visited(listOf("https://github.com/", "https://unknown.test/")))
    }

    @Test fun deleteAndClearCanBeUndone() = runBlocking {
        library.history.recordVisit("https://a.test/", now = 1_000)
        library.history.recordVisit("https://b.test/", now = 5_000)
        val old = library.history.search("a.test", 1).single()
        library.history.delete(old.url)
        assertTrue(library.history.search("a.test", 1).isEmpty())
        library.history.restore(listOf(old))
        assertEquals(old, library.history.search("a.test", 1).single())
        val removed = library.history.clear(since = 2_000)
        assertEquals(listOf("https://b.test/"), removed.map { it.url })
        assertEquals(listOf("https://a.test/"), library.history.search("", 10).map { it.url })
        library.history.restore(removed)
        assertEquals(2, library.history.search("", 10).size)
    }

    @Test fun likeWildcardsInQueriesAreLiteral() = runBlocking {
        library.history.recordVisit("https://a.test/100%25", now = 1_000)
        library.history.recordVisit("https://b.test/plain", now = 2_000)
        assertEquals(listOf("https://a.test/100%25"), library.history.search("%", 10).map { it.url })
        assertTrue(library.history.search("_", 10).isEmpty())
    }

    @Test fun bookmarksToggleAndRestore() = runBlocking {
        library.bookmarks.add("https://miru.test/", "Miru")
        library.bookmarks.add("https://miru.test/", "Duplicate")
        assertTrue(library.bookmarks.isBookmarked("https://miru.test/").first())
        val saved = library.bookmarks.find("https://miru.test/")!!
        assertEquals("Miru", saved.title)
        library.bookmarks.remove(saved.url)
        assertFalse(library.bookmarks.isBookmarked(saved.url).first())
        library.bookmarks.restore(saved)
        assertEquals(listOf("Miru"), library.bookmarks.search("miru").map { it.title })
        library.bookmarks.update(library.bookmarks.find(saved.url)!!.id, "Renamed", "https://miru.test/new")
        assertEquals("https://miru.test/new", library.bookmarks.search("").single().url)
    }
}
