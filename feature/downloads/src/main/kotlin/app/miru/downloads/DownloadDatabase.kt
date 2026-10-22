// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
internal data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val destUri: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: String = "queued",
    val error: String? = null,
    val sourcePage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val directoryUri: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptsRanges: Boolean = false,
    // Credentials and complete request URLs are never serialized into a headers blob.
    val requiresAuth: Boolean = false,
    val referrer: String? = null,
    val userNamed: Boolean = false,
    val isPrivate: Boolean = false,
)
@Entity(tableName = "download_segments", primaryKeys = ["downloadId", "index"], foreignKeys = [ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE)], indices = [Index("downloadId")])
internal data class DownloadSegment(@ColumnInfo(name = "downloadId") val downloadId: String, val index: Int, val start: Long, val end: Long, val downloaded: Long = 0)
@Dao
internal interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE id=:id") suspend fun get(id: String): DownloadEntity?
    @Query("SELECT * FROM downloads WHERE status IN ('queued','running','waiting_network') ORDER BY createdAt") suspend fun pending(): List<DownloadEntity>
    @Query("SELECT * FROM download_segments WHERE downloadId=:id ORDER BY `index`") suspend fun segments(id: String): List<DownloadSegment>
    @Upsert suspend fun put(entity: DownloadEntity)
    @Upsert suspend fun putSegments(segments: List<DownloadSegment>)
    @Query("UPDATE download_segments SET downloaded=:bytes WHERE downloadId=:id AND `index`=:index") suspend fun segmentProgress(id: String, index: Int, bytes: Long)
    @Query("UPDATE downloads SET downloadedBytes=(SELECT COALESCE(SUM(downloaded),0) FROM download_segments WHERE downloadId=:id) WHERE id=:id") suspend fun sumProgress(id: String)
    @Query("UPDATE downloads SET status=:status,error=:error WHERE id=:id") suspend fun status(id: String, status: String, error: String? = null)
    @Query("UPDATE downloads SET fileName=:name,userNamed=1 WHERE id=:id") suspend fun rename(id:String,name:String)
    @Query("UPDATE downloads SET downloadedBytes=:bytes WHERE id=:id") suspend fun streamProgress(id:String,bytes:Long)
    @Query("DELETE FROM download_segments WHERE downloadId=:id") suspend fun clearSegments(id: String)
    @Query("DELETE FROM downloads WHERE id=:id") suspend fun delete(id: String)
    @Query("UPDATE downloads SET status='queued' WHERE status IN ('running','waiting_network')") suspend fun recover()
}
@Database(entities = [DownloadEntity::class, DownloadSegment::class], version = 1, exportSchema = true)
internal abstract class DownloadDatabase : RoomDatabase() { abstract fun downloads(): DownloadDao }
