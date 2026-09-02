package com.jomurgel.pocketcasts.data.api

import com.jomurgel.pocketcasts.data.sync.SyncException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * Authorized calls to api.pocketcasts.com plus unauthenticated catalog reads
 * from cache.pocketcasts.com. Every authorized call retries exactly once
 * after a 401 by refreshing through the shared AuthSession.
 */
class PocketCastsSyncApi(
    private val client: HttpClient,
    private val session: AuthSession,
    private val apiBase: String = API_BASE_URL,
    private val cacheBase: String = CACHE_BASE_URL,
) {

    internal suspend fun podcastList(): PodcastListResponseDto =
        authorizedPost("$apiBase/user/podcast/list", PodcastListRequestDto())

    internal suspend fun inProgress(): InProgressResponseDto =
        authorizedPost("$apiBase/user/in_progress", EmptyRequestDto())

    internal suspend fun upNextSync(request: UpNextSyncRequestDto): UpNextSyncResponseDto =
        authorizedPost("$apiBase/up_next/sync", request)

    /** Push one episode's position/status. Response body is empty. */
    internal suspend fun pushEpisodeProgress(update: EpisodeSyncRequestDto) {
        authorizedPostNoContent("$apiBase/sync/update_episode", update)
    }

    internal suspend fun podcastCatalog(podcastUuid: String): CachePodcastResponseDto {
        val response = guard { client.get("$cacheBase/mobile/podcast/full/$podcastUuid") }
        if (!response.status.isSuccess()) {
            throw SyncException.Server(response.status.value)
        }
        return response.parse()
    }

    private suspend inline fun <reified B : Any, reified R> authorizedPost(url: String, requestBody: B): R {
        var bearer = session.bearer() ?: throw SyncException.SignedOut
        var attempt = 0
        while (true) {
            val response = guard {
                client.post(url) {
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }
            when {
                response.status.isSuccess() -> return response.parse()
                response.status == HttpStatusCode.Unauthorized && attempt == 0 -> {
                    attempt++
                    bearer = refreshBearer(bearer)
                }
                else -> throw SyncException.Server(response.status.value)
            }
        }
    }

    private suspend inline fun <reified B : Any> authorizedPostNoContent(url: String, requestBody: B) {
        var bearer = session.bearer() ?: throw SyncException.SignedOut
        var attempt = 0
        while (true) {
            val response = guard {
                client.post(url) {
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }
            when {
                response.status.isSuccess() -> return
                response.status == HttpStatusCode.Unauthorized && attempt == 0 -> {
                    attempt++
                    bearer = refreshBearer(bearer)
                }
                else -> throw SyncException.Server(response.status.value)
            }
        }
    }

    private suspend fun refreshBearer(stale: String): String = try {
        session.refreshedBearer(stale)
    } catch (error: AuthError) {
        throw when (error) {
            is AuthError.Network -> SyncException.Network(error)
            is AuthError.Server -> SyncException.Server(error.status, error.message)
            else -> SyncException.SignedOut
        }
    }

    private inline fun guard(block: () -> HttpResponse): HttpResponse = try {
        block()
    } catch (error: IOException) {
        throw SyncException.Network(error)
    }

    private suspend inline fun <reified R> HttpResponse.parse(): R = try {
        body()
    } catch (error: Exception) {
        throw SyncException.Server(status.value, "Unreadable response: ${error.message}")
    }
}
