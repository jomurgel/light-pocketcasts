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
import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.AuthState
import com.jomurgel.pocketcasts.data.DownloadManager
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val downloadManager: DownloadManager,
) : LightViewModel<Unit>() {

    val authState = authRepository.authState

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes = _storageBytes.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refreshStorage()
    }

    fun clearDownloads() {
        viewModelScope.launch {
            downloadManager.clearAll()
            refreshStorage()
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    private fun refreshStorage() {
        viewModelScope.launch { _storageBytes.value = downloadManager.storageBytes() }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(
        AppGraph.authRepository(lightContext),
        AppGraph.downloadManager(lightContext),
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val authState by viewModel.authState.collectAsState()
        val storageBytes by viewModel.storageBytes.collectAsState()

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
                    center = LightTopBarCenter.Text("Settings"),
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                val signedInAs = (authState as? AuthState.SignedIn)?.email
                LightText(
                    text = signedInAs?.let { "Signed in as $it" } ?: "Not signed in",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Downloads: ${formatBytes(storageBytes)}",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                )

                SettingsAction("CLEAR DOWNLOADS") { viewModel.clearDownloads() }
                if (signedInAs != null) {
                    SettingsAction("SIGN OUT") {
                        viewModel.signOut()
                        goBack(null)
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsAction(label: String, onClick: () -> Unit) {
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

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
