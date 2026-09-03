package com.jomurgel.pocketcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jomurgel.pocketcasts.AppGraph
import com.jomurgel.pocketcasts.data.DownloadManager
import com.jomurgel.pocketcasts.data.EpisodeStateRepository
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.data.db.DownloadState
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.toQueueItem
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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

class EpisodeActionsViewModel(
    private val library: LibraryRepository,
    private val upNextQueue: UpNextQueue,
    private val downloadManager: DownloadManager,
    private val stateRepository: EpisodeStateRepository,
    private val episodeUuid: String,
) : LightViewModel<Unit>() {

    private val _episode = MutableStateFlow<EpisodeEntity?>(null)
    val episode = _episode.asStateFlow()

    private val _queued = MutableStateFlow<String?>(null)
    val queued = _queued.asStateFlow()

    init {
        viewModelScope.launch {
            library.observeEpisode(episodeUuid).collect { _episode.value = it }
        }
    }

    fun download() = downloadManager.enqueue(episodeUuid)

    fun toggleArchived() {
        val episode = _episode.value ?: return
        viewModelScope.launch {
            stateRepository.setArchived(episodeUuid, episode.podcastUuid, !episode.isArchived)
        }
    }

    fun toggleStarred() {
        val episode = _episode.value ?: return
        viewModelScope.launch {
            stateRepository.setStarred(episodeUuid, episode.podcastUuid, !episode.starred)
        }
    }

    fun deleteDownload() {
        viewModelScope.launch { downloadManager.delete(episodeUuid) }
    }

    fun playNext() = queueAction("Queued next") { upNextQueue.playNext(it) }

    fun playLast() = queueAction("Queued last") { upNextQueue.playLast(it) }

    private fun queueAction(label: String, action: suspend (com.jomurgel.pocketcasts.data.QueueItem) -> Unit) {
        val episode = _episode.value ?: return
        viewModelScope.launch {
            action(episode.toQueueItem())
            _queued.value = label
            upNextQueue.sync()
        }
    }
}

/** Episode detail: play now, queue next/last. Downloads arrive in Task 5. */
class EpisodeActionsScreen(
    sealedActivity: SealedLightActivity,
    private val episodeUuid: String,
) : LightScreen<Unit, EpisodeActionsViewModel>(sealedActivity) {

    override val viewModelClass: Class<EpisodeActionsViewModel>
        get() = EpisodeActionsViewModel::class.java

    override fun createViewModel() = EpisodeActionsViewModel(
        AppGraph.libraryRepository(lightContext),
        AppGraph.upNextQueue(lightContext),
        AppGraph.downloadManager(lightContext),
        AppGraph.episodeStateRepository(lightContext),
        episodeUuid,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val episode by viewModel.episode.collectAsState()
        val queued by viewModel.queued.collectAsState()

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
                    center = LightTopBarCenter.Text("Episode"),
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                val current = episode
                if (current == null) {
                    LightText(
                        text = "Episode not found.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    return@Column
                }

                LightText(
                    text = current.title,
                    variant = LightTextVariant.Heading,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = episodeDetailLine(current),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                )

                queued?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }

                ActionRow("PLAY NOW") {
                    navigateTo(screenFactory = { PlayerScreen(it, episodeUuid) })
                }
                ActionRow("PLAY NEXT") { viewModel.playNext() }
                ActionRow("PLAY LAST") { viewModel.playLast() }

                ActionRow(if (current.isArchived) "UNARCHIVE" else "ARCHIVE") { viewModel.toggleArchived() }
                ActionRow(if (current.starred) "UNSTAR" else "STAR") { viewModel.toggleStarred() }

                when (current.downloadState) {
                    DownloadState.QUEUED -> ActionRow("QUEUED…") {}
                    DownloadState.DOWNLOADING ->
                        ActionRow("DOWNLOADING ${current.downloadProgress}%") {}
                    DownloadState.DONE -> ActionRow("DELETE DOWNLOAD") { viewModel.deleteDownload() }
                    DownloadState.FAILED -> ActionRow("RETRY DOWNLOAD") { viewModel.download() }
                    else -> ActionRow("DOWNLOAD") { viewModel.download() }
                }
            }
        }
    }

    @Composable
    private fun ActionRow(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Heading,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        )
    }
}
