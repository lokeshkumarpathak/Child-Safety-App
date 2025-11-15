package com.example.child_safety_app_version1.userInterface

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.child_safety_app_version1.data.PaymentThreshold
import com.example.child_safety_app_version1.data.PaymentTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMonitoringScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()

    var selectedChildUid by remember { mutableStateOf<String?>(null) }
    var selectedChildName by remember { mutableStateOf("Select Child") }
    var children by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingChildren by remember { mutableStateOf(true) }

    var threshold by remember { mutableStateOf(PaymentThreshold()) }
    var transactions by remember { mutableStateOf<List<PaymentTransaction>>(emptyList()) }
    var isLoadingTransactions by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var showThresholdDialog by remember { mutableStateOf(false) }
    var smsPermissionGranted by remember { mutableStateOf(false) }

    // SMS Permission Launcher
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        smsPermissionGranted = isGranted
        if (isGranted) {
            Toast.makeText(context, "SMS permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context,
                "SMS permission is required to monitor transactions",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Check SMS permission on launch
    LaunchedEffect(Unit) {
        smsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // Load children
    LaunchedEffect(Unit) {
        isLoadingChildren = true
        try {
            val parentUid = auth.currentUser?.uid
            if (parentUid != null) {
                val snapshot = db.collection("users")
                    .document(parentUid)
                    .collection("children")
                    .get()
                    .await()

                children = snapshot.documents.mapNotNull { doc ->
                    val childId = doc.getString("childId")
                    val childName = doc.getString("childName")
                    if (childId != null && childName != null) {
                        Pair(childId, childName)
                    } else null
                }

                // Auto-select first child if available
                if (children.isNotEmpty()) {
                    selectedChildUid = children[0].first
                    selectedChildName = children[0].second
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error loading children: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoadingChildren = false
        }
    }

    // Load threshold and transactions when child is selected
    LaunchedEffect(selectedChildUid) {
        selectedChildUid?.let { childUid ->
            // Load threshold
            try {
                val thresholdDoc = db.collection("users")
                    .document(childUid)
                    .collection("paymentThresholds")
                    .document("config")
                    .get()
                    .await()

                threshold = if (thresholdDoc.exists()) {
                    PaymentThreshold.fromFirestore(thresholdDoc.data ?: emptyMap())
                        .copy(childUid = childUid)
                } else {
                    PaymentThreshold(childUid = childUid)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading threshold", Toast.LENGTH_SHORT).show()
            }

            // Load transactions
            isLoadingTransactions = true
            try {
                val snapshot = db.collection("users")
                    .document(childUid)
                    .collection("paymentTransactions")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(50)
                    .get()
                    .await()

                transactions = snapshot.documents.mapNotNull { doc ->
                    try {
                        PaymentTransaction.fromFirestore(doc.id, doc.data ?: emptyMap())
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading transactions", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingTransactions = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment Monitoring") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SMS Permission Card
            item {
                if (!smsPermissionGranted) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    "Warning",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "SMS Permission Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                "To monitor payment transactions from bank SMS, we need SMS permission.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = {
                                    smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Check, "Grant")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Permission")
                            }
                        }
                    }
                }
            }

            // Child Selection Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Select Child",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (isLoadingChildren) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        } else if (children.isEmpty()) {
                            Text(
                                "No children added yet. Add a child first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            var expanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it }
                            ) {
                                OutlinedTextField(
                                    value = selectedChildName,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Child") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    children.forEach { (childId, childName) ->
                                        DropdownMenuItem(
                                            text = { Text(childName) },
                                            onClick = {
                                                selectedChildUid = childId
                                                selectedChildName = childName
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Threshold Configuration Card
            if (selectedChildUid != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Payment Threshold",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { showThresholdDialog = true }) {
                                    Icon(Icons.Default.Edit, "Edit Threshold")
                                }
                            }

                            Divider()

                            ThresholdItem("Single Transaction", "₹${threshold.singleTransactionLimit}")
                            ThresholdItem("Daily Limit", "₹${threshold.dailyLimit}")
                            ThresholdItem("Weekly Limit", "₹${threshold.weeklyLimit}")
                            ThresholdItem("Monthly Limit", "₹${threshold.monthlyLimit}")

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable Notifications", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = threshold.enableNotifications,
                                    onCheckedChange = { enabled ->
                                        threshold = threshold.copy(enableNotifications = enabled)
                                        scope.launch {
                                            saveThreshold(db, threshold)
                                            Toast.makeText(context, "Notifications ${if (enabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Notify on Every Transaction", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Get notified for all payments, not just threshold exceeds",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = threshold.notifyOnEveryTransaction,
                                    onCheckedChange = { enabled ->
                                        threshold = threshold.copy(notifyOnEveryTransaction = enabled)
                                        scope.launch {
                                            saveThreshold(db, threshold)
                                            Toast.makeText(context, "Every transaction notification ${if (enabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Recent Transactions Section
                item {
                    Text(
                        "Recent Transactions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isLoadingTransactions) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (transactions.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    "No transactions",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "No Transactions Yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Transactions will appear here when payment SMS are received",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(transactions) { transaction ->
                        TransactionCard(transaction)
                    }
                }
            }
        }
    }

    // Threshold Edit Dialog
    if (showThresholdDialog) {
        ThresholdEditDialog(
            threshold = threshold,
            onDismiss = { showThresholdDialog = false },
            onSave = { newThreshold ->
                scope.launch {
                    isSaving = true
                    val success = saveThreshold(db, newThreshold)
                    isSaving = false
                    if (success) {
                        threshold = newThreshold
                        Toast.makeText(context, "Threshold saved successfully", Toast.LENGTH_SHORT).show()
                        showThresholdDialog = false
                    } else {
                        Toast.makeText(context, "Failed to save threshold", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            isSaving = isSaving
        )
    }
}

@Composable
fun ThresholdItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun TransactionCard(transaction: PaymentTransaction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (transaction.exceedsThreshold) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (transaction.transactionType) {
                            com.example.child_safety_app_version1.data.TransactionType.UPI -> Icons.Default.Phone
                            com.example.child_safety_app_version1.data.TransactionType.CARD -> Icons.Default.CreditCard
                            else -> Icons.Default.ShoppingCart
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (transaction.exceedsThreshold) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        transaction.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    transaction.bankName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    formatTimestamp2(transaction.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (transaction.exceedsThreshold) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            "Threshold exceeded",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Threshold Exceeded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "₹${String.format("%.2f", transaction.amount)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.exceedsThreshold) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    transaction.category.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ThresholdEditDialog(
    threshold: PaymentThreshold,
    onDismiss: () -> Unit,
    onSave: (PaymentThreshold) -> Unit,
    isSaving: Boolean
) {
    var singleLimit by remember { mutableStateOf(threshold.singleTransactionLimit.toString()) }
    var dailyLimit by remember { mutableStateOf(threshold.dailyLimit.toString()) }
    var weeklyLimit by remember { mutableStateOf(threshold.weeklyLimit.toString()) }
    var monthlyLimit by remember { mutableStateOf(threshold.monthlyLimit.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Threshold Limits") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = singleLimit,
                    onValueChange = { singleLimit = it },
                    label = { Text("Single Transaction (₹)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dailyLimit,
                    onValueChange = { dailyLimit = it },
                    label = { Text("Daily Limit (₹)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = weeklyLimit,
                    onValueChange = { weeklyLimit = it },
                    label = { Text("Weekly Limit (₹)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = monthlyLimit,
                    onValueChange = { monthlyLimit = it },
                    label = { Text("Monthly Limit (₹)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newThreshold = threshold.copy(
                        singleTransactionLimit = singleLimit.toDoubleOrNull() ?: 500.0,
                        dailyLimit = dailyLimit.toDoubleOrNull() ?: 2000.0,
                        weeklyLimit = weeklyLimit.toDoubleOrNull() ?: 5000.0,
                        monthlyLimit = monthlyLimit.toDoubleOrNull() ?: 10000.0
                    )
                    onSave(newThreshold)
                },
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        }
    )
}

fun formatTimestamp2(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

suspend fun saveThreshold(db: FirebaseFirestore, threshold: PaymentThreshold): Boolean {
    return try {
        db.collection("users")
            .document(threshold.childUid)
            .collection("paymentThresholds")
            .document("config")
            .set(threshold.toFirestoreMap())
            .await()
        true
    } catch (e: Exception) {
        false
    }
}