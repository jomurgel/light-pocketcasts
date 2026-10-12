package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.DataStoreTokenStore
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.PocketCastsAuthApi
import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import com.jomurgel.pocketcasts.data.db.PocketCastsDatabase
import com.jomurgel.pocketcasts.data.DownloadManager
import com.jomurgel.pocketcasts.data.EpisodeStateRepository
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
import kotlinx.coroutines.withContext
import java.io.File

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

    @Volatile
    private var downloads: DownloadManager? = null

    @Volatile
    private var episodeState: EpisodeStateRepository? = null

    // One connection pool and one sync API for the whole tool.
    private val httpClient by lazy { pocketCastsHttpClient() }

    @Volatile
    private var syncApiInstance: PocketCastsSyncApi? = null

    private fun syncApi(context: SealedLightContext): PocketCastsSyncApi {
        return syncApiInstance ?: synchronized(this) {
            syncApiInstance
                ?: PocketCastsSyncApi(httpClient, authRepository(context).session).also { syncApiInstance = it }
        }
    }

    private fun backend(context: SealedLightContext) = PocketCastsSyncBackend(syncApi(context))

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

    fun episodeStateRepository(context: SealedLightContext): EpisodeStateRepository {
        return episodeState ?: synchronized(this) {
            episodeState ?: run {
                EpisodeStateRepository(
                    episodeDao = db(context).episodeDao(),
                    pendingDao = db(context).pendingEpisodeStateDao(),
                    backend = backend(context),
                    schedulePush = { LightWork.enqueue(context, PUSH_PROGRESS_JOB_KEY) },
                ).also { episodeState = it }
            }
        }
    }

    fun downloadManager(context: SealedLightContext): DownloadManager {
        return downloads ?: synchronized(this) {
            downloads ?: DownloadManager(
                episodeDao = db(context).episodeDao(),
                client = httpClient,
                downloadsDir = File(context.filesDir, "episodes"),
                isOnline = { context.connectivity.currentStatus.isConnected },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { downloads = it }
        }
    }

    private fun buildUpNext(context: SealedLightContext): UpNextQueue {
        return UpNextQueue(db(context).syncMetaDao(), backend(context))
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
        val db = db(context)
        return ProgressRepository(
            episodeDao = db.episodeDao(),
            pendingProgressDao = db.pendingProgressDao(),
            syncApi = syncApi(context),
            schedulePush = { LightWork.enqueue(context, PUSH_PROGRESS_JOB_KEY) },
        )
    }

    private fun db(context: SealedLightContext): PocketCastsDatabase {
        return database ?: run {
            ensureSchemaVersion(context)
            context.buildDatabase(PocketCastsDatabase::class.java, "pocketcasts.db")
                .also { database = it }
        }
    }

    /**
     * Self-managed destructive migration: the SDK's buildDatabase exposes no
     * Room migration hooks, so on a schema bump we delete the database and let
     * the caches rebuild from the server. Local-only download files are swept
     * as orphans afterward.
     */
    private const val SCHEMA_VERSION = "2"

    private fun ensureSchemaVersion(context: SealedLightContext) {
        val marker = File(context.filesDir, "db_schema_version")
        val current = runCatching { marker.readText().trim() }.getOrNull()
        if (current == SCHEMA_VERSION) return
        val dbDir = File(context.filesDir.parentFile, "databases")
        listOf("pocketcasts.db", "pocketcasts.db-wal", "pocketcasts.db-shm").forEach {
            File(dbDir, it).delete()
        }
        marker.writeText(SCHEMA_VERSION)
    }

    private fun buildAuth(context: SealedLightContext): AuthRepository {
        val tokenStore = DataStoreTokenStore(context.dataStore)
        val authApi = PocketCastsAuthApi(httpClient)
        val session = AuthSession(tokenStore, authApi)
        val repository = AuthRepository(tokenStore, authApi, session) { clearAccountData(context) }
        session.onSessionExpired = { repository.onSessionExpired() }
        return repository
    }

    private fun buildLibrary(context: SealedLightContext): LibraryRepository {
        val db = db(context)
        return LibraryRepository(
            backend = backend(context),
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
        )
    }

    /**
     * Removes everything belonging to the signed-in account. Order matters:
     * playback stops before its files go, and the queue's in-memory copy is
     * dropped only once the tables behind it are empty.
     */
    private suspend fun clearAccountData(context: SealedLightContext) {
        withContext(Dispatchers.Main.immediate) { playbackController(context).reset() }
        downloadManager(context).clearAll()
        withContext(Dispatchers.IO) { db(context).clearAllTables() }
        upNextQueue(context).reset()
    }
}
