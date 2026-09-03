package com.jomurgel.pocketcasts.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

const val API_BASE_URL = "https://api.pocketcasts.com"
const val CACHE_BASE_URL = "https://cache.pocketcasts.com"

/** Fields WILL drift on an unofficial API; never let that crash parsing. */
val pocketCastsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // Record pushes must omit unset optional fields entirely.
    explicitNulls = false
}

/**
 * Shared client factory. Pass an engine in tests (MockEngine); defaults to
 * OkHttp on device.
 */
fun pocketCastsHttpClient(engine: HttpClientEngine? = null): HttpClient {
    val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(ContentNegotiation) { json(pocketCastsJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
        install(UserAgent) { agent = "LightPocketCasts/0.1 (Light Phone tool)" }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(OkHttp, config)
}
