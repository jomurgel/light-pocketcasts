package com.jomurgel.pocketcasts.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class EmptyRequestDto

@Serializable
internal data class PodcastListRequestDto(
    @SerialName("v") val v: String = "1",
)

@Serializable
internal data class PodcastListResponseDto(
    @SerialName("podcasts") val podcasts: List<PodcastDto> = emptyList(),
)

@Serializable
internal data class PodcastDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("title") val title: String = "",
    @SerialName("author") val author: String = "",
)

@Serializable
internal data class InProgressResponseDto(
    @SerialName("episodes") val episodes: List<EpisodeStateDto> = emptyList(),
)

@Serializable
internal data class EpisodeStateDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("podcastUuid") val podcastUuid: String? = null,
    @SerialName("playedUpTo") val playedUpTo: Long = 0,
    @SerialName("playingStatus") val playingStatus: Int = 2,
    @SerialName("duration") val duration: Double = 0.0,
)

// --- cache.pocketcasts.com catalog ---

@Serializable
internal data class CachePodcastResponseDto(
    @SerialName("podcast") val podcast: CachePodcastDto,
)

@Serializable
internal data class CachePodcastDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("title") val title: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("episodes") val episodes: List<CacheEpisodeDto> = emptyList(),
)

@Serializable
internal data class CacheEpisodeDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("title") val title: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("duration") val duration: Double? = null,
    @SerialName("published") val published: String? = null,
)

// --- progress push (/sync/update_episode) ---

@Serializable
internal data class EpisodeSyncRequestDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("podcast") val podcast: String,
    @SerialName("position") val position: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("status") val status: Int,
)

// --- up next sync (/up_next/sync) ---

@Serializable
internal data class UpNextSyncRequestDto(
    @SerialName("deviceTime") val deviceTime: Long,
    @SerialName("version") val version: String = "2",
    @SerialName("upNext") val upNext: UpNextBodyDto,
)

@Serializable
internal data class UpNextBodyDto(
    @SerialName("serverModified") val serverModified: Long,
    @SerialName("changes") val changes: List<UpNextChangeDto>,
)

@Serializable
internal data class UpNextChangeDto(
    @SerialName("action") val action: Int,
    @SerialName("modified") val modified: Long,
    @SerialName("uuid") val uuid: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("published") val published: String? = null,
    @SerialName("podcast") val podcast: String? = null,
)

@Serializable
internal data class UpNextSyncResponseDto(
    @SerialName("serverModified") val serverModified: Long = 0,
    @SerialName("episodes") val episodes: List<QueueEpisodeDto>? = null,
)

@Serializable
internal data class QueueEpisodeDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("title") val title: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("podcast") val podcast: String? = null,
    @SerialName("published") val published: String? = null,
)

// --- record sync push (/user/sync/update, proto3 JSON mapping) ---

@Serializable
internal data class SyncUpdateRequestDto(
    @SerialName("deviceUtcTimeMs") val deviceUtcTimeMs: Long,
    @SerialName("lastModified") val lastModified: Long = 0,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("records") val records: List<RecordDto>,
)

@Serializable
internal data class RecordDto(
    @SerialName("episode") val episode: EpisodeRecordDto,
)

@Serializable
internal data class EpisodeRecordDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("podcastUuid") val podcastUuid: String,
    @SerialName("isDeleted") val isDeleted: Boolean? = null,
    @SerialName("isDeletedModified") val isDeletedModified: Long? = null,
    @SerialName("starred") val starred: Boolean? = null,
    @SerialName("starredModified") val starredModified: Long? = null,
)

// --- per-podcast user state (/user/podcast/episodes) ---

@Serializable
internal data class PodcastEpisodesRequestDto(
    @SerialName("uuid") val uuid: String,
)

@Serializable
internal data class PodcastEpisodesResponseDto(
    @SerialName("episodes") val episodes: List<EpisodeUserStateDto> = emptyList(),
)

@Serializable
internal data class EpisodeUserStateDto(
    @SerialName("uuid") val uuid: String,
    @SerialName("playingStatus") val playingStatus: Int = 1,
    @SerialName("playedUpTo") val playedUpTo: Long = 0,
    @SerialName("isDeleted") val isDeleted: Boolean = false,
    @SerialName("starred") val starred: Boolean = false,
)
