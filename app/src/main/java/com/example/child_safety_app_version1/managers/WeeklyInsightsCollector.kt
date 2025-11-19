package com.example.child_safety_app_version1.managers

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.data.AppUsageInfo
import com.example.child_safety_app_version1.data.DailyUsageStats
import com.example.child_safety_app_version1.data.WeeklyUsageData
import com.example.child_safety_app_version1.utils.UsageStatsHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

object WeeklyInsightsCollector {
    private const val TAG = "WeeklyInsightsCollector"

    /**
     * Collect weekly usage data for the last complete week
     * Returns null if insufficient data
     */
    suspend fun collectLastWeekData(
        context: Context,
        childUid: String
    ): WeeklyUsageData? {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║        COLLECTING LAST WEEK'S USAGE DATA                  ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

        try {
            val db = FirebaseFirestore.getInstance()

            // Calculate last week's date range (Monday to Sunday)
            val (weekId, startDate, endDate) = getLastWeekDateRange()

            Log.d(TAG, "📅 Week ID: $weekId")
            Log.d(TAG, "📅 Start Date: $startDate")
            Log.d(TAG, "📅 End Date: $endDate")
            Log.d(TAG, "")

            val dailyDataMap = mutableMapOf<String, DailyUsageStats>()
            val aggregatedAppsMap = mutableMapOf<String, AppUsageInfo>()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val startCal = Calendar.getInstance()
            startCal.time = dateFormat.parse(startDate) ?: return null

            // Collect data for each day of the week
            for (dayOffset in 0..6) {
                val currentDate = Calendar.getInstance()
                currentDate.time = startCal.time
                currentDate.add(Calendar.DAY_OF_MONTH, dayOffset)
                val dateString = dateFormat.format(currentDate.time)

                Log.d(TAG, "📊 Collecting data for: $dateString")

                try {
                    // Fetch from Firestore: /users/{childUid}/appUsage/{date}/apps/
                    val appsSnapshot = db.collection("users")
                        .document(childUid)
                        .collection("appUsage")
                        .document(dateString)
                        .collection("apps")
                        .get()
                        .await()

                    if (appsSnapshot.isEmpty) {
                        Log.d(TAG, "   ⚠️ No data for $dateString")
                        continue
                    }

                    val dayApps = appsSnapshot.documents.mapNotNull { doc ->
                        try {
                            AppUsageInfo(
                                packageName = doc.getString("packageName") ?: return@mapNotNull null,
                                appName = doc.getString("appName") ?: "Unknown",
                                totalTimeMs = doc.getLong("totalTimeMs") ?: 0L,
                                lastUsed = doc.getLong("lastUsed") ?: 0L,
                                icon = null
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "      Error parsing app: ${e.message}")
                            null
                        }
                    }

                    Log.d(TAG, "   ✅ Found ${dayApps.size} apps")
                    Log.d(TAG, "   Total time: ${UsageStatsHelper.formatDuration(dayApps.sumOf { it.totalTimeMs })}")

                    // Add to daily data
                    dailyDataMap[dateString] = DailyUsageStats.fromAppList(dayApps, dateString)

                    // Aggregate apps
                    for (app in dayApps) {
                        val existing = aggregatedAppsMap[app.packageName]
                        if (existing != null) {
                            aggregatedAppsMap[app.packageName] = existing.copy(
                                totalTimeMs = existing.totalTimeMs + app.totalTimeMs,
                                lastUsed = maxOf(existing.lastUsed, app.lastUsed)
                            )
                        } else {
                            aggregatedAppsMap[app.packageName] = app
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "   ❌ Error fetching data for $dateString: ${e.message}")
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "📊 Collection Summary:")
            Log.d(TAG, "   Days with data: ${dailyDataMap.size}/7")
            Log.d(TAG, "   Unique apps: ${aggregatedAppsMap.size}")
            Log.d(TAG, "   Total screen time: ${UsageStatsHelper.formatDuration(aggregatedAppsMap.values.sumOf { it.totalTimeMs })}")

            if (dailyDataMap.isEmpty()) {
                Log.w(TAG, "⚠️ No data available for last week")
                return null
            }

            val weeklyData = WeeklyUsageData(
                weekId = weekId,
                startDate = startDate,
                endDate = endDate,
                dailyData = dailyDataMap,
                aggregatedApps = aggregatedAppsMap
            )

            Log.d(TAG, "")
            Log.d(TAG, "✅ Weekly data collection completed")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

            return weeklyData

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR collecting weekly data", e)
            e.printStackTrace()
            return null
        }
    }

    /**
     * Get date range for last complete week (Monday to Sunday)
     * Returns Triple(weekId, startDate, endDate)
     */
    fun getLastWeekDateRange(): Triple<String, String, String> {
        val calendar = Calendar.getInstance()

        // Go back to last Sunday
        val daysToSubtract = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 0
            else -> 0
        }

        calendar.add(Calendar.DAY_OF_MONTH, -daysToSubtract)
        val lastSunday = calendar.clone() as Calendar

        // Go back 7 more days to get Monday of last week
        calendar.add(Calendar.DAY_OF_MONTH, -6)
        val lastMonday = calendar.clone() as Calendar

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = dateFormat.format(lastMonday.time)
        val endDate = dateFormat.format(lastSunday.time)

        // Generate week ID (ISO week format)
        val weekFormat = SimpleDateFormat("yyyy-'W'ww", Locale.getDefault())
        val weekId = weekFormat.format(lastMonday.time)

        return Triple(weekId, startDate, endDate)
    }

    /**
     * Get date range for current week (for notifications)
     */
    fun getCurrentWeekDateRange(): Triple<String, String, String> {
        val calendar = Calendar.getInstance()

        // Go to Monday of current week
        val daysToSubtract = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }

        calendar.add(Calendar.DAY_OF_MONTH, -daysToSubtract)
        val monday = calendar.clone() as Calendar

        // Go to Sunday of current week
        calendar.add(Calendar.DAY_OF_MONTH, 6)
        val sunday = calendar.clone() as Calendar

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = dateFormat.format(monday.time)
        val endDate = dateFormat.format(sunday.time)

        val weekFormat = SimpleDateFormat("yyyy-'W'ww", Locale.getDefault())
        val weekId = weekFormat.format(monday.time)

        return Triple(weekId, startDate, endDate)
    }

