package ai.zyraxon.assistant.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessageEntity::class, MemoryEntity::class, TaskEntity::class, ConversationEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ZyraxonDatabase : RoomDatabase() {
    abstract fun dao(): ZyraxonDao

    companion object {
        /**
         * 1 -> 2: several chats. Every existing message and task moves into the
         * "main" conversation, so nothing the user already said is lost.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `parentId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `conversationId` TEXT NOT NULL DEFAULT 'main'")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `meta` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `conversationId` TEXT NOT NULL DEFAULT 'main'")
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR IGNORE INTO `conversations` " +
                        "(`id`,`title`,`mode`,`kind`,`parentId`,`createdAt`,`updatedAt`) " +
                        "VALUES ('main','প্রথম চ্যাট','AGENT','chat','',$now,$now)"
                )
            }
        }

        @Volatile
        private var INSTANCE: ZyraxonDatabase? = null

        fun getDatabase(context: Context): ZyraxonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ZyraxonDatabase::class.java,
                    "zyraxon_sql.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Only a downgrade may rebuild; an upgrade always migrates.
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
