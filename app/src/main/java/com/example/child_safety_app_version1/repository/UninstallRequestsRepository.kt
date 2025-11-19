package com.example.child_safety_app_version1.repository

import com.google.firebase.firestore.FirebaseFirestore

object UninstallRequestRepository {
    private val db = FirebaseFirestore.getInstance()

    fun createRequest(childUid: String, approvals: Map<String,String>, onComplete: (Boolean,String?) -> Unit) {
        val reqRef = db.collection("users").document(childUid).collection("uninstallRequests").document()
        val data = mapOf(
            "requestId" to reqRef.id,
            "childUid" to childUid,
            "status" to "pending",
            "approvals" to approvals,
            "createdAt" to System.currentTimeMillis()
        )
        reqRef.set(data).addOnSuccessListener { onComplete(true, reqRef.id) }
            .addOnFailureListener { e -> onComplete(false, e.message) }
    }
}
