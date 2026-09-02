package com.jomurgel.pocketcasts.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Failures the auth layer can produce; typed so UI can react per-case. */
sealed class AuthError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidCredentials(message: String? = null) : AuthError(message)
    class Network(cause: Throwable) : AuthError(cause.message, cause)
    class Server(val status: Int, message: String? = null) : AuthError(message)
    data object SessionExpired : AuthError() {
        private fun readResolve(): Any = SessionExpired
    }
}

@Serializable
internal data class LoginRequestDto(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("scope") val scope: String,
)

@Serializable
internal data class RefreshRequestDto(
    @SerialName("grant_type") val grantType: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("scope") val scope: String,
)

@Serializable
data class LoginTokenResponseDto(
    @SerialName("email") val email: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("isNew") val isNew: Boolean = false,
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String = "",
    @SerialName("tokenType") val tokenType: String = "",
    @SerialName("expiresIn") val expiresIn: Int = 0,
)

@Serializable
internal data class ApiErrorDto(
    @SerialName("errorMessage") val errorMessage: String? = null,
)
