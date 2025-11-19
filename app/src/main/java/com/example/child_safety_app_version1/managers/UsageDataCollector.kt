package com.example.child_safety_app_version1.managers

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.data.AppUsageInfo
import com.example.child_safety_app_version1.utils.UsageStatsHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object UsageDataCollector {
    private const val TAG = "UsageDataCollector-DEBUG"

    /**
     * Collect app usage data and upload to Firestore
     * Called when parent requests data
     */
    suspend fun collectAndUpload(
        context: Context,
        childUid: String,
        requestId: String
    ): Boolean {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║        USAGE DATA COLLECTION STARTED                      ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "🔍 STEP 1: Checking Permissions")
        Log.d(TAG, "   Child UID: ${childUid.take(15)}...")
        Log.d(TAG, "   Request ID: $requestId")

        return try {
            // Step 1: Check permission
            Log.d(TAG, "   Checking Usage Stats Permission...")
            val hasPermission = UsageStatsHelper.hasUsageStatsPermission(context)
            Log.d(TAG, "   Permission Result: $hasPermission")

            if (!hasPermission) {
                Log.e(TAG, "   ❌ FAILED: Usage stats permission not granted")
                Log.e(TAG, "   Action Required: User must enable Usage Stats in Settings")
                updateRequestStatus(childUid, requestId, "failed", "Permission not granted")
                return false
            }

            Log.d(TAG, "   ✅ Permission check passed")

            // Step 2: Collect usage data
            Log.d(TAG, "")
            Log.d(TAG, "🔍 STEP 2: Collecting Today's Usage Statistics")
            Log.d(TAG, "   Querying UsageStatsManager...")
            val usageData = UsageStatsHelper.getTodayUsageStats(context)

            Log.d(TAG, "   📊 Collection Result: ${usageData.size} apps found")

            if (usageData.isEmpty()) {
                Log.w(TAG, "   ⚠️ WARNING: No usage data found for today")
                Log.w(TAG, "   Possible reasons:")
                Log.w(TAG, "   - Device just started")
                Log.w(TAG, "   - No apps have been used yet")
                Log.w(TAG, "   - All apps are system apps (filtered out)")
                updateRequestStatus(childUid, requestId, "completed", "No data")
                return true
            }

            Log.d(TAG, "   ✅ Successfully collected ${usageData.size} apps")
            Log.d(TAG, "")
            Log.d(TAG, "   Top 10 Apps:")
            usageData.take(10).forEachIndexed { index, app ->
                val duration = UsageStatsHelper.formatDuration(app.totalTimeMs)
                Log.d(TAG, "      ${index + 1}. ${app.appName}: $duration")
            }

            // Step 3: Upload to Firestore
            Log.d(TAG, "")
            Log.d(TAG, "🔍 STEP 3: Uploading to Firestore")
            val todayDate = UsageStatsHelper.getTodayDateString()
            Log.d(TAG, "   Date: $todayDate")
            Log.d(TAG, "   Path: /users/$childUid/appUsage/$todayDate/apps/")
            Log.d(TAG, "   Records to upload: ${usageData.size}")

            val uploadSuccess = uploadToFirestore(childUid, usageData, todayDate)

            if (!uploadSuccess) {
                Log.e(TAG, "   ❌ FAILED: Upload to Firestore failed")
                updateRequestStatus(childUid, requestId, "failed", "Upload failed")
                return false
            }

            Log.d(TAG, "   ✅ Successfully uploaded to Firestore")

            // Step 4: Update request status
            Log.d(TAG, "")
            Log.d(TAG, "🔍 STEP 4: Updating Request Status")
            updateRequestStatus(childUid, requestId, "completed", null)
            Log.d(TAG, "   ✅ Request marked as completed")

            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║   ✅ COLLECTION COMPLETED SUCCESSFULLY                    ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

            return true

        } catch (e: Exception) {
            Log.e(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.e(TAG, "║   ❌ CRITICAL ERROR during data collection               ║")
            Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
            Log.e(TAG, "Exception Type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception Message: ${e.message}")
            Log.e(TAG, "Stack Trace:")
            e.printStackTrace()

            updateRequestStatus(childUid, requestId, "failed", e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Upload usage data to Firestore
     */
    private suspend fun uploadToFirestore(
        childUid: String,
        usageData: List<AppUsageInfo>,
        todayDate: String
    ): Boolean {
        return try {
            val db = FirebaseFirestore.getInstance()

            Log.d(TAG, "   Starting batch upload...")

            var successCount = 0
            var failureCount = 0

            for (app in usageData) {
                try {
                    val uploadData = app.toFirestoreMap()

                    Log.d(TAG, "      ↳ Uploading: ${app.appName}")

                    // Use package name as document ID for easy updates
                    db.collection("users")
                        .document(childUid)
                        .collection("appUsage")
                        .document(todayDate)
                        .collection("apps")
                        .document(app.packageName)
                        .set(uploadData)
                        .await()

                    successCount++
                    Log.d(TAG, "         ✅ Success")

                } catch (e: Exception) {
                    failureCount++
                    Log.e(TAG, "         ❌ Failed: ${e.message}")
                }
            }

            Log.d(TAG, "   ")
            Log.d(TAG, "   📊 Upload Summary:")
            Log.d(TAG, "      ✅ Success: $successCount")
            Log.d(TAG, "      ❌ Failed: $failureCount")
            Log.d(TAG, "      📈 Success Rate: ${if (usageData.isNotEmpty()) (successCount * 100 / usageData.size) else 0}%")

            // Consider it successful if at least some data uploaded
            val isSuccessful = successCount > 0
            Log.d(TAG, "   Result: ${if (isSuccessful) "✅ SUCCESSFUL" else "❌ FAILED"}")

            return isSuccessful

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ CRITICAL: Error in uploadToFirestore")
            Log.e(TAG, "   ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Update the status of the data request
     */
    private suspend fun updateRequestStatus(
        childUid: String,
        requestId: String,
        status: String,
        errorMessage: String? = null
    ) {
        try {
            Log.d(TAG, "   Updating request status to: $status")

            val db = FirebaseFirestore.getInstance()
            val requestRef = db.collection("users")
                .document(childUid)
                .collection("usageDataRequest")
                .document(requestId)

            val updateData = hashMapOf<String, Any>(
                "status" to status,
                "completedAt" to System.currentTimeMillis()
            )

            if (errorMessage != null) {
                updateData["errorMessage"] = errorMessage
                Log.d(TAG, "   Error Message: $errorMessage")
            }

            requestRef.update(updateData).await()
            Log.d(TAG, "   ✅ Status updated successfully")

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error updating request status: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Save last collection timestamp to SharedPreferences
     */
    fun saveLastCollectionTime(context: Context) {
        try {
            val prefs = context.getSharedPreferences("usage_stats_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_collection_time", System.currentTimeMillis()).apply()
            Log.d(TAG, "✅ Last collection time saved")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving last collection time", e)
        }
    }

    /**
     * Get last collection timestamp from SharedPreferences
     */
    fun getLastCollectionTime(context: Context): Long? {
        return try {
            val prefs = context.getSharedPreferences("usage_stats_prefs", Context.MODE_PRIVATE)
            val time = prefs.getLong("last_collection_time", 0)
            if (time > 0) time else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last collection time", e)
            null
        }
    }

    suspend fun collectAndUploadAllApps(
        context: Context,
        childUid: String,
        requestId: String
    ): Boolean {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║        ALL APPS COLLECTION STARTED                        ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "🔍 STEP 1: Collecting All Installed Apps")
        Log.d(TAG, "   Child UID: ${childUid.take(15)}...")
        Log.d(TAG, "   Request ID: $requestId")

        return try {
            // Step 1: Collect all installed apps
            Log.d(TAG, "   📱 Querying PackageManager for all user apps...")
            val allApps = UsageStatsHelper.getAllInstalledAppsWithUsage(context)

            Log.d(TAG, "   📊 Collection Result: ${allApps.size} apps found")

            if (allApps.isEmpty()) {
                Log.w(TAG, "   ⚠️ WARNING: No apps found")
                updateAllAppsRequestStatus(childUid, requestId, "completed", "No apps")
                return true
            }

            Log.d(TAG , "   ✅ Successfully collected ${allApps.size} apps")
            Log.d(TAG , "")
            Log.d(TAG, "   First 10 Apps:")
            allApps.take(10).forEachIndexed { index, app ->
                val duration = UsageStatsHelper.formatDuration(app.totalTimeMs)
                Log.d(TAG, "      ${index + 1}. ${app.appName}: $duration")
            }

            // Step 2: Upload to Firestore
            Log.d(TAG, "")
            Log.d(TAG, "🔍 STEP 2: Uploading to Firestore")
            Log.d(TAG, "   Path: /users/$childUid/allApps/")
            Log.d(TAG, "   Records to upload: ${allApps.size}")

            val uploadSuccess = uploadAllAppsToFirestore(childUid, allApps)

            if (!uploadSuccess) {
                Log.e(TAG, "   ❌ FAILED: Upload to Firestore failed")
                updateAllAppsRequestStatus(childUid, requestId, "failed", "Upload failed")
                return false
            }

            Log.d(TAG, "   ✅ Successfully uploaded to Firestore")

            // Step 3: Update request status
            Log.d(TAG, "")
            Log.d(TAG, "🔍 STEP 3: Updating Request Status")
            updateAllAppsRequestStatus(childUid, requestId, "completed", null)
            Log.d(TAG, "   ✅ Request marked as completed")

            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║   ✅ ALL APPS COLLECTION COMPLETED SUCCESSFULLY           ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

            return true

        } catch (e: Exception) {
            Log.e(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.e(TAG, "║   ❌ CRITICAL ERROR during all apps collection           ║")
            Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
            Log.e(TAG, "Exception Type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception Message: ${e.message}")
            Log.e(TAG, "Stack Trace:")
            e.printStackTrace()

            updateAllAppsRequestStatus(childUid, requestId, "failed", e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Upload all apps list to Firestore
     */
    private suspend fun uploadAllAppsToFirestore(
        childUid: String,
        allApps: List<AppUsageInfo>
    ): Boolean {
        return try {
            val db = FirebaseFirestore.getInstance()

            Log.d(TAG, "   Starting batch upload...")

            // 🔧 FIX: Clear existing data first using BATCH operations (much faster)
            Log.d(TAG, "   🗑️ Clearing old allApps data...")
            val allAppsRef = db.collection("users")
                .document(childUid)
                .collection("allApps")

            val existingDocs = allAppsRef.get().await()
            Log.d(TAG, "   Found ${existingDocs.size()} existing documents to delete")

            if (existingDocs.documents.isNotEmpty()) {
                // Use batch operations for efficient deletion (max 500 per batch)
                val deleteBatches = existingDocs.documents.chunked(500)

                for ((batchIndex, batchDocs) in deleteBatches.withIndex()) {
                    Log.d(TAG, "   Deleting batch ${batchIndex + 1}/${deleteBatches.size} (${batchDocs.size} docs)...")
                    val writeBatch = db.batch()

                    batchDocs.forEach { doc ->
                        writeBatch.delete(doc.reference)
                    }

                    writeBatch.commit().await()
                    Log.d(TAG, "   ✅ Batch ${batchIndex + 1} deleted")
                }

                Log.d(TAG, "   ✅ All ${existingDocs.size()} old documents deleted")
            } else {
                Log.d(TAG, "   No existing documents to delete")
            }

            // 🔧 OPTIMIZED: Upload in batches for better performance
            Log.d(TAG, "   📤 Uploading ${allApps.size} apps in batches...")

            var successCount = 0
            var failureCount = 0

            // Split apps into batches of 500 (Firestore batch limit)
            val uploadBatches = allApps.chunked(500)

            for ((batchIndex, batchApps) in uploadBatches.withIndex()) {
                try {
                    Log.d(TAG, "   Uploading batch ${batchIndex + 1}/${uploadBatches.size} (${batchApps.size} apps)...")
                    val writeBatch = db.batch()

                    batchApps.forEach { app ->
                        val uploadData = app.toFirestoreMap()
                        val docRef = allAppsRef.document(app.packageName)
                        writeBatch.set(docRef, uploadData)
                    }

                    writeBatch.commit().await()
                    successCount += batchApps.size
                    Log.d(TAG, "   ✅ Batch ${batchIndex + 1} uploaded successfully")

                } catch (e: Exception) {
                    failureCount += batchApps.size
                    Log.e(TAG, "   ❌ Batch ${batchIndex + 1} failed: ${e.message}")
                }
            }

            Log.d(TAG, "   ")
            Log.d(TAG, "   📊 Upload Summary:")
            Log.d(TAG, "      ✅ Success: $successCount")
            Log.d(TAG, "      ❌ Failed: $failureCount")
            Log.d(TAG, "      📈 Success Rate: ${if (allApps.isNotEmpty()) (successCount * 100 / allApps.size) else 0}%")

            val isSuccessful = successCount > 0
            Log.d(TAG, "   Result: ${if (isSuccessful) "✅ SUCCESSFUL" else "❌ FAILED"}")

            return isSuccessful

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ CRITICAL: Error in uploadAllAppsToFirestore")
            Log.e(TAG, "   ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Update the status of the all apps request
     */
    private suspend fun updateAllAppsRequestStatus(
        childUid: String,
        requestId: String,
        status: String,
        errorMessage: String? = null
    ) {
        try {
            Log.d(TAG, "   Updating all apps request status to: $status")

            val db = FirebaseFirestore.getInstance()
            val requestRef = db.collection("users")
                .document(childUid)
                .collection("allAppsRequest")
                .document(requestId)

            val updateData = hashMapOf<String, Any>(
                "status" to status,
                "completedAt" to System.currentTimeMillis()
            )

            if (errorMessage != null) {
                updateData["errorMessage"] = errorMessage
                Log.d(TAG, "   Error Message: $errorMessage")
            }

            requestRef.update(updateData).await()
            Log.d(TAG, "   ✅ Status updated successfully")

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error updating all apps request status: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Collect ALL installed apps (including those with 0 usage) and upload to Firestore
 * Called when parent requests all apps list
 */
