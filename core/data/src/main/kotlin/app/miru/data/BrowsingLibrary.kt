// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One row per address; a revisit refreshes its time, title and count. */
data class HistoryEntry(val url: String, val title: String, val lastVisited: Long, val visitCount: Int = 1)

const val MOBILE_BOOKMARKS_FOLDER_ID = 1L

data class BookmarkFolder(val id: Long, val parentId: Long?, val title: String)

data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val created: Long,
    val folderId: Long = MOBILE_BOOKMARKS_FOLDER_ID,
)

/**
 * Local history and bookmarks. Private tabs never reach it: Gecko does not call the history delegate for
 * private sessions, and callers must not record them either. Nothing here is synced or uploaded.
 */
class BrowsingLibrary(context: Context, name: String? = "library.db") : Closeable {
    private val helper = LibraryDatabase(context.applicationContext, name)
    private val version = MutableStateFlow(0)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    val history = History()
    val bookmarks = Bookmarks()

    override fun close() = helper.close()

    private suspend fun <T> read(block: SQLiteDatabase.() -> T): T = withContext(Dispatchers.IO) { db.block() }
    private suspend fun write(block: SQLiteDatabase.() -> Unit) = mutate(block)

    private suspend fun <T> mutate(block: SQLiteDatabase.() -> T): T = withContext(Dispatchers.IO) {
        db.block().also { version.update { current -> current + 1 } }
    }

