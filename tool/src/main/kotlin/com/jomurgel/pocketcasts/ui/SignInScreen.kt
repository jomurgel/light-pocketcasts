package com.jomurgel.pocketcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jomurgel.pocketcasts.AppGraph
import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.SignInResult
import com.jomurgel.pocketcasts.data.api.AuthError
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignInViewModel(private val authRepository: AuthRepository) : LightViewModel<Unit>() {

    data class UiState(
        val email: String = "",
        val password: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setEmail(value: String) {
        _state.value = _state.value.copy(email = value.trim(), error = null)
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun signIn() {
        val current = _state.value
        if (current.busy) return
        if (current.email.isBlank() || current.password.isBlank()) {
            _state.value = current.copy(error = "Email and password are required.")
            return
        }
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = authRepository.signIn(current.email, current.password)) {
                is SignInResult.Success ->
                    _state.value = _state.value.copy(busy = false, done = true)
                is SignInResult.Failure ->
                    _state.value = _state.value.copy(busy = false, error = result.error.toUserMessage())
            }
        }
    }
}

private fun AuthError.toUserMessage(): String = when (this) {
    is AuthError.InvalidCredentials -> "Sign in failed. Check your email and password."
    is AuthError.Network -> "No connection. Try again."
    is AuthError.Server -> "Pocket Casts is unavailable right now ($status)."
    is AuthError.SessionExpired -> "Session expired. Sign in again."
}

class SignInScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SignInViewModel>(sealedActivity) {

    override val viewModelClass: Class<SignInViewModel>
        get() = SignInViewModel::class.java

    override fun createViewModel() = SignInViewModel(AppGraph.authRepository(lightContext))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        // An effect, not a call in the composable body: the body can run again
        // before this screen leaves, and each run would pop another screen.
        LaunchedEffect(state.done) {
            if (state.done) goBack(Unit)
        }

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
                    center = LightTopBarCenter.Text("Sign in"),
                    rightButton = null,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightTextField(
                    label = "Email:",
                    value = state.email,
                    placeholder = "you@example.com",
                    onClick = {
                        navigateTo(
                            screenFactory = {
                                TextEditScreen(it, EditRequest("Email", state.email))
                            },
                            resultCallback = { value -> viewModel.setEmail(value) },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                )

                LightTextField(
                    label = "Password:",
                    value = "•".repeat(state.password.length),
                    placeholder = "Password",
                    onClick = {
                        navigateTo(
                            screenFactory = {
                                PasswordEditScreen(it, "Password", state.password)
                            },
                            resultCallback = { value -> viewModel.setPassword(value) },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                )

                state.error?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    )
                }

                LightText(
                    text = if (state.busy) "SIGNING IN…" else "SIGN IN",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.signIn() }
                        .padding(1f.gridUnitsAsDp()),
                )
            }
        }
    }
}
