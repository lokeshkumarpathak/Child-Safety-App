package com.example.child_safety_app_version1.utils

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.child_safety_app_version1.data.AppInfo
import com.example.child_safety_app_version1.data.AppUsageInfo
import java.text.SimpleDateFormat
import java.util.*

object UsageStatsHelper {
    private const val TAG = "UsageStatsHelper"

    /**
     * Get app usage statistics for a specific time range
     */
    fun getAppUsageStats(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<AppUsageInfo> {
        if (!hasUsageStatsPermission(context)) {
            Log.e(TAG, "Usage stats permission not granted")
            return emptyList()
        }

        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (usageStatsList.isNullOrEmpty()) {
                Log.w(TAG, "No usage stats found for time range")
                return emptyList()
            }

            Log.d(TAG, "Found ${usageStatsList.size} usage stats entries")

            val packageManager = context.packageManager
            val appUsageList = mutableListOf<AppUsageInfo>()

            for (usageStats in usageStatsList) {
                try {
                    // Skip system apps and apps with no usage
                    if (usageStats.totalTimeInForeground == 0L) continue
                    if (isSystemApp(context, usageStats.packageName)) continue

                    val appName = getAppName(packageManager, usageStats.packageName)
                    val tempLastUsed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // API 29+: Use lastTimeVisible for accuracy
                        maxOf(
                            usageStats.lastTimeUsed,
                            usageStats.lastTimeVisible
                        )
                    } else {
                        // API 26-28: Use lastTimeStamp as fallback
                        maxOf(
                            usageStats.lastTimeUsed,
                            usageStats.lastTimeStamp
                        )
                    }

                    appUsageList.add(
                        AppUsageInfo(
                            packageName = usageStats.packageName,
                            appName = appName,
                            totalTimeMs = usageStats.totalTimeInForeground,
                            lastUsed = tempLastUsed,
                            icon = null // Don't load icons for Firestore upload
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing usage stats for ${usageStats.packageName}", e)
                }
            }

            // Sort by usage time (descending)
            return appUsageList.sortedByDescending { it.totalTimeMs }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting usage stats", e)
            return emptyList()
        }
    }

    /**
     * Get today's app usage statistics (from midnight to now)
     * Only returns apps with usage > 0
     */
    fun getTodayUsageStats(context: Context): List<AppUsageInfo> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        Log.d(TAG, "Getting usage stats for today: ${formatTimestamp(startTime)} to ${formatTimestamp(endTime)}")

        return getAppUsageStats(context, startTime, endTime)
    }

    /**
     * ⭐ NEW: Get ALL installed apps with their usage data (if any)
     * This includes apps with 0 usage time
     * Used for "All Apps" card
     */
    /**
     * ⭐ UPDATED: Get ALL installed apps with their usage data (if any)
     * This includes apps with 0 usage time
     * Used for "All Apps" card
     *
     * FIXES:
     * 1. Less aggressive system app filtering
     * 2. Uses MATCH_UNINSTALLED_PACKAGES flag
     * 3. Better error handling per app
     */
    /**
     * ⭐ FIXED: Get ALL installed apps with their usage data (if any)
     * This includes apps with 0 usage time
     * Used for "All Apps" card
     *
     * KEY FIXES:
     * 1. Much less aggressive system app filtering - only excludes pure system apps
     * 2. Includes launcher apps, pre-installed apps, and user-installed apps
     * 3. Better error handling per app
     * 4. More detailed logging
     */
    // Replace the getAllInstalledAppsWithUsage function in UsageStatsHelper with this:

    /**
     * ⭐ UPDATED: Get ALL installed apps with their usage data (if any)
     * This includes apps with 0 usage time
     * Used for "All Apps" card
     *
     * KEY CHANGES:
     * 1. More lenient filtering - includes more apps
     * 2. Only excludes pure system packages without launcher
     * 3. Better logging to track what's being filtered
     */
    fun getAllInstalledAppsWithUsage(context: Context): List<AppUsageInfo> {
        val packageManager = context.packageManager
        val allApps = mutableListOf<AppUsageInfo>()

        try {
            // Get today's usage stats
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            ) ?: emptyList()

            // Create a map of package name to usage stats
            val usageMap = mutableMapOf<String, UsageStats>()
            for (stat in usageStatsList) {
                val existing = usageMap[stat.packageName]
                if (existing == null || stat.lastTimeUsed > existing.lastTimeUsed) {
                    usageMap[stat.packageName] = stat
                }
            }

            Log.d(TAG, "Usage stats found for ${usageMap.size} apps")

            // Get all installed applications
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.GET_META_DATA.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "Total installed packages: ${packages.size}")

            var includedCount = 0
            var excludedCount = 0
            val excludedApps = mutableListOf<String>()

