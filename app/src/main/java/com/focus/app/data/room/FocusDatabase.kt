package com.focus.app.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local database for session history and schedules. Nothing leaves the device.
 *
 * Version 2 adds an index on `session_history.endedAtMillis` (statistics and the newest-first
 * list). The migration only creates that index, so no data is touched. There is
 * deliberately no destructive fallback: a failed migration must never silently wipe history.
 * Any later schema change must ship its own migration.
 */
@Database(
    entities = [SessionHistoryEntity::class, ScheduleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun historyDao(): SessionHistoryDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        /** The index name and columns must match what Room generates for `@Index(["endedAtMillis"])`. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_history_endedAtMillis` " +
                        "ON `session_history` (`endedAtMillis`)",
                )
            }
        }

        fun create(context: Context): FocusDatabase =
            Room.databaseBuilder(context.applicationContext, FocusDatabase::class.java, "focus.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
