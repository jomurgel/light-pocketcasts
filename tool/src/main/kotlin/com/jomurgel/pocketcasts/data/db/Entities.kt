package com.jomurgel.pocketcasts.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object PlayStatus {
    const val UNPLAYED = 1
    const val IN_PROGRESS = 2
    const val COMPLETE = 3
}

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val DOWNLOADING = 2
    const val DONE = 3
    const val FAILED = 4
}

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "author") val author: String = "",
    @ColumnInfo(name = "episode_count") val episodeCount: Int = 0,
    @ColumnInfo(name = "latest_episode_at") val latestEpisodeAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["podcast_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["podcast_uuid", "published_at"]),
        Index(value = ["play_status", "position_updated_at"]),
        Index(value = ["download_state"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "podcast_uuid") val podcastUuid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long,
    @ColumnInfo(name = "duration_sec") val durationSec: Long = 0,
    @ColumnInfo(name = "audio_url") val audioUrl: String,
    // playback state (synced)
    @ColumnInfo(name = "position_sec") val positionSec: Long = 0,
    @ColumnInfo(name = "play_status") val playStatus: Int = PlayStatus.UNPLAYED,
    @ColumnInfo(name = "position_updated_at") val positionUpdatedAt: Long = 0,
    // download state (local only)
    @ColumnInfo(name = "download_state") val downloadState: Int = DownloadState.NONE,
    @ColumnInfo(name = "download_progress") val downloadProgress: Int = 0,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
)

/** Outbox for progress pushes; PK-upsert keeps only the newest per episode. */
@Entity(tableName = "pending_progress")
data class PendingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "episode_uuid") val episodeUuid: String,
    @ColumnInfo(name = "podcast_uuid") val podcastUuid: String,
    @ColumnInfo(name = "position_sec") val positionSec: Long,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
)

/** Single-table key/value store for sync watermarks. */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
