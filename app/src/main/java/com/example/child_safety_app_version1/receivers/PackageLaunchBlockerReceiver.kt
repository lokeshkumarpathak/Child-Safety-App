package com.example.child_safety_app_version1.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Broadcast Receiver to intercept app launch attempts
 * Blocks launch of restricted apps
 */
class PackageLaunchBlockerReceiver : BroadcastReceiver() {
    private val TAG = "PackageLaunchBlocker"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private var blockedAppsCache = setOf<String>()
        private var lastCacheUpdate = 0L
        private const val CACHE_DURATION_MS = 30000L
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        val packageName = intent.data?.schemeSpecificPart

        Log.d(TAG, "📢 Broadcast received: $action | Package: $packageName")

        if (packageName != null && isAppBlocked(packageName)) {
            Log.w(TAG, "🚫 BLOCKED APP LAUNCH ATTEMPT: $packageName")

            abortBroadcast()
            logAttempt(context, packageName)

            try {
                Runtime.getRuntime().exec("am force-stop $packageName").waitFor()
                Log.d(TAG, "✅ Force stopped: $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Error force stopping: ${e.message}")
            }
        }
    }

    /**
     * Check if app is blocked (with caching)
     */
    private fun isAppBlocked(packageName: String): Boolean {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastCacheUpdate < CACHE_DURATION_MS) {
            return packageName in blockedAppsCache
        }

        updateBlockedAppsCache()
        return packageName in blockedAppsCache
    }

    /**
     * Update blocked apps cache from Firestore
     */
    private fun updateBlockedAppsCache() {
        scope.launch {
            try {
                val childUid = auth.currentUser?.uid ?: return@launch

                val snapshot = db.collection("users")
                    .document(childUid)
                    .collection("blockedApps")
                    .get()
                    .await()

                blockedAppsCache = snapshot.documents
                    .filter { it.getBoolean("blocked") == true }
                    .mapNotNull { it.getString("packageName") }
                    .toSet()

                lastCacheUpdate = System.currentTimeMillis()
                Log.d(TAG, "✅ Cache updated: ${blockedAppsCache.size} apps blocked")

            } catch (e: Exception) {
                Log.e(TAG, "Error updating cache: ${e.message}")
            }
        }
    }

    /**
     * Log blocked app launch attempt
     */
    private fun logAttempt(context: Context, packageName: String) {
        scope.launch {
            try {
                val childUid = auth.currentUser?.uid ?: return@launch

                val attemptData = hashMapOf(
                    "packageName" to packageName,
                    "timestamp" to System.currentTimeMillis(),
                    "attemptType" to "broadcast_launch_attempt",
                    "deviceTime" to java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())
                )

                db.collection("users")
                    .document(childUid)
                    .collection("blockedAppAttempts")
                    .document("${packageName}_${System.currentTimeMillis()}")
                    .set(attemptData)

                Log.d(TAG, "📝 Logged attempt for: $packageName")

            } catch (e: Exception) {
                Log.e(TAG, "Error logging attempt: ${e.message}")
            }
        }
    }
}