package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.ProgressRepository
import com.jomurgel.pocketcasts.data.db.EpisodeEntity
import com.jomurgel.pocketcasts.data.db.PendingProgressDao
import com.jomurgel.pocketcasts.data.db.PendingProgressEntity
import com.jomurgel.pocketcasts.data.db.PlayStatus
import com.jomurgel.pocketcasts.data.progressStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakePendingProgressDao : PendingProgressDao {
    val rows = linkedMapOf<String, PendingProgressEntity>()

    override suspend fun upsert(entity: PendingProgressEntity) {
        rows[entity.episodeUuid] = entity
    }

    override suspend fun all(): List<PendingProgressEntity> = rows.values.toList()

    override suspend fun delete(episodeUuid: String, queuedAt: Long) {
        if (rows[episodeUuid]?.queuedAt == queuedAt) rows.remove(episodeUuid)
    }
}

class ProgressStatusTest {

    @Test
    fun `zero position is unplayed`() {
        assertEquals(PlayStatus.UNPLAYED, progressStatus(0, 1800))
    }

    @Test
    fun `mid-episode is in progress`() {
        assertEquals(PlayStatus.IN_PROGRESS, progressStatus(600, 1800))
    }

    @Test
    fun `within threshold of the end is complete`() {
        assertEquals(PlayStatus.COMPLETE, progressStatus(1775, 1800))
    }

    @Test
    fun `unknown duration never completes`() {
        assertEquals(PlayStatus.IN_PROGRESS, progressStatus(4000, 0))
    }
}

class ProgressRepositoryTest {

    private val episodeDao = FakeEpisodeDao()
    private val pendingDao = FakePendingProgressDao()
    private var pushes = 0

    private fun repo(clock: () -> Long = { 1000L }) = ProgressRepository(
        episodeDao = episodeDao,
        pendingProgressDao = pendingDao,
        syncApi = null,
        schedulePush = { pushes++ },
        now = clock,
    )

    private suspend fun seedEpisode(uuid: String = "e1") {
        episodeDao.insertIgnore(
            listOf(
                EpisodeEntity(
                    uuid = uuid, podcastUuid = "p1", title = "T",
                    publishedAt = 1, audioUrl = "https://x",
                ),
            ),
        )
    }

    @Test
    fun `record writes episode row and outbox and schedules a push`() = runTest {
        seedEpisode()
        repo().record("e1", "p1", 600, 1800)

        val episode = episodeDao.get("e1")!!
        assertEquals(600, episode.positionSec)
        assertEquals(PlayStatus.IN_PROGRESS, episode.playStatus)
        assertEquals(600, pendingDao.rows["e1"]?.positionSec)
        assertEquals(1, pushes)
    }

    @Test
    fun `rapid records collapse to the newest outbox row`() = runTest {
        seedEpisode()
        var time = 1000L
        val repository = repo { time }
        repository.record("e1", "p1", 600, 1800)
        time = 2000L
        repository.record("e1", "p1", 615, 1800)

        assertEquals(1, pendingDao.rows.size)
        assertEquals(615, pendingDao.rows["e1"]?.positionSec)
    }

    @Test
    fun `finishing an episode marks it complete`() = runTest {
        seedEpisode()
        repo().record("e1", "p1", 1790, 1800)
        assertEquals(PlayStatus.COMPLETE, episodeDao.get("e1")?.playStatus)
    }
}