    inner class History {
        /** Recent first; a blank query lists everything. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun observe(query: String = "", limit: Int = 500): Flow<List<HistoryEntry>> =
            version.mapLatest { search(query, limit) }.flowOn(Dispatchers.IO)

        suspend fun search(query: String, limit: Int): List<HistoryEntry> = read {
            val (where, args) = matching(query)
            query("history", null, where, args, null, null, "last_visited DESC", limit.toString()).use { it.historyEntries() }
        }

        /** Address bar matches: prefix host matches rank before frequent and recent pages. */
        suspend fun suggest(query: String, limit: Int = 3): List<HistoryEntry> = read {
            val (where, args) = matching(query)
            val prefix = query.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").likeEscaped() + "%"
            rawQuery(
                "SELECT * FROM history WHERE $where ORDER BY " +
                    "(url LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\') DESC, visit_count DESC, last_visited DESC LIMIT $limit",
                args + arrayOf("https://$prefix", "https://www.$prefix", "http://$prefix"),
            ).use { it.historyEntries() }
        }

        // UPSERT needs SQLite 3.24 (API 30); minSdk 26 ships 3.18.
        suspend fun recordVisit(url: String, now: Long = System.currentTimeMillis()) = write {
            beginTransaction()
            try {
                execSQL("UPDATE history SET last_visited = ?, visit_count = visit_count + 1 WHERE url = ?", arrayOf<Any>(now, url))
                insertWithOnConflict("history", null, ContentValues().apply {
                    put("url", url); put("last_visited", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                setTransactionSuccessful()
            } finally { endTransaction() }
        }

        suspend fun updateTitle(url: String, title: String) = write {
            update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
        }

        suspend fun visited(urls: List<String>): List<Boolean> = read {
            val found = mutableSetOf<String>()
            urls.chunked(500).forEach { batch ->
                query("history", arrayOf("url"), "url IN (${batch.joinToString { "?" }})", batch.toTypedArray(), null, null, null).use {
                    while (it.moveToNext()) found += it.getString(0)
                }
            }
            urls.map { it in found }
        }

        suspend fun allUrls(limit: Int = 5_000): List<String> = read {
            query("history", arrayOf("url"), null, null, null, null, "last_visited DESC", limit.toString()).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }
        }

        suspend fun delete(url: String) = write { delete("history", "url = ?", arrayOf(url)) }

        /** Reinserts entries removed by [delete] or [clear] for undo. */
        suspend fun restore(entries: List<HistoryEntry>) = write {
            beginTransaction()
            try {
                entries.forEach { entry ->
                    insertWithOnConflict("history", null, ContentValues().apply {
                        put("url", entry.url); put("title", entry.title)
                        put("last_visited", entry.lastVisited); put("visit_count", entry.visitCount)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                setTransactionSuccessful()
            } finally { endTransaction() }
        }

        /** Removes visits at or after [since] (0 clears everything) and returns them for undo. */
        suspend fun clear(since: Long = 0L): List<HistoryEntry> {
            val removed = read {
                query("history", null, "last_visited >= ?", arrayOf(since.toString()), null, null, null).use { it.historyEntries() }
            }
            write { delete("history", "last_visited >= ?", arrayOf(since.toString())) }
            return removed
        }
    }

    inner class Bookmarks {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun observe(query: String = "", folderId: Long? = null): Flow<List<Bookmark>> =
            version.mapLatest { search(query, folderId = folderId) }.flowOn(Dispatchers.IO)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun observeFolders(): Flow<List<BookmarkFolder>> = version.mapLatest { folders() }.flowOn(Dispatchers.IO)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun isBookmarked(url: String): Flow<Boolean> = version.mapLatest { find(url) != null }.flowOn(Dispatchers.IO)

        suspend fun search(query: String, limit: Int = 1_000, folderId: Long? = null): List<Bookmark> = read {
            val (match, args) = matching(query)
            val where = if (folderId == null) match else "$match AND folder_id = ?"
            val bindings = if (folderId == null) args else args + folderId.toString()
            query("bookmarks", null, where, bindings, null, null, "created DESC, id DESC", limit.toString()).use { it.bookmarks() }
        }

        suspend fun find(url: String): Bookmark? = read {
            query("bookmarks", null, "url = ?", arrayOf(url), null, null, null).use { it.bookmarks().firstOrNull() }
        }

        suspend fun add(url: String, title: String, created: Long = System.currentTimeMillis()) = write {
            insertWithOnConflict("bookmarks", null, ContentValues().apply {
                put("url", url); put("title", title); put("created", created)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }

        suspend fun update(id: Long, title: String, url: String, folderId: Long? = null) = write {
            transaction {
                updateWithOnConflict("bookmarks", ContentValues().apply {
                    put("title", title)
                    put("url", url)
                    if (folderId != null) put("folder_id", existingBookmarkFolder(folderId))
                }, "id = ?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_IGNORE)
            }
        }

        suspend fun remove(url: String) = write { delete("bookmarks", "url = ?", arrayOf(url)) }

        /** Undo keeps the original location, or uses the mobile folder if it was removed. */
        suspend fun restore(bookmark: Bookmark) = write {
            transaction {
                insertWithOnConflict("bookmarks", null, ContentValues().apply {
                    put("url", bookmark.url)
                    put("title", bookmark.title)
                    put("created", bookmark.created)
                    put("folder_id", existingBookmarkFolder(bookmark.folderId))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }

        suspend fun folders(): List<BookmarkFolder> = read {
            query("bookmark_folders", null, null, null, null, null, "title COLLATE NOCASE, id").use { cursor ->
                val id = cursor.getColumnIndexOrThrow("id")
                val parent = cursor.getColumnIndexOrThrow("parent_id")
                val title = cursor.getColumnIndexOrThrow("title")
                buildList {
                    while (cursor.moveToNext()) {
                        val parentId = if (cursor.isNull(parent)) null else cursor.getLong(parent)
                        add(BookmarkFolder(cursor.getLong(id), parentId, cursor.getString(title)))
                    }
                }
            }
        }

        suspend fun createFolder(title: String, parentId: Long = MOBILE_BOOKMARKS_FOLDER_ID): Long = mutate {
            transaction {
                insertOrThrow("bookmark_folders", null, ContentValues().apply {
                    put("title", checkedFolderTitle(title))
                    put("parent_id", existingBookmarkFolder(parentId))
                })
            }
        }

        suspend fun renameFolder(id: Long, title: String) = write {
            if (id == MOBILE_BOOKMARKS_FOLDER_ID) return@write
            update("bookmark_folders", ContentValues().apply { put("title", checkedFolderTitle(title)) }, "id = ?", arrayOf(id.toString()))
        }

        suspend fun moveFolder(id: Long, parentId: Long) = write {
            if (id == MOBILE_BOOKMARKS_FOLDER_ID) return@write
            execSQL(
                """WITH RECURSIVE descendants(id) AS (
                    SELECT id FROM bookmark_folders WHERE id = ?
                    UNION ALL SELECT f.id FROM bookmark_folders f JOIN descendants d ON f.parent_id = d.id
                ) UPDATE bookmark_folders SET parent_id = ? WHERE id = ?
                    AND EXISTS (SELECT 1 FROM bookmark_folders WHERE id = ?)
                    AND NOT EXISTS (SELECT 1 FROM descendants WHERE id = ?)""".trimIndent(),
                arrayOf<Any>(id, parentId, id, parentId, parentId),
            )
        }

        /** Removing a folder promotes its contents to its parent in a single transaction. */
        suspend fun removeFolder(id: Long) = write {
            if (id == MOBILE_BOOKMARKS_FOLDER_ID) return@write
            transaction {
                val key = arrayOf(id.toString())
                val parentId = query("bookmark_folders", arrayOf("parent_id"), "id = ?", key, null, null, null).use {
                    if (it.moveToFirst()) it.getLong(0) else return@transaction
                }
                update("bookmarks", ContentValues().apply { put("folder_id", parentId) }, "folder_id = ?", key)
                update("bookmark_folders", ContentValues().apply { put("parent_id", parentId) }, "parent_id = ?", key)
                delete("bookmark_folders", "id = ?", key)
            }
        }

        private fun checkedFolderTitle(title: String): String = title.trim().also { require(it.isNotEmpty()) }

        private fun SQLiteDatabase.existingBookmarkFolder(id: Long): Long =
            query("bookmark_folders", arrayOf("id"), "id = ?", arrayOf(id.toString()), null, null, null).use {
                if (it.moveToFirst()) id else MOBILE_BOOKMARKS_FOLDER_ID
            }
    }

    private fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            return block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    private fun matching(query: String): Pair<String, Array<String>> {
        val value = query.trim()
        if (value.isEmpty()) return "1 = 1" to emptyArray()
        val pattern = "%${value.likeEscaped()}%"
        return "(url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\')" to arrayOf(pattern, pattern)
    }

    private fun String.likeEscaped() = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun Cursor.historyEntries(): List<HistoryEntry> = buildList {
        val url = getColumnIndexOrThrow("url"); val title = getColumnIndexOrThrow("title")
        val visited = getColumnIndexOrThrow("last_visited"); val count = getColumnIndexOrThrow("visit_count")
        while (moveToNext()) add(HistoryEntry(getString(url), getString(title).orEmpty(), getLong(visited), getInt(count)))
    }

    private fun Cursor.bookmarks(): List<Bookmark> = buildList {
        val id = getColumnIndexOrThrow("id"); val url = getColumnIndexOrThrow("url")
        val title = getColumnIndexOrThrow("title"); val created = getColumnIndexOrThrow("created")
        val folder = getColumnIndexOrThrow("folder_id")
        while (moveToNext()) add(Bookmark(getLong(id), getString(url), getString(title).orEmpty(), getLong(created), getLong(folder)))
    }
}

/** A null [name] keeps the database in memory (tests). */
private class LibraryDatabase(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        if (databaseName != null) db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', last_visited INTEGER NOT NULL, visit_count INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX history_last_visited ON history(last_visited)")
        createBookmarkTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_v1")
            createBookmarkTables(db)
            db.execSQL("INSERT INTO bookmarks(id, url, title, created) SELECT id, url, title, created FROM bookmarks_v1")
            db.execSQL("DROP TABLE bookmarks_v1")
        }
    }

    private fun createBookmarkTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE bookmark_folders(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            parent_id INTEGER REFERENCES bookmark_folders(id),
            title TEXT NOT NULL,
            CHECK ((id = $MOBILE_BOOKMARKS_FOLDER_ID) = (parent_id IS NULL)))""".trimIndent())
        db.execSQL("INSERT INTO bookmark_folders(id, title) VALUES ($MOBILE_BOOKMARKS_FOLDER_ID, '')")
        db.execSQL("CREATE INDEX bookmark_folders_parent ON bookmark_folders(parent_id)")
        db.execSQL("""CREATE TABLE bookmarks(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT NOT NULL UNIQUE,
            title TEXT NOT NULL DEFAULT '',
            created INTEGER NOT NULL,
            folder_id INTEGER NOT NULL DEFAULT $MOBILE_BOOKMARKS_FOLDER_ID REFERENCES bookmark_folders(id))""".trimIndent())
        db.execSQL("CREATE INDEX bookmarks_folder ON bookmarks(folder_id)")
    }
}
