package com.jomurgel.pocketcasts.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        PendingProgressEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PocketCastsDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun syncMetaDao(): SyncMetaDao
}
