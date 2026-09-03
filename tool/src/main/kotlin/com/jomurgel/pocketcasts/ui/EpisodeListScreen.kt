package com.jomurgel.pocketcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jomurgel.pocketcasts.AppGraph
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.db.PlayStatus
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class EpisodeFilter(val label: String) {
    ALL("ALL"),
    UNPLAYED("UNPLAYED"),
    IN_PROGRESS("IN PROGRESS"),
}

data class EpisodeListUiState(
    val visible: List<EpisodeEntity> = emptyList(),
    val archivedCount: Int = 0,
    val filter: EpisodeFilter = EpisodeFilter.ALL,
    val showArchived: Boolean = false,
)

/** Archived episodes hide by default; the filter narrows by play state. */
internal fun filterEpisodes(
    episodes: List<EpisodeEntity>,
    filter: EpisodeFilter,
    showArchived: Boolean,
): List<EpisodeEntity> =
    episodes
        .filter { showArchived || !it.isArchived }
        .filter {
            when (filter) {
                EpisodeFilter.ALL -> true
                EpisodeFilter.UNPLAYED -> it.playStatus == PlayStatus.UNPLAYED
                EpisodeFilter.IN_PROGRESS -> it.playStatus == PlayStatus.IN_PROGRESS
            }
        }

class EpisodeListViewModel(
    repository: LibraryRepository,
    podcastUuid: String,
) : LightViewModel<Unit>() {

    private val filter = MutableStateFlow(EpisodeFilter.ALL)
    private val showArchived = MutableStateFlow(false)

    val uiState: StateFlow<EpisodeListUiState> = combine(
        repository.episodesFor(podcastUuid),
        filter,
        showArchived,
    ) { episodes, activeFilter, archived ->
        EpisodeListUiState(
            visible = filterEpisodes(episodes, activeFilter, archived),
            archivedCount = episodes.count { it.isArchived },
            filter = activeFilter,
            showArchived = archived,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, EpisodeListUiState())

    init {
        // Pull this show's account state (progress, archived, starred).
        viewModelScope.launch { repository.refreshEpisodeStates(podcastUuid) }
    }

    fun cycleFilter() {
        val values = EpisodeFilter.entries
        filter.value = values[(values.indexOf(filter.value) + 1) % values.size]
    }

    fun toggleArchived() {
        showArchived.value = !showArchived.value
    }
}

class EpisodeListScreen(
    sealedActivity: SealedLightActivity,
    private val podcastUuid: String,
    private val podcastTitle: String,
) : LightScreen<Unit, EpisodeListViewModel>(sealedActivity) {

    override val viewModelClass: Class<EpisodeListViewModel>
        get() = EpisodeListViewModel::class.java

    override fun createViewModel() =
        EpisodeListViewModel(AppGraph.libraryRepository(lightContext), podcastUuid)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()

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
                    center = LightTopBarCenter.Text(podcastTitle),
                    rightButton = LightBarButton.Text(
                        text = uiState.filter.label,
                        onClick = { viewModel.cycleFilter() },
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (uiState.archivedCount > 0 || uiState.showArchived) {
                    LightText(
                        text = if (uiState.showArchived) {
                            "SHOWING ARCHIVED · TAP TO HIDE"
                        } else {
                            "${uiState.archivedCount} ARCHIVED HIDDEN · TAP TO SHOW"
                        },
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.toggleArchived() }
                            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
                    )
                }

                if (uiState.visible.isEmpty()) {
                    LightText(
                        text = "Nothing here with this filter.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                } else {
                    LazyColumn {
                        items(uiState.visible, key = { it.uuid }) { episode ->
                            EpisodeRow(episode) {
                                navigateTo(
                                    screenFactory = { EpisodeActionsScreen(it, episode.uuid) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeRow(episode: EpisodeEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = episode.title,
            variant = LightTextVariant.Copy,
            maxLines = 2,
        )
        LightText(
            text = episodeDetailLine(episode),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

internal fun episodeDetailLine(episode: EpisodeEntity): String {
    val date = SimpleDateFormat("MMM d", Locale.US).format(Date(episode.publishedAt))
    val duration = formatDuration(episode.durationSec)
    val progress = when {
        episode.playStatus == PlayStatus.COMPLETE -> " · played"
        episode.positionSec > 0 -> " · at ${formatDuration(episode.positionSec)}"
        else -> ""
    }
    val flags = buildString {
        if (episode.starred) append(" · starred")
        if (episode.isArchived) append(" · archived")
    }
    val downloaded = when (episode.downloadState) {
        com.jomurgel.pocketcasts.data.db.DownloadState.DONE -> " · downloaded"
        com.jomurgel.pocketcasts.data.db.DownloadState.DOWNLOADING -> " · downloading"
        com.jomurgel.pocketcasts.data.db.DownloadState.QUEUED -> " · queued"
        else -> ""
    }
    return "$date · $duration$progress$flags$downloaded"
}

internal fun formatDuration(totalSec: Long): String {
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSec}s"
    }
}
