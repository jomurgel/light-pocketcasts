package com.jomurgel.pocketcasts.data.api

import com.jomurgel.pocketcasts.data.TokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns bearer tokens for authorized calls. Callers do:
 *
 *   var bearer = session.bearer() ?: throw AuthError.SessionExpired
 *   // ... call, and on a 401:
 *   bearer = session.refreshedBearer(bearer)  // then retry ONCE
 *
 * Refresh is single-flight: concurrent 401s share one refresh; a caller whose
 * stale token was already replaced gets the new token without a second
 * network call.
 */
class AuthSession(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
) {
    /** Wired by AppGraph to AuthRepository.onSessionExpired. */
    var onSessionExpired: (suspend () -> Unit)? = null

    private val refreshMutex = Mutex()

    suspend fun bearer(): String? = tokenStore.read()?.accessToken

    suspend fun refreshedBearer(staleBearer: String?): String = refreshMutex.withLock {
        val current = tokenStore.read() ?: throw expire()
        if (staleBearer != null && current.accessToken != staleBearer) {
            // Someone else already refreshed while we waited on the lock.
            return@withLock current.accessToken
        }
        val fresh = try {
            authApi.refresh(current.refreshToken)
        } catch (error: AuthError) {
            when (error) {
                is AuthError.Network, is AuthError.Server -> throw error // transient; do not sign out
                else -> throw expire()
            }
        }
        val updated = current.copy(
            accessToken = fresh.accessToken,
            refreshToken = fresh.refreshToken.ifBlank { current.refreshToken },
        )
        tokenStore.write(updated)
        updated.accessToken
    }

    private suspend fun expire(): AuthError.SessionExpired {
        onSessionExpired?.invoke()
        return AuthError.SessionExpired
    }
}
