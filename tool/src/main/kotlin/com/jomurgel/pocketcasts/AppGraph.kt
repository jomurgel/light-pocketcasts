package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.DataStoreTokenStore
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.PocketCastsAuthApi
import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import com.jomurgel.pocketcasts.data.db.PocketCastsDatabase
import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.data.sync.PocketCastsSyncBackend
import com.jomurgel.pocketcasts.playback.PlaybackController
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    @Volatile
    private var progress: ProgressRepository? = null

    @Volatile
    private var playback: PlaybackController? = null

    @Volatile
    private var upNext: UpNextQueue? = null

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

    fun progressRepository(context: SealedLightContext): ProgressRepository {
        return progress ?: synchronized(this) {
            progress ?: buildProgress(context).also { progress = it }
        }
    }

    fun upNextQueue(context: SealedLightContext): UpNextQueue {
        return upNext ?: synchronized(this) {
            upNext ?: buildUpNext(context).also { upNext = it }
        }
    }

    private fun buildUpNext(context: SealedLightContext): UpNextQueue {
        val authRepository = authRepository(context)
        val syncApi = PocketCastsSyncApi(pocketCastsHttpClient(), authRepository.session)
        return UpNextQueue(db(context).syncMetaDao(), PocketCastsSyncBackend(syncApi))
    }

    fun playbackController(context: SealedLightContext): PlaybackController {
        return playback ?: synchronized(this) {
            playback ?: PlaybackController(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                progressRepository = progressRepository(context),
                upNextQueue = upNextQueue(context),
                episodeLookup = { uuid -> db(context).episodeDao().get(uuid) },
                podcastTitleLookup = { uuid -> db(context).podcastDao().get(uuid)?.title ?: "" },
            ).also { playback = it }
        }
    }

    private fun buildProgress(context: SealedLightContext): ProgressRepository {
        val authRepository = authRepository(context)
        val syncApi = PocketCastsSyncApi(pocketCastsHttpClient(), authRepository.session)
        val db = db(context)
        return ProgressRepository(
            episodeDao = db.episodeDao(),
            pendingProgressDao = db.pendingProgressDao(),
            syncApi = syncApi,
            schedulePush = { LightWork.enqueue(context, PUSH_PROGRESS_JOB_KEY) },
        )
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
