package com.jomurgel.pocketcasts.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        PendingProgressEntity::class,
        PendingEpisodeStateEntity::class,
        SyncMetaEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PocketCastsDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun pendingProgressDao(): PendingProgressDao
    abstract fun pendingEpisodeStateDao(): PendingEpisodeStateDao
    abstract fun syncMetaDao(): SyncMetaDao
}
