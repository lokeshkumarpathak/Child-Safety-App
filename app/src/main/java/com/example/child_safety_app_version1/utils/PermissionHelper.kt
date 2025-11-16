package com.example.child_safety_app_version1.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    private const val TAG = "PermissionHelper"

    // Permission request codes
    const val CAMERA_PERMISSION_REQUEST_CODE = 101
    const val MICROPHONE_PERMISSION_REQUEST_CODE = 102
    const val MEDIA_PERMISSIONS_REQUEST_CODE = 103
    const val LOCATION_PERMISSIONS_REQUEST_CODE = 104
    const val SMS_PERMISSIONS_REQUEST_CODE = 105
    const val ALL_RUNTIME_PERMISSIONS_REQUEST_CODE = 106

    /**
     * Check if usage stats permission is granted
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return UsageStatsHelper.hasUsageStatsPermission(context)
    }

    /**
     * Request usage stats permission - opens Settings
     */
    fun requestUsageStatsPermission(context: Context) {
        try {
            Log.d(TAG, "Requesting usage stats permission")
            Toast.makeText(
                context,
                "Please enable 'Permit usage access' for this app",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening usage stats settings", e)
            Toast.makeText(
                context,
                "Unable to open settings. Please enable usage access manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Check if accessibility service is enabled
     */
    fun hasAccessibilityPermission(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )

            if (accessibilityEnabled == 0) {
                return false
            }

            val service = "${context.packageName}/com.example.child_safety_app_version1.services.AppBlockingAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            enabledServices?.contains(service) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility permission", e)
            false
        }
    }

    /**
     * Request accessibility permission - opens Settings
     */
    fun requestAccessibilityPermission(context: Context) {
        try {
            Log.d(TAG, "Requesting accessibility permission")
            Toast.makeText(
                context,
                "Please enable 'Accessibility Service' for app monitoring",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings", e)
            Toast.makeText(
                context,
                "Unable to open settings. Please enable accessibility manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Check if overlay permission is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Not required for older versions
        }
    }

    /**
     * Request overlay permission - opens Settings
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Log.d(TAG, "Requesting overlay permission")
                Toast.makeText(
                    context,
                    "Please allow 'Display over other apps' permission",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening overlay settings", e)
                Toast.makeText(
                    context,
                    "Unable to open settings. Please enable overlay permission manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CAMERA & MICROPHONE PERMISSIONS (Runtime Permissions)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if Camera permission is granted
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if Microphone (RECORD_AUDIO) permission is granted
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if Audio Settings (MODIFY_AUDIO_SETTINGS) permission is granted
     */
    fun hasAudioSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all media permissions are granted
     */
    fun hasAllMediaPermissions(context: Context): Boolean {
        return hasCameraPermission(context) &&
                hasMicrophonePermission(context) &&
                hasAudioSettingsPermission(context)
    }

    /**
     * ✅ REQUEST camera permission at runtime (from Activity)
     */
    fun requestCameraPermission(activity: Activity) {
        Log.d(TAG, "Requesting camera permission (runtime)")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * ✅ REQUEST microphone permission at runtime (from Activity)
     */
    fun requestMicrophonePermission(activity: Activity) {
        Log.d(TAG, "Requesting microphone permission (runtime)")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MICROPHONE_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * ✅ REQUEST both camera and microphone permissions at once
     */
    fun requestMediaPermissions(activity: Activity) {
        Log.d(TAG, "Requesting camera and microphone permissions (runtime)")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
            ),
            MEDIA_PERMISSIONS_REQUEST_CODE
        )
    }

    /**
     * Open app settings to manually grant permissions (works from Context)
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app settings", e)
        }
    }

    /**
     * Open app settings for media permissions (Context version for Composables)
     */
    fun openMediaPermissionsSettings(context: Context) {
        try {
            Log.d(TAG, "Opening app settings for media permissions")
            Toast.makeText(
                context,
                "Please enable Camera and Microphone permissions",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening media settings", e)
            Toast.makeText(
                context,
                "Unable to open settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Check if camera hardware is available and not in use
     */
    fun isCameraAvailable(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

            if (cameraManager == null) {
                Log.e(TAG, "❌ CameraManager not available")
                return false
            }

            val cameraIdList = cameraManager.cameraIdList

            if (cameraIdList.isEmpty()) {
                Log.e(TAG, "❌ No cameras found on device")
                return false
            }

            Log.d(TAG, "✅ Camera available (${cameraIdList.size} camera(s) found)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking camera availability", e)
            false
        }
    }

    /**
     * Comprehensive check before attempting media capture
     */
    fun canCaptureMedia(context: Context): Boolean {
        val hasCam = hasCameraPermission(context)
        val hasMic = hasMicrophonePermission(context)
        val camAvailable = isCameraAvailable(context)

        Log.d(TAG, "Media capture check:")
        Log.d(TAG, "  Camera permission: $hasCam")
        Log.d(TAG, "  Microphone permission: $hasMic")
        Log.d(TAG, "  Camera available: $camAvailable")

        return hasCam && hasMic && camAvailable
    }

    /**
     * Get list of missing media permissions
     */
    fun getMissingMediaPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()

        if (!hasCameraPermission(context)) {
            missing.add("Camera")
        }

        if (!hasMicrophonePermission(context)) {
            missing.add("Microphone")
        }

        if (!hasAudioSettingsPermission(context)) {
            missing.add("Audio Settings")
        }

        return missing
    }

    // ═══════════════════════════════════════════════════════════════
    // LOCATION PERMISSIONS
    // ═══════════════════════════════════════════════════════════════

    fun hasLocationPermissions(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation && coarseLocation
    }

    fun requestLocationPermissions(activity: Activity) {
        Log.d(TAG, "Requesting location permissions")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSIONS_REQUEST_CODE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // SMS PERMISSIONS
    // ═══════════════════════════════════════════════════════════════

    fun hasSmsPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestSmsPermissions(activity: Activity) {
        Log.d(TAG, "Requesting SMS permissions")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.SEND_SMS),
            SMS_PERMISSIONS_REQUEST_CODE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ALL RUNTIME PERMISSIONS AT ONCE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get all runtime permissions needed for the app
     */
    fun getAllRequiredRuntimePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE
            )
        }
    }

    /**
     * Check if all runtime permissions are granted
     */
    fun hasAllRuntimePermissions(context: Context): Boolean {
        return getAllRequiredRuntimePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request all runtime permissions at once
     */
    fun requestAllRuntimePermissions(activity: Activity) {
        val missing = getAllRequiredRuntimePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "✅ All runtime permissions already granted")
            return
        }

        Log.d(TAG, "Requesting ${missing.size} runtime permissions")
        ActivityCompat.requestPermissions(
            activity,
            missing.toTypedArray(),
            ALL_RUNTIME_PERMISSIONS_REQUEST_CODE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPREHENSIVE PERMISSION STATUS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if all required permissions are granted (runtime + special)
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        val hasRuntime = hasAllRuntimePermissions(context)
        val hasUsageStats = hasUsageStatsPermission(context)
        val hasAccessibility = hasAccessibilityPermission(context)
        val hasOverlay = hasOverlayPermission(context)

        Log.d(TAG, "Complete permission status:")
        Log.d(TAG, "  Runtime permissions: $hasRuntime")
        Log.d(TAG, "  Usage Stats: $hasUsageStats")
        Log.d(TAG, "  Accessibility: $hasAccessibility")
        Log.d(TAG, "  Overlay: $hasOverlay")

        return hasRuntime && hasUsageStats && hasAccessibility && hasOverlay
    }

    /**
     * Request battery optimization exemption
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)

                Toast.makeText(
                    context,
                    "Please disable battery optimization for continuous monitoring",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting battery optimization exemption", e)
            }
        }
    }

    /**
     * Get list of missing permissions with descriptions
     */
    fun getMissingPermissions(context: Context): List<Pair<String, String>> {
        val missing = mutableListOf<Pair<String, String>>()

        // Special permissions
        if (!hasUsageStatsPermission(context)) {
            missing.add("Usage Access" to "Required to monitor app usage time")
        }

        if (!hasAccessibilityPermission(context)) {
            missing.add("Accessibility Service" to "Required to enforce app blocking")
        }

        if (!hasOverlayPermission(context)) {
            missing.add("Display Over Apps" to "Required to show blocking screens")
        }

        // Runtime permissions
        if (!hasCameraPermission(context)) {
            missing.add("Camera" to "Required to capture evidence when leaving safe zone")
        }

        if (!hasMicrophonePermission(context)) {
            missing.add("Microphone" to "Required to capture audio evidence when leaving safe zone")
        }

        if (!hasLocationPermissions(context)) {
            missing.add("Location" to "Required to track child's location")
        }

        if (!hasSmsPermissions(context)) {
            missing.add("SMS" to "Required to send emergency alerts")
        }

        return missing
    }

    /**
     * Get complete status of all permissions
     */
    fun getCompletePermissionStatus(context: Context): PermissionStatus {
        return PermissionStatus(
            usageStats = hasUsageStatsPermission(context),
            accessibility = hasAccessibilityPermission(context),
            overlay = hasOverlayPermission(context),
            camera = hasCameraPermission(context),
            microphone = hasMicrophonePermission(context),
            audioSettings = hasAudioSettingsPermission(context),
            location = hasLocationPermissions(context),
            sms = hasSmsPermissions(context)
        )
    }

    /**
     * Log permission status for debugging
     */
    fun logPermissionStatus(context: Context) {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "PERMISSION STATUS")
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "Runtime Permissions:")
        Log.d(TAG, "  Camera: ${if (hasCameraPermission(context)) "✅" else "❌"}")
        Log.d(TAG, "  Microphone: ${if (hasMicrophonePermission(context)) "✅" else "❌"}")
        Log.d(TAG, "  Audio Settings: ${if (hasAudioSettingsPermission(context)) "✅" else "❌"}")
        Log.d(TAG, "  Location: ${if (hasLocationPermissions(context)) "✅" else "❌"}")
        Log.d(TAG, "  SMS: ${if (hasSmsPermissions(context)) "✅" else "❌"}")
        Log.d(TAG, "Special Permissions:")
        Log.d(TAG, "  Usage Stats: ${if (hasUsageStatsPermission(context)) "✅" else "❌"}")
        Log.d(TAG, "  Accessibility: ${if (hasAccessibilityPermission(context)) "✅" else "❌"}")
        Log.d(TAG, "  Overlay: ${if (hasOverlayPermission(context)) "✅" else "❌"}")
        Log.d(TAG, "Hardware:")
        Log.d(TAG, "  Camera available: ${if (isCameraAvailable(context)) "✅" else "❌"}")
        Log.d(TAG, "════════════════════════════════════════")
    }
}

/**
 * Data class to hold complete permission status
 */
data class PermissionStatus(
    val usageStats: Boolean = false,
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val audioSettings: Boolean = false,
    val location: Boolean = false,
    val sms: Boolean = false
) {
    val allGranted: Boolean
        get() = usageStats && accessibility && overlay && camera &&
                microphone && audioSettings && location && sms

    val allMediaGranted: Boolean
        get() = camera && microphone && audioSettings

    val allRuntimeGranted: Boolean
        get() = camera && microphone && audioSettings && location && sms

    val allSpecialPermissionsGranted: Boolean
        get() = usageStats && accessibility && overlay

    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!usageStats) missing.add("Usage Stats")
        if (!accessibility) missing.add("Accessibility")
        if (!overlay) missing.add("Overlay")
        if (!camera) missing.add("Camera")
        if (!microphone) missing.add("Microphone")
        if (!audioSettings) missing.add("Audio Settings")
        if (!location) missing.add("Location")
        if (!sms) missing.add("SMS")
        return missing
    }

    fun getMissingCount(): Int = getMissingPermissions().size
}