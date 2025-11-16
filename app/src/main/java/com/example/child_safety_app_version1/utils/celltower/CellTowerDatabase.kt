package com.example.child_safety_app_version1.database.celltower

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Room Database for Cell Tower Location Data
 *
 * Database Name: cell_towers.db
 * Estimated Size: 50-100 MB for India
 * Version: 1
 */
@Database(
    entities = [CellTowerEntity::class],
    version = 1,
    exportSchema = true
)
abstract class CellTowerDatabase : RoomDatabase() {

    abstract fun cellTowerDao(): CellTowerDao

    companion object {
        private const val TAG = "CellTowerDatabase"
        private const val DATABASE_NAME = "cell_towers.db"

        @Volatile
        private var INSTANCE: CellTowerDatabase? = null

        /**
         * Get singleton database instance
         */
        fun getInstance(context: Context): CellTowerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        /**
         * Build the Room database
         */
        private fun buildDatabase(context: Context): CellTowerDatabase {
            Log.d(TAG, "Building CellTowerDatabase...")

            return Room.databaseBuilder(
                context.applicationContext,
                CellTowerDatabase::class.java,
                DATABASE_NAME
            )
                .addCallback(DatabaseCallback())
                .fallbackToDestructiveMigration() // For development only
                .build()
                .also {
                    Log.d(TAG, "✅ Database built successfully")
                }
        }

        /**
         * Close database and clear instance (for testing)
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
            Log.d(TAG, "Database closed")
        }
    }

    /**
     * Database callback for initialization and logging
     */
    private class DatabaseCallback : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "DATABASE CREATED")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "Database: $DATABASE_NAME")
            Log.d(TAG, "Version: 1")
            Log.d(TAG, "Path: ${db.path}")
            Log.d(TAG, "═══════════════════════════════════════════════════")

            // Log database creation in background
            CoroutineScope(Dispatchers.IO).launch {
                logDatabaseInfo(db)
            }
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            Log.d(TAG, "Database opened: $DATABASE_NAME")
        }

        /**
         * Log detailed database information
         */
        private fun logDatabaseInfo(db: SupportSQLiteDatabase) {
            try {
                // Get table count
                val cursor = db.query("SELECT COUNT(*) FROM cell_towers")
                cursor.moveToFirst()
                val count = cursor.getInt(0)
                cursor.close()

                Log.d(TAG, "Current tower count: $count")

                if (count == 0) {
                    Log.w(TAG, "⚠️ Database is empty - needs to be populated")
                    Log.w(TAG, "   Use CellTowerDatabaseManager.downloadAndPopulateDatabase()")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error getting database info", e)
            }
        }
    }
}

/**
 * Extension functions for database operations
 */

/**
 * Check if database exists and is populated
 */
suspend fun CellTowerDatabase.isPopulated(): Boolean {
    return try {
        cellTowerDao().isDatabasePopulated()
    } catch (e: Exception) {
        false
    }
}

/**
 * Get database statistics
 */
suspend fun CellTowerDatabase.getStats(): DatabaseStats? {
    return try {
        cellTowerDao().getDatabaseStats()
    } catch (e: Exception) {
        Log.e("CellTowerDatabase", "Error getting stats", e)
        null
    }
}

/**
 * Get human-readable database size
 */
fun CellTowerDatabase.getDatabaseSizeMB(): String {
    return try {
        val dbFile = openHelper.readableDatabase.path?.let { java.io.File(it) }
        val sizeBytes = dbFile?.length() ?: 0
        val sizeMB = sizeBytes / (1024.0 * 1024.0)
        String.format("%.2f MB", sizeMB)
    } catch (e: Exception) {
        "Unknown"
    }
}

/**
 * Log database status
 */
suspend fun CellTowerDatabase.logStatus() {
    try {
        val stats = getStats()
        val size = getDatabaseSizeMB()

        Log.d("CellTowerDatabase", "═══════════════════════════════════════════════════")
        Log.d("CellTowerDatabase", "DATABASE STATUS")
        Log.d("CellTowerDatabase", "═══════════════════════════════════════════════════")
        Log.d("CellTowerDatabase", "Database Size: $size")
        Log.d("CellTowerDatabase", "Is Populated: ${isPopulated()}")

        if (stats != null) {
            Log.d("CellTowerDatabase", stats.toString())
        } else {
            Log.d("CellTowerDatabase", "No statistics available (empty database)")
        }

        Log.d("CellTowerDatabase", "═══════════════════════════════════════════════════")

    } catch (e: Exception) {
        Log.e("CellTowerDatabase", "Error logging status", e)
    }
}