package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.UpNextQueue
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

    override suspend fun awaitReady(): Boolean = ready
    override fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int) {
        queue = items
    }

    override fun play() {
        isPlaying.value = true
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

private fun episode(positionSec: Long = 0, filePath: String? = null) = EpisodeEntity(
    uuid = "e1", podcastUuid = "p1", title = "Episode",
    publishedAt = 1, durationSec = 1800, audioUrl = "https://cdn.test/e1.mp3",
    positionSec = positionSec, filePath = filePath,
)

private class NoopQueueBackend : SyncBackend {
    override suspend fun fetchSubscriptions(): List<RemotePodcast> = emptyList()
    override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> = emptyList()
    override suspend fun fetchInProgress(): List<RemoteEpisodeState> = emptyList()
    override suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult =
        RemoteQueueResult(serverModified + 1, null)
}

class PlaybackControllerTest {

    private val episodeDao = FakeEpisodeDao()
    private val pendingDao = FakePendingProgressDao()
    private val queue = UpNextQueue(FakeSyncMetaDao(), NoopQueueBackend(), now = { 42L })

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
    fun `attach fails cleanly when the player never readies`() = runTest {
        val handle = FakePlayerHandle().apply { ready = false }
        val controller = PlaybackController(
            this, progressRepo(), queue,
            episodeLookup = { episodeDao.get(it) },
        )
        assertEquals(false, controller.attach { handle })
    }
}
