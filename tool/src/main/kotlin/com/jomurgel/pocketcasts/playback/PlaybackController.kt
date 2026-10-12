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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What is (or was last) loaded in the detached session, for UI surfaces. */
data class NowPlaying(
    val episodeUuid: String,
    val podcastUuid: String,
    val episodeTitle: String,
    val podcastTitle: String,
    /** True when the loaded source is a local download rather than a stream. */
    val fromDownload: Boolean = false,
)

const val PROGRESS_SAMPLE_INTERVAL_MS = 15_000L

/** Skip seeking when resuming within the first few seconds. */
const val RESUME_MIN_POSITION_SEC = 5L

/** Positions this close to the end count as "finished". */
const val FINISH_THRESHOLD_MS = 2_000L

/** Let a burst of queue edits (a move is a remove plus an insert) land as one reload. */
const val QUEUE_SETTLE_MS = 250L

val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

/**
 * Process-wide owner of the single detached player handle. The player's media
 * queue mirrors Up Next: current episode at index 0, upcoming behind it.
 * Auto-advancing to the next item (or finishing the last) removes the done
 * episode from Up Next, matching Pocket Casts semantics.
 *
 * The controller watches Up Next itself, so an edit from any screen or a queue
 * adopted from the server reaches the player. The episode being listened to is
 * never swapped out by such a change: it stays put and the rest lines up behind it.
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

    /** Serializes everything that loads the player or moves its queue bookkeeping. */
    private val playerMutex = Mutex()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** True between requesting playback and audio actually rolling. */
    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()

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
        playerMutex.withLock {
            recordCurrent()
            upNextQueue.playNow(episode.toQueueItem())
            startQueuePlayback(resumePositionSec = episode.positionSec, durationSec = episode.durationSec)
        }
        scope.launch { upNextQueue.sync() }
    }

    /** Start whatever is at the head of the synced queue (e.g. from the web). */
    suspend fun playFromQueue() {
        playerMutex.withLock {
            val head = upNextQueue.head() ?: return
            val known = episodeLookup(head.episodeUuid)
            recordCurrent()
            startQueuePlayback(
                resumePositionSec = known?.positionSec ?: 0,
                durationSec = known?.durationSec ?: 0,
            )
        }
    }

    /** Reload the player's queue in place, e.g. to pick up a finished download. */
    suspend fun refreshQueueInPlayer() {
        playerMutex.withLock {
            val player = handle ?: return
            val desired = queueBehindCurrent() ?: return
            reloadKeepingPosition(player, desired)
        }
    }

    /** Bring the player in line with Up Next after it changed underneath us. */
    private suspend fun reconcileQueue() {
        playerMutex.withLock {
            val player = handle ?: return
            val desired = queueBehindCurrent() ?: return
            val loaded = loadedQueue.drop(lastKnownIndex)
            if (desired.map { it.episodeUuid } == loaded.map { it.episodeUuid }) return
            reloadKeepingPosition(player, desired)
        }
    }

    /**
     * What the player should hold: the episode being listened to, then the rest
     * of Up Next. Up Next's own head can differ (another device started
     * something else); that must not yank the current episode away.
     */
    private fun queueBehindCurrent(): List<QueueItem>? {
        val playing = _nowPlaying.value ?: return null
        val queue = upNextQueue.queue.value
        val current = loadedQueue.getOrNull(lastKnownIndex)?.takeIf { it.episodeUuid == playing.episodeUuid }
            ?: queue.firstOrNull { it.episodeUuid == playing.episodeUuid }
            ?: return null
        return listOf(current) + queue.filterNot { it.episodeUuid == playing.episodeUuid }
    }

    private suspend fun reloadKeepingPosition(player: PlayerHandle, items: List<QueueItem>) {
        val wasPlaying = player.isPlaying.value
        val position = player.positionMs.value
        loadIntoPlayer(player, items)
        player.seekTo(position)
        if (wasPlaying) player.play()
    }

    /** Stop and forget the session, e.g. when its account's data is wiped. */
    fun reset() {
        samplerJob?.cancel()
        samplerJob = null
        // Cleared first so the pause that follows records nothing.
        _nowPlaying.value = null
        loadedQueue = emptyList()
        lastKnownIndex = 0
        _isPreparing.value = false
        handle?.stop()
    }

    fun togglePlayPause() {
        val player = handle ?: return
        if (player.isPlaying.value) {
            _isPreparing.value = false
            player.pause()
        } else {
            _isPreparing.value = true
            player.play()
        }
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

    private suspend fun startQueuePlayback(resumePositionSec: Long, durationSec: Long) {
        val player = handle ?: return
        val queue = upNextQueue.queue.value
        if (queue.isEmpty()) return
        loadIntoPlayer(player, queue)
        // The mirrored position still belongs to whatever played before. Point
        // it at this episode before anything can record it against the new one.
        _positionMs.value = resumePositionSec * 1000
        if (resumePositionSec > RESUME_MIN_POSITION_SEC) {
            player.seekTo(resumePositionSec * 1000)
        }
        _isPreparing.value = true
        player.play()
        lastRecordedSec = resumePositionSec
        if (resumePositionSec > 0) {
            val current = queue.first()
            progressRepository.record(current.episodeUuid, current.podcastUuid, resumePositionSec, durationSec)
        }
    }

    private suspend fun loadIntoPlayer(player: PlayerHandle, items: List<QueueItem>) {
        player.setMediaQueue(items.map { it.toAudioItem(episodeLookup(it.episodeUuid)) }, 0)
        loadedQueue = items
        lastKnownIndex = 0
        updateNowPlaying(items.first())
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
            fromDownload = episodeLookup(item.episodeUuid)?.filePath != null,
        )
    }

    private fun mirror(player: PlayerHandle) {
        mirrorJobs += scope.launch { player.positionMs.collect { _positionMs.value = it } }
        mirrorJobs += scope.launch { player.durationMs.collect { _durationMs.value = it } }
        mirrorJobs += scope.launch {
            player.error.collect { error ->
                _error.value = error
                if (error != null) _isPreparing.value = false
            }
        }
        mirrorJobs += scope.launch {
            player.currentMediaItemIndex.collect { index -> onIndexChanged(index) }
        }
        mirrorJobs += scope.launch {
            upNextQueue.queue.collectLatest {
                delay(QUEUE_SETTLE_MS)
                // Once a reload starts it has to finish, even if the queue moves again.
                withContext(NonCancellable) { reconcileQueue() }
            }
        }
        mirrorJobs += scope.launch {
            player.isPlaying.collect { playing ->
                _isPlaying.value = playing
                if (playing) {
                    _isPreparing.value = false
                    startSampler()
                } else {
                    onPaused()
                }
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
        scope.launch {
            playerMutex.withLock {
                if (index <= lastKnownIndex || index >= loadedQueue.size) return@launch
                val finished = loadedQueue.subList(lastKnownIndex, index).toList()
                lastKnownIndex = index
                updateNowPlaying(loadedQueue[index])
                finished.forEach { done ->
                    val durationSec = episodeLookup(done.episodeUuid)?.durationSec ?: 0
                    progressRepository.recordCompleted(done.episodeUuid, done.podcastUuid, durationSec)
                    upNextQueue.remove(done.episodeUuid)
                }
                lastRecordedSec = -1
            }
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
        playerMutex.withLock {
            val playing = _nowPlaying.value ?: return
            val duration = currentDurationMs(playing)
            if (duration <= 0 || _positionMs.value < duration - FINISH_THRESHOLD_MS) return
            if (lastKnownIndex != loadedQueue.lastIndex) return
            upNextQueue.remove(playing.episodeUuid)
            _nowPlaying.value = null
            loadedQueue = emptyList()
            lastKnownIndex = 0
        }
        upNextQueue.sync()
    }

    private suspend fun recordCurrent() {
        val playing = _nowPlaying.value ?: return
        val positionSec = _positionMs.value / 1000
        if (positionSec == lastRecordedSec) return
        lastRecordedSec = positionSec
        progressRepository.record(
            playing.episodeUuid,
            playing.podcastUuid,
            positionSec,
            currentDurationMs(playing) / 1000,
        )
    }

    /**
     * The catalog's duration when we have one. The player's own figure lags a
     * switch of episode and would briefly be the previous episode's length.
     */
    private suspend fun currentDurationMs(playing: NowPlaying): Long =
        episodeLookup(playing.episodeUuid)?.durationSec?.takeIf { it > 0 }?.times(1000) ?: _durationMs.value
}
