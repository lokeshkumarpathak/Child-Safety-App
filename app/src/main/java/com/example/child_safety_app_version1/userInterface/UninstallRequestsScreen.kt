package com.example.child_safety_app_version1.userInterface

import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallRequestsScreen(navController: androidx.navigation.NavController? = null) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val parentUid = auth.currentUser?.uid ?: return
    val scope = rememberCoroutineScope()

    var requests by remember { mutableStateOf(listOf<com.google.firebase.firestore.DocumentSnapshot>()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            // fetch children of this parent
            val childDocs = db.collection("users").document(parentUid)
                .collection("children").get().await()
            val childIds = childDocs.documents.mapNotNull { it.getString("childId") }

            val pending = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
            for (childId in childIds) {
                val snaps = db.collection("users").document(childId)
                    .collection("uninstallRequests")
                    .whereEqualTo("status", "pending")
                    .get().await()
                pending.addAll(snaps.documents)
            }
            requests = pending
        } catch (e: Exception) {
            Toast.makeText(context, "Error fetching requests: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            loading = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Uninstall Requests") }) }) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            } else if (requests.isEmpty()) {
                Text("No pending uninstall requests", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(requests) { doc ->
                        val data = doc.data ?: mapOf<String, Any>()
                        val requestId = data["requestId"] as? String ?: doc.id
                        val childUid = data["childUid"] as? String ?: ""
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(text = "Child: $childUid")
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    OutlinedButton(onClick = {
                                        // Reject (no password needed)
                                        performApprovalUpdate(context, parentUid, childUid, requestId, false)
                                    }) {
                                        Icon(Icons.Default.Close, "Reject")
                                        Spacer(Modifier.width(6.dp))
                                        Text("Reject")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        // Show password prompt then approve
                                        showPasswordPromptAndApprove(context, parentUid, childUid, requestId)
                                    }) {
                                        Icon(Icons.Default.Check, "Approve")
                                        Spacer(Modifier.width(6.dp))
                                        Text("Approve")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun showPasswordPromptAndApprove(context: android.content.Context, parentUid: String, childUid: String, requestId: String) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("Enter password to approve")
    val input = EditText(context)
    input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    builder.setView(input)
    builder.setPositiveButton("OK") { dialog, _ ->
        val pwd = input.text.toString()
        if (pwd.isBlank()) {
            Toast.makeText(context, "Password required", Toast.LENGTH_SHORT).show()
        } else {
            reauthAndRespond(context, parentUid, childUid, requestId, true, pwd)
        }
        dialog.dismiss()
    }
    builder.setNegativeButton("Cancel") { d, _ -> d.dismiss() }
    builder.show()
}

private fun reauthAndRespond(context: android.content.Context, parentUid: String, childUid: String, requestId: String, approve: Boolean, password: String) {
    val auth = FirebaseAuth.getInstance()
    val user = auth.currentUser ?: run { Toast.makeText(context, "No user", Toast.LENGTH_SHORT).show(); return }
    val credential = EmailAuthProvider.getCredential(user.email!!, password)
    user.reauthenticate(credential).addOnCompleteListener { task ->
        if (task.isSuccessful) {
            performApprovalUpdate(context, parentUid, childUid, requestId, approve)
        } else {
            Toast.makeText(context, "Wrong password", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun performApprovalUpdate(context: android.content.Context, parentUid: String, childUid: String, requestId: String, approve: Boolean) {
    val db = FirebaseFirestore.getInstance()
    val requestRef = db.collection("users").document(childUid).collection("uninstallRequests").document(requestId)
    val statusVal = if (approve) "approved" else "rejected"
    val updates = mapOf("approvals.$parentUid" to statusVal)
    requestRef.update(updates).addOnSuccessListener {
        // After updating, recompute overall status
        requestRef.get().addOnSuccessListener { doc ->
            val approvals = doc.get("approvals") as? Map<*, *>
            if (approvals != null) {
                val values = approvals.values.map { it as String }
                when {
                    values.any { it == "rejected" } -> {
                        requestRef.update("status", "rejected")
                        // notify child of rejection
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.child_safety_app_version1.utils.FcmNotificationSender.sendUninstallResponseToChild(
                                context, childUid, requestId, parentUid,
                                com.example.child_safety_app_version1.utils.NotificationType.UNINSTALL_REJECTED
                            )
                        }
                    }
                    values.all { it == "approved" } -> {
                        requestRef.update("status", "approved")
                        // notify child approved
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.child_safety_app_version1.utils.FcmNotificationSender.sendUninstallResponseToChild(
                                context, childUid, requestId, parentUid,
                                com.example.child_safety_app_version1.utils.NotificationType.UNINSTALL_APPROVED
                            )
                        }
                    }
                    else -> {
                        requestRef.update("status", "partial")
                        // notify child partial
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.child_safety_app_version1.utils.FcmNotificationSender.sendUninstallResponseToChild(
                                context, childUid, requestId, parentUid,
                                com.example.child_safety_app_version1.utils.NotificationType.UNINSTALL_PARTIAL_RESPONSE
                            )
                        }
                    }
                }
            }
        }
        Toast.makeText(context, if (approve) "Approved" else "Rejected", Toast.LENGTH_SHORT).show()
    }.addOnFailureListener { e ->
        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
