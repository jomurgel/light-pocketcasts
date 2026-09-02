package com.jomurgel.pocketcasts.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * Scope string sent with login/refresh. "mobile" is what the official
 * Android app sends (SyncServiceManager.SCOPE_MOBILE).
 */
const val AUTH_SCOPE = "mobile"

interface AuthApi {
    suspend fun login(email: String, password: String): LoginTokenResponseDto
    suspend fun refresh(refreshToken: String): LoginTokenResponseDto
}

class PocketCastsAuthApi(
    private val client: HttpClient,
    private val baseUrl: String = API_BASE_URL,
    private val scope: String = AUTH_SCOPE,
) : AuthApi {

    override suspend fun login(email: String, password: String): LoginTokenResponseDto {
        val response = guardNetwork {
            client.post("$baseUrl/user/login_pocket_casts") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequestDto(email = email, password = password, scope = scope))
            }
        }
        return response.parseTokens()
    }

    override suspend fun refresh(refreshToken: String): LoginTokenResponseDto {
        val response = guardNetwork {
            client.post("$baseUrl/user/token") {
                contentType(ContentType.Application.Json)
                setBody(
                    RefreshRequestDto(
                        grantType = "refresh_token",
                        refreshToken = refreshToken,
                        scope = scope,
                    ),
                )
            }
        }
        return response.parseTokens()
    }

    private inline fun guardNetwork(block: () -> HttpResponse): HttpResponse {
        return try {
            block()
        } catch (error: IOException) {
            throw AuthError.Network(error)
        }
    }

    private suspend fun HttpResponse.parseTokens(): LoginTokenResponseDto = when {
        status.isSuccess() -> try {
            body()
        } catch (error: Exception) {
            throw AuthError.Server(status.value, "Unreadable response: ${error.message}")
        }
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            throw AuthError.InvalidCredentials(serverMessage())
        else -> throw AuthError.Server(status.value, serverMessage())
    }

    private suspend fun HttpResponse.serverMessage(): String? = try {
        pocketCastsJson.decodeFromString(ApiErrorDto.serializer(), bodyAsText()).errorMessage
    } catch (_: Exception) {
        null
    }
}
