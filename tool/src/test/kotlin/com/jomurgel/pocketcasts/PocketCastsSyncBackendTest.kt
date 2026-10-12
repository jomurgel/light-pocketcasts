package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.api.AuthSession
import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import com.jomurgel.pocketcasts.data.db.PlayStatus
import com.jomurgel.pocketcasts.data.sync.PocketCastsSyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException
import com.jomurgel.pocketcasts.data.sync.isoToEpochMs
import com.jomurgel.pocketcasts.data.AuthTokens
import com.jomurgel.pocketcasts.data.api.AuthApi
import com.jomurgel.pocketcasts.data.api.LoginTokenResponseDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val PODCAST_LIST_JSON = """
{"podcasts":[
  {"uuid":"p1","title":"Show One","author":"Author A","unknownField":1},
  {"uuid":"p2","title":"Show Two","author":""}
]}
"""

private val CATALOG_JSON = """
{"podcast":{"uuid":"p1","title":"Show One","episodes":[
  {"uuid":"e1","title":"First","url":"https://cdn.test/e1.mp3","duration":1800.5,"published":"2026-08-30T10:00:00Z"},
  {"uuid":"e2","title":"HLS only","url":"","duration":900.0,"published":"2026-08-29T10:00:00Z"}
]}}
"""

private val IN_PROGRESS_JSON = """
{"episodes":[
  {"uuid":"e1","podcastUuid":"p1","playedUpTo":642,"playingStatus":2,"duration":1800.5},
  {"uuid":"orphan","playedUpTo":10,"playingStatus":2}
]}
"""

private fun jsonHeaders() = headersOf("Content-Type", "application/json")

private class NoRefreshApi : AuthApi {
    override suspend fun login(email: String, password: String) = error("not under test")
    override suspend fun refresh(refreshToken: String): LoginTokenResponseDto =
        LoginTokenResponseDto(accessToken = "fresh-token", refreshToken = "refresh-2")
}

private fun sessionWith(tokens: AuthTokens?) =
    AuthSession(FakeTokenStore(tokens), NoRefreshApi())

private fun signedInTokens() = AuthTokens("token-1", "refresh-1", "jo@example.com", "u1")

private fun backendWith(engine: MockEngine, tokens: AuthTokens? = signedInTokens()): PocketCastsSyncBackend {
    val api = PocketCastsSyncApi(
        client = pocketCastsHttpClient(engine),
        session = sessionWith(tokens),
        apiBase = "https://api.test",
        cacheBase = "https://cache.test",
    )
    return PocketCastsSyncBackend(api)
}

class PocketCastsSyncBackendTest {

    @Test
    fun `fetchSubscriptions parses the podcast list`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/user/podcast/list", request.url.encodedPath)
            assertEquals("Bearer token-1", request.headers["Authorization"])
            respond(PODCAST_LIST_JSON, HttpStatusCode.OK, jsonHeaders())
        }
        val subscriptions = backendWith(engine).fetchSubscriptions()
        assertEquals(2, subscriptions.size)
        assertEquals("Show One", subscriptions[0].title)
    }

    @Test
    fun `fetchEpisodes maps catalog and drops url-less episodes`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/mobile/podcast/full/p1", request.url.encodedPath)
            respond(CATALOG_JSON, HttpStatusCode.OK, jsonHeaders())
        }
        val episodes = backendWith(engine).fetchEpisodes("p1")
        assertEquals(1, episodes.size)
        assertEquals("e1", episodes[0].uuid)
        assertEquals(1800, episodes[0].durationSec)
        assertEquals(isoToEpochMs("2026-08-30T10:00:00Z"), episodes[0].publishedAtMs)
    }

    @Test
    fun `fetchInProgress maps state and drops orphans without podcastUuid`() = runTest {
        val engine = MockEngine { respond(IN_PROGRESS_JSON, HttpStatusCode.OK, jsonHeaders()) }
        val states = backendWith(engine).fetchInProgress()
        assertEquals(1, states.size)
        assertEquals(642, states[0].positionSec)
        assertEquals(PlayStatus.IN_PROGRESS, states[0].playStatus)
    }

    @Test
    fun `401 triggers one refresh and a retry`() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            if (request.headers["Authorization"] == "Bearer fresh-token") {
                respond(PODCAST_LIST_JSON, HttpStatusCode.OK, jsonHeaders())
            } else {
                respond("{}", HttpStatusCode.Unauthorized, jsonHeaders())
            }
        }
        val subscriptions = backendWith(engine).fetchSubscriptions()
        assertEquals(2, subscriptions.size)
        assertEquals(2, calls)
    }

    @Test
    fun `no stored tokens means signed out`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders()) }
        assertFailsWith<SyncException.SignedOut> {
            backendWith(engine, tokens = null).fetchSubscriptions()
        }
    }

    @Test
    fun `server errors surface with status`() = runTest {
        val engine = MockEngine { respond("oops", HttpStatusCode.BadGateway, jsonHeaders()) }
        val error = assertFailsWith<SyncException.Server> {
            backendWith(engine).fetchSubscriptions()
        }
        assertEquals(502, error.status)
    }

    @Test
    fun `a subscription response without a podcast list is an error, not an empty library`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders()) }

        assertFailsWith<SyncException.Server> { backendWith(engine).fetchSubscriptions() }
    }
}
