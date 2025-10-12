package com.example.child_safety_app_version1.userInterface

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Data class for Child
data class Child(
    val childId: String = "",
    val childEmail: String = "",
    val childName: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "childId" to childId,
            "childEmail" to childEmail,
            "childName" to childName,
            "addedAt" to addedAt
        )
    }

    companion object {
        fun fromMap(map: Map<String, Any>): Child? {
            return try {
                Child(
                    childId = map["childId"] as? String ?: "",
                    childEmail = map["childEmail"] as? String ?: "",
                    childName = map["childName"] as? String ?: "",
                    addedAt = (map["addedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChildScreen(navController: NavController) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()

    var childEmail by remember { mutableStateOf("") }
    var childName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingChildren by remember { mutableStateOf(true) }
    var children by remember { mutableStateOf<List<Child>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Load existing children
    LaunchedEffect(Unit) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            scope.launch {
                try {
                    val snapshot = firestore.collection("users")
                        .document(uid)
                        .collection("children")
                        .get()
                        .await()

                    val loadedChildren = snapshot.documents.mapNotNull { doc ->
                        Child.fromMap(doc.data ?: emptyMap())
                    }
                    children = loadedChildren
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Error loading children: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    isLoadingChildren = false
                }
            }
        } else {
            isLoadingChildren = false
        }
    }

    // Function to add child
    fun addChild(email: String, name: String) {
        val parentUid = auth.currentUser?.uid
        if (parentUid == null) {
            Toast.makeText(context, "Parent not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        val trimmedEmail = email.trim()
        val trimmedName = name.trim()

        // Validation
        if (trimmedEmail.isEmpty() || trimmedName.isEmpty()) {
            errorMessage = "Please fill all fields"
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            errorMessage = "Please enter a valid email"
            return
        }

        isLoading = true
        errorMessage = ""

        scope.launch {
            try {
                // Search for the child user by email in the users collection
                val querySnapshot = firestore.collection("users")
                    .whereEqualTo("email", trimmedEmail)
                    .whereEqualTo("role", "child")
                    .get()
                    .await()

                if (querySnapshot.documents.isEmpty()) {
                    errorMessage = "No child account found with this email. Please ensure the child has registered first."
                    isLoading = false
                    return@launch
                }

                val childDoc = querySnapshot.documents.first()
                val childUid = childDoc.id

                // Check if child is already added
                val existingChild = firestore.collection("users")
                    .document(parentUid)
                    .collection("children")
                    .document(childUid)
                    .get()
                    .await()

                if (existingChild.exists()) {
                    errorMessage = "This child is already added to your account"
                    isLoading = false
                    return@launch
                }

                // Create child entry
                val newChild = Child(
                    childId = childUid,
                    childEmail = trimmedEmail,
                    childName = trimmedName
                )

                // Add child to parent's children subcollection
                firestore.collection("users")
                    .document(parentUid)
                    .collection("children")
                    .document(childUid)
                    .set(newChild.toMap())
                    .await()

                // Also add parent reference to child's document
                firestore.collection("users")
                    .document(childUid)
                    .collection("parents")
                    .document(parentUid)
                    .set(mapOf(
                        "parentId" to parentUid,
                        "parentEmail" to auth.currentUser?.email,
                        "addedAt" to System.currentTimeMillis()
                    ))
                    .await()

                // Update local list
                children = children + newChild

                Toast.makeText(context, "Child added successfully!", Toast.LENGTH_SHORT).show()
                showAddDialog = false
                childEmail = ""
                childName = ""

            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
                Toast.makeText(context, "Failed to add child: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    // Function to remove child
    fun removeChild(child: Child) {
        val parentUid = auth.currentUser?.uid ?: return

        scope.launch {
            try {
                // Remove from parent's children collection
                firestore.collection("users")
                    .document(parentUid)
                    .collection("children")
                    .document(child.childId)
                    .delete()
                    .await()

                // Remove parent reference from child
                firestore.collection("users")
                    .document(child.childId)
                    .collection("parents")
                    .document(parentUid)
                    .delete()
                    .await()

                // Update local list
                children = children.filter { it.childId != child.childId }

                Toast.makeText(context, "Child removed successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error removing child: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Children") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Add Child")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoadingChildren) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                if (children.isEmpty()) {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FamilyRestroom,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Children Added Yet",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add your first child by clicking the + button",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Children list
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Note: Children must register with their own account first before you can add them.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        items(children) { child ->
                            var showDeleteDialog by remember { mutableStateOf(false) }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChildCare,
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(
                                                text = child.childName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = child.childEmail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showDeleteDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove child",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            // Delete confirmation dialog
                            if (showDeleteDialog) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteDialog = false },
                                    title = { Text("Remove Child") },
                                    text = { Text("Are you sure you want to remove ${child.childName} from your account?") },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                removeChild(child)
                                                showDeleteDialog = false
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Text("Remove")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteDialog = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Child Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) {
                    showAddDialog = false
                    childEmail = ""
                    childName = ""
                    errorMessage = ""
                }
            },
            title = { Text("Add Child") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Enter the child's details. The child must have already registered with their email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = childName,
                        onValueChange = {
                            childName = it
                            errorMessage = ""
                        },
                        label = { Text("Child's Name") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Person, "Name")
                        }
                    )

                    OutlinedTextField(
                        value = childEmail,
                        onValueChange = {
                            childEmail = it
                            errorMessage = ""
                        },
                        label = { Text("Child's Email") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Email, "Email")
                        }
                    )

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Adding child...")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { addChild(childEmail, childName) },
                    enabled = !isLoading
                ) {
                    Text("Add Child")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        childEmail = ""
                        childName = ""
                        errorMessage = ""
                    },
                    enabled = !isLoading
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}