package com.example.child_safety_app_version1.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.example.child_safety_app_version1.userInterface.LoginScreen
import com.example.child_safety_app_version1.userInterface.ParentDashboard
import com.example.child_safety_app_version1.userInterface.ChildDashboard
import com.example.child_safety_app_version1.userInterface.EnhancedMapScreen
import com.example.child_safety_app_version1.userInterface.AddChildScreen
import com.example.child_safety_app_version1.userInterface.EmergencyContactsScreen
import com.example.child_safety_app_version1.userInterface.NotificationsScreen
import com.example.child_safety_app_version1.userInterface.ChildMediaViewer
import com.example.child_safety_app_version1.utils.getSavedRole
import com.example.child_safety_app_version1.screens.AppManagementScreen
import com.example.child_safety_app_version1.screens.ModeControlScreen
import com.example.child_safety_app_version1.userInterface.PaymentMonitoringScreen
import com.example.child_safety_app_version1.userInterface.UninstallRequestsScreen
//import com.example.child_safety_app_version1.userInterface.UninstallPermissionsScreen
import com.example.child_safety_app_version1.viewModels.ModeControlViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@Composable
fun NavGraph(shouldOpenNotifications: Boolean = false) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    // Defensively get the saved role
    val savedRole = remember { getSavedRole(context) ?: "" }

    // Default start destination
    val startDestination = when (savedRole.lowercase()) {
        "parent" -> "parent_dashboard"
        "child" -> "child_dashboard"
        else -> "login"
    }

    // Handle navigation to notifications screen when triggered by notification click
    LaunchedEffect(shouldOpenNotifications) {
        if (shouldOpenNotifications && savedRole.lowercase() == "parent") {
            // Small delay to ensure navigation is ready
            kotlinx.coroutines.delay(300)
            navController.navigate("notifications") {
                // Don't add to back stack if already on notifications
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("login") {
            LoginScreen(navController = navController)
        }

        composable("parent_dashboard") {
            ParentDashboard(navController = navController)
        }

        composable("child_dashboard") {
            ChildDashboard(navController = navController)
        }

        composable("safe_zone") {
            EnhancedMapScreen(navController = navController)
        }

        composable("add_child") {
            AddChildScreen(navController = navController)
        }

        composable("notifications") {
            NotificationsScreen(navController)
        }

        composable("emergency_contacts") {
            EmergencyContactsScreen(navController = navController)
        }

        // 🆕 MEDIA VIEWER ROUTE - Pass child info from navigation arguments
        composable("media_viewer/{childUid}/{childName}") { backStackEntry ->
            val childUid = backStackEntry.arguments?.getString("childUid") ?: ""
            val childName = backStackEntry.arguments?.getString("childName") ?: "Child"

            ChildMediaViewer(
                childUid = childUid,
                childName = childName
            )
        }

        // ⭐ FIXED: App Management Route with Child Selection
        composable("app_management") {
            AppManagementScreen()
        }

        // ⭐ FIXED: Mode Control Route with Child Selection
        composable("mode_control") {
            ModeControlScreen()
        }

        composable("payment_monitoring") {
            PaymentMonitoringScreen(navController = navController)
        }

        composable("uninstall_requests") {
            UninstallRequestsScreen()
        }

    }

    // Debug log to verify correct navigation start
    LaunchedEffect(Unit) {
        println("Starting NavGraph with destination: $startDestination")
        if (shouldOpenNotifications) {
            println("Notification click detected - will navigate to notifications")
        }
    }
}