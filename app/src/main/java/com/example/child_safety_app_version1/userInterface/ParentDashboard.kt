package com.example.child_safety_app_version1.userInterface

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.child_safety_app_version1.utils.FcmTokenManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboard(navController: NavController) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var isLoggingOut by remember { mutableStateOf(false) }

    // 🆕 SMS permission launcher (for receiving location SMS from child)
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val sendGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        val readGranted = permissions[Manifest.permission.READ_SMS] ?: false
        val receiveGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false

        if (readGranted && receiveGranted) {
            Toast.makeText(
                context,
                "✅ SMS permissions granted - You'll receive offline location alerts",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                "⚠️ SMS permissions needed to receive location alerts when child is offline",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "✅ Notification permission granted", Toast.LENGTH_SHORT).show()

            // After notification permission, request SMS permissions
            requestSmsPermissionsIfNeeded(context, smsPermissionLauncher)
        } else {
            Toast.makeText(
                context,
                "⚠️ Notification permission denied. You won't receive alerts.",
                Toast.LENGTH_LONG
            ).show()

            // Still try to request SMS even if notification denied
            requestSmsPermissionsIfNeeded(context, smsPermissionLauncher)
        }
    }

    // Request permissions on first composition
    LaunchedEffect(Unit) {
        // 1. Request notification permission first (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED -> {
                    // Notification permission already granted, check SMS
                    requestSmsPermissionsIfNeeded(context, smsPermissionLauncher)
                }
                else -> {
                    // Request notification permission first
                    notificationPermissionLauncher.launch(notificationPermission)
                }
            }
        } else {
            // For Android < 13, just request SMS permissions
            requestSmsPermissionsIfNeeded(context, smsPermissionLauncher)
        }
    }

    fun handleLogout() {
        isLoggingOut = true
        scope.launch {
            FcmTokenManager.performLogout(context) {
                navController.navigate("login") {
                    popUpTo("parent_dashboard") { inclusive = true }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parent Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(
                        onClick = { handleLogout() },
                        enabled = !isLoggingOut
                    ) {
                        if (isLoggingOut) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Logout, "Logout")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)  // ← ADD THIS
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Welcome Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Welcome Back!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentUser?.email ?: "Parent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Action Buttons
            DashboardButton(
                icon = Icons.Default.Shield,
                title = "Safe Zones",
                description = "Manage safe zones for your children",
                onClick = { navController.navigate("safe_zone") }
            )

            DashboardButton(
                icon = Icons.Default.Apps,
                title = "App Management",
                description = "View usage & control apps",
                onClick = { navController.navigate("app_management") }
            )

            DashboardButton(
                icon = Icons.Default.School,
                title = "Study & Bedtime Mode",
                description = "Control device access modes",
                onClick = { navController.navigate("mode_control") }
            )

            DashboardButton(
                icon = Icons.Default.CreditCard,
                title = "Payment Monitoring",
                description = "Monitor child's payment transactions",
                onClick = { navController.navigate("payment_monitoring") }
            )

            DashboardButton(
                icon = Icons.Default.ChildCare,
                title = "Manage Children",
                description = "Add or remove children from your account",
                onClick = { navController.navigate("add_child") }
            )

            DashboardButton(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "View safety alerts and updates",
                onClick = { navController.navigate("notifications") }
            )

            DashboardButton(
                icon = Icons.Default.ContactPhone,
                title = "Emergency Contacts",
                description = "Manage emergency contact numbers",
                onClick = { navController.navigate("emergency_contacts") }
            )

            // Logout Button

            OutlinedButton(
                onClick = { handleLogout() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoggingOut,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isLoggingOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logging out...")
                } else {
                    Icon(Icons.Default.Logout, "Logout")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logout")
                }
            }
        }
    }
}

/**
 * 🆕 Helper function to request SMS permissions if not already granted
 */
private fun requestSmsPermissionsIfNeeded(
    context: android.content.Context,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
) {
    val sendSmsGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.SEND_SMS
    ) == PackageManager.PERMISSION_GRANTED

    val readSmsGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED

    val receiveSmsGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS
    ) == PackageManager.PERMISSION_GRANTED

    // If any SMS permission is missing, request all of them
    if (!sendSmsGranted || !readSmsGranted || !receiveSmsGranted) {
        launcher.launch(
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        )
    }
}

@Composable
fun DashboardButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}