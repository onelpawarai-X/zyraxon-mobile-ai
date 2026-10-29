package ai.zyraxon.assistant.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatMessageEntity::class, MemoryEntity::class, TaskEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ZyraxonDatabase : RoomDatabase() {
    abstract fun dao(): ZyraxonDao

    companion object {
        @Volatile
        private var INSTANCE: ZyraxonDatabase? = null

        fun getDatabase(context: Context): ZyraxonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ZyraxonDatabase::class.java,
                    "zyraxon_sql.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
