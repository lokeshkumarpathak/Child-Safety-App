package com.example.child_safety_app_version1.services

import android.app.ActivityManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ComprehensiveAppBlockingService : Service() {

    private val TAG = "AppBlockingService"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)

    private var blockedAppsListener: ListenerRegistration? = null
    private var blockedApps = setOf<String>()
    private var monitoringRunnable: Runnable? = null
    private val CHECK_INTERVAL_MS = 1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 Service started")
        try {
            startForeground(1, createNotification())
            listenToBlockedApps()
            startMonitoring()
        } catch (e: Exception) {
            Log.e(TAG, "❌ FATAL ERROR in onStartCommand: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun listenToBlockedApps() {
        Log.d(TAG, "🎧 Listening to blocked apps")
        try {
            val childUid = auth.currentUser?.uid
            if (childUid == null) {
                Log.e(TAG, "❌ No user authenticated - blocking service cannot start")
                stopSelf() // Stop the service if no user
                return
            }

            blockedAppsListener = db.collection("users")
                .document(childUid)
                .collection("blockedApps")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Firestore error: ${error.message}")
                        return@addSnapshotListener
                    }

                    blockedApps = snapshot?.documents
                        ?.filter { it.getBoolean("blocked") == true }
                        ?.mapNotNull { it.getString("packageName") }
                        ?.toSet() ?: emptySet()

                    Log.d(TAG, "📋 Updated: ${blockedApps.size} apps blocked")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in listenToBlockedApps: ${e.message}")
            stopSelf()
        }
    }

    private fun startMonitoring() {
        Log.d(TAG, "▶️ Starting monitoring")
        monitoringRunnable = object : Runnable {
            override fun run() {
                monitorProcesses()
                monitorActiveWindow()
                monitorRecentApps()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.post(monitoringRunnable!!)
    }

    private fun monitorProcesses() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningApps = activityManager.runningAppProcesses ?: return

            for (appProcess in runningApps) {
                // pkgList contains the package names for this process
                for (pkgName in appProcess.pkgList) {
                    if (pkgName in blockedApps) {
                        Log.w(TAG, "🚫 Process detected: $pkgName")
                        forceStopApp(pkgName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process monitor error: ${e.message}")
        }
    }

    private fun monitorActiveWindow() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 500

            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                startTime,
                endTime
            )

            val currentApp = stats
                .filter { it.totalTimeInForeground > 0 }
                .maxByOrNull { it.lastTimeUsed }
                ?.packageName

            if (currentApp != null && currentApp in blockedApps) {
                Log.w(TAG, "🚫 Active app blocked: $currentApp")
                forceStopApp(currentApp)
                bringLauncherToFront()
                logAttempt(currentApp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Window monitor error: ${e.message}")
        }
    }

    private fun monitorRecentApps() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val recentTasks = activityManager.getRecentTasks(10, ActivityManager.RECENT_WITH_EXCLUDED)

            for (task in recentTasks) {
                val pkgName = task.baseIntent.component?.packageName
                if (pkgName != null && pkgName in blockedApps) {
                    Log.w(TAG, "🚫 Recent app blocked: $pkgName")
                    forceStopApp(pkgName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recent apps error: ${e.message}")
        }
    }

    private fun forceStopApp(packageName: String) {
        try {
            Log.d(TAG, "💥 Stopping: $packageName")
            Runtime.getRuntime().exec("am force-stop $packageName").waitFor()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
    }

    private fun bringLauncherToFront() {
        try {
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
        } catch (e: Exception) {
            Log.e(TAG, "Launcher error: ${e.message}")
        }
    }

    private fun logAttempt(packageName: String) {
        scope.launch {
            try {
                val childUid = auth.currentUser?.uid ?: return@launch
                val data = hashMapOf(
                    "packageName" to packageName,
                    "timestamp" to System.currentTimeMillis(),
                    "attemptType" to "blocked_app_attempt"
                )
                db.collection("users")
                    .document(childUid)
                    .collection("blockedAppAttempts")
                    .document("${packageName}_${System.currentTimeMillis()}")
                    .set(data)
            } catch (e: Exception) {
                Log.e(TAG, "Log error: ${e.message}")
            }
        }
    }

    private fun createNotification() = android.app.Notification.Builder(this, "app_blocking")
        .setContentTitle("🛡️ App Protection Active")
        .setContentText("${blockedApps.size} apps blocked")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(android.app.Notification.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Service destroyed")
        blockedAppsListener?.remove()
        monitoringRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}