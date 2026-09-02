package com.jomurgel.pocketcasts.data

import com.jomurgel.pocketcasts.data.db.EpisodeDao
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.db.PodcastDao
import com.jomurgel.pocketcasts.data.db.PodcastEntity
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RefreshState {
    data object Idle : RefreshState
    data object Refreshing : RefreshState
    data class Failed(val message: String) : RefreshState
}

/**
 * Offline-first library: Room is the source of truth, [refresh] reconciles it
 * with the sync backend. Catalog writes never touch playback/download columns
 * (see EpisodeDao), and server progress never overwrites a pending local push.
 */
class LibraryRepository(
    private val backend: SyncBackend,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    val podcasts: Flow<List<PodcastEntity>> = podcastDao.observeAll()
    val inProgress: Flow<List<EpisodeEntity>> = episodeDao.observeInProgress()

    fun episodesFor(podcastUuid: String): Flow<List<EpisodeEntity>> =
        episodeDao.observeByPodcast(podcastUuid)

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    /** Full pull: subscriptions, per-podcast catalogs, then progress overlay. */
    suspend fun refresh() {
        if (_refreshState.value == RefreshState.Refreshing) return
        _refreshState.value = RefreshState.Refreshing
        try {
            val timestamp = now()
            val subscriptions = backend.fetchSubscriptions()

            // Remove unsubscribed podcasts, deleting their downloads first.
            val keepUuids = subscriptions.map { it.uuid }
            val removedUuids = podcastDao.allUuids().filterNot { it in keepUuids }
            removedUuids.forEach { uuid ->
                episodeDao.downloadedFilePaths(uuid).forEach { path -> File(path).delete() }
            }
            if (keepUuids.isNotEmpty()) {
                podcastDao.deleteNotIn(keepUuids)
            }

            // Per-podcast catalogs. A single podcast failing shouldn't sink
            // the whole refresh; remember the failure, keep going.
            var failures = 0
            subscriptions.forEach { podcast ->
                val episodes = try {
                    backend.fetchEpisodes(podcast.uuid)
                } catch (error: SyncException) {
                    if (error is SyncException.SignedOut) throw error
                    failures++
                    return@forEach
                }
                podcastDao.upsertAll(
                    listOf(
                        PodcastEntity(
                            uuid = podcast.uuid,
                            title = podcast.title,
                            author = podcast.author,
                            episodeCount = episodes.size,
                            latestEpisodeAt = episodes.maxOfOrNull { it.publishedAtMs },
                            updatedAt = timestamp,
                        ),
                    ),
                )
                episodeDao.upsertCatalog(
                    episodes.map {
                        EpisodeEntity(
                            uuid = it.uuid,
                            podcastUuid = it.podcastUuid,
                            title = it.title,
                            publishedAt = it.publishedAtMs,
                            durationSec = it.durationSec,
                            audioUrl = it.audioUrl,
                        )
                    },
                )
            }

            // Server progress overlay (skips episodes with pending local pushes).
            backend.fetchInProgress().forEach { state ->
                episodeDao.applyServerProgress(
                    uuid = state.uuid,
                    positionSec = state.positionSec,
                    playStatus = state.playStatus,
                    updatedAt = timestamp,
                )
            }

            _refreshState.value = if (failures > 0) {
                RefreshState.Failed("$failures show(s) failed to refresh")
            } else {
                RefreshState.Idle
            }
        } catch (error: SyncException) {
            _refreshState.value = RefreshState.Failed(error.toUserMessage())
        }
    }

    fun clearRefreshError() {
        if (_refreshState.value is RefreshState.Failed) {
            _refreshState.value = RefreshState.Idle
        }
    }
}

private fun SyncException.toUserMessage(): String = when (this) {
    is SyncException.Network -> "No connection. Showing saved library."
    is SyncException.Server -> "Pocket Casts is unavailable ($status). Showing saved library."
    is SyncException.SignedOut -> "Session expired. Sign in again."
}
