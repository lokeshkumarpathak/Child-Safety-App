package com.example.child_safety_app_version1.utils.celltower

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.NetworkType
import com.example.child_safety_app_version1.database.celltower.CellTowerDatabaseManager
import com.example.child_safety_app_version1.database.celltower.CellTowerDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Initializes and manages Cell Tower database download
 * Call this ONCE after user logs in to download offline location database
 */
object CellTowerDatabaseInitializer {

    private const val TAG = "CellTowerDBInit"
    private const val PREFS_NAME = "celltower_db_prefs"
    private const val KEY_DB_DOWNLOADED = "db_downloaded"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val CHECK_INTERVAL_DAYS = 30 // Re-check every 30 days

    /**
     * Call this in ChildDashboard after user logs in
     * Checks if database needs to be downloaded and schedules it
     */
    fun initializeDatabase(context: Context) {
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "🗄️ INITIALIZING CELL TOWER DATABASE")
        Log.d(TAG, "═══════════════════════════════════════════════════")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDbDownloaded = prefs.getBoolean(KEY_DB_DOWNLOADED, false)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK, 0L)
        val currentTime = System.currentTimeMillis()
        val daysSinceLastCheck = (currentTime - lastCheckTime) / (1000 * 60 * 60 * 24)

        Log.d(TAG, "Database status:")
        Log.d(TAG, "  - Downloaded: $isDbDownloaded")
        Log.d(TAG, "  - Last check: ${daysSinceLastCheck} days ago")

        when {
            !isDbDownloaded -> {
                Log.d(TAG, "📥 Database not downloaded - scheduling download...")
                scheduleImmediateDownload(context)
            }
            daysSinceLastCheck >= CHECK_INTERVAL_DAYS -> {
                Log.d(TAG, "🔄 Database needs update - scheduling background download...")
                scheduleBackgroundDownload(context)
            }
            else -> {
                Log.d(TAG, "✅ Database is up to date")
                verifyDatabaseIntegrity(context)
            }
        }

        // Update last check time
        prefs.edit().putLong(KEY_LAST_CHECK, currentTime).apply()
    }

    /**
     * Schedule immediate download (when user first logs in)
     * FIX: Removed battery constraint for expedited work
     */
    private fun scheduleImmediateDownload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
            // ❌ REMOVED: .setRequiresBatteryNotLow(true) - Not allowed with expedited work
            .setRequiresStorageNotLow(true)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<CellTowerDownloadWorker>()
            .setConstraints(constraints)
            .addTag("celltower_initial_download")
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "celltower_initial_download",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        Log.d(TAG, "✅ Initial download scheduled (waiting for WiFi)")
    }

    /**
     * Schedule background update (periodic maintenance)
     */
    private fun scheduleBackgroundDownload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(true) // More restrictive for updates
            .build()

        val updateRequest = OneTimeWorkRequestBuilder<CellTowerDownloadWorker>()
            .setConstraints(constraints)
            .addTag("celltower_background_update")
            // No .setExpedited() here - this is background work
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "celltower_background_update",
            ExistingWorkPolicy.REPLACE,
            updateRequest
        )

        Log.d(TAG, "✅ Background update scheduled")
    }

    /**
     * Verify database integrity (quick check)
     */
    private fun verifyDatabaseIntegrity(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = CellTowerDatabaseManager(context)
                val needsPopulation = manager.needsPopulation()

                if (needsPopulation) {
                    Log.w(TAG, "⚠️ Database is empty - triggering download...")
                    scheduleImmediateDownload(context)
                } else {
                    Log.d(TAG, "✅ Database integrity verified")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Database verification failed", e)
            }
        }
    }

    /**
     * Mark database as successfully downloaded
     * Call this from CellTowerDownloadWorker after successful download
     */
    fun markDatabaseAsDownloaded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_DB_DOWNLOADED, true)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()

        Log.d(TAG, "✅ Database marked as downloaded")
    }

    /**
     * Check if database is ready for use
     */
    suspend fun isDatabaseReady(context: Context): Boolean {
        return try {
            val manager = CellTowerDatabaseManager(context)
            !manager.needsPopulation()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking database readiness", e)
            false
        }
    }

    /**
     * Get database status for UI display
     */
    fun getDatabaseStatus(context: Context): DatabaseStatus {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDownloaded = prefs.getBoolean(KEY_DB_DOWNLOADED, false)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)

        return DatabaseStatus(
            isDownloaded = isDownloaded,
            lastCheckTime = lastCheck,
            isPending = isDownloadPending(context)
        )
    }

    /**
     * Check if download is currently pending/running
     */
    private fun isDownloadPending(context: Context): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosByTag("celltower_initial_download")
            .get()

        return workInfos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
    }

    /**
     * Force immediate download (for manual trigger from settings)
     */
    fun forceDownload(context: Context) {
        Log.d(TAG, "🔄 Forcing database download...")

        // Cancel any existing work
        WorkManager.getInstance(context).cancelAllWorkByTag("celltower_initial_download")
        WorkManager.getInstance(context).cancelAllWorkByTag("celltower_background_update")

        // Schedule new download with relaxed constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Any internet
            .setRequiresStorageNotLow(true)
            // ❌ REMOVED: battery constraint for expedited work
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<CellTowerDownloadWorker>()
            .setConstraints(constraints)
            .addTag("celltower_forced_download")
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "celltower_forced_download",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )

        Log.d(TAG, "✅ Forced download scheduled")
    }
}

/**
 * Data class for database status
 */
data class DatabaseStatus(
    val isDownloaded: Boolean,
    val lastCheckTime: Long,
    val isPending: Boolean
) {
    fun getStatusMessage(): String {
        return when {
            isPending -> "⏳ Downloading database..."
            isDownloaded -> "✅ Offline location ready"
            else -> "⚠️ Database not downloaded"
        }
    }

    fun getDaysSinceLastCheck(): Long {
        if (lastCheckTime == 0L) return -1
        return (System.currentTimeMillis() - lastCheckTime) / (1000 * 60 * 60 * 24)
    }
}