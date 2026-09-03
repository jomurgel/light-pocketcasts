package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.DownloadManager
import com.jomurgel.pocketcasts.data.api.pocketCastsHttpClient
import com.jomurgel.pocketcasts.data.db.DownloadState
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun episode(uuid: String, filePath: String? = null, state: Int = DownloadState.NONE) = EpisodeEntity(
    uuid = uuid, podcastUuid = "p1", title = "Episode $uuid",
    publishedAt = 1, durationSec = 1800, audioUrl = "https://cdn.test/$uuid.mp3",
    downloadState = state, filePath = filePath,
)

/**
 * The manager is genuinely asynchronous (its own scope, Ktor engine threads),
 * so these tests run it on real dispatchers and await observable state.
 */
class DownloadManagerTest {

    private val episodeDao = FakeEpisodeDao()
    private val dir = createTempDirectory("downloads").toFile()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioBytes = ByteArray(10_000) { (it % 251).toByte() }

    @AfterTest
    fun tearDown() {
        managerScope.cancel()
        dir.deleteRecursively()
    }

    private fun engineServing() = MockEngine {
        respond(
            content = audioBytes,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Length", audioBytes.size.toString()),
        )
    }

    private fun manager(engine: MockEngine, online: Boolean = true) = DownloadManager(
        episodeDao = episodeDao,
        client = pocketCastsHttpClient(engine),
        downloadsDir = dir,
        isOnline = { online },
        scope = managerScope,
    )

    private suspend fun awaitUntil(what: String = "condition", condition: suspend () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (!condition()) delay(20)
            }
        }
    }

    private suspend fun awaitState(uuid: String, state: Int) =
        awaitUntil("state $state") { episodeDao.get(uuid)?.downloadState == state }

    @Test
    fun `happy path downloads to a final file and marks done`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        manager(engineServing()).enqueue("e1")

        awaitState("e1", DownloadState.DONE)

        val updated = episodeDao.get("e1")!!
        val file = File(updated.filePath!!)
        assertTrue(file.exists())
        assertEquals(audioBytes.size.toLong(), file.length())
        assertEquals(100, updated.downloadProgress)
        assertFalse(File(dir, "e1.part").exists())
    }

    @Test
    fun `http failure marks failed and leaves no partial file`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        manager(MockEngine { respondError(HttpStatusCode.NotFound) }).enqueue("e1")

        awaitState("e1", DownloadState.FAILED)

        assertFalse(File(dir, "e1.part").exists())
        assertFalse(File(dir, "e1.mp3").exists())
    }

    @Test
    fun `offline enqueue fails fast without a request`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        val engine = engineServing()
        manager(engine, online = false).enqueue("e1")

        awaitState("e1", DownloadState.FAILED)
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `delete removes the file and resets state`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        val manager = manager(engineServing())
        manager.enqueue("e1")
        awaitState("e1", DownloadState.DONE)

        val path = episodeDao.get("e1")!!.filePath!!
        manager.delete("e1")

        assertFalse(File(path).exists())
        assertEquals(DownloadState.NONE, episodeDao.get("e1")!!.downloadState)
    }

    @Test
    fun `downloads run one at a time in order`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1"), episode("e2")))
        val order = mutableListOf<String>()
        val engine = MockEngine { request ->
            synchronized(order) { order += request.url.encodedPath }
            respond(audioBytes, HttpStatusCode.OK, headersOf("Content-Length", audioBytes.size.toString()))
        }
        val manager = manager(engine)

        manager.enqueue("e1")
        manager.enqueue("e2")
        awaitState("e1", DownloadState.DONE)
        awaitState("e2", DownloadState.DONE)

        assertEquals(listOf("/e1.mp3", "/e2.mp3"), order)
    }

    @Test
    fun `startup resets interrupted downloads and sweeps orphans`() = runTest {
        val orphan = File(dir, "orphan.mp3").apply { writeBytes(ByteArray(10)) }
        episodeDao.insertIgnore(
            listOf(
                episode("stuck", state = DownloadState.DOWNLOADING),
                episode("gone", state = DownloadState.DONE).copy(filePath = File(dir, "missing.mp3").absolutePath),
            ),
        )
        manager(engineServing())

        awaitState("stuck", DownloadState.FAILED)
        awaitState("gone", DownloadState.NONE)
        awaitUntil("orphan removed") { !orphan.exists() }
    }

    @Test
    fun `storage bytes sums real files`() = runTest {
        episodeDao.insertIgnore(listOf(episode("e1")))
        val manager = manager(engineServing())
        manager.enqueue("e1")
        awaitState("e1", DownloadState.DONE)

        assertEquals(audioBytes.size.toLong(), manager.storageBytes())
    }
}
