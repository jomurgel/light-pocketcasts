package com.jomurgel.pocketcasts.data.sync

/**
 * Everything the app needs from a podcast sync service, service-agnostically.
 * PocketCastsSyncBackend is the v1 implementation; a gpodder-style or
 * local-only backend can replace it behind this interface.
 */
interface SyncBackend {
    suspend fun fetchSubscriptions(): List<RemotePodcast>
    suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode>
    suspend fun fetchInProgress(): List<RemoteEpisodeState>

    /**
     * Two-way queue sync: send the last-seen watermark plus local changes,
     * receive the authoritative queue when it differs from [serverModified].
     */
    suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult

    /** Per-episode user state (progress, archived, starred) for one podcast. */
    suspend fun fetchEpisodeUserStates(podcastUuid: String): List<RemoteEpisodeUserState>

    /** Push archive/starred changes; throws SyncException on failure. */
    suspend fun pushEpisodeFlags(changes: List<RemoteEpisodeFlagChange>)
}

data class RemoteEpisodeUserState(
    val uuid: String,
    val positionSec: Long,
    val playStatus: Int,
    val archived: Boolean,
    val starred: Boolean,
)

data class RemoteEpisodeFlagChange(
    val episodeUuid: String,
    val podcastUuid: String,
    val archived: Boolean?,
    val starred: Boolean?,
    val modifiedAtMs: Long,
)

/** Queue actions, matching Pocket Casts change-log values. */
object QueueAction {
    const val PLAY_NOW = 1
    const val PLAY_NEXT = 2
    const val PLAY_LAST = 3
    const val REMOVE = 4
}

data class RemoteQueueChange(
    val action: Int,
    val modifiedAtMs: Long,
    val episodeUuid: String?,
    val title: String? = null,
    val audioUrl: String? = null,
    val podcastUuid: String? = null,
    val publishedIso: String? = null,
)

data class RemoteQueueEpisode(
    val uuid: String,
    val title: String,
    val audioUrl: String,
    val podcastUuid: String,
)

data class RemoteQueueResult(
    val serverModified: Long,
    /** Null when the server queue matches the sent watermark. */
    val episodes: List<RemoteQueueEpisode>?,
)

data class RemotePodcast(
    val uuid: String,
    val title: String,
    val author: String,
)

data class RemoteEpisode(
    val uuid: String,
    val podcastUuid: String,
    val title: String,
    val publishedAtMs: Long,
    val durationSec: Long,
    val audioUrl: String,
)

data class RemoteEpisodeState(
    val uuid: String,
    val podcastUuid: String,
    val positionSec: Long,
    val playStatus: Int,
)

sealed class SyncException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : SyncException(cause.message, cause)
    class Server(val status: Int, message: String? = null) : SyncException(message)
    data object SignedOut : SyncException() {
        private fun readResolve(): Any = SignedOut
    }
}
