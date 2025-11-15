package com.example.child_safety_app_version1.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.child_safety_app_version1.R
import com.example.child_safety_app_version1.data.AppMode
import com.example.child_safety_app_version1.managers.AppModeManager

/**
 * BroadcastReceiver that enforces BEDTIME mode by showing an overlay
 * Listens to SCREEN_ON, SCREEN_OFF, and USER_PRESENT events
 *
 * Integration with Three-Mode System:
 * - NORMAL Mode: No overlay (normal device operation)
 * - STUDY Mode: No overlay (app-level blocking only)
 * - BEDTIME Mode: Shows full-screen overlay blocking access
 */
class ScreenStateReceiver : BroadcastReceiver() {
    private val TAG = "ScreenStateReceiver"

    companion object {
        private var bedtimeOverlay: android.view.View? = null
        private var windowManager: WindowManager? = null
        private var isOverlayShowing = false
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.d(TAG, "========================================")
        Log.d(TAG, "SCREEN STATE CHANGED: $action")
        Log.d(TAG, "Current Mode: ${AppModeManager.getCurrentMode().displayName}")
        Log.d(TAG, "========================================")

        when (action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "📱 Screen turned ON")
                handleScreenOn(context)
            }

            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "📴 Screen turned OFF")
                handleScreenOff(context)
            }

            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "🔓 User unlocked device")
                handleUserPresent(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "🚀 Device booted")
                handleBootCompleted(context)
            }
        }
    }

    /**
     * Handle screen turned on
     * Show overlay only if BEDTIME mode is active
     */
    private fun handleScreenOn(context: Context) {
        val currentMode = AppModeManager.getCurrentMode()
        Log.d(TAG, "   Current mode: ${currentMode.displayName}")

        when (currentMode) {
            AppMode.NORMAL -> {
                Log.d(TAG, "✅ NORMAL Mode - No overlay needed")
            }

            AppMode.STUDY -> {
                Log.d(TAG, "✅ STUDY Mode - App-level blocking active, no overlay")
            }

            AppMode.BEDTIME -> {
                Log.d(TAG, "🌙 BEDTIME MODE ACTIVE - Showing bedtime overlay")
                showBedtimeOverlay(context)
            }
        }
    }

    /**
     * Handle screen turned off
     * Remove overlay to save resources
     */
    private fun handleScreenOff(context: Context) {
        Log.d(TAG, "   Removing overlay to save resources")
        removeBedtimeOverlay(context)
    }

    /**
     * Handle user unlocked device (after PIN/Pattern/Fingerprint)
     * Re-show overlay if BEDTIME mode active
     */
    private fun handleUserPresent(context: Context) {
        val currentMode = AppModeManager.getCurrentMode()

        when (currentMode) {
            AppMode.NORMAL, AppMode.STUDY -> {
                Log.d(TAG, "✅ Mode allows normal operation")
            }

            AppMode.BEDTIME -> {
                Log.d(TAG, "🌙 User unlocked but BEDTIME active - Re-showing overlay")
                showBedtimeOverlay(context)
            }
        }
    }

    /**
     * Handle device boot completed
     * Initialize AppModeManager and show overlay if needed
     */
    private fun handleBootCompleted(context: Context) {
        Log.d(TAG, "   Initializing AppModeManager...")
        AppModeManager.initialize(context)

        // Small delay to ensure initialization complete
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "   Interrupted during initialization delay", e)
        }

        val currentMode = AppModeManager.getCurrentMode()
        Log.d(TAG, "   Mode after boot: ${currentMode.displayName}")

        if (currentMode == AppMode.BEDTIME) {
            Log.d(TAG, "🌙 BEDTIME MODE active after boot - Showing overlay")
            showBedtimeOverlay(context)
        }
    }

    /**
     * Show full-screen bedtime overlay
     * Blocks access to all apps except emergency dialer
     */
    private fun showBedtimeOverlay(context: Context) {
        if (isOverlayShowing && bedtimeOverlay != null) {
            Log.d(TAG, "   ⚠️ Overlay already showing - skipping")
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(context)
            bedtimeOverlay = inflater.inflate(R.layout.bedtime_overlay, null)

            // Setup overlay content
            val titleTextView = bedtimeOverlay?.findViewById<TextView>(R.id.bedtimeTitle)
            val messageTextView = bedtimeOverlay?.findViewById<TextView>(R.id.bedtimeMessage)
            val timeTextView = bedtimeOverlay?.findViewById<TextView>(R.id.bedtimeCurrentTime)
            val emergencyButton = bedtimeOverlay?.findViewById<Button>(R.id.emergencyCallButton)

            titleTextView?.text = "🌙 Bedtime Mode Active"
            messageTextView?.text = "Your device is in bedtime mode.\nTime to rest and recharge!\n\nOnly emergency calls allowed."

            // Show current time
            val currentTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                .format(java.util.Date())
            timeTextView?.text = "Current time: $currentTime"

            // Emergency call button - opens phone dialer
            emergencyButton?.setOnClickListener {
                Log.d(TAG, "📞 Emergency button clicked")
                openEmergencyDialer(context)
            }

            // Overlay window parameters - Full screen, blocks everything
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.CENTER

            // Add overlay to window
            windowManager?.addView(bedtimeOverlay, params)
            isOverlayShowing = true

            Log.d(TAG, "✅ Bedtime overlay displayed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing bedtime overlay: ${e.message}", e)
            isOverlayShowing = false
        }
    }

    /**
     * Remove bedtime overlay
     */
    private fun removeBedtimeOverlay(context: Context) {
        try {
            if (bedtimeOverlay != null && windowManager != null && isOverlayShowing) {
                windowManager?.removeView(bedtimeOverlay)
                bedtimeOverlay = null
                isOverlayShowing = false
                Log.d(TAG, "✅ Bedtime overlay removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing bedtime overlay: ${e.message}", e)
            isOverlayShowing = false
        }
    }

    /**
     * Open emergency dialer for bedtime mode
     * Allows child to make emergency calls
     */
    private fun openEmergencyDialer(context: Context) {
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL)
            dialIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(dialIntent)
            Log.d(TAG, "✅ Emergency dialer opened")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening emergency dialer: ${e.message}", e)
        }
    }
}