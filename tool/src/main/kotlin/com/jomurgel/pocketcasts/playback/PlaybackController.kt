package com.jomurgel.pocketcasts.playback

import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
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

val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

/**
 * Process-wide owner of the single detached player handle. Screens attach a
 * factory (activity-scoped) but the controller and its state outlive them;
 * the detached session itself outlives the whole process.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val progressRepository: ProgressRepository,
) {
    private var handle: PlayerHandle? = null
    private var samplerJob: Job? = null
    private var mirrorJobs = mutableListOf<Job>()
    private var lastRecordedSec = -1L

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

    /**
     * Bind (or rebind) the single detached handle. Idempotent while a handle
     * is live. Returns false if the player never became ready.
     */
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

    /** Replace the queue with [episode] and play from its saved position. */
    suspend fun play(episode: EpisodeEntity, podcastTitle: String) {
        val player = handle ?: return
        val source = episode.filePath?.let { LightAudioSource.FileSource(File(it)) }
            ?: LightAudioSource.UrlSource(episode.audioUrl)
        player.setMediaQueue(
            listOf(
                LightAudioItem(
                    source = source,
                    metadata = LightMediaMetadata(
                        title = episode.title,
                        artist = podcastTitle,
                        durationMs = episode.durationSec * 1000,
                    ),
                ),
            ),
        )
        if (episode.positionSec > RESUME_MIN_POSITION_SEC) {
            player.seekTo(episode.positionSec * 1000)
        }
        player.play()
        _nowPlaying.value = NowPlaying(
            episodeUuid = episode.uuid,
            podcastUuid = episode.podcastUuid,
            episodeTitle = episode.title,
            podcastTitle = podcastTitle,
        )
        lastRecordedSec = -1
        recordNow()
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

    /** Adopt a live detached session's queue state after process restart. */
    fun adopt(nowPlaying: NowPlaying) {
        if (_nowPlaying.value == null) _nowPlaying.value = nowPlaying
    }

    private fun mirror(player: PlayerHandle) {
        mirrorJobs += scope.launch { player.positionMs.collect { _positionMs.value = it } }
        mirrorJobs += scope.launch { player.durationMs.collect { _durationMs.value = it } }
        mirrorJobs += scope.launch { player.error.collect { _error.value = it } }
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

    private fun startSampler() {
        if (samplerJob?.isActive == true) return
        samplerJob = scope.launch {
            while (true) {
                delay(PROGRESS_SAMPLE_INTERVAL_MS)
                recordNow()
            }
        }
    }

    private fun onPaused() {
        samplerJob?.cancel()
        samplerJob = null
        scope.launch { recordCurrent() }
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
