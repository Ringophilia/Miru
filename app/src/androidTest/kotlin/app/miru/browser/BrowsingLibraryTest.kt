// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.BrowsingLibrary
import app.miru.data.MOBILE_BOOKMARKS_FOLDER_ID
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated test databases; the app's library.db is untouched. */
@RunWith(AndroidJUnit4::class)
class BrowsingLibraryTest {
    private val library = BrowsingLibrary(InstrumentationRegistry.getInstrumentation().targetContext, name = null)

    @After fun closeLibrary() = library.close()

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

    @Test fun versionOneLibraryKeepsBookmarksAndHistoryAfterUpgrade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "library-upgrade-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val url = "https://previous.test/article"
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', last_visited INTEGER NOT NULL, visit_count INTEGER NOT NULL DEFAULT 1)")
                old.execSQL("CREATE INDEX history_last_visited ON history(last_visited)")
                old.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
                old.execSQL("INSERT INTO history VALUES (?, 'Previous visit', 7000, 3)", arrayOf(url))
                old.execSQL("INSERT INTO bookmarks VALUES (31, ?, 'Previous bookmark', 5000)", arrayOf(url))
                old.version = 1
            }
            BrowsingLibrary(context, name).use { upgraded ->
                assertEquals(MOBILE_BOOKMARKS_FOLDER_ID, upgraded.bookmarks.folders().single().id)
                val bookmark = upgraded.bookmarks.search("", folderId = MOBILE_BOOKMARKS_FOLDER_ID).single()
                assertEquals(31L, bookmark.id)
                assertEquals(url, bookmark.url)
                assertEquals("Previous bookmark", bookmark.title)
                assertEquals(5000L, bookmark.created)
                assertEquals(3, upgraded.history.search("Previous visit", 1).single().visitCount)
                val folder = upgraded.bookmarks.createFolder("Articles")
                upgraded.bookmarks.update(bookmark.id, bookmark.title, bookmark.url, folder)
                upgraded.bookmarks.add("https://new.test/", "New bookmark")
                assertEquals(MOBILE_BOOKMARKS_FOLDER_ID, upgraded.bookmarks.find("https://new.test/")?.folderId)
            }
            BrowsingLibrary(context, name).use { reopened ->
                val folder = reopened.bookmarks.folders().single { it.title == "Articles" }
                assertEquals(31L, reopened.bookmarks.search("", folderId = folder.id).single().id)
                assertEquals(url, reopened.bookmarks.search("Previous", limit = 3).single().url)
                assertEquals(2, reopened.bookmarks.search("").size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun folderEditsKeepContentsAndUndoKeepsTheLocation() = runBlocking {
        val root = MOBILE_BOOKMARKS_FOLDER_ID
        val parent = library.bookmarks.createFolder("Parent")
        val child = library.bookmarks.createFolder("Child", parent)
        val grandchild = library.bookmarks.createFolder("Grandchild", child)
        val destination = library.bookmarks.createFolder("Destination")
        val url = "https://folder.test/"
        library.bookmarks.add(url, "Folder bookmark")
        val original = library.bookmarks.find(url)!!
        library.bookmarks.update(original.id, original.title, original.url, child)
        val moved = library.bookmarks.find(url)!!
        assertEquals(child, moved.folderId)

        library.bookmarks.moveFolder(child, grandchild)
        library.bookmarks.moveFolder(child, child)
        assertEquals(parent, library.bookmarks.folders().single { it.id == child }.parentId)
        library.bookmarks.moveFolder(child, destination)
        library.bookmarks.renameFolder(child, "Renamed")
        val renamed = library.bookmarks.folders().single { it.id == child }
        assertEquals(destination, renamed.parentId)
        assertEquals("Renamed", renamed.title)

        library.bookmarks.remove(url)
        library.bookmarks.restore(moved)
        val restored = library.bookmarks.find(url)!!
        assertEquals(child, restored.folderId)
        assertEquals(original.created, restored.created)
        library.bookmarks.removeFolder(child)
        assertEquals(destination, library.bookmarks.find(url)?.folderId)
        assertEquals(destination, library.bookmarks.folders().single { it.id == grandchild }.parentId)

        library.bookmarks.remove(url)
        library.bookmarks.restore(moved)
        library.bookmarks.removeFolder(root)
        assertEquals(root, library.bookmarks.find(url)?.folderId)
        assertTrue(library.bookmarks.folders().any { it.id == root })
    }
}
