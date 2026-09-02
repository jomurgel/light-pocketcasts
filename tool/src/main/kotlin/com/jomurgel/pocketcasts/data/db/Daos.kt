package com.jomurgel.pocketcasts.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Catalog writes must NEVER clobber local playback/download columns, so
 * refresh paths use insert-ignore + targeted UPDATE of catalog columns only.
 */
@Dao
interface PodcastDao {

    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE uuid = :uuid")
    suspend fun get(uuid: String): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(podcasts: List<PodcastEntity>)

    @Query("DELETE FROM podcasts WHERE uuid NOT IN (:keepUuids)")
    suspend fun deleteNotIn(keepUuids: List<String>)

    @Query("SELECT COUNT(*) FROM podcasts")
    suspend fun count(): Int

    @Query("SELECT uuid FROM podcasts")
    suspend fun allUuids(): List<String>
}

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes WHERE podcast_uuid = :podcastUuid ORDER BY published_at DESC")
    fun observeByPodcast(podcastUuid: String): Flow<List<EpisodeEntity>>

    @Query(
        "SELECT * FROM episodes WHERE play_status = ${PlayStatus.IN_PROGRESS} " +
            "ORDER BY position_updated_at DESC LIMIT :limit",
    )
    fun observeInProgress(limit: Int = 20): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE uuid = :uuid")
    suspend fun get(uuid: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>)

    @Query(
        "UPDATE episodes SET title = :title, published_at = :publishedAt, " +
            "duration_sec = :durationSec, audio_url = :audioUrl WHERE uuid = :uuid",
    )
    suspend fun updateCatalog(uuid: String, title: String, publishedAt: Long, durationSec: Long, audioUrl: String)

    @Transaction
    suspend fun upsertCatalog(episodes: List<EpisodeEntity>) {
        insertIgnore(episodes)
        episodes.forEach {
            updateCatalog(it.uuid, it.title, it.publishedAt, it.durationSec, it.audioUrl)
        }
    }

    /**
     * Server overlay: skipped entirely when the episode has a pending local
     * push (local wins until the outbox drains).
     */
    @Query(
        "UPDATE episodes SET position_sec = :positionSec, play_status = :playStatus, " +
            "position_updated_at = :updatedAt WHERE uuid = :uuid " +
            "AND uuid NOT IN (SELECT episode_uuid FROM pending_progress)",
    )
    suspend fun applyServerProgress(uuid: String, positionSec: Long, playStatus: Int, updatedAt: Long)

    @Query("SELECT file_path FROM episodes WHERE podcast_uuid = :podcastUuid AND file_path IS NOT NULL")
    suspend fun downloadedFilePaths(podcastUuid: String): List<String>

    @Query(
        "UPDATE episodes SET position_sec = :positionSec, play_status = :playStatus, " +
            "position_updated_at = :updatedAt WHERE uuid = :uuid",
    )
    suspend fun updateLocalProgress(uuid: String, positionSec: Long, playStatus: Int, updatedAt: Long)
}

@Dao
interface PendingProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingProgressEntity)

    @Query("SELECT * FROM pending_progress ORDER BY queued_at ASC")
    suspend fun all(): List<PendingProgressEntity>

    /** Delete only if the row hasn't been replaced by a newer update since. */
    @Query("DELETE FROM pending_progress WHERE episode_uuid = :episodeUuid AND queued_at = :queuedAt")
    suspend fun delete(episodeUuid: String, queuedAt: Long)
}

@Dao
interface SyncMetaDao {

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SyncMetaEntity)
}
