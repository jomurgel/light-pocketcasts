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
import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.AuthState
import com.jomurgel.pocketcasts.data.LibraryRepository
import com.jomurgel.pocketcasts.data.RefreshState
import com.jomurgel.pocketcasts.data.db.PodcastEntity
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.playback.PlaybackController
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    playbackController: PlaybackController,
    private val upNextQueue: UpNextQueue,
) : LightViewModel<Unit>() {

    val authState = authRepository.authState
    val refreshState = libraryRepository.refreshState

    /** Live session here, or a synced queue head from another device. */
    val showPlaying: StateFlow<Boolean> =
        combine(playbackController.nowPlaying, upNextQueue.queue) { nowPlaying, queue ->
            nowPlaying != null || queue.isNotEmpty()
        }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val podcasts: StateFlow<List<PodcastEntity>> = libraryRepository.podcasts
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var refreshedThisSession = false
    private var lastQueueSyncMs = 0L

    /** Re-sync the queue whenever Home comes back on screen (throttled). */
    override fun onScreenShow(screen: com.thelightphone.sdk.SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        val now = System.currentTimeMillis()
        if (now - lastQueueSyncMs < 30_000) return
        lastQueueSyncMs = now
        viewModelScope.launch { upNextQueue.sync() }
    }

    init {
        viewModelScope.launch {
            authRepository.restore()
            authRepository.authState.collect { state ->
                if (state is AuthState.SignedIn && !refreshedThisSession) {
                    refreshedThisSession = true
                    // Queue sync first: it's one cheap call and drives the
                    // PLAYING affordance; the full library refresh can take
                    // a while on a large account.
                    launch { upNextQueue.sync() }
                    libraryRepository.refresh()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { libraryRepository.refresh() }
    }

    fun signOut() {
        viewModelScope.launch {
            refreshedThisSession = false
            authRepository.signOut()
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(
        AppGraph.authRepository(lightContext),
        AppGraph.libraryRepository(lightContext),
        AppGraph.playbackController(lightContext),
        AppGraph.upNextQueue(lightContext),
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val authState by viewModel.authState.collectAsState()
        val refreshState by viewModel.refreshState.collectAsState()
        val podcasts by viewModel.podcasts.collectAsState()
        val showPlaying by viewModel.showPlaying.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = if (showPlaying) {
                        LightBarButton.Text(
                            text = "PLAYING",
                            onClick = { navigateTo(screenFactory = { PlayerScreen(it) }) },
                        )
                    } else {
                        null
                    },
                    center = LightTopBarCenter.Text("Pocket Casts"),
                    rightButton = if (authState is AuthState.SignedIn) {
                        LightBarButton.Text(
                            text = if (refreshState is RefreshState.Refreshing) "SYNCING" else "SYNC",
                            onClick = { viewModel.refresh() },
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (authState) {
                    is AuthState.Unknown -> Unit

                    is AuthState.SignedOut -> SignedOutContent()

                    is AuthState.SignedIn -> SignedInContent(
                        podcasts = podcasts,
                        refreshState = refreshState,
                    )
                }
            }
        }
    }

    @Composable
    private fun SignedOutContent() {
        LightText(
            text = "Listen to your Pocket Casts subscriptions on your Light Phone.",
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = "SIGN IN",
            variant = LightTextVariant.Heading,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { navigateTo(screenFactory = { SignInScreen(it) }) }
                .padding(1f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun SignedInContent(
        podcasts: List<PodcastEntity>,
        refreshState: RefreshState,
    ) {
        if (refreshState is RefreshState.Failed) {
            LightText(
                text = refreshState.message,
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
            )
        }

        if (podcasts.isEmpty() && refreshState is RefreshState.Refreshing) {
            LightText(
                text = "Syncing your library…",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(1f.gridUnitsAsDp()),
            )
            return
        }

        LazyColumn {
            // Home is podcasts only; a Now Playing surface (current episode +
            // queue) arrives with the player and navigates from the top bar.
            item { SectionHeader("PODCASTS") }
            if (podcasts.isEmpty()) {
                item {
                    LightText(
                        text = "No subscriptions found on this account.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }
            }
            items(podcasts, key = { it.uuid }) { podcast ->
                PodcastRow(podcast) {
                    navigateTo(
                        screenFactory = { EpisodeListScreen(it, podcast.uuid, podcast.title) },
                    )
                }
            }

            item {
                LightText(
                    text = "SIGN OUT",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.signOut() }
                        .padding(1f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    LightText(
        text = title,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(
            top = 1f.gridUnitsAsDp(),
            bottom = 0.25f.gridUnitsAsDp(),
        ).padding(horizontal = 1f.gridUnitsAsDp()),
    )
}

@Composable
private fun PodcastRow(podcast: PodcastEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = podcast.title,
            variant = LightTextVariant.Copy,
            maxLines = 1,
        )
        LightText(
            text = if (podcast.author.isBlank()) {
                "${podcast.episodeCount} episodes"
            } else {
                "${podcast.author} · ${podcast.episodeCount} episodes"
            },
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
        )
    }
}
