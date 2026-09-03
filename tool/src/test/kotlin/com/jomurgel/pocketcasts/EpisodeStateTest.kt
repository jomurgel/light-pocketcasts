package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.EpisodeStateRepository
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.db.PlayStatus
import com.jomurgel.pocketcasts.data.sync.RemoteEpisode
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeFlagChange
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeState
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeUserState
import com.jomurgel.pocketcasts.data.sync.RemotePodcast
import com.jomurgel.pocketcasts.data.sync.RemoteQueueChange
import com.jomurgel.pocketcasts.data.sync.RemoteQueueResult
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException
import com.jomurgel.pocketcasts.ui.EpisodeFilter
import com.jomurgel.pocketcasts.ui.filterEpisodes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private open class StubBackend : SyncBackend {
    var userStates: Map<String, List<RemoteEpisodeUserState>> = emptyMap()
    var pushedFlags = mutableListOf<List<RemoteEpisodeFlagChange>>()
    var throwOnPush: SyncException? = null

    override suspend fun fetchSubscriptions(): List<RemotePodcast> = emptyList()
    override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> = emptyList()
    override suspend fun fetchInProgress(): List<RemoteEpisodeState> = emptyList()
    override suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult =
        RemoteQueueResult(serverModified, null)

    override suspend fun fetchEpisodeUserStates(podcastUuid: String): List<RemoteEpisodeUserState> =
        userStates[podcastUuid].orEmpty()

    override suspend fun pushEpisodeFlags(changes: List<RemoteEpisodeFlagChange>) {
        throwOnPush?.let { throw it }
        pushedFlags += listOf(changes)
    }
}

private fun episode(uuid: String, archived: Boolean = false, starred: Boolean = false, status: Int = PlayStatus.UNPLAYED, position: Long = 0) =
    EpisodeEntity(
        uuid = uuid, podcastUuid = "p1", title = "Episode $uuid", publishedAt = 1,
        audioUrl = "https://x/$uuid.mp3", isArchived = archived, starred = starred,
        playStatus = status, positionSec = position,
    )

class EpisodeStateRepositoryTest {

    private val episodeDao = FakeEpisodeDao()
    private val pendingDao = FakePendingEpisodeStateDao()
    private val backend = StubBackend()
    private var pushes = 0
    private var time = 1000L

    private fun repo() = EpisodeStateRepository(
        episodeDao, pendingDao, backend,
        schedulePush = { pushes++ }, now = { time },
    )

    @Test
    fun `archive updates the row and queues a merged outbox entry`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        val repository = repo()

        repository.setArchived("e1", "p1", true)
        time = 2000L
        repository.setStarred("e1", "p1", true)

        assertTrue(episodeDao.get("e1")!!.isArchived)
        assertTrue(episodeDao.get("e1")!!.starred)
        assertEquals(1, pendingDao.rows.size)
        val row = pendingDao.rows["e1"]!!
        assertEquals(true, row.archived)
        assertEquals(true, row.starred)
        assertEquals(2000L, row.queuedAt)
        assertEquals(2, pushes)
    }

    @Test
    fun `drain pushes one request and clears the outbox`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1"), episode("e2")))
        val repository = repo()
        repository.setArchived("e1", "p1", true)
        repository.setStarred("e2", "p1", true)

        val result = repository.drain()

        assertEquals(ProgressRepository.DrainResult.Done, result)
        assertEquals(1, backend.pushedFlags.size)
        assertEquals(2, backend.pushedFlags.single().size)
        assertTrue(pendingDao.rows.isEmpty())
    }

    @Test
    fun `failed drain keeps rows for retry`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        val repository = repo()
        repository.setArchived("e1", "p1", true)
        backend.throwOnPush = SyncException.Network(RuntimeException("offline"))

        assertEquals(ProgressRepository.DrainResult.Retry, repository.drain())
        assertEquals(1, pendingDao.rows.size)
    }
}

class EpisodeStateOverlayTest {

    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val backend = StubBackend()

    private fun library() = LibraryRepository(backend, podcastDao, episodeDao, now = { 5000L })

    @Test
    fun `overlay applies archive starred and progress`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        backend.userStates = mapOf(
            "p1" to listOf(RemoteEpisodeUserState("e1", 300, PlayStatus.IN_PROGRESS, archived = true, starred = true)),
        )

        library().refreshEpisodeStates("p1")

        val episode = episodeDao.get("e1")!!
        assertTrue(episode.isArchived)
        assertTrue(episode.starred)
        assertEquals(300, episode.positionSec)
    }

    @Test
    fun `overlay skips flags with pending local pushes`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1", archived = true)))
        episodeDao.pendingFlagUuids.add("e1")
        backend.userStates = mapOf(
            "p1" to listOf(RemoteEpisodeUserState("e1", 0, PlayStatus.UNPLAYED, archived = false, starred = false)),
        )

        library().refreshEpisodeStates("p1")

        assertTrue(episodeDao.get("e1")!!.isArchived) // local pending wins
    }
}

class EpisodeFilterTest {

    private val episodes = listOf(
        episode("a"),
        episode("b", status = PlayStatus.IN_PROGRESS, position = 100),
        episode("c", archived = true),
        episode("d", status = PlayStatus.COMPLETE),
    )

    @Test
    fun `archived hidden by default`() {
        val visible = filterEpisodes(episodes, EpisodeFilter.ALL, showArchived = false)
        assertEquals(listOf("a", "b", "d"), visible.map { it.uuid })
    }

    @Test
    fun `show archived reveals them`() {
        val visible = filterEpisodes(episodes, EpisodeFilter.ALL, showArchived = true)
        assertEquals(4, visible.size)
    }

    @Test
    fun `unplayed filter`() {
        val visible = filterEpisodes(episodes, EpisodeFilter.UNPLAYED, showArchived = false)
        assertEquals(listOf("a"), visible.map { it.uuid })
    }

    @Test
    fun `in progress filter`() {
        val visible = filterEpisodes(episodes, EpisodeFilter.IN_PROGRESS, showArchived = false)
        assertEquals(listOf("b"), visible.map { it.uuid })
    }

    @Test
    fun `archived stays hidden under filters`() {
        val archived = filterEpisodes(listOf(episode("x", archived = true)), EpisodeFilter.ALL, false)
        assertNull(archived.firstOrNull())
    }
}
