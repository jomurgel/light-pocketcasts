package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.db.EpisodeDao
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.db.PendingEpisodeStateDao
import com.jomurgel.pocketcasts.data.db.PendingEpisodeStateEntity
import com.jomurgel.pocketcasts.data.db.PodcastDao
import com.jomurgel.pocketcasts.data.db.PodcastEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakePodcastDao : PodcastDao {
    val store = MutableStateFlow<Map<String, PodcastEntity>>(emptyMap())

    override fun observeAll(): Flow<List<PodcastEntity>> =
        store.map { it.values.sortedBy { p -> p.title.lowercase() } }

    override suspend fun get(uuid: String): PodcastEntity? = store.value[uuid]

    override suspend fun insertIgnore(podcasts: List<PodcastEntity>) {
        val current = store.value.toMutableMap()
        podcasts.forEach { current.putIfAbsent(it.uuid, it) }
        store.value = current
    }

    override suspend fun updateCatalog(
        uuid: String,
        title: String,
        author: String,
        episodeCount: Int,
        latestEpisodeAt: Long?,
        updatedAt: Long,
    ) {
        val existing = store.value[uuid] ?: return
        store.value = store.value + (
            uuid to existing.copy(
                title = title,
                author = author,
                episodeCount = episodeCount,
                latestEpisodeAt = latestEpisodeAt,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun deleteNotIn(keepUuids: List<String>) {
        store.value = store.value.filterKeys { it in keepUuids }
    }

    override suspend fun count(): Int = store.value.size

    override suspend fun allUuids(): List<String> = store.value.keys.toList()
}

class FakeEpisodeDao : EpisodeDao {
    val store = MutableStateFlow<Map<String, EpisodeEntity>>(emptyMap())

    /** Uuids with a pending local push; applyServerProgress skips these. */
    val pendingUuids = mutableSetOf<String>()

    /** Uuids with a pending flag push; applyServerFlags skips these. */
    val pendingFlagUuids = mutableSetOf<String>()

    override fun observeByPodcast(podcastUuid: String): Flow<List<EpisodeEntity>> =
        store.map { map ->
            map.values.filter { it.podcastUuid == podcastUuid }.sortedByDescending { it.publishedAt }
        }

    override fun observeInProgress(limit: Int): Flow<List<EpisodeEntity>> =
        store.map { map ->
            map.values.filter { it.playStatus == com.jomurgel.pocketcasts.data.db.PlayStatus.IN_PROGRESS }
                .sortedByDescending { it.positionUpdatedAt }
                .take(limit)
        }

    override suspend fun get(uuid: String): EpisodeEntity? = store.value[uuid]

    override suspend fun insertIgnore(episodes: List<EpisodeEntity>) {
        val current = store.value.toMutableMap()
        episodes.forEach { current.putIfAbsent(it.uuid, it) }
        store.value = current
    }

    override suspend fun updateCatalog(uuid: String, title: String, publishedAt: Long, durationSec: Long, audioUrl: String) {
        val existing = store.value[uuid] ?: return
        store.value = store.value + (
            uuid to existing.copy(
                title = title,
                publishedAt = publishedAt,
                durationSec = durationSec,
                audioUrl = audioUrl,
            )
            )
    }

    override suspend fun applyServerProgress(uuid: String, positionSec: Long, playStatus: Int, updatedAt: Long) {
        if (uuid in pendingUuids) return
        val existing = store.value[uuid] ?: return
        store.value = store.value + (
            uuid to existing.copy(
                positionSec = positionSec,
                playStatus = playStatus,
                positionUpdatedAt = updatedAt,
            )
            )
    }

    override suspend fun updateLocalProgress(uuid: String, positionSec: Long, playStatus: Int, updatedAt: Long) {
        val existing = store.value[uuid] ?: return
        store.value = store.value + (
            uuid to existing.copy(
                positionSec = positionSec,
                playStatus = playStatus,
                positionUpdatedAt = updatedAt,
            )
            )
    }

    override suspend fun downloadedFilePaths(podcastUuid: String): List<String> =
        store.value.values.filter { it.podcastUuid == podcastUuid }.mapNotNull { it.filePath }

    override fun observe(uuid: String): Flow<EpisodeEntity?> = store.map { it[uuid] }

    override suspend fun updateDownloadState(uuid: String, state: Int, progress: Int, filePath: String?) {
        val existing = store.value[uuid] ?: return
        store.value = store.value + (
            uuid to existing.copy(downloadState = state, downloadProgress = progress, filePath = filePath)
            )
    }

    override suspend fun resetInterruptedDownloads() {
        store.value = store.value.mapValues { (_, episode) ->
            if (episode.downloadState == com.jomurgel.pocketcasts.data.db.DownloadState.QUEUED ||
                episode.downloadState == com.jomurgel.pocketcasts.data.db.DownloadState.DOWNLOADING
            ) {
                episode.copy(
                    downloadState = com.jomurgel.pocketcasts.data.db.DownloadState.FAILED,
                    downloadProgress = 0,
                    filePath = null,
                )
            } else {
                episode
            }
        }
    }

    override suspend fun downloadedEpisodes(): List<EpisodeEntity> =
        store.value.values.filter { it.downloadState == com.jomurgel.pocketcasts.data.db.DownloadState.DONE }

    override suspend fun applyServerFlags(uuid: String, archived: Boolean, starred: Boolean) {
        if (uuid in pendingFlagUuids) return
        val existing = store.value[uuid] ?: return
        store.value = store.value + (uuid to existing.copy(isArchived = archived, starred = starred))
    }

    override suspend fun updateArchived(uuid: String, archived: Boolean) {
        val existing = store.value[uuid] ?: return
        store.value = store.value + (uuid to existing.copy(isArchived = archived))
    }

    override suspend fun updateStarred(uuid: String, starred: Boolean) {
        val existing = store.value[uuid] ?: return
        store.value = store.value + (uuid to existing.copy(starred = starred))
    }
}


class FakePendingEpisodeStateDao : PendingEpisodeStateDao {
    val rows = linkedMapOf<String, PendingEpisodeStateEntity>()

    override suspend fun upsert(entity: PendingEpisodeStateEntity) {
        rows[entity.episodeUuid] = entity
    }

    override suspend fun get(episodeUuid: String): PendingEpisodeStateEntity? = rows[episodeUuid]

    override suspend fun all(): List<PendingEpisodeStateEntity> = rows.values.toList()

    override suspend fun delete(episodeUuid: String, queuedAt: Long) {
        if (rows[episodeUuid]?.queuedAt == queuedAt) rows.remove(episodeUuid)
    }
}
