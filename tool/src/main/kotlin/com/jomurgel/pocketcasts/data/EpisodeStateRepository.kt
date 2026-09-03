package com.jomurgel.pocketcasts.data

import com.jomurgel.pocketcasts.data.db.EpisodeDao
import com.jomurgel.pocketcasts.data.db.PendingEpisodeStateDao
import com.jomurgel.pocketcasts.data.db.PendingEpisodeStateEntity
import com.jomurgel.pocketcasts.data.sync.RemoteEpisodeFlagChange
import com.jomurgel.pocketcasts.data.sync.SyncBackend
import com.jomurgel.pocketcasts.data.sync.SyncException

/**
 * Archive/starred flags: local write plus an outbox row, drained to
 * /user/sync/update records by the push job. One row per episode; a second
 * flag change before the push merges into it.
 */
class EpisodeStateRepository(
    private val episodeDao: EpisodeDao,
    private val pendingDao: PendingEpisodeStateDao,
    private val backend: SyncBackend,
    private val schedulePush: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun setArchived(episodeUuid: String, podcastUuid: String, archived: Boolean) {
        episodeDao.updateArchived(episodeUuid, archived)
        merge(episodeUuid, podcastUuid) { it.copy(archived = archived) }
        schedulePush()
    }

    suspend fun setStarred(episodeUuid: String, podcastUuid: String, starred: Boolean) {
        episodeDao.updateStarred(episodeUuid, starred)
        merge(episodeUuid, podcastUuid) { it.copy(starred = starred) }
        schedulePush()
    }

    private suspend fun merge(
        episodeUuid: String,
        podcastUuid: String,
        transform: (PendingEpisodeStateEntity) -> PendingEpisodeStateEntity,
    ) {
        val base = pendingDao.get(episodeUuid) ?: PendingEpisodeStateEntity(
            episodeUuid = episodeUuid,
            podcastUuid = podcastUuid,
            archived = null,
            starred = null,
            queuedAt = 0,
        )
        pendingDao.upsert(transform(base).copy(queuedAt = now()))
    }

    /** Push all pending flags in one request. */
    suspend fun drain(): ProgressRepository.DrainResult {
        val pending = pendingDao.all()
        if (pending.isEmpty()) return ProgressRepository.DrainResult.Done
        try {
            backend.pushEpisodeFlags(
                pending.map {
                    RemoteEpisodeFlagChange(
                        episodeUuid = it.episodeUuid,
                        podcastUuid = it.podcastUuid,
                        archived = it.archived,
                        starred = it.starred,
                        modifiedAtMs = it.queuedAt,
                    )
                },
            )
        } catch (error: SyncException) {
            return when (error) {
                is SyncException.SignedOut -> ProgressRepository.DrainResult.SignedOut
                else -> ProgressRepository.DrainResult.Retry
            }
        }
        pending.forEach { pendingDao.delete(it.episodeUuid, it.queuedAt) }
        return ProgressRepository.DrainResult.Done
    }
}
