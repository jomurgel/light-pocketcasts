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
