package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.db.EpisodeDao
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
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

    override suspend fun upsertAll(podcasts: List<PodcastEntity>) {
        store.value = store.value + podcasts.associateBy { it.uuid }
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

    override suspend fun downloadedFilePaths(podcastUuid: String): List<String> =
        store.value.values.filter { it.podcastUuid == podcastUuid }.mapNotNull { it.filePath }
}
