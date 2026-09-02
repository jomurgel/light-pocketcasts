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
}

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
