package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.api.AuthError
import com.jomurgel.pocketcasts.data.api.PocketCastsAuthApi
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TOKENS_JSON = """
{
  "email": "jo@example.com",
  "uuid": "user-uuid-1",
  "isNew": false,
  "accessToken": "access-1",
  "refreshToken": "refresh-1",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "someFutureField": {"nested": true}
}
"""

private fun jsonHeaders() = headersOf("Content-Type", "application/json")

private fun apiWith(engine: MockEngine) =
    PocketCastsAuthApi(pocketCastsHttpClient(engine), baseUrl = "https://api.test")

class PocketCastsAuthApiTest {

    @Test
    fun `login success parses tokens and tolerates unknown fields`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/user/login_pocket_casts", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue("\"email\":\"jo@example.com\"" in body)
            assertTrue("\"scope\"" in body)
            respond(TOKENS_JSON, HttpStatusCode.OK, jsonHeaders())
        }
        val response = apiWith(engine).login("jo@example.com", "hunter2")
        assertEquals("access-1", response.accessToken)
        assertEquals("refresh-1", response.refreshToken)
        assertEquals("user-uuid-1", response.uuid)
    }

    @Test
    fun `login 401 surfaces invalid credentials with server message`() = runTest {
        val engine = MockEngine {
            respond("""{"errorMessage":"Invalid email or password"}""", HttpStatusCode.Unauthorized, jsonHeaders())
        }
        val error = assertFailsWith<AuthError.InvalidCredentials> {
            apiWith(engine).login("jo@example.com", "wrong")
        }
        assertEquals("Invalid email or password", error.message)
    }

    @Test
    fun `login 500 is a server error, not credentials`() = runTest {
        val engine = MockEngine { respond("oops", HttpStatusCode.InternalServerError, jsonHeaders()) }
        val error = assertFailsWith<AuthError.Server> {
            apiWith(engine).login("jo@example.com", "hunter2")
        }
        assertEquals(500, error.status)
    }

    @Test
    fun `refresh posts grant_type refresh_token to user token`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/user/token", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue("\"grant_type\":\"refresh_token\"" in body)
            assertTrue("\"refresh_token\":\"refresh-1\"" in body)
            respond(TOKENS_JSON, HttpStatusCode.OK, jsonHeaders())
        }
        val response = apiWith(engine).refresh("refresh-1")
        assertEquals("access-1", response.accessToken)
    }
}
