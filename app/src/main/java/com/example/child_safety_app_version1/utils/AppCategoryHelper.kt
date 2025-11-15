package com.example.child_safety_app_version1.utils

import android.util.Log
import com.example.child_safety_app_version1.data.AppCategory

/**
 * Helper object to categorize apps by package name
 * Maintains predefined lists for common apps
 */
object AppCategoryHelper {
    private const val TAG = "AppCategoryHelper"

    /**
     * Categorize an app based on its package name
     */
    fun categorizeApp(packageName: String): AppCategory {
        return when {
            isEducationalApp(packageName) -> AppCategory.EDUCATIONAL
            isEntertainmentApp(packageName) -> AppCategory.ENTERTAINMENT
            isSocialApp(packageName) -> AppCategory.SOCIAL
            isGameApp(packageName) -> AppCategory.GAMES
            isProductivityApp(packageName) -> AppCategory.PRODUCTIVITY
            isSystemApp(packageName) -> AppCategory.SYSTEM
            else -> AppCategory.OTHER
        }
    }

    /**
     * Get all apps in a specific category
     */
    fun getAppsInCategory(category: AppCategory): List<String> {
        return when (category) {
            AppCategory.EDUCATIONAL -> getEducationalApps()
            AppCategory.ENTERTAINMENT -> getEntertainmentApps()
            AppCategory.SOCIAL -> getSocialApps()
            AppCategory.GAMES -> getGameApps()
            AppCategory.PRODUCTIVITY -> getProductivityApps()
            AppCategory.SYSTEM -> getSystemApps()
            else -> emptyList()
        }
    }

    /**
     * Check if app is educational
     */
    fun isEducationalApp(packageName: String): Boolean {
        return getEducationalApps().contains(packageName)
    }

    /**
     * Check if app is entertainment
     */
    fun isEntertainmentApp(packageName: String): Boolean {
        return getEntertainmentApps().contains(packageName)
    }

    /**
     * Check if app is social
     */
    fun isSocialApp(packageName: String): Boolean {
        return getSocialApps().contains(packageName)
    }

    /**
     * Check if app is a game
     */
    fun isGameApp(packageName: String): Boolean {
        return getGameApps().contains(packageName)
    }

    /**
     * Check if app is productivity
     */
    fun isProductivityApp(packageName: String): Boolean {
        return getProductivityApps().contains(packageName)
    }

    /**
     * Check if app is system app
     */
    fun isSystemApp(packageName: String): Boolean {
        return getSystemApps().contains(packageName)
    }

    /**
     * Get default educational app whitelist for Study Mode
     */
    fun getDefaultEducationalWhitelist(): List<String> {
        return listOf(
            // Calculators
            "com.android.calculator2",
            "com.google.android.calculator",

            // Note-taking
            "com.google.android.keep",
            "com.samsung.android.app.notes",
            "com.evernote",

            // Learning Platforms
            "com.google.android.apps.classroom",
            "com.duolingo",
            "org.khanacademy.android",
            "com.photomath.app",
            "com.coursera",

            // Productivity & Office
            "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.microsoft.office.word",
            "com.microsoft.office.excel",
            "com.microsoft.office.powerpoint",

            // Reference & Dictionary
            "com.dictionary",
            "com.google.android.apps.translate",
            "com.wikipedia",

            // Web Browser (for research)
            "com.android.chrome",
            "com.google.android.apps.maps",

            // Additional Educational
            "com.ted",
            "com.google.android.videos",
            "com.todoist",
            "com.slack"
        )
    }

    // ======================== EDUCATIONAL APPS ========================

    private fun getEducationalApps(): List<String> {
        return listOf(
            // Calculators
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.mathway",

            // Note-taking
            "com.google.android.keep",
            "com.samsung.android.app.notes",
            "com.evernote",
            "com.notionlabs.notion",
            "com.simplenote",

            // Learning Platforms
            "com.google.android.apps.classroom",
            "com.duolingo",
            "org.khanacademy.android",
            "com.photomath.app",
            "com.coursera",
            "com.udemy",
            "com.skillshare.android",
            "com.byjus.byju",
            "com.vedantu",

            // Productivity & Office
            "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.microsoft.office.word",
            "com.microsoft.office.excel",
            "com.microsoft.office.powerpoint",
            "org.libreoffice.android",

            // Reference & Dictionary
            "com.dictionary",
            "com.google.android.apps.translate",
            "com.wikipedia",
            "com.merriam_webster",

            // Web Browser (research)
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",

            // Maps (geography learning)
            "com.google.android.apps.maps",

            // Additional Educational
            "com.ted",
            "com.google.android.videos",
            "com.todoist",
            "com.asana",
            "com.slack"
        )
    }

    // ======================== ENTERTAINMENT APPS ========================

    private fun getEntertainmentApps(): List<String> {
        return listOf(
            // Video Streaming
            "com.netflix.mediaclient",
            "com.amazon.venezia",
            "com.hotstar",
            "com.hulu.plus",
            "com.disneyplus",

            // Music Streaming
            "com.spotify.music",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.gaana",
            "com.wynk.music",

            // Movies & Shows
            "com.imdb.mobile",
            "com.mxplayer.videoplayer",
            "com.xmedia.videoplayer.hd",
            "in.flipkart.fplay",

            // Podcasts
            "com.podcast.podcasts",
            "com.spotify.podcasts",

            // Comics & Reading
            "com.tapas.android",
            "com.webtoons",
            "com.wattpad",

            // Entertainment General
            "com.youtube",
            "com.google.android.youtube"
        )
    }

