package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthRepository
import com.jomurgel.pocketcasts.data.AuthState
import com.jomurgel.pocketcasts.data.SignInResult
import com.jomurgel.pocketcasts.data.api.AuthApi
import com.jomurgel.pocketcasts.data.api.AuthError
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.LoginTokenResponseDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class LoginScriptApi(
    private val onLogin: suspend (String, String) -> LoginTokenResponseDto,
) : AuthApi {
    override suspend fun login(email: String, password: String): LoginTokenResponseDto =
        onLogin(email, password)

    override suspend fun refresh(refreshToken: String): LoginTokenResponseDto =
        error("not under test")
}

private fun repo(store: FakeTokenStore, api: AuthApi): AuthRepository =
    AuthRepository(store, api, AuthSession(store, api))

class AuthRepositoryTest {

    @Test
    fun `restore with no tokens is signed out`() = runTest {
        val repository = repo(FakeTokenStore(), LoginScriptApi { _, _ -> error("no") })
        repository.restore()
        assertEquals(AuthState.SignedOut, repository.authState.value)
    }

    @Test
    fun `sign in persists tokens and updates state`() = runTest {
        val store = FakeTokenStore()
        val api = LoginScriptApi { _, _ ->
            LoginTokenResponseDto(
                email = "jo@example.com",
                uuid = "user-uuid-1",
                accessToken = "access-1",
                refreshToken = "refresh-1",
            )
        }
        val repository = repo(store, api)

        val result = repository.signIn("jo@example.com", "hunter2")

        assertIs<SignInResult.Success>(result)
        assertEquals("access-1", store.tokens?.accessToken)
        assertEquals(AuthState.SignedIn("jo@example.com"), repository.authState.value)
    }

    @Test
    fun `failed sign in leaves signed out state and no tokens`() = runTest {
        val store = FakeTokenStore()
        val repository = repo(store, LoginScriptApi { _, _ -> throw AuthError.InvalidCredentials() })

        repository.restore()
        val result = repository.signIn("jo@example.com", "wrong")

        assertIs<SignInResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
        assertNull(store.tokens)
        assertEquals(AuthState.SignedOut, repository.authState.value)
    }

    @Test
    fun `sign out clears tokens`() = runTest {
        val store = FakeTokenStore()
        val api = LoginScriptApi { _, _ ->
            LoginTokenResponseDto(accessToken = "access-1", refreshToken = "refresh-1")
        }
        val repository = repo(store, api)
        repository.signIn("jo@example.com", "hunter2")

        repository.signOut()

        assertNull(store.tokens)
        assertEquals(AuthState.SignedOut, repository.authState.value)
    }
}
