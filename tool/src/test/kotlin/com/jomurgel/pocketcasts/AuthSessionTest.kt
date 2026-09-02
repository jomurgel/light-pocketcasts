package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthTokens
import com.jomurgel.pocketcasts.data.api.AuthApi
import com.jomurgel.pocketcasts.data.api.AuthError
import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.LoginTokenResponseDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun tokens(access: String = "old-access") = AuthTokens(
    accessToken = access,
    refreshToken = "refresh-1",
    email = "jo@example.com",
    accountUuid = "user-uuid-1",
)

private class ScriptedAuthApi(
    private val onRefresh: suspend (String) -> LoginTokenResponseDto,
) : AuthApi {
    var refreshCalls = 0

    override suspend fun login(email: String, password: String): LoginTokenResponseDto =
        error("not under test")

    override suspend fun refresh(refreshToken: String): LoginTokenResponseDto {
        refreshCalls++
        return onRefresh(refreshToken)
    }
}

class AuthSessionTest {

    @Test
    fun `refreshedBearer refreshes once and persists new tokens`() = runTest {
        val store = FakeTokenStore(tokens())
        val api = ScriptedAuthApi { LoginTokenResponseDto(accessToken = "new-access", refreshToken = "refresh-2") }
        val session = AuthSession(store, api)

        val bearer = session.refreshedBearer("old-access")

        assertEquals("new-access", bearer)
        assertEquals(1, api.refreshCalls)
        assertEquals("new-access", store.tokens?.accessToken)
        assertEquals("refresh-2", store.tokens?.refreshToken)
    }

    @Test
    fun `blank refresh token in response keeps the old one`() = runTest {
        val store = FakeTokenStore(tokens())
        val api = ScriptedAuthApi { LoginTokenResponseDto(accessToken = "new-access") }
        AuthSession(store, api).refreshedBearer("old-access")
        assertEquals("refresh-1", store.tokens?.refreshToken)
    }

    @Test
    fun `stale caller after a refresh gets current token without second refresh`() = runTest {
        val store = FakeTokenStore(tokens(access = "already-fresh"))
        val api = ScriptedAuthApi { error("must not refresh") }
        val session = AuthSession(store, api)

        val bearer = session.refreshedBearer("old-access")

        assertEquals("already-fresh", bearer)
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun `concurrent refreshes collapse to one network call`() = runTest {
        val store = FakeTokenStore(tokens())
        val api = ScriptedAuthApi { LoginTokenResponseDto(accessToken = "new-access", refreshToken = "refresh-2") }
        val session = AuthSession(store, api)

        coroutineScope {
            val a = async { session.refreshedBearer("old-access") }
            val b = async { session.refreshedBearer("old-access") }
            assertEquals("new-access", a.await())
            assertEquals("new-access", b.await())
        }
        assertEquals(1, api.refreshCalls)
    }

    @Test
    fun `invalid refresh signs the session out`() = runTest {
        val store = FakeTokenStore(tokens())
        val api = ScriptedAuthApi { throw AuthError.InvalidCredentials("expired") }
        val session = AuthSession(store, api)
        var expired = false
        session.onSessionExpired = { expired = true }

        assertFailsWith<AuthError.SessionExpired> { session.refreshedBearer("old-access") }
        assertTrue(expired)
    }

    @Test
    fun `transient network failure does not sign out`() = runTest {
        val store = FakeTokenStore(tokens())
        val api = ScriptedAuthApi { throw AuthError.Network(RuntimeException("offline")) }
        val session = AuthSession(store, api)
        var expired = false
        session.onSessionExpired = { expired = true }

        assertFailsWith<AuthError.Network> { session.refreshedBearer("old-access") }
        assertEquals(false, expired)
        assertEquals("old-access", store.tokens?.accessToken)
    }
}
