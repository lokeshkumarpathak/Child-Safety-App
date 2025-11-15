package com.example.child_safety_app_version1.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.child_safety_app_version1.utils.PaymentParser
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.NotificationType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "SMS RECEIVED - Broadcast triggered")
        Log.d(TAG, "========================================")

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d(TAG, "❌ Not an SMS_RECEIVED_ACTION, ignoring")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            Log.d(TAG, "❌ No messages extracted from intent")
            return
        }

        for (smsMessage in messages) {
            val sender = smsMessage.displayOriginatingAddress ?: ""
            val messageBody = smsMessage.messageBody ?: ""

            Log.d(TAG, "")
            Log.d(TAG, "📱 SMS Details:")
            Log.d(TAG, "   Sender: $sender")
            Log.d(TAG, "   Message: $messageBody")

            // Check if it's a payment SMS
            if (PaymentParser.isPaymentSms(sender, messageBody)) {
                Log.d(TAG, "✅ PAYMENT SMS DETECTED!")
                Log.d(TAG, "   Processing transaction...")

                // Process in background
                scope.launch {
                    processPaymentSms(context, sender, messageBody)
                }
            } else {
                Log.d(TAG, "ℹ️ Not a payment SMS, skipping")
            }
        }
    }

    private suspend fun processPaymentSms(context: Context, sender: String, message: String) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "PROCESSING PAYMENT SMS")
            Log.d(TAG, "========================================")

            // Get current user (child)
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.e(TAG, "❌ User not logged in, cannot process payment")
                return
            }

            val childUid = currentUser.uid
            Log.d(TAG, "Child UID: ${childUid.take(10)}...")

            // Parse transaction
            val transaction = PaymentParser.parseTransaction(sender, message, childUid)
            if (transaction == null) {
                Log.e(TAG, "❌ Failed to parse transaction")
                return
            }

            Log.d(TAG, "✅ Transaction parsed successfully:")
            Log.d(TAG, "   Amount: ₹${transaction.amount}")
            Log.d(TAG, "   Merchant: ${transaction.merchant}")
            Log.d(TAG, "   Type: ${transaction.transactionType}")

            // Get threshold configuration
            val db = FirebaseFirestore.getInstance()
            val thresholdDoc = db.collection("users")
                .document(childUid)
                .collection("paymentThresholds")
                .document("config")
                .get()
                .await()

            val singleTransactionLimit = (thresholdDoc.getDouble("singleTransactionLimit")) ?: 500.0
            val enableNotifications = thresholdDoc.getBoolean("enableNotifications") ?: true
            val notifyOnEveryTransaction = thresholdDoc.getBoolean("notifyOnEveryTransaction") ?: false

            Log.d(TAG, "")
            Log.d(TAG, "📊 Threshold Configuration:")
            Log.d(TAG, "   Single Transaction Limit: ₹$singleTransactionLimit")
            Log.d(TAG, "   Enable Notifications: $enableNotifications")
            Log.d(TAG, "   Notify on Every Transaction: $notifyOnEveryTransaction")

            // Check if threshold exceeded
            val exceedsThreshold = transaction.amount > singleTransactionLimit
            Log.d(TAG, "")
            Log.d(TAG, "🔍 Threshold Check:")
            Log.d(TAG, "   Transaction Amount: ₹${transaction.amount}")
            Log.d(TAG, "   Threshold: ₹$singleTransactionLimit")
            Log.d(TAG, "   Exceeds Threshold: $exceedsThreshold")

            // Update transaction with threshold status
            val updatedTransaction = transaction.copy(
                exceedsThreshold = exceedsThreshold
            )

            // Save transaction to Firestore
            Log.d(TAG, "")
            Log.d(TAG, "💾 Saving transaction to Firestore...")
            val transactionRef = db.collection("users")
                .document(childUid)
                .collection("paymentTransactions")
                .document(transaction.id)

            transactionRef.set(updatedTransaction.toFirestoreMap()).await()
            Log.d(TAG, "✅ Transaction saved successfully")

            // Send notification to parents if threshold exceeded or if notifyOnEveryTransaction is enabled
            if (enableNotifications && (exceedsThreshold || notifyOnEveryTransaction)) {
                Log.d(TAG, "")
                Log.d(TAG, "📤 Sending notification to parents...")
                Log.d(TAG, "   Reason: ${if (exceedsThreshold) "Threshold Exceeded" else "Every Transaction Notification"}")

                val notificationType = if (exceedsThreshold) {
                    NotificationType.PAYMENT_THRESHOLD_EXCEEDED
                } else {
                    NotificationType.PAYMENT_TRANSACTION
                }

                val success = FcmNotificationSender.sendPaymentNotificationToParents(
                    context = context,
                    childUid = childUid,
                    transaction = updatedTransaction,
                    notificationType = notificationType
                )

                if (success) {
                    Log.d(TAG, "✅ Parent notification sent successfully")

                    // Mark as notified
                    transactionRef.update("notifiedParent", true).await()
                } else {
                    Log.e(TAG, "❌ Failed to send parent notification")
                }
            } else {
                Log.d(TAG, "ℹ️ No notification sent:")
                if (!enableNotifications) {
                    Log.d(TAG, "   - Notifications disabled")
                } else {
                    Log.d(TAG, "   - Below threshold and not set to notify on every transaction")
                }
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "✅ PAYMENT SMS PROCESSING COMPLETE")
            Log.d(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "❌ ERROR PROCESSING PAYMENT SMS")
            Log.e(TAG, "========================================")
            Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
            Log.e(TAG, "Message: ${e.message}")
            e.printStackTrace()
        }
    }
}