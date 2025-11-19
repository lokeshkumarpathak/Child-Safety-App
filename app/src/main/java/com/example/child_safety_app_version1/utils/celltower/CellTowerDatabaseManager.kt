package com.example.child_safety_app_version1.database.celltower

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.child_safety_app_version1.utils.celltower.CellTowerDatabaseInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * ✅ FIXED: Uses Mozilla Location Services (FREE, NO AUTH REQUIRED)
 * Downloads cell tower database for offline location lookup
 */
class CellTowerDatabaseManager(private val context: Context) {

    companion object {
        private const val TAG = "CellTowerDBManager"

        // ✅ Mozilla Location Services - FREE, NO API KEY NEEDED!
        // Download list: https://location.services.mozilla.com/downloads
        private const val MLS_INDIA_URL = "https://d17pt8qph6ncyq.cloudfront.net/export/MLS-full-cell-export-2024-11-01T000000.csv.gz"

        // Alternative: Use entire world dataset (larger ~900MB)
        private const val MLS_WORLD_URL = "https://d17pt8qph6ncyq.cloudfront.net/export/MLS-full-cell-export-2024-11-01T000000.csv.gz"

        // File paths
        private const val DOWNLOAD_DIR = "celltower_data"
        private const val TEMP_GZ_FILE = "cell_towers.csv.gz"
        private const val TEMP_CSV_FILE = "cell_towers.csv"
        private const val FILTERED_CSV_FILE = "india_towers.csv"

        // India MCC codes
        private val INDIA_MCC_CODES = setOf(404, 405, 406)

        // Batch insert size
        private const val BATCH_SIZE = 1000

        // Notification
        private const val CHANNEL_ID = "celltower_download"
        private const val NOTIFICATION_ID = 9001
    }

