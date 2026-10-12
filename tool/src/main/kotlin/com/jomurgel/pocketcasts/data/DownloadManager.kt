package com.jomurgel.pocketcasts.data

import com.jomurgel.pocketcasts.data.db.DownloadState
import com.jomurgel.pocketcasts.data.db.EpisodeDao
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serial episode downloader: one at a time (kind to the LP3 modem), streamed
 * to a .part file and atomically renamed on completion. State lives on the
 * episode row, so every screen reflects it through the usual Room flows.
 */
class DownloadManager(
    private val episodeDao: EpisodeDao,
    private val client: HttpClient,
    private val downloadsDir: File,
    private val isOnline: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val requests = Channel<String>(Channel.UNLIMITED)

    init {
        downloadsDir.mkdirs()
        scope.launch {
            episodeDao.resetInterruptedDownloads()
            sweepOrphans()
            for (uuid in requests) {
                process(uuid)
            }
        }
    }

    fun enqueue(episodeUuid: String) {
        scope.launch {
            // Already on disk or on its way: re-queueing would discard the file
            // (or the in-flight transfer) and fetch the whole episode again.
            val state = episodeDao.get(episodeUuid)?.downloadState
            if (state == DownloadState.QUEUED ||
                state == DownloadState.DOWNLOADING ||
                state == DownloadState.DONE
            ) {
                return@launch
            }
            episodeDao.updateDownloadState(episodeUuid, DownloadState.QUEUED, 0, null)
            requests.send(episodeUuid)
        }
    }

    suspend fun delete(episodeUuid: String) {
        episodeDao.get(episodeUuid)?.filePath?.let { File(it).delete() }
        episodeDao.updateDownloadState(episodeUuid, DownloadState.NONE, 0, null)
    }

    suspend fun clearAll() {
        episodeDao.downloadedEpisodes().forEach { delete(it.uuid) }
        sweepOrphans()
    }

    /** Total bytes on disk across downloaded episodes. */
    suspend fun storageBytes(): Long =
        episodeDao.downloadedEpisodes().sumOf { episode ->
            episode.filePath?.let { File(it).length() } ?: 0L
        }

    private suspend fun process(uuid: String) {
        val episode = episodeDao.get(uuid) ?: return
        if (episode.downloadState != DownloadState.QUEUED) return
        if (!isOnline()) {
            episodeDao.updateDownloadState(uuid, DownloadState.FAILED, 0, null)
            return
        }
        episodeDao.updateDownloadState(uuid, DownloadState.DOWNLOADING, 0, null)
        val part = File(downloadsDir, "$uuid.part")
        val destination = File(downloadsDir, "$uuid.mp3")
        try {
            client.prepareGet(episode.audioUrl) {
                // The client's request timeout covers the whole call, body included,
                // and an episode takes minutes on a phone connection. Bound the gap
                // between reads instead so a stalled transfer still fails.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = DOWNLOAD_STALL_TIMEOUT_MS
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IOException("HTTP ${response.status.value}")
                }
                val totalBytes = response.contentLength() ?: -1L
                val channel = response.bodyAsChannel()
                var received = 0L
                var lastReportedPercent = -1
                part.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        received += read
                        if (totalBytes > 0) {
                            val percent = ((received * 100) / totalBytes).toInt()
                            if (percent >= lastReportedPercent + 5) {
                                lastReportedPercent = percent
                                episodeDao.updateDownloadState(uuid, DownloadState.DOWNLOADING, percent, null)
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(destination)) throw IOException("rename failed")
            episodeDao.updateDownloadState(uuid, DownloadState.DONE, 100, destination.absolutePath)
        } catch (error: CancellationException) {
            // Shutting down: the row stays DOWNLOADING and is reset on next start.
            part.delete()
            throw error
        } catch (error: Exception) {
            part.delete()
            episodeDao.updateDownloadState(uuid, DownloadState.FAILED, 0, null)
        }
    }

    /** Files no episode claims (crashed installs, replaced feeds) get removed;
     * downloaded rows whose file vanished go back to not-downloaded. */
    private suspend fun sweepOrphans() {
        val downloaded = episodeDao.downloadedEpisodes()
        val claimed = downloaded.mapNotNull { it.filePath }.map { File(it).name }.toSet()
        downloadsDir.listFiles()?.forEach { file ->
            if (file.name !in claimed) file.delete()
        }
        downloaded.forEach { episode ->
            val path = episode.filePath
            if (path == null || !File(path).exists()) {
                episodeDao.updateDownloadState(episode.uuid, DownloadState.NONE, 0, null)
            }
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val DOWNLOAD_STALL_TIMEOUT_MS = 30_000L
    }
}
