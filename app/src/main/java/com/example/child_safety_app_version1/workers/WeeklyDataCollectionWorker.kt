package com.example.child_safety_app_version1.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.child_safety_app_version1.MainActivity
import com.example.child_safety_app_version1.managers.WeeklyInsightsCollector
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Worker to collect weekly usage data
 * Runs daily at 7 PM (19:00)
 */
class WeeklyDataCollectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WeeklyDataWorker"
        private const val WORK_NAME = "weekly_data_collection"

        /**
         * Schedule the worker to run daily at 7 PM (19:00)
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<WeeklyDataCollectionWorker>(
                24, TimeUnit.HOURS,  // Repeat every 24 hours
                6, TimeUnit.HOURS    // Flex period: 6 hours
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

            Log.d(TAG, "✅ Weekly data collection worker scheduled (daily at 7 PM)")
        }

        /**
         * Calculate delay to next 7 PM (19:00)
         */
        private fun calculateInitialDelay(): Long {
            val now = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 19)  // 7 PM
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)

            var targetTime = calendar.timeInMillis
            if (targetTime <= now) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                targetTime = calendar.timeInMillis
            }

            val delayHours = (targetTime - now) / (1000 * 60 * 60)
            Log.d(TAG, "Next collection scheduled in ~$delayHours hours (at 7 PM)")

            return targetTime - now
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "⏰ Weekly data collection worker started at 7 PM")
        Log.d(TAG, "════════════════════════════════════════")

        return try {
            // Get current user (parent)
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.w(TAG, "No user logged in, skipping")
                return Result.success()
            }

            val parentUid = currentUser.uid
            Log.d(TAG, "Parent UID: ${parentUid.take(10)}...")

            // Get all children
            val db = FirebaseFirestore.getInstance()
            val childrenSnapshot = db.collection("users")
                .document(parentUid)
                .collection("children")
                .get()
                .await()

            if (childrenSnapshot.isEmpty) {
                Log.d(TAG, "No children found")
                return Result.success()
            }

            Log.d(TAG, "Found ${childrenSnapshot.size()} children")

            // Process each child
            for (childDoc in childrenSnapshot.documents) {
                val childUid = childDoc.getString("childId") ?: continue
                val childName = childDoc.getString("childName") ?: "Child"

                Log.d(TAG, "")
                Log.d(TAG, "Processing: $childName")

                // 🔧 FIX: Collect latest available data (not "last week")
                Log.d(TAG, "   Collecting latest 7 days of data...")
                val weeklyData = WeeklyInsightsCollector.collectLastWeekData(
                    applicationContext,
                    childUid
                )

                if (weeklyData != null) {
                    val weekId = weeklyData.weekId

                    // Check if data already collected for this week
                    val exists = WeeklyInsightsCollector.weeklyDataExists(childUid, weekId)
                    if (exists) {
                        Log.d(TAG, "   Data already collected for $weekId")
                        continue
                    }

                    // Save to Firestore
                    val saved = WeeklyInsightsCollector.saveWeeklyDataToFirestore(
                        childUid,
                        weeklyData
                    )

                    if (saved) {
                        Log.d(TAG, "   ✅ Data collected and saved for $weekId")
                        Log.d(TAG, "      Days: ${weeklyData.dataAvailableDays}")
                        Log.d(TAG, "      Apps: ${weeklyData.aggregatedApps.size}")
                    } else {
                        Log.e(TAG, "   ❌ Failed to save data")
                    }
                } else {
                    Log.w(TAG, "   ⚠️ No data available - need at least 1 day of usage data")
                }
            }

            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "✅ Worker completed successfully")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed", e)
            e.printStackTrace()
            Result.retry()
        }
    }
}

/**
 * Worker to send weekly insights notifications
 * Runs every Monday at 9 AM
 */
class WeeklyInsightsNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WeeklyInsightsNotif"
        private const val WORK_NAME = "weekly_insights_notification"
        private const val CHANNEL_ID = "weekly_insights"
        private const val CHANNEL_NAME = "Weekly Insights"

        /**
         * Schedule the worker to run every Monday at 9 AM
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<WeeklyInsightsNotificationWorker>(
                7, TimeUnit.DAYS  // Once per week
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelayToMonday(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

            Log.d(TAG, "✅ Weekly insights notification worker scheduled")
        }

        /**
         * Calculate delay to next Monday 9 AM
         */
        private fun calculateInitialDelayToMonday(): Long {
            val now = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance()

            // Set to Monday 9 AM
            calendar.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 9)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)

            var targetTime = calendar.timeInMillis

            // If Monday 9 AM already passed this week, schedule for next week
            if (targetTime <= now) {
                calendar.add(java.util.Calendar.WEEK_OF_YEAR, 1)
                targetTime = calendar.timeInMillis
            }

            return targetTime - now
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "📬 Weekly insights notification worker started")
        Log.d(TAG, "════════════════════════════════════════")

        return try {
            createNotificationChannel()

            // Get current user
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.w(TAG, "No user logged in")
                return Result.success()
            }

            val parentUid = currentUser.uid

            // Get all children
            val db = FirebaseFirestore.getInstance()
            val childrenSnapshot = db.collection("users")
                .document(parentUid)
                .collection("children")
                .get()
                .await()

            if (childrenSnapshot.isEmpty) {
                Log.d(TAG, "No children found")
                return Result.success()
            }

            Log.d(TAG, "Checking insights for ${childrenSnapshot.size()} children")

            var notificationsSent = 0

            // Check each child for available insights
            for (childDoc in childrenSnapshot.documents) {
                val childUid = childDoc.getString("childId") ?: continue
                val childName = childDoc.getString("childName") ?: "Child"

                // 🔧 FIX: Query Firestore for any weekly data
                val weeklyDataSnapshot = db.collection("users")
                    .document(childUid)
                    .collection("weeklyUsageData")
                    .orderBy("collectedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .await()

                if (!weeklyDataSnapshot.isEmpty) {
                    val latestDoc = weeklyDataSnapshot.documents[0]
                    val weekId = latestDoc.getString("weekId") ?: continue
                    val startDate = latestDoc.getString("startDate") ?: ""
                    val endDate = latestDoc.getString("endDate") ?: ""
                    val dataAvailableDays = latestDoc.getLong("dataAvailableDays")?.toInt() ?: 0

                    Log.d(TAG, "Latest data for $childName:")
                    Log.d(TAG, "   Week ID: $weekId")
                    Log.d(TAG, "   Days: $dataAvailableDays")

                    if (dataAvailableDays > 0) {
                        sendInsightsNotification(childName, weekId, startDate, endDate)
                        notificationsSent++
                    } else {
                        Log.d(TAG, "   ⚠️ No valid data days")
                    }
                } else {
                    Log.d(TAG, "No weekly data found for $childName")
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "✅ Sent $notificationsSent notification(s)")
            Log.d(TAG, "════════════════════════════════════════")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed", e)
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Weekly app usage insights notifications"
                enableLights(true)
                enableVibration(true)
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendInsightsNotification(
        childName: String,
        weekId: String,
        startDate: String,
        endDate: String
    ) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_insights", true)
            putExtra("weekId", weekId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            weekId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📊 Weekly Insights Available")
            .setContentText("Check $childName's app usage insights for $startDate to $endDate")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("New weekly insights are ready for $childName. View patterns, trends, and recommendations for the week of $startDate to $endDate."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(weekId.hashCode(), notification)

        Log.d(TAG, "✅ Notification sent for $childName")
    }
}