            for (packageInfo in packages) {
                try {
                    val packageName = packageInfo.packageName

                    // Skip only this app itself
                    if (packageName == context.packageName) {
                        excludedCount++
                        excludedApps.add("$packageName (self)")
                        continue
                    }

                    // VERY LENIENT FILTERING: Only exclude if ALL these conditions are true:
                    // 1. It's a system app
                    // 2. It was NOT updated by user
                    // 3. It has NO launcher intent
                    // 4. Package name starts with known system prefixes
                    val isSystemApp = (packageInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystemApp = (packageInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                    // Check if app has a launcher intent
                    val hasLauncherIntent = try {
                        packageManager.getLaunchIntentForPackage(packageName) != null
                    } catch (e: Exception) {
                        false
                    }

                    // 🔧 STRICTER: Comprehensive list of critical system packages to ALWAYS exclude
                    val criticalSystemPackages = setOf(
                        // Core Android System
                        "android",
                        "com.android.systemui",
                        "com.android.settings",
                        "com.android.launcher",
                        "com.android.launcher3",
                        "com.android.phone",
                        "com.android.dialer",
                        "com.android.contacts",
                        "com.android.providers.contacts",
                        "com.android.providers.telephony",
                        "com.android.mms",
                        "com.android.messaging",
                        "com.android.server.telecom",
                        "com.android.incallui",
                        "com.android.stk",

                        // System Navigation & UI
                        "com.android.inputmethod.latin",
                        "com.android.inputmethod.keyboard",
                        "com.google.android.inputmethod.latin",
                        "com.samsung.android.honeyboard",
                        "com.android.nfc",
                        "com.android.bluetooth",

                        // Critical Services
                        "com.android.vending", // Google Play Store (system)
                        "com.google.android.gms",
                        "com.google.android.gsf",
                        "com.google.android.packageinstaller",
                        "com.android.packageinstaller",
                        "com.android.permissioncontroller",
                        "com.google.android.permissioncontroller",

                        // Device Admin & Security
                        "com.android.deviceadmin",
                        "com.google.android.apps.work.oobconfig",
                        "com.android.managedprovisioning",
                        "com.android.certinstaller",

                        // Shell & Development
                        "com.android.shell",
                        "com.android.sharedstoragebackup",
                        "com.android.defcontainer",
                        "com.android.development",

                        // Your app package (self-exclusion)
                        context.packageName
                    )

                    // System package prefixes to exclude
                    val systemPrefixes = listOf(
                        "android.",
                        "com.android.internal",
                        "com.google.android.gsf",
                        "com.google.android.gms.persistent",
                        "com.sec.android.app.launcher", // Samsung launcher
                        "com.miui.", // Xiaomi system
                        "com.huawei.", // Huawei system
                        "com.oppo.", // Oppo system
                        "com.oneplus.", // OnePlus system
                        "com.coloros.", // ColorOS system
                        "com.bbk." // Vivo/Oppo parent company
                    )

                    val isSystemPrefix = systemPrefixes.any { packageName.startsWith(it) }
                    val isCriticalPackage = criticalSystemPackages.contains(packageName)

                    // 🔧 STRICTER FILTERING:
                    // Exclude if ANY of these conditions are true:
                    // 1. It's in the critical system packages list (ALWAYS exclude)
                    // 2. It's a system app with system prefix and no launcher
                    // 3. It's a pure system app (not updated) with no launcher
                    val shouldFilter = isCriticalPackage ||
                            (isSystemApp && isSystemPrefix && !hasLauncherIntent) ||
                            (isSystemApp && !isUpdatedSystemApp && !hasLauncherIntent)

                    if (shouldFilter) {
                        excludedCount++
                        val reason = when {
                            isCriticalPackage -> "(critical system)"
                            isSystemPrefix -> "(system prefix)"
                            else -> "(system no launcher)"
                        }
                        excludedApps.add("$packageName $reason")
                        continue
                    }

                    // 🔧 ADDITIONAL SAFETY: Check for essential app categories
                    val essentialCategories = listOf(
                        "launcher",
                        "phone",
                        "dialer",
                        "contacts",
                        "messaging",
                        "settings",
                        "keyboard",
                        "systemui"
                    )

                    val appNameLower = try {
                        packageManager.getApplicationLabel(packageInfo).toString().lowercase()
                    } catch (e: Exception) {
                        packageName.lowercase()
                    }

                    // Skip if app name or package contains essential keywords
                    val isEssential = essentialCategories.any {
                        appNameLower.contains(it) || packageName.lowercase().contains(it)
                    }

                    if (isEssential && isSystemApp) {
                        excludedCount++
                        excludedApps.add("$packageName (essential: $appNameLower)")
                        continue
                    }

                    // Get app name
                    val appName = try {
                        packageManager.getApplicationLabel(packageInfo).toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not get label for $packageName")
                        packageName // Fallback to package name
                    }

                    // Get usage data if available
                    val usageStat = usageMap[packageName]

                    val tempLastUsed = if (usageStat != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            maxOf(usageStat.lastTimeUsed, usageStat.lastTimeVisible)
                        } else {
                            maxOf(usageStat.lastTimeUsed, usageStat.lastTimeStamp)
                        }
                    } else {
                        0L
                    }

                    allApps.add(
                        AppUsageInfo(
                            packageName = packageName,
                            appName = appName,
                            totalTimeMs = usageStat?.totalTimeInForeground ?: 0L,
                            lastUsed = tempLastUsed,
                            icon = null
                        )
                    )
                    includedCount++

                } catch (e: Exception) {
                    Log.e(TAG, "Error getting app info for ${packageInfo.packageName}: ${e.message}")
                }
            }

            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "🔒 STRICT App Collection Summary:")
            Log.d(TAG, "  Total packages scanned: ${packages.size}")
            Log.d(TAG, "  ✅ Apps included (safe to block): $includedCount")
            Log.d(TAG, "  ⏭️ Apps excluded (protected): $excludedCount")
            Log.d(TAG, "═══════════════════════════════════════")

