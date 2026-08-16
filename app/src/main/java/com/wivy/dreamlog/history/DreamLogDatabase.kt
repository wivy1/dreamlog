package com.wivy.dreamlog.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NightEntity::class,
        CaptureSessionEntity::class,
        NightEventEntity::class,
        SessionTranscriptEntity::class,
        TranscriptSegmentEntity::class,
        EnrichmentRunEntity::class,
        DreamEntity::class,
        DreamSourceSpanEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class DreamLogDatabase : RoomDatabase() {
    abstract fun nightDao(): NightDao
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun enrichmentDao(): EnrichmentDao

    companion object {
        private const val DATABASE_NAME = "dreamlog.db"

        @Volatile
        private var instance: DreamLogDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_transcripts` (
                        `sessionId` TEXT NOT NULL,
                        `nightId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `failureDetail` TEXT,
                        `rawText` TEXT,
                        `localeTag` TEXT NOT NULL,
                        `engineId` TEXT NOT NULL,
                        `engineVersion` TEXT NOT NULL,
                        `runtimeId` TEXT NOT NULL,
                        `runtimeVersion` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `modelSha256` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `completedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `capture_sessions`(`sessionId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`nightId`) REFERENCES `nights`(`nightId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_session_transcripts_nightId`
                    ON `session_transcripts` (`nightId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                        `index_session_transcripts_state_startedAtEpochMillis`
                    ON `session_transcripts` (`state`, `startedAtEpochMillis`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcript_segments` (
                        `sessionId` TEXT NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `sourceStartMillis` INTEGER NOT NULL,
                        `sourceEndMillis` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`, `segmentIndex`),
                        FOREIGN KEY(`sessionId`) REFERENCES `session_transcripts`(`sessionId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enrichment_runs` (
                        `runId` TEXT NOT NULL,
                        `nightId` TEXT NOT NULL,
                        `attemptNumber` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `failureDetail` TEXT,
                        `localeTag` TEXT NOT NULL,
                        `engineId` TEXT NOT NULL,
                        `engineVersion` TEXT NOT NULL,
                        `runtimeId` TEXT NOT NULL,
                        `runtimeVersion` TEXT NOT NULL,
                        `backendId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `modelSha256` TEXT NOT NULL,
                        `modelBytes` INTEGER NOT NULL,
                        `contextWindowTokens` INTEGER NOT NULL,
                        `maxTotalTokens` INTEGER NOT NULL,
                        `promptId` TEXT NOT NULL,
                        `promptVersion` TEXT NOT NULL,
                        `promptSha256` TEXT NOT NULL,
                        `outputSchemaVersion` INTEGER NOT NULL,
                        `inputSha256` TEXT NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `completedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`runId`),
                        FOREIGN KEY(`nightId`) REFERENCES `nights`(`nightId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        `index_enrichment_runs_nightId_attemptNumber`
                    ON `enrichment_runs` (`nightId`, `attemptNumber`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                        `index_enrichment_runs_state_startedAtEpochMillis`
                    ON `enrichment_runs` (`state`, `startedAtEpochMillis`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dreams` (
                        `dreamId` TEXT NOT NULL,
                        `nightId` TEXT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `dreamOrder` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `isUncertain` INTEGER NOT NULL,
                        `generatedTitle` TEXT,
                        `generatedText` TEXT NOT NULL,
                        `currentTitle` TEXT,
                        `currentText` TEXT NOT NULL,
                        `ownerEdited` INTEGER NOT NULL,
                        `editedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`dreamId`),
                        FOREIGN KEY(`nightId`) REFERENCES `nights`(`nightId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`runId`) REFERENCES `enrichment_runs`(`runId`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_dreams_nightId_dreamOrder`
                    ON `dreams` (`nightId`, `dreamOrder`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_dreams_runId`
                    ON `dreams` (`runId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dream_source_spans` (
                        `dreamId` TEXT NOT NULL,
                        `spanOrder` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sourceTranscriptAttemptCount` INTEGER NOT NULL,
                        `firstSegmentIndex` INTEGER NOT NULL,
                        `lastSegmentIndex` INTEGER NOT NULL,
                        `sourceStartMillis` INTEGER NOT NULL,
                        `sourceEndMillis` INTEGER NOT NULL,
                        `sourceText` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        PRIMARY KEY(`dreamId`, `spanOrder`),
                        FOREIGN KEY(`dreamId`) REFERENCES `dreams`(`dreamId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sessionId`) REFERENCES `capture_sessions`(`sessionId`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_dream_source_spans_sessionId`
                    ON `dream_source_spans` (`sessionId`)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `capture_sessions`
                    ADD COLUMN `automaticSilenceTailSampleCount` INTEGER
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `dreams`
                    ADD COLUMN `deletedAtEpochMillis` INTEGER
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): DreamLogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DreamLogDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
