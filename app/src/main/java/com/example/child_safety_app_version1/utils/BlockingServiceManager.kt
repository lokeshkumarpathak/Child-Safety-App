package com.example.child_safety_app_version1.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.child_safety_app_version1.services.ComprehensiveAppBlockingService

/**
 * Manager to ensure blocking service is always running
 * Call this periodically or when user logs in
 */
object BlockingServiceManager {
    private const val TAG = "BlockingServiceManager"

    /**
     * Start blocking service and ensure it keeps running
     */
    fun startBlockingService(context: Context) {
        Log.d(TAG, "🚀 Starting blocking service...")
        try {
            val serviceIntent = Intent(context, ComprehensiveAppBlockingService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "✅ Started as foreground service")
            } else {
                context.startService(serviceIntent)
                Log.d(TAG, "✅ Started as regular service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting service: ${e.message}", e)
        }
    }

    /**
     * Check if blocking service is running
     */
    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = manager.getRunningServices(Int.MAX_VALUE)

        val isRunning = runningServices.any {
            it.service.className == ComprehensiveAppBlockingService::class.java.name
        }

        Log.d(TAG, "Service running: $isRunning")
        return isRunning
    }

    /**
     * Stop blocking service (for testing only)
     */
    fun stopBlockingService(context: Context) {
        Log.d(TAG, "🛑 Stopping blocking service...")
        val intent = Intent(context, ComprehensiveAppBlockingService::class.java)
        context.stopService(intent)
    }

    /**
     * Restart blocking service
     */
    fun restartBlockingService(context: Context) {
        Log.d(TAG, "🔄 Restarting blocking service...")
        stopBlockingService(context)
        Thread.sleep(500)
        startBlockingService(context)
    }

    /**
     * Get diagnostics info
     */
    fun getDiagnostics(context: Context): String {
        return """
            ═══════════════════════════════════════
            📊 BLOCKING SERVICE DIAGNOSTICS
            ═══════════════════════════════════════
            Service Running: ${isServiceRunning(context)}
            Foreground Service Available: ${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O}
            Android Version: ${Build.VERSION.SDK_INT}
            ═══════════════════════════════════════
        """.trimIndent()
    }
}