    private val database: CellTowerDatabase by lazy {
        CellTowerDatabase.getInstance(context)
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Check if database needs to be populated
     */
    suspend fun needsPopulation(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val count = database.cellTowerDao().getTotalCount()
                Log.d(TAG, "Current database count: $count")
                count == 0
            } catch (e: Exception) {
                Log.e(TAG, "Error checking database", e)
                true
            }
        }
    }

    /**
     * Download and populate database
     */
    suspend fun downloadAndPopulateDatabase(
        progressCallback: ((Int) -> Unit)? = null
    ): DatabasePopulationResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "STARTING DATABASE POPULATION (Mozilla MLS)")
        Log.d(TAG, "═══════════════════════════════════════════════════")

        try {
            // Step 1: Check if already populated
            if (!needsPopulation()) {
                Log.d(TAG, "✅ Database already populated")
                return@withContext DatabasePopulationResult.AlreadyPopulated
            }

            createNotificationChannel()
            showNotification("Downloading cell tower database...", 0)

            // Step 2: Create download directory
            val downloadDir = File(context.filesDir, DOWNLOAD_DIR)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            // Step 3: Download GZ file
            Log.d(TAG, "📥 Step 1: Downloading compressed data from Mozilla...")
            val gzFile = File(downloadDir, TEMP_GZ_FILE)

            val downloadSuccess = downloadFile(MLS_WORLD_URL, gzFile) { progress ->
                showNotification("Downloading... $progress%", progress)
                progressCallback?.invoke(progress / 5) // 0-20%
            }

            if (!downloadSuccess) {
                Log.e(TAG, "❌ Download failed")
                showNotification("Download failed", 0, isError = true)
                return@withContext DatabasePopulationResult.DownloadFailed
            }

            Log.d(TAG, "✅ Download complete: ${gzFile.length() / 1024 / 1024} MB")

            // Verify file size
            if (gzFile.length() < 1024 * 1024) { // Less than 1MB is suspicious
                Log.e(TAG, "❌ Downloaded file is too small (${gzFile.length()} bytes)")
                showNotification("Invalid file downloaded", 0, isError = true)
                return@withContext DatabasePopulationResult.DownloadFailed
            }

            // Step 4: Decompress GZ file
            Log.d(TAG, "📦 Step 2: Decompressing data...")
            showNotification("Decompressing...", 20)
            progressCallback?.invoke(20)

            val csvFile = File(downloadDir, TEMP_CSV_FILE)
            val decompressSuccess = decompressGzFile(gzFile, csvFile)

            // Delete GZ file immediately to save space
            gzFile.delete()

            if (!decompressSuccess) {
                Log.e(TAG, "❌ Decompression failed")
                showNotification("Decompression failed", 0, isError = true)
                return@withContext DatabasePopulationResult.DecompressionFailed
            }

            Log.d(TAG, "✅ Decompressed: ${csvFile.length() / 1024 / 1024} MB")

            // Step 5: Filter for India only (to save space)
            Log.d(TAG, "🇮🇳 Step 3: Filtering India data...")
            showNotification("Filtering India data...", 40)
            progressCallback?.invoke(40)

            val filteredFile = File(downloadDir, FILTERED_CSV_FILE)
            val filterSuccess = filterIndiaData(csvFile, filteredFile) { progress ->
                progressCallback?.invoke(40 + (progress / 5)) // 40-60%
            }

            // Delete full CSV to save space
            csvFile.delete()

            if (!filterSuccess) {
                Log.e(TAG, "❌ Filtering failed")
                showNotification("Filtering failed", 0, isError = true)
                return@withContext DatabasePopulationResult.ImportFailed("Filtering failed")
            }

            Log.d(TAG, "✅ Filtered: ${filteredFile.length() / 1024 / 1024} MB")

            // Step 6: Import into database
            Log.d(TAG, "💾 Step 4: Importing into database...")
            showNotification("Importing...", 60)

            val importResult = importCsvToDatabase(filteredFile) { progress ->
                val totalProgress = 60 + (progress * 40 / 100) // 60-100%
                showNotification("Importing... $progress%", totalProgress)
                progressCallback?.invoke(totalProgress)
            }

            // Step 7: Cleanup
            Log.d(TAG, "🧹 Step 5: Cleaning up...")
            filteredFile.delete()

            // Step 8: Show result
            if (importResult.success) {
                Log.d(TAG, "✅ DATABASE POPULATION COMPLETE")
                Log.d(TAG, "   Towers imported: ${importResult.recordsImported}")
                Log.d(TAG, "   Time taken: ${importResult.timeTakenMs / 1000}s")
                showNotification("✅ Import complete: ${importResult.recordsImported} towers", 100)

                database.logStatus()

                DatabasePopulationResult.Success(importResult.recordsImported)
            } else {
                Log.e(TAG, "❌ DATABASE IMPORT FAILED")
                showNotification("Import failed", 0, isError = true)
                DatabasePopulationResult.ImportFailed(importResult.error ?: "Unknown error")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR during database population", e)
            e.printStackTrace()
            showNotification("Critical error", 0, isError = true)
            DatabasePopulationResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Download file with proper error handling
     */
    private fun downloadFile(
        urlString: String,
        outputFile: File,
        progressCallback: (Int) -> Unit
    ): Boolean {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            Log.d(TAG, "  Downloading from Mozilla MLS...")
            Log.d(TAG, "  URL: ${urlString.take(80)}...")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000 // Longer timeout for large file
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "ChildSafetyApp/1.0")

            val responseCode = connection.responseCode
            Log.d(TAG, "  HTTP Response Code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "  ❌ HTTP Error: $responseCode")
                return false
            }

            val contentType = connection.contentType
            Log.d(TAG, "  Content-Type: $contentType")

            val fileSize = connection.contentLength.toLong()

            if (fileSize <= 0) {
                Log.w(TAG, "  ⚠️ Unknown file size, proceeding anyway...")
            } else {
                Log.d(TAG, "  File size: ${fileSize / 1024 / 1024} MB")
            }

            inputStream = connection.inputStream
            outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int
            var lastProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileSize > 0) {
                    val progress = ((totalBytesRead * 100) / fileSize).toInt()
                    if (progress != lastProgress && progress % 5 == 0) {
                        Log.d(TAG, "  Download progress: $progress%")
                        progressCallback(progress)
                        lastProgress = progress
                    }
                } else {
                    // Show progress in MB if size unknown
                    if (totalBytesRead % (5 * 1024 * 1024) == 0L) {
                        Log.d(TAG, "  Downloaded: ${totalBytesRead / 1024 / 1024} MB")
                    }
                }
            }

            outputStream.flush()
            Log.d(TAG, "  ✅ Download complete: ${totalBytesRead / 1024 / 1024} MB")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Download exception", e)
            e.printStackTrace()
            return false
        } finally {
            inputStream?.close()
            outputStream?.close()
            connection?.disconnect()
        }
    }

    /**
     * Decompress GZ file to CSV
     */
    private fun decompressGzFile(gzFile: File, csvFile: File): Boolean {
        var gzipStream: GZIPInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            Log.d(TAG, "  Decompressing ${gzFile.name}...")

            gzipStream = GZIPInputStream(FileInputStream(gzFile))
            outputStream = FileOutputStream(csvFile)

            val buffer = ByteArray(8192)
            var totalBytes = 0L
            var bytesRead: Int

            while (gzipStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead

                // Log progress every 50MB
                if (totalBytes % (50 * 1024 * 1024) == 0L) {
                    Log.d(TAG, "  Decompressed: ${totalBytes / 1024 / 1024} MB")
                }
            }

            outputStream.flush()
            Log.d(TAG, "  ✅ Decompression complete: ${totalBytes / 1024 / 1024} MB")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Decompression exception", e)
            e.printStackTrace()
            return false
        } finally {
            gzipStream?.close()
            outputStream?.close()
        }
    }

    /**
     * ✅ NEW: Filter only India data from full dataset
     */
    private fun filterIndiaData(
        inputFile: File,
        outputFile: File,
        progressCallback: (Int) -> Unit
    ): Boolean {
        var reader: BufferedReader? = null
        var writer: BufferedWriter? = null

        try {
            Log.d(TAG, "  Filtering India data (MCC: 404, 405, 406)...")

            reader = BufferedReader(FileReader(inputFile))
            writer = BufferedWriter(FileWriter(outputFile))

            // Copy header
            val header = reader.readLine()
            writer.write(header)
            writer.newLine()

            val totalLines = inputFile.length() / 100 // Rough estimate
            var processedLines = 0L
            var keptLines = 0
            var lastProgress = -1

            reader.lineSequence().forEach { line ->
                processedLines++

                // Parse MCC from line (second column)
                val parts = line.split(",")
                if (parts.size >= 2) {
                    val mcc = parts[1].toIntOrNull()
                    if (mcc in INDIA_MCC_CODES) {
                        writer.write(line)
                        writer.newLine()
                        keptLines++
                    }
                }

                // Progress every 100k lines
                if (processedLines % 100000 == 0L) {
                    val progress = ((processedLines * 100) / totalLines).toInt().coerceIn(0, 100)
                    if (progress != lastProgress) {
                        Log.d(TAG, "  Filtering: $progress% (Kept: $keptLines)")
                        progressCallback(progress)
                        lastProgress = progress
                    }
                }
            }

            writer.flush()
            Log.d(TAG, "  ✅ Filtering complete: $keptLines India towers kept")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Filtering exception", e)
            e.printStackTrace()
            return false
        } finally {
            reader?.close()
            writer?.close()
        }
    }

    /**
     * Import CSV file into database with batch inserts
     */
    private suspend fun importCsvToDatabase(
        csvFile: File,
        progressCallback: (Int) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {

        val startTime = System.currentTimeMillis()
        var reader: BufferedReader? = null

        try {
            Log.d(TAG, "  Opening CSV file...")
            reader = BufferedReader(FileReader(csvFile))

            // Count total lines for progress
            val totalLines = csvFile.length() / 100 // Rough estimate
            Log.d(TAG, "  Estimated rows: ~${totalLines / 1000}k")

            // Skip header line
            val header = reader.readLine()
            Log.d(TAG, "  Header: $header")

            val dao = database.cellTowerDao()
            var batch = mutableListOf<CellTowerEntity>()
            var totalImported = 0
            var lineNumber = 0L
            var lastProgress = -1

            reader.lineSequence().forEach { line ->
                lineNumber++

                // Parse CSV row
                val entity = CellTowerEntity.fromCsvRow(line)
                if (entity != null) {
                    batch.add(entity)
                }

                // Insert batch when full
                if (batch.size >= BATCH_SIZE) {
                    dao.insertAllIgnoreConflicts(batch)
                    totalImported += batch.size
                    batch.clear()

                    val progress = ((lineNumber * 100) / totalLines).toInt().coerceIn(0, 100)
                    if (progress != lastProgress && progress % 5 == 0) {
                        Log.d(TAG, "  Import progress: $progress% ($totalImported towers)")
                        progressCallback(progress)
                        lastProgress = progress
                    }
                }
            }

            // Insert remaining batch
            if (batch.isNotEmpty()) {
                dao.insertAllIgnoreConflicts(batch)
                totalImported += batch.size
            }

            val timeTaken = System.currentTimeMillis() - startTime

            Log.d(TAG, "  ✅ Import complete")
            Log.d(TAG, "     Records imported: $totalImported")
            Log.d(TAG, "     Time taken: ${timeTaken / 1000}s")

            ImportResult(
                success = true,
                recordsImported = totalImported,
                timeTakenMs = timeTaken
            )

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Import exception", e)
            e.printStackTrace()
            ImportResult(
                success = false,
                error = e.message
            )
        } finally {
            reader?.close()
        }
    }

    // Notification methods remain the same...
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cell Tower Database",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download and import cell tower database"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(message: String, progress: Int, isError: Boolean = false) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Cell Tower Database")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(!isError && progress < 100)
            .apply {
                if (progress > 0 && progress < 100) {
                    setProgress(100, progress, false)
                }
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        if (progress >= 100 || isError) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(NOTIFICATION_ID)
            }, 3000)
        }
    }
}

