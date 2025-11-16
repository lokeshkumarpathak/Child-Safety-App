package com.example.child_safety_app_version1.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Helper class to ensure app survives in background on all Android manufacturers
 */
object BackgroundSurvivalHelper {
    private const val TAG = "BackgroundSurvival"

    /**
     * Data class for all required exemptions
     */
    data class BackgroundStatus(
        val batteryOptimizationDisabled: Boolean,
        val autoStartEnabled: Boolean?, // Null if can't detect
        val backgroundRestrictionDisabled: Boolean
    ) {
        val allRequirementsMet: Boolean
            get() = batteryOptimizationDisabled && backgroundRestrictionDisabled
    }

    /**
     * Check all background survival requirements
     */
    fun checkBackgroundStatus(context: Context): BackgroundStatus {
        val batteryOk = isBatteryOptimizationDisabled(context)
        val backgroundOk = !hasBackgroundRestrictions(context)

        Log.d(TAG, "Background Status Check:")
        Log.d(TAG, "  Battery Optimization Disabled: $batteryOk")
        Log.d(TAG, "  Background Restrictions Disabled: $backgroundOk")
        Log.d(TAG, "  All Requirements Met: ${batteryOk && backgroundOk}")

        return BackgroundStatus(
            batteryOptimizationDisabled = batteryOk,
            autoStartEnabled = null, // Can't reliably detect
            backgroundRestrictionDisabled = backgroundOk
        )
    }

    /**
     * Check if battery optimization is disabled
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // Not needed on older Android
        }
    }

    /**
     * Check if app has background restrictions
     */
    /**
     * Check if app has background restrictions
     */
    private fun hasBackgroundRestrictions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
            activityManager.isBackgroundRestricted
        } else {
            false
        }
    }

    /**
     * Get manufacturer name
     */
    fun getManufacturer(): String {
        return Build.MANUFACTURER.uppercase()
    }

    /**
     * Check if device is from a manufacturer known for aggressive battery optimization
     */
    fun isAggressiveManufacturer(): Boolean {
        val aggressive = listOf(
            "XIAOMI", "REDMI", "POCO",
            "OPPO", "REALME", "ONEPLUS",
            "VIVO", "IQOO",
            "HUAWEI", "HONOR",
            "SAMSUNG",
            "ASUS"
        )
        return aggressive.any { getManufacturer().contains(it) }
    }

    /**
     * Open battery optimization settings
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            // Fallback to battery settings page
            openBatterySettings(context)
        }
    }

    /**
     * Open general battery settings
     */
    private fun openBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery settings", e)
        }
    }

    /**
     * Open manufacturer-specific settings (best effort)
     */
    fun openManufacturerSettings(context: Context) {
        val manufacturer = getManufacturer()

        try {
            when {
                manufacturer.contains("XIAOMI") ||
                        manufacturer.contains("REDMI") ||
                        manufacturer.contains("POCO") -> {
                    // Try MIUI autostart
                    openMIUIAutoStart(context)
                }

                manufacturer.contains("HUAWEI") ||
                        manufacturer.contains("HONOR") -> {
                    // Try Huawei protected apps
                    openHuaweiProtectedApps(context)
                }

                manufacturer.contains("OPPO") ||
                        manufacturer.contains("REALME") -> {
                    // Try ColorOS settings
                    openOppoSettings(context)
                }

                manufacturer.contains("VIVO") -> {
                    // Try Vivo background settings
                    openVivoSettings(context)
                }

                manufacturer.contains("SAMSUNG") -> {
                    // Try Samsung settings
                    openSamsungSettings(context)
                }

                manufacturer.contains("ONEPLUS") -> {
                    // Try OnePlus settings
                    openOnePlusSettings(context)
                }

                else -> {
                    // Fallback to app info page
                    openAppInfoSettings(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open manufacturer settings", e)
            // Final fallback
            openAppInfoSettings(context)
        }
    }

    /**
     * MIUI (Xiaomi) - AutoStart permission
     */
    private fun openMIUIAutoStart(context: Context) {
        val intents = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager))
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "MIUI intent failed: ${e.message}")
            }
        }

        openAppInfoSettings(context)
    }

    /**
     * Huawei - Protected Apps
     */
    private fun openHuaweiProtectedApps(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Huawei intent failed: ${e.message}")
            }
        }

        openAppInfoSettings(context)
    }

    /**
     * Oppo/Realme - ColorOS settings
     */
    private fun openOppoSettings(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Oppo intent failed: ${e.message}")
            }
        }

        openAppInfoSettings(context)
    }

    /**
     * Vivo - Background settings
     */
    private fun openVivoSettings(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Vivo intent failed: ${e.message}")
            }
        }

        openAppInfoSettings(context)
    }

    /**
     * Samsung - Battery optimization
     */
    private fun openSamsungSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.d(TAG, "Samsung intent failed: ${e.message}")
            openAppInfoSettings(context)
        }
    }

    /**
     * OnePlus - Battery optimization
     */
    private fun openOnePlusSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.d(TAG, "OnePlus intent failed: ${e.message}")
            openAppInfoSettings(context)
        }
    }

    /**
     * Fallback - Open app info settings
     */
    private fun openAppInfoSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app info settings", e)
        }
    }

    /**
     * Get user-friendly instructions for current manufacturer
     */
    fun getManufacturerInstructions(): List<String> {
        return when {
            getManufacturer().contains("XIAOMI") ||
                    getManufacturer().contains("REDMI") ||
                    getManufacturer().contains("POCO") -> listOf(
                "1. Enable 'Autostart' permission",
                "2. Disable 'Battery saver' for this app",
                "3. Set 'Battery optimization' to 'No restrictions'"
            )

            getManufacturer().contains("HUAWEI") ||
                    getManufacturer().contains("HONOR") -> listOf(
                "1. Enable in 'Protected apps'",
                "2. Allow 'Launch' permissions",
                "3. Disable 'Battery optimization'"
            )

            getManufacturer().contains("OPPO") ||
                    getManufacturer().contains("REALME") -> listOf(
                "1. Enable 'Startup Manager'",
                "2. Disable 'Battery optimization'",
                "3. Allow background running"
            )

            getManufacturer().contains("VIVO") ||
                    getManufacturer().contains("IQOO") -> listOf(
                "1. Enable 'High background power consumption'",
                "2. Allow 'Auto-start'",
                "3. Disable 'Battery optimization'"
            )

            getManufacturer().contains("SAMSUNG") -> listOf(
                "1. Disable 'Battery optimization'",
                "2. Add to 'Never sleeping apps'",
                "3. Allow all battery permissions"
            )

            getManufacturer().contains("ONEPLUS") -> listOf(
                "1. Enable 'Advanced optimization'",
                "2. Disable 'Battery optimization'",
                "3. Allow background activity"
            )

            else -> listOf(
                "1. Disable 'Battery optimization'",
                "2. Allow background activity",
                "3. Grant all permissions"
            )
        }
    }
}