package com.droidnotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, NoteFtsEntity::class, NoteLinkEntity::class, NoteImageEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN lineCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_images` (" +
                        "`noteId` TEXT NOT NULL, " +
                        "`imageName` TEXT NOT NULL, " +
                        "PRIMARY KEY(`noteId`, `imageName`), " +
                        "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_note_images_imageName` " +
                        "ON `note_images` (`imageName`)"
                )

                // One-time backfill so existing notes participate in orphan-image cleanup.
                db.query("SELECT `id`, `content` FROM `notes`").use { cursor ->
                    while (cursor.moveToNext()) {
                        val noteId = cursor.getString(0)
                        val content = cursor.getString(1)
                        LocalImageRefs.extractNames(content).forEach { name ->
                            db.execSQL(
                                "INSERT OR IGNORE INTO `note_images` (`noteId`, `imageName`) VALUES (?, ?)",
                                arrayOf<Any?>(noteId, name),
                            )
                        }
                    }
                }
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "droidnotes.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