// Result classes remain the same...
sealed class DatabasePopulationResult {
    data class Success(val recordsImported: Int) : DatabasePopulationResult()
    object AlreadyPopulated : DatabasePopulationResult()
    object DownloadFailed : DatabasePopulationResult()
    object DecompressionFailed : DatabasePopulationResult()
    data class ImportFailed(val error: String) : DatabasePopulationResult()
    data class Error(val message: String) : DatabasePopulationResult()
}

data class ImportResult(
    val success: Boolean,
    val recordsImported: Int = 0,
    val timeTakenMs: Long = 0,
    val error: String? = null
)

class CellTowerDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CellTowerDownloadWorker"
        private const val FOREGROUND_CHANNEL_ID = "celltower_worker_foreground"
        private const val FOREGROUND_NOTIFICATION_ID = 9911
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createForegroundChannel()

        val notification = NotificationCompat.Builder(applicationContext, FOREGROUND_CHANNEL_ID)
            .setContentTitle("Downloading Offline Location Data")
            .setContentText("Preparing cell tower database…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun createForegroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Cell Tower Database Worker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles background downloading of the offline cell tower database."
            }

            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "⚙ Starting CellTowerDownloadWorker...")

        setForeground(getForegroundInfo())

        val manager = CellTowerDatabaseManager(applicationContext)

        return try {
            when (val result = manager.downloadAndPopulateDatabase()) {
                is DatabasePopulationResult.Success -> {
                    Log.d(TAG, "✅ Successfully imported ${result.recordsImported} towers")
                    CellTowerDatabaseInitializer.markDatabaseAsDownloaded(applicationContext)
                    Result.success()
                }
                is DatabasePopulationResult.AlreadyPopulated -> {
                    Log.d(TAG, "ℹ️ Already populated")
                    CellTowerDatabaseInitializer.markDatabaseAsDownloaded(applicationContext)
                    Result.success()
                }
                else -> {
                    Log.e(TAG, "❌ Download/Import failed")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL Worker Error", e)
            Result.retry()
        }
    }
}