            // Log excluded apps by category for debugging
            if (excludedApps.isNotEmpty() && Log.isLoggable(TAG, Log.DEBUG)) {
                val criticalExcluded = excludedApps.filter { it.contains("(critical system)") }
                val essentialExcluded = excludedApps.filter { it.contains("(essential:") }
                val otherExcluded = excludedApps.filterNot {
                    it.contains("(critical system)") || it.contains("(essential:")
                }

                Log.d(TAG, "📋 Excluded Apps Breakdown:")
                Log.d(TAG, "  Critical system apps: ${criticalExcluded.size}")
                Log.d(TAG, "  Essential apps (phone/contacts): ${essentialExcluded.size}")
                Log.d(TAG, "  Other system apps: ${otherExcluded.size}")

                Log.d(TAG, "")
                Log.d(TAG, "Sample Critical/Essential Excluded (first 30):")
                (criticalExcluded + essentialExcluded).take(30).forEach {
                    Log.d(TAG, "  🔒 $it")
                }
            }

            // Sort by app name alphabetically
            return allApps.sortedBy { it.appName }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error getting all installed apps", e)
            return emptyList()
        }
    }

    /**
     * UPDATED: Less aggressive system app check
     */
     /**
     * Get all installed apps (non-system)
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val packageManager = context.packageManager
        val installedApps = mutableListOf<AppInfo>()

        try {
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            for (packageInfo in packages) {
                // Skip system apps
                if (isSystemApp(packageInfo)) continue

                try {
                    val appName = packageManager.getApplicationLabel(packageInfo).toString()

                    installedApps.add(
                        AppInfo(
                            packageName = packageInfo.packageName,
                            appName = appName,
                            isSystemApp = false
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting app info for ${packageInfo.packageName}", e)
                }
            }

            Log.d(TAG, "Found ${installedApps.size} installed user apps")
            return installedApps.sortedBy { it.appName }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed apps", e)
            return emptyList()
        }
    }

    /**
     * Check if app is a system app
     */
    private fun isSystemApp(context: Context, packageName: String): Boolean {
        return try {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            isSystemApp(applicationInfo)
        } catch (e: Exception) {
            false
        }
    }

    private fun isSystemApp(applicationInfo: ApplicationInfo): Boolean {
        // Only filter out true system apps that haven't been updated by the user
        // This allows pre-installed apps that were updated to show up
        return (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }

    /**
     * Get app name from package name
     */
    private fun getAppName(packageManager: PackageManager, packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            packageName // Fallback to package name
        }
    }

    /**
     * Get app icon
     */
    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting icon for $packageName", e)
            null
        }
    }

    /**
     * Check if usage stats permission is granted
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            false
        }
    }

    /**
     * Format milliseconds to readable duration (e.g., "2h 45m", "30m", "5s")
     */
    fun formatDuration(milliseconds: Long): String {
        if (milliseconds < 1000) return "0s"

        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> {
                val remainingHours = hours % 24
                if (remainingHours > 0) "${days}d ${remainingHours}h" else "${days}d"
            }
            hours > 0 -> {
                val remainingMinutes = minutes % 60
                if (remainingMinutes > 0) "${hours}h ${remainingMinutes}m" else "${hours}h"
            }
            minutes > 0 -> {
                val remainingSeconds = seconds % 60
                if (remainingSeconds > 0) "${minutes}m ${remainingSeconds}s" else "${minutes}m"
            }
            else -> "${seconds}s"
        }
    }

    /**
     * Format timestamp to readable date-time
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Get today's date in yyyy-MM-dd format
     */
    fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}