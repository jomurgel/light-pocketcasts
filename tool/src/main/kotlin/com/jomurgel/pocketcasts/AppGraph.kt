package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.DataStoreTokenStore
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.PocketCastsAuthApi
import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import com.jomurgel.pocketcasts.data.db.PocketCastsDatabase
import com.jomurgel.pocketcasts.data.sync.PocketCastsSyncBackend
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

/**
 * Tiny hand-rolled service locator. Screens call [authRepository] from
 * createViewModel(); everything is memoized after the first call.
 */
object AppGraph {
    @Volatile
    private var auth: AuthRepository? = null

    @Volatile
    private var library: LibraryRepository? = null

    @Volatile
    private var database: PocketCastsDatabase? = null

    fun authRepository(context: SealedLightContext): AuthRepository {
        return auth ?: synchronized(this) {
            auth ?: buildAuth(context).also { auth = it }
        }
    }

    fun libraryRepository(context: SealedLightContext): LibraryRepository {
        return library ?: synchronized(this) {
            library ?: buildLibrary(context).also { library = it }
        }
    }

    private fun db(context: SealedLightContext): PocketCastsDatabase {
        return database ?: context.buildDatabase(PocketCastsDatabase::class.java, "pocketcasts.db")
            .also { database = it }
    }

    private fun buildAuth(context: SealedLightContext): AuthRepository {
        val tokenStore = DataStoreTokenStore(context.dataStore)
        val authApi = PocketCastsAuthApi(pocketCastsHttpClient())
        val session = AuthSession(tokenStore, authApi)
        val repository = AuthRepository(tokenStore, authApi, session)
        session.onSessionExpired = { repository.onSessionExpired() }
        return repository
    }

    private fun buildLibrary(context: SealedLightContext): LibraryRepository {
        val authRepository = authRepository(context)
        val syncApi = PocketCastsSyncApi(pocketCastsHttpClient(), authRepository.session)
        val db = db(context)
        return LibraryRepository(
            backend = PocketCastsSyncBackend(syncApi),
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
        )
    }
}
