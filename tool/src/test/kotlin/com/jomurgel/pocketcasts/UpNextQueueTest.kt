package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.QueueItem
import com.jomurgel.pocketcasts.data.UpNextQueue
import com.jomurgel.pocketcasts.data.db.SyncMetaDao
import com.jomurgel.pocketcasts.data.db.SyncMetaEntity
import com.jomurgel.pocketcasts.data.sync.QueueAction
import com.jomurgel.pocketcasts.data.sync.RemoteEpisode
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeState
import com.jomurgel.pocketcasts.data.sync.RemotePodcast
import com.jomurgel.pocketcasts.data.sync.RemoteQueueChange
import com.jomurgel.pocketcasts.data.sync.RemoteQueueEpisode
import com.jomurgel.pocketcasts.data.sync.RemoteQueueResult
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeSyncMetaDao : SyncMetaDao {
    val store = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun get(key: String): String? = store.value[key]

    override fun observe(key: String): Flow<String?> = store.map { it[key] }

    override suspend fun put(entity: SyncMetaEntity) {
        store.value = store.value + (entity.key to entity.value)
    }
}

private class FakeQueueBackend : SyncBackend {
    var lastWatermark: Long? = null
    var lastChanges: List<RemoteQueueChange> = emptyList()
    var response: RemoteQueueResult = RemoteQueueResult(0, null)
    var throwOnSync = false
    var syncCalls = 0

    override suspend fun fetchSubscriptions(): List<RemotePodcast> = emptyList()
    override suspend fun fetchEpisodes(podcastUuid: String): List<RemoteEpisode> = emptyList()
    override suspend fun fetchInProgress(): List<RemoteEpisodeState> = emptyList()

    override suspend fun syncUpNext(serverModified: Long, changes: List<RemoteQueueChange>): RemoteQueueResult {
        syncCalls++
        if (throwOnSync) throw SyncException.Network(RuntimeException("offline"))
        lastWatermark = serverModified
        lastChanges = changes
        return response
    }
}

private fun item(uuid: String) = QueueItem(uuid, "p-$uuid", "Episode $uuid", "https://cdn.test/$uuid.mp3")

private fun remoteEp(uuid: String) = RemoteQueueEpisode(uuid, "Episode $uuid", "https://cdn.test/$uuid.mp3", "p-$uuid")

class UpNextQueueTest {

    private val dao = FakeSyncMetaDao()
    private val backend = FakeQueueBackend()
    private var time = 1000L
    private val queue = UpNextQueue(dao, backend, now = { time })

    private fun uuids() = queue.queue.value.map { it.episodeUuid }

    @Test
    fun `playNow puts the episode at the head`() = runTest {
        queue.playLast(item("a"))
        queue.playNow(item("b"))
        assertEquals(listOf("b", "a"), uuids())
    }

    @Test
    fun `playNext slots behind the current episode`() = runTest {
        queue.playNow(item("a"))
        queue.playLast(item("b"))
        queue.playNext(item("c"))
        assertEquals(listOf("a", "c", "b"), uuids())
    }

    @Test
    fun `playNext on an empty queue becomes the head`() = runTest {
        queue.playNext(item("a"))
        assertEquals(listOf("a"), uuids())
    }

    @Test
    fun `remove drops the episode and records the action`() = runTest {
        queue.playNow(item("a"))
        queue.playLast(item("b"))
        queue.remove("b")
        assertEquals(listOf("a"), uuids())

        queue.sync()
        assertEquals(
            listOf(QueueAction.PLAY_NOW, QueueAction.PLAY_LAST, QueueAction.REMOVE),
            backend.lastChanges.map { it.action },
        )
    }

    @Test
    fun `moveToTop reorders an upcoming episode behind the head`() = runTest {
        queue.playNow(item("a"))
        queue.playLast(item("b"))
        queue.playLast(item("c"))
        queue.moveToTop("c")
        assertEquals(listOf("a", "c", "b"), uuids())
    }

    @Test
    fun `sync sends watermark and drains only pushed changes`() = runTest {
        queue.playNow(item("a"))
        backend.response = RemoteQueueResult(555, listOf(remoteEp("a")))

        assertTrue(queue.sync())

        assertEquals(0L, backend.lastWatermark)
        assertEquals(1, backend.lastChanges.size)
        assertEquals("555", dao.store.value["up_next_server_modified"])
        // change log is now empty
        queue.sync()
        assertEquals(0, backend.lastChanges.size)
    }

    @Test
    fun `authoritative response replaces local order`() = runTest {
        queue.playNow(item("a"))
        backend.response = RemoteQueueResult(700, listOf(remoteEp("x"), remoteEp("y")))
        queue.sync()
        assertEquals(listOf("x", "y"), uuids())
    }

    @Test
    fun `null episodes response keeps the local queue`() = runTest {
        queue.playNow(item("a"))
        backend.response = RemoteQueueResult(700, null)
        queue.sync()
        assertEquals(listOf("a"), uuids())
    }

    @Test
    fun `failed sync keeps the change log for retry`() = runTest {
        queue.playNow(item("a"))
        backend.throwOnSync = true
        assertEquals(false, queue.sync())

        backend.throwOnSync = false
        backend.response = RemoteQueueResult(1, listOf(remoteEp("a")))
        queue.sync()
        assertEquals(1, backend.lastChanges.size)
    }

    @Test
    fun `queue state survives reload from storage`() = runTest {
        queue.playNow(item("a"))
        queue.playLast(item("b"))

        val reloaded = UpNextQueue(dao, backend, now = { time })
        assertEquals(listOf("a", "b"), reloaded.head()?.let { listOf(it.episodeUuid) + reloaded.queue.value.drop(1).map { q -> q.episodeUuid } })
    }
}