    // ======================== SOCIAL APPS ========================

    private fun getSocialApps(): List<String> {
        return listOf(
            // Messaging
            "com.whatsapp",
            "com.telegram",
            "com.facebook.orca",
            "com.viber.voip",
            "com.skype.raider",

            // Social Media
            "com.facebook.katana",
            "com.instagram.android",
            "com.twitter.android",
            "com.snapchat.android",
            "com.tik.tiktok",
            "com.zhiliaoapp.musically",
            "com.reddit.frontpage",

            // Dating
            "com.tinder",
            "com.bumble.app",
            "com.hinge.app",

            // Professional
            "com.linkedin.android",

            // Messaging Alternatives
            "com.discord",
            "org.mozilla.firefox",
            "com.android.vending"
        )
    }

    // ======================== GAMES ========================

    private fun getGameApps(): List<String> {
        return listOf(
            // Popular Action Games
            "com.tencent.ig",
            "com.pubg.imobile",
            "com.activision.callofduty.shooter",
            "com.mojang.minecraftpe",
            "com.roblox.client",

            // Puzzle Games
            "com.king.candycrushsaga",
            "com.playrix.townshipcity",
            "com.supercell.clashofclans",
            "com.supercell.clashroyale",

            // Racing Games
            "com.gameloft.android.ANMP.GloftASAR",
            "com.zynga.words.gp",
            "com.ea.games.need_for_speed_payback",

            // Casino/Cards
            "com.zynga.lWords",
            "com.playrix.fishdomgo",

            // Platform Games
            "com.halfbrick.fruitninja",
            "com.outfit7.talkingtom",
            "com.ketchapp.flappybird",

            // Strategy
            "com.supercell.brawlstars",
            "com.kabam.FFusionAndroid"
        )
    }

    // ======================== PRODUCTIVITY APPS ========================

    private fun getProductivityApps(): List<String> {
        return listOf(
            // Office Suites
            "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.microsoft.office.word",
            "com.microsoft.office.excel",
            "com.microsoft.office.powerpoint",

            // Task Management
            "com.todoist",
            "com.asana",
            "com.microsoft.todo",

            // Note-Taking
            "com.google.android.keep",
            "com.evernote",
            "com.notionlabs.notion",
            "com.simplenote",

            // File Management
            "com.google.android.apps.nbu.files",
            "com.sec.android.app.myfiles",

            // Communication
            "com.slack",
            "com.microsoft.teams",
            "com.zoom.videomeetings",

            // Calendar & Organization
            "com.google.android.calendar",
            "com.sec.android.app.sbrowser",

            // Cloud Storage
            "com.google.android.apps.docs.editors.drive",
            "com.microsoft.skydrive",
            "com.dropbox.android"
        )
    }

    // ======================== SYSTEM APPS ========================

    private fun getSystemApps(): List<String> {
        return listOf(
            // Core System
            "com.android.settings",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",

            // Google Apps
            "com.google.android.apps.nexuslauncher",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.wellbeing",

            // Samsung Apps
            "com.samsung.android.app.launcher",
            "com.samsung.android.aremoji",
            "com.samsung.systemui",

            // Dialer & Contacts
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.contacts",

            // Messages
            "com.android.mms",
            "com.google.android.apps.messaging",

            // Camera
            "com.android.camera",
            "com.android.camera2",
            "com.samsung.android.app.camera",

            // Gallery
            "com.android.gallery3d",
            "com.samsung.android.gallery",
            "com.google.android.apps.photos",

            // File Manager
            "com.android.documentsui",
            "com.sec.android.app.myfiles",

            // Clock
            "com.android.alarm",
            "com.samsung.android.app.clockapps",

            // Notes
            "com.samsung.android.app.notes",

            // Calculator
            "com.android.calculator2",

            // This App
            "com.example.child_safety_app_version1",
            "com.example.parent_safety_app"
        )
    }

    /**
     * Log app categorization (for debugging)
     */
    fun logAppCategory(packageName: String, appName: String) {
        val category = categorizeApp(packageName)
        Log.d(TAG, "App: $appName | Package: $packageName | Category: ${category.name}")
    }

    /**
     * Get category color for UI display
     */
    fun getCategoryColor(category: AppCategory): String {
        return when (category) {
            AppCategory.EDUCATIONAL -> "#4CAF50"      // Green
            AppCategory.ENTERTAINMENT -> "#FF9800"     // Orange
            AppCategory.SOCIAL -> "#2196F3"            // Blue
            AppCategory.GAMES -> "#E91E63"             // Pink
            AppCategory.PRODUCTIVITY -> "#9C27B0"      // Purple
            AppCategory.SYSTEM -> "#757575"            // Gray
            else -> "#9E9E9E"                          // Light Gray
        }
    }

    /**
     * Get category emoji for UI display
     */
    fun getCategoryEmoji(category: AppCategory): String {
        return when (category) {
            AppCategory.EDUCATIONAL -> "📚"
            AppCategory.ENTERTAINMENT -> "🎬"
            AppCategory.SOCIAL -> "👥"
            AppCategory.GAMES -> "🎮"
            AppCategory.PRODUCTIVITY -> "📊"
            AppCategory.SYSTEM -> "⚙️"
            else -> "📦"
        }
    }
}