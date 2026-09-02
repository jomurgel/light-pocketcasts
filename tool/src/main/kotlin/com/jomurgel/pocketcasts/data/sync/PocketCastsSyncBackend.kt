package com.jomurgel.pocketcasts.data.sync

import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.api.UpNextBodyDto
import com.jomurgel.pocketcasts.data.api.UpNextChangeDto
import com.jomurgel.pocketcasts.data.api.UpNextSyncRequestDto
import com.jomurgel.pocketcasts.data.db.PlayStatus
import java.time.Instant
import java.time.OffsetDateTime

/** Maps Pocket Casts wire types to the backend-neutral domain types. */
class PocketCastsSyncBackend(
    private val api: PocketCastsSyncApi,
    private val now: () -> Long = { System.currentTimeMillis() },
) : SyncBackend {

    override suspend fun fetchSubscriptions(): List<RemotePodcast> =
        api.podcastList().podcasts.map {
            RemotePodcast(uuid = it.uuid, title = it.title, author = it.author)
        }

    override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> {
        val podcast = api.podcastCatalog(podcastUuid).podcast
        return podcast.episodes.mapNotNull { episode ->
            val url = episode.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RemoteEpisode(
                uuid = episode.uuid,
                podcastUuid = podcastUuid,
                title = episode.title.orEmpty(),
                publishedAtMs = isoToEpochMs(episode.published),
                durationSec = (episode.duration ?: 0.0).toLong(),
                audioUrl = url,
            )
        }
    }

    override suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult {
        val response = api.upNextSync(
            UpNextSyncRequestDto(
                deviceTime = now(),
                upNext = UpNextBodyDto(
                    serverModified = serverModified,
                    changes = changes.map {
                        UpNextChangeDto(
                            action = it.action,
                            modified = it.modifiedAtMs,
                            uuid = it.episodeUuid,
                            title = it.title,
                            url = it.audioUrl,
                            podcast = it.podcastUuid,
                            published = it.publishedIso,
                        )
                    },
                ),
            ),
        )
        return RemoteQueueResult(
            serverModified = response.serverModified,
            episodes = response.episodes?.mapNotNull { episode ->
                val podcastUuid = episode.podcast ?: return@mapNotNull null
                RemoteQueueEpisode(
                    uuid = episode.uuid,
                    title = episode.title.orEmpty(),
                    audioUrl = episode.url.orEmpty(),
                    podcastUuid = podcastUuid,
                )
            },
        )
    }

    override suspend fun fetchInProgress(): List<RemoteEpisodeState> =
        api.inProgress().episodes.mapNotNull { state ->
            val podcastUuid = state.podcastUuid ?: return@mapNotNull null
            RemoteEpisodeState(
                uuid = state.uuid,
                podcastUuid = podcastUuid,
                positionSec = state.playedUpTo,
                playStatus = state.playingStatus.coerceIn(PlayStatus.UNPLAYED, PlayStatus.COMPLETE),
            )
        }
}

internal fun isoToEpochMs(value: String?): Long {
    if (value.isNullOrBlank()) return 0
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0
        }
    }
}
