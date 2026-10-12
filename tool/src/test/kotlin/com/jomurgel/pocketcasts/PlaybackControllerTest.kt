package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.QueueItem
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.data.db.PlayStatus
import com.jomurgel.pocketcasts.data.sync.RemoteQueueEpisode
import com.jomurgel.pocketcasts.playback.QUEUE_SETTLE_MS
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.sync.RemoteEpisode
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeState
import com.jomurgel.pocketcasts.data.sync.RemotePodcast
import com.jomurgel.pocketcasts.data.sync.RemoteQueueChange
import com.jomurgel.pocketcasts.data.sync.RemoteQueueResult
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.playback.PROGRESS_SAMPLE_INTERVAL_MS
import com.jomurgel.pocketcasts.playback.PlaybackController
import com.jomurgel.pocketcasts.playback.PlayerHandle
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakePlayerHandle : PlayerHandle {
    override val positionMs = MutableStateFlow(0L)
    override val durationMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    override val currentMediaItemIndex = MutableStateFlow(-1)
    override val error = MutableStateFlow<LightAudioError?>(null)
    override var speed: Float = 1.0f

    var queue: List<LightAudioItem> = emptyList()
    var seekedToMs: Long? = null
    var ready = true
    var autoPlayOnPlay = true

    override suspend fun awaitReady(): Boolean = ready
    override fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int) {
        queue = items
    }

    override fun play() {
        if (autoPlayOnPlay) isPlaying.value = true
    }

    override fun pause() {
        isPlaying.value = false
    }

    override fun stop() {
        isPlaying.value = false
    }

    override fun seekTo(ms: Long) {
        seekedToMs = ms
        positionMs.value = ms
    }

    override fun skipBack() = Unit
    override fun skipForward() = Unit
    override fun release() = Unit
}

private fun episode(
    positionSec: Long = 0,
    filePath: String? = null,
    uuid: String = "e1",
    title: String = "Episode",
    durationSec: Long = 1800,
) = EpisodeEntity(
    uuid = uuid, podcastUuid = "p1", title = title,
    publishedAt = 1, durationSec = durationSec, audioUrl = "https://cdn.test/$uuid.mp3",
    positionSec = positionSec, filePath = filePath,
)

private fun queueItem(uuid: String) = QueueItem(uuid, "p1", "Episode $uuid", "https://cdn.test/$uuid.mp3")

private fun FakePlayerHandle.titles() = queue.map { it.metadata?.title }

private class NoopQueueBackend : SyncBackend {
    override suspend fun fetchSubscriptions(): List<RemotePodcast> = emptyList()
    override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> = emptyList()
    override suspend fun fetchInProgress(): List<RemoteEpisodeState> = emptyList()
    /** When set, the server answers every sync with this queue. */
    var serverQueue: List<RemoteQueueEpisode>? = null

    override suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult =
        RemoteQueueResult(serverModified + 1, serverQueue)

    override suspend fun fetchEpisodeUserStates(podcastUuid: String): List<com.jomurgel.pocketcasts.data.sync.RemoteEpisodeUserState> = emptyList()

    override suspend fun pushEpisodeFlags(changes: List<com.jomurgel.pocketcasts.data.sync.RemoteEpisodeFlagChange>) = Unit
}

class PlaybackControllerTest {

    private val episodeDao = FakeEpisodeDao()
    private val pendingDao = FakePendingProgressDao()
    private val backend = NoopQueueBackend()
    private val queue = UpNextQueue(FakeSyncMetaDao(), backend, now = { 42L })

    private fun progressRepo() = ProgressRepository(
        episodeDao = episodeDao,
        pendingProgressDao = pendingDao,
        syncApi = null,
        schedulePush = {},
        now = { 99L },
    )

    @Test
    fun `play resumes from saved position and records progress`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        episodeDao.insertIgnore(listOf(episode(positionSec = 0)))

