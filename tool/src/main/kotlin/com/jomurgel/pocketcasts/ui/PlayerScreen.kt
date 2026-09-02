package com.jomurgel.pocketcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.jomurgel.pocketcasts.AppGraph
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.QueueItem
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.playback.LightPlayerHandle
import com.jomurgel.pocketcasts.playback.PlaybackController
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val controller: PlaybackController,
    private val library: LibraryRepository,
    private val upNextQueue: UpNextQueue,
    private val audio: LightAudio,
    private val startEpisodeUuid: String?,
) : LightViewModel<Unit>() {

    val nowPlaying = controller.nowPlaying
    val queue = upNextQueue.queue
    val positionMs = controller.positionMs
    val durationMs = controller.durationMs
    val isPlaying = controller.isPlaying
    val speed = controller.speed
    val playbackError = controller.error

    private val _attachError = MutableStateFlow<String?>(null)
    val attachError = _attachError.asStateFlow()

    init {
        viewModelScope.launch {
            val attached = try {
                controller.attach {
                    LightPlayerHandle(
                        audio.newPlayer(
                            usage = LightAudioUsage.Speech,
                            playback = LightAudioPlayback.Detached,
                        ),
                    )
                }
            } catch (error: Exception) {
                _attachError.value = error.message ?: "Audio is unavailable."
                false
            }
            if (attached && startEpisodeUuid != null) {
                val episode = library.episode(startEpisodeUuid) ?: return@launch
                // Don't restart an episode that is already the live session.
                if (controller.nowPlaying.value?.episodeUuid != episode.uuid) {
                    controller.play(episode, library.podcastTitle(episode.podcastUuid))
                }
            }
        }
        viewModelScope.launch { upNextQueue.sync() }
    }

    /** Start the synced queue head (e.g. an episode begun on the web). */
    fun resumeQueueHead() {
        viewModelScope.launch { controller.playFromQueue() }
    }

    fun removeFromQueue(episodeUuid: String) {
        viewModelScope.launch {
            upNextQueue.remove(episodeUuid)
            controller.refreshQueueInPlayer()
            upNextQueue.sync()
        }
    }

    fun moveToTop(episodeUuid: String) {
        viewModelScope.launch {
            upNextQueue.moveToTop(episodeUuid)
            controller.refreshQueueInPlayer()
            upNextQueue.sync()
        }
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun skipBack() = controller.skipBack()
    fun skipForward() = controller.skipForward()
    fun cycleSpeed() = controller.cycleSpeed()
}

class PlayerScreen(
    sealedActivity: SealedLightActivity,
    private val startEpisodeUuid: String? = null,
) : LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    private val sealed = sealedActivity

    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel() = PlayerViewModel(
        controller = AppGraph.playbackController(lightContext),
        library = AppGraph.libraryRepository(lightContext),
        upNextQueue = AppGraph.upNextQueue(lightContext),
        audio = DefaultLightAudio(sealed),
        startEpisodeUuid = startEpisodeUuid,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val nowPlaying by viewModel.nowPlaying.collectAsState()
        val positionMs by viewModel.positionMs.collectAsState()
        val durationMs by viewModel.durationMs.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val speed by viewModel.speed.collectAsState()
        val playbackError by viewModel.playbackError.collectAsState()
        val attachError by viewModel.attachError.collectAsState()
        val queue by viewModel.queue.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text("Now Playing"),
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    val head = queue.firstOrNull()
                    when {
                        nowPlaying != null -> {
                            LightText(
                                text = nowPlaying?.episodeTitle.orEmpty(),
                                variant = LightTextVariant.Heading,
                                maxLines = 3,
                            )
                            LightText(
                                text = nowPlaying?.podcastTitle.orEmpty(),
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        head != null -> {
                            // Synced current episode from another device, not
                            // yet started here. Play resumes it.
                            LightText(
                                text = head.title,
                                variant = LightTextVariant.Heading,
                                maxLines = 3,
                            )
                            LightText(
                                text = "Synced from your other devices. Press play to resume.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        else -> LightText(
                            text = "Nothing playing. Pick an episode from a podcast.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    }

                    UpNextSection(
                        upcoming = queue.drop(1),
                        onRemove = viewModel::removeFromQueue,
                        onMoveToTop = viewModel::moveToTop,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    attachError?.let {
                        LightText(text = it, variant = LightTextVariant.Detail)
                    }
                    playbackError?.let {
                        LightText(
                            text = "Playback problem (${it.kind}). Try again.",
                            variant = LightTextVariant.Detail,
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        LightText(
                            text = "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                            variant = LightTextVariant.Fine,
                            monospace = true,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(
                            text = "SPEED ${formatSpeed(speed)}",
                            variant = LightTextVariant.Fine,
                            align = TextAlign.End,
                            modifier = Modifier
                                .weight(1f)
                                .lightClickable { viewModel.cycleSpeed() },
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            LightIcons.SKIP_BACKWARD_FIFTEEN,
                            viewModel::skipBack,
                            contentDescription = "Back 15 seconds",
                        ),
                        LightBarButton.LightIcon(
                            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                            onClick = {
                                if (nowPlaying == null && queue.isNotEmpty()) {
                                    viewModel.resumeQueueHead()
                                } else {
                                    viewModel.togglePlayPause()
                                }
                            },
                            contentDescription = if (playing) "Pause" else "Play",
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.SKIP_FORWARD_FIFTEEN,
                            viewModel::skipForward,
                            contentDescription = "Forward 15 seconds",
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun UpNextSection(
    upcoming: List<QueueItem>,
    onRemove: (String) -> Unit,
    onMoveToTop: (String) -> Unit,
) {
    if (upcoming.isEmpty()) return
    LightText(
        text = "UP NEXT (${upcoming.size})",
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
    upcoming.take(5).forEach { item ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 0.25f.gridUnitsAsDp())) {
            LightText(
                text = item.title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
            )
            Row {
                LightText(
                    text = "TOP",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier
                        .lightClickable { onMoveToTop(item.episodeUuid) }
                        .padding(end = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = "REMOVE",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.lightClickable { onRemove(item.episodeUuid) },
                )
            }
        }
    }
    if (upcoming.size > 5) {
        LightText(
            text = "+${upcoming.size - 5} more",
            variant = LightTextVariant.Fine,
            lighten = true,
        )
    }
}

internal fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${speed}x"
