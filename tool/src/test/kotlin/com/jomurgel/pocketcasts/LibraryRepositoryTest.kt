package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.RefreshState
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.db.PlayStatus
import com.jomurgel.pocketcasts.data.db.PodcastEntity
import com.jomurgel.pocketcasts.data.sync.RemoteEpisode
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeState
import com.jomurgel.pocketcasts.data.sync.RemotePodcast
import com.jomurgel.pocketcasts.data.sync.RemoteQueueChange
import com.jomurgel.pocketcasts.data.sync.RemoteQueueResult
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeBackend(
    var subscriptions: List<RemotePodcast> = emptyList(),
    var episodes: Map<String, List<RemoteEpisode>> = emptyMap(),
    var inProgress: List<RemoteEpisodeState> = emptyList(),
    var subscriptionsError: SyncException? = null,
    var episodesErrors: Set<String> = emptySet(),
) : SyncBackend {

    override suspend fun fetchSubscriptions(): List<RemotePodcast> {
        subscriptionsError?.let { throw it }
        return subscriptions
    }

    override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> {
        if (podcastUuid in episodesErrors) throw SyncException.Server(500)
        return episodes[podcastUuid].orEmpty()
    }

    override suspend fun fetchInProgress(): List<RemoteEpisodeState> = inProgress

    override suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult =
        RemoteQueueResult(serverModified, null)

    override suspend fun fetchEpisodeUserStates(podcastUuid: String): List<com.jomurgel.pocketcasts.data.sync.RemoteEpisodeUserState> = emptyList()

    override suspend fun pushEpisodeFlags(changes: List<com.jomurgel.pocketcasts.data.sync.RemoteEpisodeFlagChange>) = Unit
}

private fun remoteEpisode(uuid: String, podcast: String, published: Long = 1000) =
    RemoteEpisode(uuid, podcast, "Episode $uuid", published, 1800, "https://cdn.test/$uuid.mp3")

class LibraryRepositoryTest {

    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()

    private fun repo(backend: SyncBackend) =
        LibraryRepository(backend, podcastDao, episodeDao, now = { 5000L })

    @Test
    fun `refresh populates podcasts and episodes`() = runTest {
        val backend = FakeBackend(
            subscriptions = listOf(RemotePodcast("p1", "Show One", "Author A")),
            episodes = mapOf("p1" to listOf(remoteEpisode("e1", "p1"), remoteEpisode("e2", "p1"))),
        )
        repo(backend).refresh()

        assertEquals(1, podcastDao.count())
        assertEquals("Show One", podcastDao.get("p1")?.title)
        assertEquals(2, podcastDao.get("p1")?.episodeCount)
        assertEquals(2, episodeDao.store.value.size)
    }

    @Test
    fun `unsubscribed podcast is removed and its downloads deleted`() = runTest {
        val file = File.createTempFile("episode", ".mp3")
        podcastDao.upsertAll(listOf(PodcastEntity("gone", "Old Show", updatedAt = 0)))
        episodeDao.insertIgnore(
            listOf(
                EpisodeEntity(
                    uuid = "old-e1", podcastUuid = "gone", title = "Old", publishedAt = 1,
                    audioUrl = "https://x", filePath = file.absolutePath,
                ),
            ),
        )
        val backend = FakeBackend(
            subscriptions = listOf(RemotePodcast("p1", "Show One", "")),
            episodes = mapOf("p1" to listOf(remoteEpisode("e1", "p1"))),
        )

        repo(backend).refresh()

        assertEquals(listOf("p1"), podcastDao.allUuids())
        assertFalse(file.exists())
    }

    @Test
    fun `catalog refresh preserves local playback state`() = runTest {
        episodeDao.insertIgnore(
            listOf(
                EpisodeEntity(
                    uuid = "e1", podcastUuid = "p1", title = "Old title", publishedAt = 1,
                    audioUrl = "https://old", positionSec = 900,
                    playStatus = PlayStatus.IN_PROGRESS, positionUpdatedAt = 42,
                ),
            ),
        )
        val backend = FakeBackend(
            subscriptions = listOf(RemotePodcast("p1", "Show One", "")),
            episodes = mapOf("p1" to listOf(remoteEpisode("e1", "p1", published = 777))),
        )

        repo(backend).refresh()

        val episode = episodeDao.get("e1")!!
        assertEquals("Episode e1", episode.title)   // catalog updated
        assertEquals(777, episode.publishedAt)
        assertEquals(900, episode.positionSec)      // local state preserved
        assertEquals(PlayStatus.IN_PROGRESS, episode.playStatus)
    }

