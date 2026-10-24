// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.content.Context
import androidx.room.*

@Entity(tableName = "playback_progress")
data class PlaybackProgress(@PrimaryKey val key: String, val position: Long, val duration: Long, val updatedAt: Long)

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE `key` = :key") suspend fun get(key: String): PlaybackProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(progress: PlaybackProgress)
    @Query("DELETE FROM playback_progress WHERE `key` NOT IN (SELECT `key` FROM playback_progress ORDER BY updatedAt DESC LIMIT 1000)") suspend fun prune()
    @Query("DELETE FROM playback_progress") suspend fun clear()
    @Query("DELETE FROM playback_progress WHERE updatedAt >= :since") suspend fun clearSince(since: Long)
}

@Database(entities = [PlaybackProgress::class], version = 1, exportSchema = true)
abstract class PlaybackHistory : RoomDatabase() {
    abstract fun progress(): PlaybackProgressDao
    companion object {
        @Volatile private var instance: PlaybackHistory? = null
        fun get(context: Context): PlaybackHistory = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PlaybackHistory::class.java, "miru-playback.db").build().also { instance = it }
        }
    }
}

/** App-level clearing does not expose Room's database type across module boundaries. */
object PlaybackData {
    suspend fun clearProgress(context: Context, since: Long = 0L) = PlaybackHistory.get(context).progress().clearSince(since)
}
