package com.example.child_safety_app_version1.database.celltower

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Manages downloading and populating the cell tower database
 * from OpenCelliD public database
 */
class CellTowerDatabaseManager(private val context: Context) {

    companion object {
        private const val TAG = "CellTowerDBManager"

        // OpenCelliD Download URLs (India data)
        private const val INDIA_MCC_404_URL = "https://opencellid.org/downloads/?token=YOUR_TOKEN&type=mcc&file=404.csv.gz"
        private const val INDIA_MCC_405_URL = "https://opencellid.org/downloads/?token=YOUR_TOKEN&type=mcc&file=405.csv.gz"

        // Alternative: Full India database (larger file)
        private const val INDIA_FULL_URL = "https://opencellid.org/downloads/?token=YOUR_TOKEN&type=country&file=IN.csv.gz"

        // File paths
        private const val DOWNLOAD_DIR = "celltower_data"
        private const val TEMP_GZ_FILE = "india_towers.csv.gz"
        private const val TEMP_CSV_FILE = "india_towers.csv"

        // Batch insert size (don't overwhelm database)
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
     * Download and populate database with India cell tower data
     * This is a LONG operation - should be called from WorkManager or background thread
     */
    suspend fun downloadAndPopulateDatabase(
        progressCallback: ((Int) -> Unit)? = null
    ): DatabasePopulationResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "STARTING DATABASE POPULATION")
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
            Log.d(TAG, "📥 Step 1: Downloading compressed data...")
            val gzFile = File(downloadDir, TEMP_GZ_FILE)

            val downloadSuccess = downloadFile(INDIA_FULL_URL, gzFile) { progress ->
                showNotification("Downloading... $progress%", progress)
                progressCallback?.invoke(progress / 4) // 0-25%
            }

            if (!downloadSuccess) {
                Log.e(TAG, "❌ Download failed")
                showNotification("Download failed", 0, isError = true)
                return@withContext DatabasePopulationResult.DownloadFailed
            }

            Log.d(TAG, "✅ Download complete: ${gzFile.length() / 1024 / 1024} MB")

            // Step 4: Decompress GZ file
            Log.d(TAG, "📦 Step 2: Decompressing data...")
            showNotification("Decompressing...", 25)
            progressCallback?.invoke(25)

            val csvFile = File(downloadDir, TEMP_CSV_FILE)
            val decompressSuccess = decompressGzFile(gzFile, csvFile)

            if (!decompressSuccess) {
                Log.e(TAG, "❌ Decompression failed")
                showNotification("Decompression failed", 0, isError = true)
                return@withContext DatabasePopulationResult.DecompressionFailed
            }

            Log.d(TAG, "✅ Decompressed: ${csvFile.length() / 1024 / 1024} MB")

            // Step 5: Parse and insert into database
            Log.d(TAG, "💾 Step 3: Importing into database...")
            showNotification("Importing...", 50)

            val importResult = importCsvToDatabase(csvFile) { progress ->
                val totalProgress = 50 + (progress / 2) // 50-100%
                showNotification("Importing... $progress%", totalProgress)
                progressCallback?.invoke(totalProgress)
            }

            // Step 6: Cleanup
            Log.d(TAG, "🧹 Step 4: Cleaning up temporary files...")
            gzFile.delete()
            csvFile.delete()

            // Step 7: Show result
            if (importResult.success) {
                Log.d(TAG, "✅ DATABASE POPULATION COMPLETE")
                Log.d(TAG, "   Towers imported: ${importResult.recordsImported}")
                Log.d(TAG, "   Time taken: ${importResult.timeTakenMs / 1000}s")
                showNotification("✅ Import complete: ${importResult.recordsImported} towers", 100)

                // Log final database stats
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
     * Download file from URL with progress callback
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
            // TODO: Replace YOUR_TOKEN with actual OpenCelliD token
            if (urlString.contains("YOUR_TOKEN")) {
                Log.e(TAG, "❌ OpenCelliD token not configured!")
                Log.e(TAG, "   Get your token from: https://opencellid.org/")
                return false
            }

            Log.d(TAG, "  Downloading from: ${urlString.replace(Regex("token=[^&]+"), "token=***")}")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "  HTTP Error: $responseCode")
                return false
            }

            val fileSize = connection.contentLength
            Log.d(TAG, "  File size: ${fileSize / 1024 / 1024} MB")

            inputStream = connection.inputStream
            outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int
            var lastProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                val progress = ((totalBytesRead * 100) / fileSize).toInt()
                if (progress != lastProgress && progress % 5 == 0) {
                    Log.d(TAG, "  Download progress: $progress%")
                    progressCallback(progress)
                    lastProgress = progress
                }
            }

            outputStream.flush()
            Log.d(TAG, "  ✅ Download complete")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Download exception", e)
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
            var bytesRead: Int

            while (gzipStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.flush()
            Log.d(TAG, "  ✅ Decompression complete")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Decompression exception", e)
            return false
        } finally {
            gzipStream?.close()
            outputStream?.close()
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
            val totalLines = reader.lines().count()
            reader.close()

            Log.d(TAG, "  Total rows: $totalLines")

            // Re-open for reading
            reader = BufferedReader(FileReader(csvFile))

            // Skip header line
            val header = reader.readLine()
            Log.d(TAG, "  Header: $header")

            val dao = database.cellTowerDao()
            var batch = mutableListOf<CellTowerEntity>()
            var totalImported = 0
            var lineNumber = 0
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

                    val progress = ((lineNumber * 100) / totalLines).toInt()
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
            Log.d(TAG, "     Records/second: ${(totalImported * 1000 / timeTaken)}")

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

    /**
     * Create notification channel for downloads
     */
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

    /**
     * Show progress notification
     */
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

        // Cancel notification after completion/error
        if (progress >= 100 || isError) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(NOTIFICATION_ID)
            }, 3000)
        }
    }

    /**
     * Schedule database download using WorkManager
     */
    fun scheduleBackgroundDownload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<CellTowerDownloadWorker>()
            .setConstraints(constraints)
            .addTag("celltower_download")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "celltower_database_download",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        Log.d(TAG, "📅 Background download scheduled (WiFi, battery not low)")
    }
}

/**
 * Result of database population
 */
sealed class DatabasePopulationResult {
    data class Success(val recordsImported: Int) : DatabasePopulationResult()
    object AlreadyPopulated : DatabasePopulationResult()
    object DownloadFailed : DatabasePopulationResult()
    object DecompressionFailed : DatabasePopulationResult()
    data class ImportFailed(val error: String) : DatabasePopulationResult()
    data class Error(val message: String) : DatabasePopulationResult()
}

/**
 * Result of CSV import
 */
data class ImportResult(
    val success: Boolean,
    val recordsImported: Int = 0,
    val timeTakenMs: Long = 0,
    val error: String? = null
)

/**
 * WorkManager worker for background database download
 */
class CellTowerDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("CellTowerDownloadWorker", "Starting background download...")

        val manager = CellTowerDatabaseManager(applicationContext)

        return when (val result = manager.downloadAndPopulateDatabase()) {
            is DatabasePopulationResult.Success -> {
                Log.d("CellTowerDownloadWorker", "✅ Success: ${result.recordsImported} towers")
                Result.success()
            }
            is DatabasePopulationResult.AlreadyPopulated -> {
                Log.d("CellTowerDownloadWorker", "✅ Already populated")
                Result.success()
            }
            else -> {
                Log.e("CellTowerDownloadWorker", "❌ Failed: $result")
                Result.retry()
            }
        }
    }
}