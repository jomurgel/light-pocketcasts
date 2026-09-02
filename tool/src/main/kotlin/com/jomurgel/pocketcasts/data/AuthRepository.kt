package com.jomurgel.pocketcasts.data

import com.jomurgel.pocketcasts.data.api.AuthError
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.AuthApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthState {
    /** Persisted tokens not yet loaded. */
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val email: String) : AuthState
}

sealed interface SignInResult {
    data object Success : SignInResult
    data class Failure(val error: AuthError) : SignInResult
}

class AuthRepository(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
    val session: AuthSession,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** Load persisted tokens into state; safe to call repeatedly. */
    suspend fun restore() {
        if (_authState.value != AuthState.Unknown) return
        val tokens = tokenStore.read()
        _authState.value = if (tokens == null) {
            AuthState.SignedOut
        } else {
            AuthState.SignedIn(tokens.email)
        }
    }

    suspend fun signIn(email: String, password: String): SignInResult {
        return try {
            val response = authApi.login(email, password)
            tokenStore.write(
                AuthTokens(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    email = response.email.ifBlank { email },
                    accountUuid = response.uuid,
                ),
            )
            _authState.value = AuthState.SignedIn(response.email.ifBlank { email })
            SignInResult.Success
        } catch (error: AuthError) {
            SignInResult.Failure(error)
        }
    }

    suspend fun signOut() {
        tokenStore.clear()
        _authState.value = AuthState.SignedOut
    }

    /** Called by the session when a token refresh fails permanently. */
    suspend fun onSessionExpired() {
        signOut()
    }
}
