package com.example.child_safety_app_version1.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

object PermissionHelper {
    private const val TAG = "PermissionHelper"

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

    /**
     * Check if all required permissions are granted
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        val hasUsageStats = hasUsageStatsPermission(context)
        val hasAccessibility = hasAccessibilityPermission(context)
        val hasOverlay = hasOverlayPermission(context)

        Log.d(TAG, "Permission status:")
        Log.d(TAG, "  Usage Stats: $hasUsageStats")
        Log.d(TAG, "  Accessibility: $hasAccessibility")
        Log.d(TAG, "  Overlay: $hasOverlay")

        return hasUsageStats && hasAccessibility && hasOverlay
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

        if (!hasUsageStatsPermission(context)) {
            missing.add("Usage Access" to "Required to monitor app usage time")
        }

        if (!hasAccessibilityPermission(context)) {
            missing.add("Accessibility Service" to "Required to enforce app blocking")
        }

        if (!hasOverlayPermission(context)) {
            missing.add("Display Over Apps" to "Required to show blocking screens")
        }

        return missing
    }
}