package com.example.child_safety_app_version1.managers

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.data.AppMode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager for handling app blocking based on current mode
 * Listens to Firestore for mode changes and blocked/allowed app lists
 */
object AppModeManager {
    private const val TAG = "AppModeManager"

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Current mode
    private val _currentMode = MutableStateFlow(AppMode.NORMAL)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    // Blocked apps (for Normal Mode)
    private val blockedApps = mutableSetOf<String>()

    // Allowed apps (for Study Mode)
    private val studyAllowedApps = mutableSetOf<String>()

    // Allowed apps (for Bedtime Mode - always minimal)
    private val bedtimeAllowedApps = setOf(
        "com.android.phone",
        "com.android.dialer",
        "com.android.contacts",
        "com.example.child_safety_app_version1"
    )

    // System apps that are always allowed
    private val systemAllowedApps = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.example.child_safety_app_version1" // Safety app always allowed
    )

    private var modeListener: ListenerRegistration? = null
    private var blockedAppsListener: ListenerRegistration? = null
    private var studyModeListener: ListenerRegistration? = null

    private var isInitialized = false

    /**
     * Initialize the manager and start listening to Firestore
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "INITIALIZING APP MODE MANAGER")
        Log.d(TAG, "========================================")

        val childUid = auth.currentUser?.uid
        if (childUid == null) {
            Log.e(TAG, "❌ Cannot initialize: User not logged in")
            return
        }

        // Listen to current mode
        listenToCurrentMode(childUid)

        // Listen to blocked apps (Normal Mode)
        listenToBlockedApps(childUid)

        // Listen to Study Mode allowed apps
        listenToStudyModeApps(childUid)

        isInitialized = true
        Log.d(TAG, "✅ Manager initialized")
    }

    /**
     * Listen to current mode changes
     */
    private fun listenToCurrentMode(childUid: String) {
        Log.d(TAG, "📡 Listening to current mode...")

        modeListener = db.collection("users")
            .document(childUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error listening to mode", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val modeString = snapshot.getString("currentMode") ?: "NORMAL"
                    val mode = AppMode.fromString(modeString)

                    Log.d(TAG, "🔄 Mode changed: ${mode.displayName}")
                    _currentMode.value = mode
                } else {
                    Log.w(TAG, "⚠️ No mode document found, defaulting to NORMAL")
                    _currentMode.value = AppMode.NORMAL
                }
            }
    }

    /**
     * Listen to blocked apps list (Normal Mode)
     */
    private fun listenToBlockedApps(childUid: String) {
        Log.d(TAG, "📡 Listening to blocked apps...")

        blockedAppsListener = db.collection("users")
            .document(childUid)
            .collection("modes")
            .document("normal")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error listening to blocked apps", error)
                    return@addSnapshotListener
                }

                blockedApps.clear()

                if (snapshot != null && snapshot.exists()) {
                    val apps = snapshot.get("blockedApps") as? List<String>
                    if (apps != null) {
                        blockedApps.addAll(apps)
                        Log.d(TAG, "✅ Updated blocked apps: ${blockedApps.size} apps")
                    }
                }
            }
    }

    /**
     * Listen to Study Mode allowed apps
     */
    private fun listenToStudyModeApps(childUid: String) {
        Log.d(TAG, "📡 Listening to Study Mode apps...")

        studyModeListener = db.collection("users")
            .document(childUid)
            .collection("modes")
            .document("study")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error listening to Study Mode apps", error)
                    return@addSnapshotListener
                }

                studyAllowedApps.clear()

                if (snapshot != null && snapshot.exists()) {
                    val apps = snapshot.get("allowedApps") as? List<String>
                    if (apps != null) {
                        studyAllowedApps.addAll(apps)
                        Log.d(TAG, "✅ Updated Study Mode apps: ${studyAllowedApps.size} apps")
                    }
                }
            }
    }

    /**
     * Check if an app is allowed based on current mode
     */
    fun isAppAllowed(packageName: String): Boolean {
        // System apps are always allowed
        if (systemAllowedApps.contains(packageName)) {
            return true
        }

        return when (_currentMode.value) {
            AppMode.NORMAL -> {
                // In Normal Mode: Allow all except blocked apps
                !blockedApps.contains(packageName)
            }

            AppMode.STUDY -> {
                // In Study Mode: Only allow whitelisted + system apps
                studyAllowedApps.contains(packageName) || systemAllowedApps.contains(packageName)
            }

            AppMode.BEDTIME -> {
                // In Bedtime Mode: Only allow minimal essential apps
                bedtimeAllowedApps.contains(packageName) || systemAllowedApps.contains(packageName)
            }
        }
    }

    /**
     * Get current mode
     */
    fun getCurrentMode(): AppMode {
        return _currentMode.value
    }

    /**
     * Stop listening to Firestore
     */
    fun stopListening() {
        Log.d(TAG, "🛑 Stopping listeners")
        modeListener?.remove()
        blockedAppsListener?.remove()
        studyModeListener?.remove()
        isInitialized = false
    }

    /**
     * Get debug info
     */
    fun getDebugInfo(): String {
        return """
            Current Mode: ${_currentMode.value.displayName}
            Blocked Apps (Normal): ${blockedApps.size}
            Allowed Apps (Study): ${studyAllowedApps.size}
            Allowed Apps (Bedtime): ${bedtimeAllowedApps.size}
        """.trimIndent()
    }
}