    /**
     * Save weekly data to Firestore (without insights)
     */
    suspend fun saveWeeklyDataToFirestore(
        childUid: String,
        weeklyData: WeeklyUsageData
    ): Boolean {
        return try {
            val db = FirebaseFirestore.getInstance()

            val data = hashMapOf(
                "weekId" to weeklyData.weekId,
                "startDate" to weeklyData.startDate,
                "endDate" to weeklyData.endDate,
                "childUid" to childUid,
                "totalScreenTimeMs" to weeklyData.totalScreenTimeMs,
                "topApps" to weeklyData.getTopApps(10).map { it.toMap() },
                "dataAvailableDays" to weeklyData.dataAvailableDays,
                "averageDailyScreenTimeMs" to weeklyData.averageDailyScreenTimeMs,
                "collectedAt" to System.currentTimeMillis(),
                "insightsGenerated" to false
            )

            db.collection("users")
                .document(childUid)
                .collection("weeklyUsageData")
                .document(weeklyData.weekId)
                .set(data)
                .await()

            Log.d(TAG, "✅ Weekly data saved to Firestore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving weekly data", e)
            false
        }
    }

    /**
     * Check if weekly data exists for a given week
     */
    suspend fun weeklyDataExists(childUid: String, weekId: String): Boolean {
        return try {
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("users")
                .document(childUid)
                .collection("weeklyUsageData")
                .document(weekId)
                .get()
                .await()

            doc.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking weekly data existence", e)
            false
        }
    }

    /**
     * Load weekly data from Firestore
     */
    suspend fun loadWeeklyDataFromFirestore(
        childUid: String,
        weekId: String
    ): WeeklyUsageData? {
        return try {
            val db = FirebaseFirestore.getInstance()

            // Get the weekly data document
            val weekDoc = db.collection("users")
                .document(childUid)
                .collection("weeklyUsageData")
                .document(weekId)
                .get()
                .await()

            if (!weekDoc.exists()) {
                Log.d(TAG, "No weekly data found for $weekId")
                return null
            }

            val startDate = weekDoc.getString("startDate") ?: return null
            val endDate = weekDoc.getString("endDate") ?: return null

            // Load daily data for this week
            val dailyDataMap = mutableMapOf<String, DailyUsageStats>()
            val aggregatedAppsMap = mutableMapOf<String, AppUsageInfo>()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val startCal = Calendar.getInstance()
            startCal.time = dateFormat.parse(startDate) ?: return null

            // Collect data for each day
            for (dayOffset in 0..6) {
                val currentDate = Calendar.getInstance()
                currentDate.time = startCal.time
                currentDate.add(Calendar.DAY_OF_MONTH, dayOffset)
                val dateString = dateFormat.format(currentDate.time)

                try {
                    val appsSnapshot = db.collection("users")
                        .document(childUid)
                        .collection("appUsage")
                        .document(dateString)
                        .collection("apps")
                        .get()
                        .await()

                    if (!appsSnapshot.isEmpty) {
                        val dayApps = appsSnapshot.documents.mapNotNull { doc ->
                            try {
                                AppUsageInfo(
                                    packageName = doc.getString("packageName") ?: return@mapNotNull null,
                                    appName = doc.getString("appName") ?: "Unknown",
                                    totalTimeMs = doc.getLong("totalTimeMs") ?: 0L,
                                    lastUsed = doc.getLong("lastUsed") ?: 0L,
                                    icon = null
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }

                        dailyDataMap[dateString] = DailyUsageStats.fromAppList(dayApps, dateString)

                        // Aggregate
                        for (app in dayApps) {
                            val existing = aggregatedAppsMap[app.packageName]
                            if (existing != null) {
                                aggregatedAppsMap[app.packageName] = existing.copy(
                                    totalTimeMs = existing.totalTimeMs + app.totalTimeMs,
                                    lastUsed = maxOf(existing.lastUsed, app.lastUsed)
                                )
                            } else {
                                aggregatedAppsMap[app.packageName] = app
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading day $dateString", e)
                }
            }

            if (dailyDataMap.isEmpty()) {
                return null
            }

            WeeklyUsageData(
                weekId = weekId,
                startDate = startDate,
                endDate = endDate,
                dailyData = dailyDataMap,
                aggregatedApps = aggregatedAppsMap
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error loading weekly data from Firestore", e)
            null
        }
    }
}