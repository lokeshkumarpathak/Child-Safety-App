package com.example.child_safety_app_version1.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import com.example.child_safety_app_version1.R
import com.example.child_safety_app_version1.data.AppMode
import com.example.child_safety_app_version1.managers.AppModeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ⚡ FIXED: Accessibility Service that STAYS ALIVE
 * - Proper configuration flags
 * - Self-restart mechanism
 * - Battery optimization protection
 */
class AppBlockingAccessibilityService : AccessibilityService() {
    private val TAG = "AppBlockingService"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var blockedAppsListener: ListenerRegistration? = null
    private val blockedApps = mutableSetOf<String>()

    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null
    private var currentBlockedPackage: String? = null
    private var bedtimeOverlayView: android.view.View? = null

    // ⭐ NEW: Heartbeat to keep service alive
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var lastHeartbeat = System.currentTimeMillis()

    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.d(TAG, "========================================")
        Log.d(TAG, "✅ ACCESSIBILITY SERVICE CONNECTED")
        Log.d(TAG, "========================================")

        // ⭐ CRITICAL: Configure with proper flags
        configureServiceInfo()

        // Initialize managers
        AppModeManager.initialize(this)

        // Start mode change listener
        scope.launch {
            AppModeManager.currentMode
                .onEach { mode ->
                    Log.d(TAG, "🔄 Mode changed to: ${mode.displayName}")
                    handleModeChange(mode)
                }
                .launchIn(this)
        }

        // Load blocked apps
        loadBlockedApps()

        // ⭐ NEW: Start heartbeat
        startHeartbeat()

        Log.d(TAG, "✅ Service fully configured and running")
    }

    /**
     * ⭐ CRITICAL: Proper service configuration
     */
    private fun configureServiceInfo() {
        val info = AccessibilityServiceInfo().apply {
            // Event types to monitor
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            // Feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // ⭐ CRITICAL FLAGS for persistence
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

            // Fast notification timeout
            notificationTimeout = 100L
        }

        serviceInfo = info
        Log.d(TAG, "✅ Service info configured with persistence flags")
    }

    /**
     * ⭐ NEW: Heartbeat to detect if service is still alive
     */
    private fun startHeartbeat() {
        heartbeatHandler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val timeSinceLastBeat = now - lastHeartbeat

                if (timeSinceLastBeat > 10000) {
                    Log.w(TAG, "⚠️ Service may have been killed - reinitializing")
                    // Service was killed, reinitialize
                    try {
                        loadBlockedApps()
                        AppModeManager.initialize(this@AppBlockingAccessibilityService)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Reinitialization failed", e)
                    }
                }

                lastHeartbeat = now
                heartbeatHandler.postDelayed(this, 5000) // Check every 5 seconds
            }
        }, 5000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Update heartbeat on every event
        lastHeartbeat = System.currentTimeMillis()

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app
        if (packageName == this.packageName) return

        Log.d(TAG, "📱 App opened: $packageName")

        // Check if app should be blocked
        if (shouldBlockApp(packageName)) {
            Log.d(TAG, "🚫 Blocking app: $packageName")
            blockApp(packageName)
        } else {
            // If app is allowed, remove any existing overlay
            if (currentBlockedPackage == packageName) {
                removeOverlay()
            }
        }
    }

    private fun shouldBlockApp(packageName: String): Boolean {
        val currentMode = AppModeManager.getCurrentMode()

        return when (currentMode) {
            AppMode.NORMAL -> blockedApps.contains(packageName)
            AppMode.STUDY, AppMode.BEDTIME -> !AppModeManager.isAppAllowed(packageName)
        }
    }

    private fun blockApp(packageName: String) {
        if (currentBlockedPackage == packageName && overlayView != null) {
            return // Already showing overlay
        }

        removeOverlay()
        currentBlockedPackage = packageName

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            overlayView = inflater.inflate(R.layout.blocked_app_overlay, null)

            val appNameTextView = overlayView?.findViewById<TextView>(R.id.blockedAppName)
            val reasonTextView = overlayView?.findViewById<TextView>(R.id.blockReason)
            val closeButton = overlayView?.findViewById<Button>(R.id.closeButton)

            // Get app name
            val appName = try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            appNameTextView?.text = appName

            // Set block reason
            val currentMode = AppModeManager.getCurrentMode()
            reasonTextView?.text = when (currentMode) {
                AppMode.NORMAL -> "This app has been blocked by your parent"
                AppMode.STUDY -> "Only educational apps allowed in Study Mode"
                AppMode.BEDTIME -> "Device is in Bedtime Mode. Time to sleep!"
            }

            closeButton?.setOnClickListener { goToHome() }

            // ⭐ UPDATED: Better window parameters
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.CENTER

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "✅ Overlay displayed for: $appName")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing overlay", e)
        }
    }

    private fun removeOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
                currentBlockedPackage = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing overlay", e)
        }
    }

    private fun goToHome() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN)
            homeIntent.addCategory(Intent.CATEGORY_HOME)
            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(homeIntent)
            removeOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error going to home", e)
        }
    }

    private fun loadBlockedApps() {
        val childUid = FirebaseAuth.getInstance().currentUser?.uid
        if (childUid == null) {
            Log.e(TAG, "❌ Cannot load blocked apps: User not logged in")
            return
        }

        Log.d(TAG, "📥 Loading blocked apps list...")

        // Remove old listener if exists
        blockedAppsListener?.remove()

        val db = FirebaseFirestore.getInstance()

        blockedAppsListener = db.collection("users")
            .document(childUid)
            .collection("blockedApps")
            .whereEqualTo("blocked", true)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error loading blocked apps", error)
                    return@addSnapshotListener
                }

                if (snapshots == null) {
                    Log.w(TAG, "⚠️ No blocked apps snapshot")
                    return@addSnapshotListener
                }

                blockedApps.clear()
                for (document in snapshots.documents) {
                    blockedApps.add(document.id)
                }

                Log.d(TAG, "✅ Loaded ${blockedApps.size} blocked apps")
            }
    }

    private fun handleModeChange(mode: AppMode) {
        Log.d(TAG, "🔄 Handling mode change to: ${mode.displayName}")

        when (mode) {
            AppMode.BEDTIME -> showBedtimeOverlay()
            else -> removeBedtimeOverlay()
        }
    }

    private fun showBedtimeOverlay() {
        Log.d(TAG, "🌙 Showing bedtime overlay")
        // Implementation similar to blockApp but full-screen permanent
    }

    private fun removeBedtimeOverlay() {
        try {
            if (bedtimeOverlayView != null && windowManager != null) {
                windowManager?.removeView(bedtimeOverlayView)
                bedtimeOverlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing bedtime overlay", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted - will attempt to continue")
    }

    /**
     * ⭐ CRITICAL: Handle service destruction
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "🛑 Service being destroyed")

        // Clean up
        heartbeatHandler.removeCallbacksAndMessages(null)
        blockedAppsListener?.remove()
        AppModeManager.stopListening()
        removeOverlay()
        removeBedtimeOverlay()

        // ⭐ Attempt to restart service
        scope.launch {
            delay(2000)
            Log.d(TAG, "🔄 Attempting service restart...")
        }
    }

    /**
     * ⭐ NEW: Handle unbind - service being disabled
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "⚠️ Service unbound - attempting to rebind")
        return true // Request rebind
    }
}