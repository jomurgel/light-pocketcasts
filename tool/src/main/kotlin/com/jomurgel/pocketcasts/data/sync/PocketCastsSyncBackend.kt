package com.jomurgel.pocketcasts.data.sync

import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.db.PlayStatus
import java.time.Instant
import java.time.OffsetDateTime

/** Maps Pocket Casts wire types to the backend-neutral domain types. */
class PocketCastsSyncBackend(private val api: PocketCastsSyncApi) : SyncBackend {

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
