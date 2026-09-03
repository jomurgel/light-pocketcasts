package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.ProgressRepository
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

const val PUSH_PROGRESS_JOB_KEY = "push-progress"

/**
 * Drains the pending-progress outbox to Pocket Casts. Enqueued after every
 * local progress write; WorkManager retries with backoff while offline.
 */
@LightJob(PUSH_PROGRESS_JOB_KEY)
val pushProgress: LightJobHandler = { ctx, _ ->
    // Queue sync piggybacks on every progress push; failures self-heal on the
    // next sync, so its result doesn't affect the job outcome.
    AppGraph.upNextQueue(ctx).sync()
    val results = listOf(
        AppGraph.progressRepository(ctx).drain(),
        AppGraph.episodeStateRepository(ctx).drain(),
    )
    when {
        results.any { it is ProgressRepository.DrainResult.Retry } -> LightJobResult.Retry
        // Signed out: nothing to push until the user signs in again.
        else -> LightJobResult.Success()
    }
}
