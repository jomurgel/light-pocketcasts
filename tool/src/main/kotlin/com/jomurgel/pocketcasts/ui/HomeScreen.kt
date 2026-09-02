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
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch

class HomeViewModel(private val authRepository: AuthRepository) : LightViewModel<Unit>() {

    val authState = authRepository.authState

    init {
        viewModelScope.launch { authRepository.restore() }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(AppGraph.authRepository(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val authState by viewModel.authState.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = null,
                    center = LightTopBarCenter.Text("Pocket Casts"),
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val state = authState) {
                    is AuthState.Unknown -> Unit // restoring; blank beats a flash of sign-in

                    is AuthState.SignedOut -> {
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
                                .lightClickable {
                                    navigateTo(screenFactory = { SignInScreen(it) })
                                }
                                .padding(1f.gridUnitsAsDp()),
                        )
                    }

                    is AuthState.SignedIn -> {
                        LightText(
                            text = "Signed in as ${state.email}",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "Library sync arrives in the next task.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                        )
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
    }
}
