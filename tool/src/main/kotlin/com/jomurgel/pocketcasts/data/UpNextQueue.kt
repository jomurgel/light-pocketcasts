package com.jomurgel.pocketcasts.data

import com.jomurgel.pocketcasts.data.db.SyncMetaDao
import com.jomurgel.pocketcasts.data.db.SyncMetaEntity
import com.jomurgel.pocketcasts.data.sync.QueueAction
import com.jomurgel.pocketcasts.data.sync.RemoteQueueChange
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One entry in the Up Next queue. Index 0 is the current episode. Entries can
 * be "skeletons" — episodes queued from another device that aren't in our
 * catalog cache; they carry enough to play.
 */
@Serializable
data class QueueItem(
    val episodeUuid: String,
    val podcastUuid: String,
    val title: String,
    val audioUrl: String,
)

@Serializable
data class QueueChange(
    val action: Int,
    val modifiedAtMs: Long,
    val item: QueueItem?,
)

private const val KEY_QUEUE = "up_next_queue"
private const val KEY_CHANGES = "up_next_changes"
private const val KEY_WATERMARK = "up_next_server_modified"

private val queueJson = Json { ignoreUnknownKeys = true }

/**
 * The Up Next queue, mirroring Pocket Casts' model: local edits append to a
 * change log; [sync] pushes the log with our watermark and adopts whatever
 * queue the server answers with. The server owns the order — we never diff.
 *
 * Persisted as JSON in sync_meta because the SDK's buildDatabase exposes no
 * Room migration hooks; the queue is small and bounded, so this is fine.
 */
class UpNextQueue(
    private val syncMetaDao: SyncMetaDao,
    private val backend: SyncBackend,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var loaded = false

    /** Bumped by [reset], so a sync begun before it can tell its answer is stale. */
    private var generation = 0

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private suspend fun ensureLoaded() {
        if (loaded) return
        _queue.value = readQueue()
        loaded = true
    }

    /**
     * Forget the queue, here and in storage. A sync still in flight was for
     * the account being cleared; its answer is dropped when it lands.
     */
    suspend fun reset() {
        mutex.withLock {
            generation++
            syncMetaDao.delete(listOf(KEY_QUEUE, KEY_CHANGES, KEY_WATERMARK))
            _queue.value = emptyList()
            loaded = false
        }
    }

    /** Current episode across devices = head of the queue. */
    suspend fun head(): QueueItem? {
        mutex.withLock { ensureLoaded() }
        return _queue.value.firstOrNull()
    }

    suspend fun playNow(item: QueueItem) = edit(QueueAction.PLAY_NOW, item) { queue ->
        listOf(item) + queue.filterNot { it.episodeUuid == item.episodeUuid }
    }

    suspend fun playNext(item: QueueItem) = edit(QueueAction.PLAY_NEXT, item) { queue ->
        val rest = queue.filterNot { it.episodeUuid == item.episodeUuid }
        if (rest.isEmpty()) listOf(item) else listOf(rest.first()) + item + rest.drop(1)
    }

    suspend fun playLast(item: QueueItem) = edit(QueueAction.PLAY_LAST, item) { queue ->
        queue.filterNot { it.episodeUuid == item.episodeUuid } + item
    }

    suspend fun remove(episodeUuid: String) {
        mutex.withLock {
            ensureLoaded()
            val item = _queue.value.firstOrNull { it.episodeUuid == episodeUuid } ?: return
            applyEdit(QueueAction.REMOVE, item) { queue ->
                queue.filterNot { it.episodeUuid == episodeUuid }
            }
        }
    }

    /** Move an upcoming episode to just after the current one. */
    suspend fun moveToTop(episodeUuid: String) {
        val item = _queue.value.firstOrNull { it.episodeUuid == episodeUuid } ?: return
        remove(episodeUuid)
        playNext(item)
    }

    private suspend fun edit(action: Int, item: QueueItem, transform: (List<QueueItem>) -> List<QueueItem>) {
        mutex.withLock {
            ensureLoaded()
            applyEdit(action, item, transform)
        }
    }

    private suspend fun applyEdit(action: Int, item: QueueItem, transform: (List<QueueItem>) -> List<QueueItem>) {
        _queue.value = transform(_queue.value)
        writeQueue(_queue.value)
        appendChange(QueueChange(action, now(), item))
    }

    /**
     * Push pending changes, adopt the server's queue. Only the changes that
     * were actually sent get cleared; edits made mid-flight survive.
     */
    suspend fun sync(): Boolean {
        val started: Int
        val (watermark, pending) = mutex.withLock {
            ensureLoaded()
            started = generation
            readWatermark() to readChanges()
        }
        val result = try {
            backend.syncUpNext(watermark, pending.map { it.toRemote() })
        } catch (_: SyncException) {
            return false
        }
        mutex.withLock {
            if (generation != started) return false
            result.episodes?.let { episodes ->
                _queue.value = episodes.map {
                    QueueItem(
                        episodeUuid = it.uuid,
                        podcastUuid = it.podcastUuid,
                        title = it.title,
                        audioUrl = it.audioUrl,
                    )
                }
                writeQueue(_queue.value)
            }
            writeWatermark(result.serverModified)
            val remaining = readChanges().drop(pending.size)
            writeChanges(remaining)
        }
        return true
    }

    // --- persistence ---

    private suspend fun readQueue(): List<QueueItem> =
        syncMetaDao.get(KEY_QUEUE)?.let {
            runCatching {
                queueJson.decodeFromString(ListSerializer(QueueItem.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    private suspend fun writeQueue(queue: List<QueueItem>) {
        syncMetaDao.put(
            SyncMetaEntity(KEY_QUEUE, queueJson.encodeToString(ListSerializer(QueueItem.serializer()), queue)),
        )
    }

    private suspend fun readChanges(): List<QueueChange> =
        syncMetaDao.get(KEY_CHANGES)?.let {
            runCatching {
                queueJson.decodeFromString(ListSerializer(QueueChange.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    private suspend fun writeChanges(changes: List<QueueChange>) {
        syncMetaDao.put(
            SyncMetaEntity(KEY_CHANGES, queueJson.encodeToString(ListSerializer(QueueChange.serializer()), changes)),
        )
    }

    private suspend fun appendChange(change: QueueChange) {
        writeChanges(readChanges() + change)
    }

    private suspend fun readWatermark(): Long =
        syncMetaDao.get(KEY_WATERMARK)?.toLongOrNull() ?: 0L

    private suspend fun writeWatermark(value: Long) {
        syncMetaDao.put(SyncMetaEntity(KEY_WATERMARK, value.toString()))
    }
}

private fun QueueChange.toRemote() = RemoteQueueChange(
    action = action,
    modifiedAtMs = modifiedAtMs,
    episodeUuid = item?.episodeUuid,
    title = item?.title,
    audioUrl = item?.audioUrl,
    podcastUuid = item?.podcastUuid,
)

fun com.jomurgel.pocketcasts.data.db.EpisodeEntity.toQueueItem(): QueueItem = QueueItem(
    episodeUuid = uuid,
    podcastUuid = podcastUuid,
    title = title,
    audioUrl = audioUrl,
)