        assertTrue(controller.attach { handle })
        controller.play(episode(positionSec = 642), "Show One")
        runCurrent()

        assertEquals(642_000L, handle.seekedToMs)
        assertTrue(handle.isPlaying.value)
        assertEquals("Episode", controller.nowPlaying.value?.episodeTitle)
        assertEquals(642, pendingDao.rows["e1"]?.positionSec)
        controller.shutdownForTest()
    }

    @Test
    fun `near-start positions do not seek`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        assertTrue(controller.attach { handle })
        controller.play(episode(positionSec = 3), "Show One")
        runCurrent()
        assertEquals(null, handle.seekedToMs)
        controller.shutdownForTest()
    }

    @Test
    fun `downloaded episodes play from file`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        episodeDao.insertIgnore(listOf(episode(filePath = "/data/episodes/e1.mp3")))
        assertTrue(controller.attach { handle })
        controller.play(episode(filePath = "/data/episodes/e1.mp3"), "Show One")
        runCurrent()
        assertTrue(handle.queue.single().source is LightAudioSource.FileSource)
        assertTrue(controller.nowPlaying.value?.fromDownload == true)
        controller.shutdownForTest()
    }

    @Test
    fun `sampler records while playing and on pause`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        episodeDao.insertIgnore(listOf(episode()))
        assertTrue(controller.attach { handle })
        controller.play(episode(), "Show One")
        runCurrent()

        handle.positionMs.value = 15_000L
        advanceTimeBy(PROGRESS_SAMPLE_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(15, pendingDao.rows["e1"]?.positionSec)

        handle.positionMs.value = 21_000L
        handle.pause()
        runCurrent()
        assertEquals(21, pendingDao.rows["e1"]?.positionSec)
        controller.shutdownForTest()
    }

    @Test
    fun `preparing state shows until audio actually rolls`() = runTest {
        val handle = FakePlayerHandle()
        // Audio does not start rolling on play() until we say so.
        handle.autoPlayOnPlay = false
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        assertTrue(controller.attach { handle })
        controller.play(episode(), "Show One")
        runCurrent()

        assertTrue(controller.isPreparing.value)
        assertEquals(false, controller.isPlaying.value)

        handle.isPlaying.value = true // buffering done, audio rolling
        runCurrent()
        assertEquals(false, controller.isPreparing.value)
        controller.shutdownForTest()
    }

    @Test
    fun `refresh queue swaps to downloaded file preserving position`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        episodeDao.insertIgnore(listOf(episode()))
        assertTrue(controller.attach { handle })
        controller.play(episode(), "Show One")
        runCurrent()
        assertTrue(handle.queue.single().source is LightAudioSource.UrlSource)

        // Download lands mid-stream.
        episodeDao.updateDownloadState("e1", 3, 100, "/data/episodes/e1.mp3")
        handle.positionMs.value = 30_000L
        controller.refreshQueueInPlayer()
        runCurrent()

        assertTrue(handle.queue.single().source is LightAudioSource.FileSource)
        assertEquals(30_000L, handle.seekedToMs)
        assertTrue(handle.isPlaying.value)
        assertTrue(controller.nowPlaying.value?.fromDownload == true)
        controller.shutdownForTest()
    }

    @Test
    fun `attach fails cleanly when the player never readies`() = runTest {
        val handle = FakePlayerHandle().apply { ready = false }
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        assertEquals(false, controller.attach { handle })
    }

    @Test
    fun `switching episodes records each one's own position`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(this, progressRepo(), queue, episodeLookup = { episodeDao.get(it) })
        val first = episode(uuid = "e1", durationSec = 2520)
        val second = episode(uuid = "e2", title = "Second", durationSec = 3600)
        episodeDao.insertIgnore(listOf(first, second))
        assertTrue(controller.attach { handle })
        controller.play(first, "Show One")
        runCurrent()
        // Deep into the first episode; the player keeps reporting this until the next one loads.
        handle.positionMs.value = 2_400_000L
        handle.durationMs.value = 2_520_000L
        runCurrent()

        controller.play(second, "Show One")
        runCurrent()

        assertEquals(2400, pendingDao.rows["e1"]?.positionSec)
        assertEquals(null, pendingDao.rows["e2"])
        assertEquals(PlayStatus.UNPLAYED, episodeDao.get("e2")!!.playStatus)
        controller.shutdownForTest()
    }

    @Test
    fun `auto-advance marks an episode of unknown length as played`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(this, progressRepo(), queue, episodeLookup = { episodeDao.get(it) })
        // "x" was queued on another device and isn't in the local catalog.
        queue.playLast(queueItem("x"))
        queue.playLast(queueItem("y"))
        assertTrue(controller.attach { handle })
        controller.playFromQueue()
        runCurrent()

        handle.currentMediaItemIndex.value = 1
        runCurrent()

        assertEquals(PlayStatus.COMPLETE, pendingDao.rows["x"]?.status)
        assertEquals("y", controller.nowPlaying.value?.episodeUuid)
        assertEquals(listOf("y"), queue.queue.value.map { it.episodeUuid })
        controller.shutdownForTest()
    }

    @Test
    fun `an episode queued from another screen reaches the player`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(this, progressRepo(), queue, episodeLookup = { episodeDao.get(it) })
        episodeDao.insertIgnore(listOf(episode()))
        assertTrue(controller.attach { handle })
        controller.play(episode(), "Show One")
        runCurrent()
        handle.positionMs.value = 30_000L
        assertEquals(1, handle.queue.size)

        queue.playNext(queueItem("e2"))
        advanceTimeBy(QUEUE_SETTLE_MS + 1)
        runCurrent()

        assertEquals(listOf("Episode", "Episode e2"), handle.titles())
        assertEquals(30_000L, handle.seekedToMs)
        assertEquals("e1", controller.nowPlaying.value?.episodeUuid)

        handle.currentMediaItemIndex.value = 1
        runCurrent()
        assertEquals("e2", controller.nowPlaying.value?.episodeUuid)
        controller.shutdownForTest()
    }

    @Test
    fun `a queue changed on another device does not replace the playing episode`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(this, progressRepo(), queue, episodeLookup = { episodeDao.get(it) })
        episodeDao.insertIgnore(listOf(episode()))
        assertTrue(controller.attach { handle })
        // The server now leads with a different episode than the one we start.
        backend.serverQueue = listOf(
            RemoteQueueEpisode(uuid = "c", title = "Episode c", audioUrl = "https://cdn.test/c.mp3", podcastUuid = "p1"),
            RemoteQueueEpisode(uuid = "e1", title = "Episode", audioUrl = "https://cdn.test/e1.mp3", podcastUuid = "p1"),
        )
        controller.play(episode(), "Show One")
        runCurrent()
        advanceTimeBy(QUEUE_SETTLE_MS + 1)
        runCurrent()
        assertEquals("c", queue.queue.value.first().episodeUuid)

        controller.refreshQueueInPlayer()
        runCurrent()

        assertEquals("e1", controller.nowPlaying.value?.episodeUuid)
        assertEquals(listOf("Episode", "Episode c"), handle.titles())
        controller.shutdownForTest()
    }

    @Test
    fun `reset stops playback and forgets the session`() = runTest {
        val handle = FakePlayerHandle()
        val controller = PlaybackController(this, progressRepo(), queue, episodeLookup = { episodeDao.get(it) })
        episodeDao.insertIgnore(listOf(episode()))
        assertTrue(controller.attach { handle })
        controller.play(episode(), "Show One")
        runCurrent()

        controller.reset()
        runCurrent()

        assertEquals(null, controller.nowPlaying.value)
        assertEquals(false, handle.isPlaying.value)
        controller.shutdownForTest()
    }
}