    @Test
    fun `server progress overlays except for pending local pushes`() = runTest {
        val backend = FakeBackend(
            subscriptions = listOf(RemotePodcast("p1", "Show One", "")),
            episodes = mapOf("p1" to listOf(remoteEpisode("e1", "p1"), remoteEpisode("e2", "p1"))),
            inProgress = listOf(
                RemoteEpisodeState("e1", "p1", 600, PlayStatus.IN_PROGRESS),
                RemoteEpisodeState("e2", "p1", 300, PlayStatus.IN_PROGRESS),
            ),
        )
        episodeDao.pendingUuids.add("e2")

        repo(backend).refresh()

        assertEquals(600, episodeDao.get("e1")?.positionSec)
        assertEquals(0, episodeDao.get("e2")?.positionSec) // pending local push wins
    }

    @Test
    fun `one failing podcast does not sink the refresh`() = runTest {
        val backend = FakeBackend(
            subscriptions = listOf(
                RemotePodcast("p1", "Show One", ""),
                RemotePodcast("p2", "Show Two", ""),
            ),
            episodes = mapOf("p1" to listOf(remoteEpisode("e1", "p1"))),
            episodesErrors = setOf("p2"),
        )
        val repository = repo(backend)

        repository.refresh()

        assertEquals("Show One", podcastDao.get("p1")?.title)
        val state = repository.refreshState.value
        assertIs<RefreshState.Failed>(state)
        assertTrue("1 show" in state.message)
    }

    @Test
    fun `network failure keeps cached data and reports failure`() = runTest {
        podcastDao.upsertAll(listOf(PodcastEntity("p1", "Cached Show", updatedAt = 0)))
        val backend = FakeBackend(subscriptionsError = SyncException.Network(RuntimeException("offline")))
        val repository = repo(backend)

        repository.refresh()

        assertEquals("Cached Show", podcastDao.get("p1")?.title)
        assertIs<RefreshState.Failed>(repository.refreshState.value)
    }

    @Test
    fun `an empty subscription list removes podcasts and their downloads together`() = runTest {
        val file = File.createTempFile("episode", ".mp3")
        podcastDao.upsertAll(listOf(PodcastEntity("p1", "Show", "", 1, null, 1)))
        episodeDao.insertIgnore(
            listOf(EpisodeEntity("e1", "p1", "Episode", 1, 1800, "https://cdn.test/e1.mp3", filePath = file.absolutePath)),
        )

        repo(FakeBackend(subscriptions = emptyList())).refresh()

        assertFalse(file.exists())
        assertEquals(emptyList(), podcastDao.allUuids())
    }

    @Test
    fun `a cancelled refresh does not leave the state stuck on refreshing`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backend = object : SyncBackend by FakeBackend() {
            override suspend fun fetchSubscriptions(): List<RemotePodcast> {
                gate.await()
                return emptyList()
            }
        }
        val repository = repo(backend)

        val job = launch { repository.refresh() }
        testScheduler.runCurrent()
        assertEquals(RefreshState.Refreshing, repository.refreshState.value)
        job.cancel()
        job.join()

        assertEquals(RefreshState.Idle, repository.refreshState.value)
    }

    @Test
    fun `stopping a refresh in flight ends its writes`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val subscribed = FakeBackend(
            subscriptions = listOf(
                RemotePodcast("p1", "Show One", "Author A"),
                RemotePodcast("p2", "Show Two", "Author B"),
            ),
        )
        val backend = object : SyncBackend by subscribed {
            override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> {
                if (podcastUuid == "p2") gate.await()
                return listOf(remoteEpisode("e-$podcastUuid", podcastUuid))
            }
        }
        val repository = repo(backend)
        val job = launch { repository.refresh() }
        testScheduler.runCurrent()
        assertEquals(listOf("p1"), podcastDao.allUuids())

        repository.stopRefresh()
        gate.complete(Unit)
        job.join()

        assertEquals(listOf("p1"), podcastDao.allUuids())
        assertEquals(RefreshState.Idle, repository.refreshState.value)
    }
}
