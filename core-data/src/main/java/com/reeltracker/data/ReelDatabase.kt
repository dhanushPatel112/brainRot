package com.reeltracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds Instagram time; version 3 adds YouTube, so each slice says which app it was.
 * The reel tables from version 1 are kept as history.
 */
@Database(entities = [ReelEvent::class, Session::class, UsageSlice::class], version = 3, exportSchema = false)
abstract class ReelDatabase : RoomDatabase() {
    abstract fun dao(): ReelDao
    abstract fun usage(): UsageDao
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_slices` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_slices_startedAt` ON `usage_slices` (`startedAt`)")
    }
}

/** Every slice so far was Instagram. The default must match [UsageSlice.app]'s, or Room rejects the table. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `usage_slices` ADD COLUMN `app` TEXT NOT NULL DEFAULT 'instagram'")
    }
}

/** Process-wide wiring. The service and the UI run in one process and share one database. */
object ReelGraph {
    @Volatile
    private var db: ReelDatabase? = null

    private fun db(context: Context): ReelDatabase =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(context.applicationContext, ReelDatabase::class.java, "reels.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { db = it }
        }

    @Volatile
    private var usageRepo: UsageRepository? = null

    fun usage(context: Context): UsageRepository =
        usageRepo ?: synchronized(this) {
            usageRepo ?: UsageRepository(db(context).usage()).also { usageRepo = it }
        }
}
