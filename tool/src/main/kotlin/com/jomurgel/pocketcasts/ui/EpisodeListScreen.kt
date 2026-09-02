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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class EpisodeListViewModel(
    repository: LibraryRepository,
    podcastUuid: String,
) : LightViewModel<Unit>() {

    val episodes: StateFlow<List<EpisodeEntity>> = repository.episodesFor(podcastUuid)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
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
        val episodes by viewModel.episodes.collectAsState()

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
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (episodes.isEmpty()) {
                    LightText(
                        text = "No episodes yet.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                } else {
                    LazyColumn {
                        items(episodes, key = { it.uuid }) { episode ->
                            EpisodeRow(episode) {
                                navigateTo(
                                    screenFactory = { PlayerScreen(it, episode.uuid) },
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
    return "$date · $duration$progress"
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
