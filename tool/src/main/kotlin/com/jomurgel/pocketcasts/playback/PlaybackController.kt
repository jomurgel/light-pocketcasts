package com.jomurgel.pocketcasts.playback

import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.QueueItem
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.toQueueItem
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What is (or was last) loaded in the detached session, for UI surfaces. */
data class NowPlaying(
    val episodeUuid: String,
    val podcastUuid: String,
    val episodeTitle: String,
    val podcastTitle: String,
)

const val PROGRESS_SAMPLE_INTERVAL_MS = 15_000L

/** Skip seeking when resuming within the first few seconds. */
const val RESUME_MIN_POSITION_SEC = 5L

/** Positions this close to the end count as "finished". */
const val FINISH_THRESHOLD_MS = 2_000L

val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

/**
 * Process-wide owner of the single detached player handle. The player's media
 * queue mirrors Up Next: current episode at index 0, upcoming behind it.
 * Auto-advancing to the next item (or finishing the last) removes the done
 * episode from Up Next, matching Pocket Casts semantics.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val progressRepository: ProgressRepository,
    private val upNextQueue: UpNextQueue,
    private val episodeLookup: suspend (String) -> EpisodeEntity? = { null },
    private val podcastTitleLookup: suspend (String) -> String = { "" },
) {
    private var handle: PlayerHandle? = null
    private var samplerJob: Job? = null
    private var mirrorJobs = mutableListOf<Job>()
    private var lastRecordedSec = -1L

    /** The QueueItems currently loaded into the player, by player index. */
    private var loadedQueue: List<QueueItem> = emptyList()
    private var lastKnownIndex = 0

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _error = MutableStateFlow<LightAudioError?>(null)
    val error: StateFlow<LightAudioError?> = _error.asStateFlow()

    suspend fun attach(factory: () -> PlayerHandle): Boolean {
        val existing = handle
        if (existing != null) return true
        val fresh = factory()
        handle = fresh
        if (!fresh.awaitReady()) {
            handle = null
            fresh.release()
            return false
        }
        fresh.speed = _speed.value
        mirror(fresh)
        return true
    }

    /** Play [episode] now: it becomes the queue head; upcoming items follow. */
    suspend fun play(episode: EpisodeEntity, podcastTitle: String) {
        upNextQueue.playNow(episode.toQueueItem())
        startQueuePlayback(resumePositionSec = episode.positionSec)
        scope.launch { upNextQueue.sync() }
    }

    /** Start whatever is at the head of the synced queue (e.g. from the web). */
    suspend fun playFromQueue() {
        val head = upNextQueue.head() ?: return
        val known = episodeLookup(head.episodeUuid)
        startQueuePlayback(resumePositionSec = known?.positionSec ?: 0)
    }

    /** Rebuild the live player queue after an Up Next edit, keeping position. */
    suspend fun refreshQueueInPlayer() {
        val player = handle ?: return
        if (_nowPlaying.value == null) return
        val wasPlaying = player.isPlaying.value
        val position = player.positionMs.value
        loadQueueIntoPlayer(player)
        player.seekTo(position)
        if (wasPlaying) player.play()
    }

    fun togglePlayPause() {
        val player = handle ?: return
        if (player.isPlaying.value) player.pause() else player.play()
    }

    fun skipBack() {
        handle?.skipBack()
    }

    fun skipForward() {
        handle?.skipForward()
    }

    fun cycleSpeed() {
        val next = SPEED_PRESETS[(SPEED_PRESETS.indexOf(_speed.value) + 1).mod(SPEED_PRESETS.size)]
        _speed.value = next
        handle?.speed = next
    }

    private suspend fun startQueuePlayback(resumePositionSec: Long) {
        val player = handle ?: return
        loadQueueIntoPlayer(player)
        if (resumePositionSec > RESUME_MIN_POSITION_SEC) {
            player.seekTo(resumePositionSec * 1000)
        }
        player.play()
        lastRecordedSec = -1
        recordNow()
    }

    private suspend fun loadQueueIntoPlayer(player: PlayerHandle) {
        val queue = upNextQueue.queue.value
        if (queue.isEmpty()) return
        val items = queue.map { it.toAudioItem(episodeLookup(it.episodeUuid)) }
        player.setMediaQueue(items, 0)
        loadedQueue = queue
        lastKnownIndex = 0
        updateNowPlaying(queue.first())
    }

    private suspend fun QueueItem.toAudioItem(known: EpisodeEntity?): LightAudioItem {
        val source = known?.filePath?.let { LightAudioSource.FileSource(File(it)) }
            ?: LightAudioSource.UrlSource(known?.audioUrl?.takeIf { it.isNotBlank() } ?: audioUrl)
        return LightAudioItem(
            source = source,
            metadata = LightMediaMetadata(
                title = title,
                artist = podcastTitleLookup(podcastUuid),
                durationMs = known?.durationSec?.times(1000),
            ),
        )
    }

    private suspend fun updateNowPlaying(item: QueueItem) {
        _nowPlaying.value = NowPlaying(
            episodeUuid = item.episodeUuid,
            podcastUuid = item.podcastUuid,
            episodeTitle = item.title,
            podcastTitle = podcastTitleLookup(item.podcastUuid),
        )
    }

    private fun mirror(player: PlayerHandle) {
        mirrorJobs += scope.launch { player.positionMs.collect { _positionMs.value = it } }
        mirrorJobs += scope.launch { player.durationMs.collect { _durationMs.value = it } }
        mirrorJobs += scope.launch { player.error.collect { _error.value = it } }
        mirrorJobs += scope.launch {
            player.currentMediaItemIndex.collect { index -> onIndexChanged(index) }
        }
        mirrorJobs += scope.launch {
            player.isPlaying.collect { playing ->
                _isPlaying.value = playing
                if (playing) startSampler() else onPaused()
            }
        }
    }

    /** Cancels the mirror/sampler coroutines. Tests only — the real
     * controller lives for the whole process. */
    fun shutdownForTest() {
        samplerJob?.cancel()
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs.clear()
    }

    /** Auto-advance: items before [index] are finished — clear them out. */
    private fun onIndexChanged(index: Int) {
        if (index <= lastKnownIndex || index >= loadedQueue.size) return
        val finished = loadedQueue.subList(lastKnownIndex, index)
        val current = loadedQueue[index]
        lastKnownIndex = index
        scope.launch {
            finished.forEach { done ->
                val known = episodeLookup(done.episodeUuid)
                val durationSec = known?.durationSec ?: 0
                progressRepository.record(done.episodeUuid, done.podcastUuid, durationSec, durationSec)
                upNextQueue.remove(done.episodeUuid)
            }
            updateNowPlaying(current)
            lastRecordedSec = -1
            upNextQueue.sync()
        }
    }

    private fun startSampler() {
        if (samplerJob?.isActive == true) return
        samplerJob = scope.launch {
            while (true) {
                delay(PROGRESS_SAMPLE_INTERVAL_MS)
                recordCurrent()
            }
        }
    }

    private fun onPaused() {
        samplerJob?.cancel()
        samplerJob = null
        scope.launch {
            recordCurrent()
            finishIfEnded()
        }
    }

    /** End of the last queue item: no index transition fires, so catch it here. */
    private suspend fun finishIfEnded() {
        val playing = _nowPlaying.value ?: return
        val duration = _durationMs.value
        if (duration <= 0 || _positionMs.value < duration - FINISH_THRESHOLD_MS) return
        if (upNextQueue.head()?.episodeUuid != playing.episodeUuid) return
        upNextQueue.remove(playing.episodeUuid)
        _nowPlaying.value = null
        loadedQueue = emptyList()
        lastKnownIndex = 0
        upNextQueue.sync()
    }

    private fun recordNow() {
        scope.launch { recordCurrent() }
    }

    private suspend fun recordCurrent() {
        val playing = _nowPlaying.value ?: return
        val positionSec = _positionMs.value / 1000
        val durationSec = _durationMs.value / 1000
        if (positionSec == lastRecordedSec) return
        lastRecordedSec = positionSec
        progressRepository.record(playing.episodeUuid, playing.podcastUuid, positionSec, durationSec)
    }
}
