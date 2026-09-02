package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.DataStoreTokenStore
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.PocketCastsAuthApi
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import com.thelightphone.sdk.SealedLightContext

/**
 * Tiny hand-rolled service locator. Screens call [authRepository] from
 * createViewModel(); everything is memoized after the first call.
 */
object AppGraph {
    @Volatile
    private var auth: AuthRepository? = null

    fun authRepository(context: SealedLightContext): AuthRepository {
        return auth ?: synchronized(this) {
            auth ?: buildAuth(context).also { auth = it }
        }
    }

    private fun buildAuth(context: SealedLightContext): AuthRepository {
        val tokenStore = DataStoreTokenStore(context.dataStore)
        val authApi = PocketCastsAuthApi(pocketCastsHttpClient())
        val session = AuthSession(tokenStore, authApi)
        val repository = AuthRepository(tokenStore, authApi, session)
        session.onSessionExpired = { repository.onSessionExpired() }
        return repository
    }
}
