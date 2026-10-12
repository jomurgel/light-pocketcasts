package com.jomurgel.pocketcasts.data

import com.jomurgel.pocketcasts.data.api.EpisodeSyncRequestDto
import com.jomurgel.pocketcasts.data.api.PocketCastsSyncApi
import com.jomurgel.pocketcasts.data.db.EpisodeDao
import com.jomurgel.pocketcasts.data.db.PendingProgressDao
import com.jomurgel.pocketcasts.data.db.PendingProgressEntity
import com.jomurgel.pocketcasts.data.db.PlayStatus
import com.jomurgel.pocketcasts.data.sync.SyncException

/** An episode is "played" when within this many seconds of its end. */
const val COMPLETE_THRESHOLD_SEC = 30L

fun progressStatus(positionSec: Long, durationSec: Long): Int = when {
    durationSec > 0 && positionSec >= (durationSec - COMPLETE_THRESHOLD_SEC) -> PlayStatus.COMPLETE
    positionSec > 0 -> PlayStatus.IN_PROGRESS
    else -> PlayStatus.UNPLAYED
}

/**
 * Records listening progress locally and drains the outbox to the sync
 * service. Local write and outbox write happen together; the push job runs
 * whenever [schedulePush] fires and retries via WorkManager backoff.
 */
class ProgressRepository(
    private val episodeDao: EpisodeDao,
    private val pendingProgressDao: PendingProgressDao,
    private val syncApi: PocketCastsSyncApi?,
    private val schedulePush: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun record(episodeUuid: String, podcastUuid: String, positionSec: Long, durationSec: Long) {
        write(episodeUuid, podcastUuid, positionSec, durationSec, progressStatus(positionSec, durationSec))
    }

    /**
     * The episode played through to its end. Says so outright instead of
     * deriving it from position and duration, which reads as "unplayed" when
     * the duration isn't known (episodes queued from another device).
     */
    suspend fun recordCompleted(episodeUuid: String, podcastUuid: String, durationSec: Long) {
        write(episodeUuid, podcastUuid, durationSec, durationSec, PlayStatus.COMPLETE)
    }

    private suspend fun write(
        episodeUuid: String,
        podcastUuid: String,
        positionSec: Long,
        durationSec: Long,
        status: Int,
    ) {
        val timestamp = now()
        episodeDao.updateLocalProgress(episodeUuid, positionSec, status, timestamp)
        pendingProgressDao.upsert(
            PendingProgressEntity(
                episodeUuid = episodeUuid,
                podcastUuid = podcastUuid,
                positionSec = positionSec,
                durationSec = durationSec,
                status = status,
                queuedAt = timestamp,
            ),
        )
        schedulePush()
    }

    sealed interface DrainResult {
        data object Done : DrainResult
        data object Retry : DrainResult
        data object SignedOut : DrainResult
    }

    /** Push every pending row; rows replaced mid-flight survive for next time. */
    suspend fun drain(): DrainResult {
        val api = syncApi ?: return DrainResult.Done
        val pending = pendingProgressDao.all()
        var retry = false
        for (row in pending) {
            try {
                api.pushEpisodeProgress(
                    EpisodeSyncRequestDto(
                        uuid = row.episodeUuid,
                        podcast = row.podcastUuid,
                        position = row.positionSec,
                        duration = row.durationSec,
                        status = row.status,
                    ),
                )
                pendingProgressDao.delete(row.episodeUuid, row.queuedAt)
            } catch (error: SyncException) {
                when (error) {
                    is SyncException.SignedOut -> return DrainResult.SignedOut
                    else -> retry = true
                }
            }
        }
        return if (retry) DrainResult.Retry else DrainResult.Done
    }
}
