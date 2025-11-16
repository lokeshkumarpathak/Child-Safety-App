package com.example.child_safety_app_version1.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.child_safety_app_version1.data.PaymentThreshold
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
import java.util.Calendar

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver_Payment"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "════════════════════════════════════════════════════")
        Log.d(TAG, "📱 SMS RECEIVED - Broadcast triggered")
        Log.d(TAG, "════════════════════════════════════════════════════")
        Log.d(TAG, "Action: ${intent.action}")
        Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString()}")

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d(TAG, "❌ Not an SMS_RECEIVED_ACTION, ignoring")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            Log.d(TAG, "❌ No messages extracted from intent")
            return
        }

        Log.d(TAG, "✅ Extracted ${messages.size} message(s) from intent")

        for (smsMessage in messages) {
            val sender = smsMessage.displayOriginatingAddress ?: ""
            val messageBody = smsMessage.messageBody ?: ""

            Log.d(TAG, "")
            Log.d(TAG, "📱 SMS Details:")
            Log.d(TAG, "   Sender: $sender")
            Log.d(TAG, "   Message Length: ${messageBody.length} chars")
            Log.d(TAG, "   Message Preview: ${messageBody.take(100)}...")
            Log.d(TAG, "   Full Message: $messageBody")

            // Check if it's a payment SMS
            val isPayment = PaymentParser.isPaymentSms(sender, messageBody)
            Log.d(TAG, "")
            Log.d(TAG, "🔍 Payment SMS Check Result: $isPayment")

            if (isPayment) {
                Log.d(TAG, "✅ PAYMENT SMS DETECTED!")
                Log.d(TAG, "   🚀 Launching background processing...")

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
            Log.d(TAG, "")
            Log.d(TAG, "════════════════════════════════════════════════════")
            Log.d(TAG, "💳 PROCESSING PAYMENT SMS")
            Log.d(TAG, "════════════════════════════════════════════════════")

            // Get current user (child)
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.e(TAG, "❌ CRITICAL: User not logged in, cannot process payment")
                Log.e(TAG, "   Make sure child is logged in on this device")
                return
            }

            val childUid = currentUser.uid
            Log.d(TAG, "✅ Child authenticated")
            Log.d(TAG, "   Child UID: ${childUid.take(15)}...${childUid.takeLast(5)}")
            Log.d(TAG, "   Full UID: $childUid")

            // Parse transaction
            Log.d(TAG, "")
            Log.d(TAG, "🔍 Parsing transaction details...")
            val transaction = PaymentParser.parseTransaction(sender, message, childUid)

            if (transaction == null) {
                Log.e(TAG, "❌ CRITICAL: Failed to parse transaction")
                Log.e(TAG, "   Sender: $sender")
                Log.e(TAG, "   Message: $message")
                return
            }

            Log.d(TAG, "✅ Transaction parsed successfully:")
            Log.d(TAG, "   Transaction ID: ${transaction.id}")
            Log.d(TAG, "   Amount: ₹${transaction.amount}")
            Log.d(TAG, "   Merchant: ${transaction.merchant}")
            Log.d(TAG, "   Type: ${transaction.transactionType}")
            Log.d(TAG, "   Bank: ${transaction.bankName}")
            Log.d(TAG, "   Category: ${transaction.category}")

            // Get threshold configuration
            Log.d(TAG, "")
            Log.d(TAG, "📋 Loading threshold configuration...")
            val db = FirebaseFirestore.getInstance()
            val thresholdDoc = db.collection("users")
                .document(childUid)
                .collection("paymentThresholds")
                .document("config")
                .get()
                .await()

            val threshold = if (thresholdDoc.exists()) {
                Log.d(TAG, "✅ Threshold config found in Firestore")
                PaymentThreshold.fromFirestore(thresholdDoc.data ?: emptyMap())
            } else {
                Log.w(TAG, "⚠️ No threshold config found, using defaults")
                PaymentThreshold(childUid = childUid)
            }

            Log.d(TAG, "")
            Log.d(TAG, "📊 Threshold Configuration:")
            Log.d(TAG, "   Single Transaction: ₹${threshold.singleTransactionLimit}")
            Log.d(TAG, "   Daily Limit: ₹${threshold.dailyLimit}")
            Log.d(TAG, "   Weekly Limit: ₹${threshold.weeklyLimit}")
            Log.d(TAG, "   Monthly Limit: ₹${threshold.monthlyLimit}")
            Log.d(TAG, "   Notifications Enabled: ${threshold.enableNotifications}")
            Log.d(TAG, "   Notify Every Transaction: ${threshold.notifyOnEveryTransaction}")

            // Check ALL thresholds
            Log.d(TAG, "")
            Log.d(TAG, "🔍 Checking ALL thresholds...")
            val exceedsThreshold = checkAllThresholds(db, childUid, transaction, threshold)

            Log.d(TAG, "")
            Log.d(TAG, "📊 Threshold Check Result:")
            Log.d(TAG, "   Transaction Amount: ₹${transaction.amount}")
            Log.d(TAG, "   Exceeds Any Threshold: $exceedsThreshold")

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
            Log.d(TAG, "   Path: /users/$childUid/paymentTransactions/${transaction.id}")

            // Determine if we should send notification
            val shouldNotify = threshold.enableNotifications &&
                    (exceedsThreshold || threshold.notifyOnEveryTransaction)

            Log.d(TAG, "")
            Log.d(TAG, "🔔 Notification Decision:")
            Log.d(TAG, "   Notifications Enabled: ${threshold.enableNotifications}")
            Log.d(TAG, "   Exceeds Threshold: $exceedsThreshold")
            Log.d(TAG, "   Notify Every Transaction: ${threshold.notifyOnEveryTransaction}")
            Log.d(TAG, "   ➡️ Should Notify: $shouldNotify")

            if (shouldNotify) {
                Log.d(TAG, "")
                Log.d(TAG, "════════════════════════════════════════════════════")
                Log.d(TAG, "📤 SENDING NOTIFICATION TO PARENTS")
                Log.d(TAG, "════════════════════════════════════════════════════")

                val reason = when {
                    exceedsThreshold && threshold.notifyOnEveryTransaction ->
                        "Threshold Exceeded + Every Transaction Enabled"
                    exceedsThreshold -> "Threshold Exceeded"
                    threshold.notifyOnEveryTransaction -> "Every Transaction Notification"
                    else -> "Unknown"
                }
                Log.d(TAG, "Notification Reason: $reason")

                val notificationType = if (exceedsThreshold) {
                    NotificationType.PAYMENT_THRESHOLD_EXCEEDED
                } else {
                    NotificationType.PAYMENT_TRANSACTION
                }
                Log.d(TAG, "Notification Type: ${notificationType.name}")

                // Send notification
                Log.d(TAG, "")
                Log.d(TAG, "🚀 Calling FcmNotificationSender.sendPaymentNotificationToParents()...")
                val success = FcmNotificationSender.sendPaymentNotificationToParents(
                    context = context,
                    childUid = childUid,
                    transaction = updatedTransaction,
                    notificationType = notificationType
                )

                Log.d(TAG, "")
                if (success) {
                    Log.d(TAG, "✅✅✅ PARENT NOTIFICATION SENT SUCCESSFULLY ✅✅✅")

                    // Mark as notified
                    transactionRef.update("notifiedParent", true).await()
                    Log.d(TAG, "✅ Transaction marked as notified")
                } else {
                    Log.e(TAG, "❌❌❌ FAILED TO SEND PARENT NOTIFICATION ❌❌❌")
                    Log.e(TAG, "")
                    Log.e(TAG, "🔍 TROUBLESHOOTING CHECKLIST:")
                    Log.e(TAG, "   1. Check if parents are linked to child:")
                    Log.e(TAG, "      Path: /users/$childUid/parents")
                    Log.e(TAG, "   2. Check if parent has FCM tokens:")
                    Log.e(TAG, "      Path: /users/{parentId}/fcmTokens")
                    Log.e(TAG, "   3. Check OAuth2 token generation")
                    Log.e(TAG, "   4. Check network connectivity")
                    Log.e(TAG, "   5. Check FcmNotificationSender logs for details")
                }
            } else {
                Log.d(TAG, "")
                Log.d(TAG, "ℹ️ No notification sent")
                if (!threshold.enableNotifications) {
                    Log.d(TAG, "   Reason: Notifications disabled in settings")
                } else {
                    Log.d(TAG, "   Reason: Below all thresholds and 'notify every transaction' disabled")
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "════════════════════════════════════════════════════")
            Log.d(TAG, "✅ PAYMENT SMS PROCESSING COMPLETE")
            Log.d(TAG, "════════════════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(TAG, "")
            Log.e(TAG, "════════════════════════════════════════════════════")
            Log.e(TAG, "❌❌❌ CRITICAL ERROR PROCESSING PAYMENT SMS ❌❌❌")
            Log.e(TAG, "════════════════════════════════════════════════════")
            Log.e(TAG, "Exception Type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception Message: ${e.message}")
            Log.e(TAG, "")
            Log.e(TAG, "Stack Trace:")
            e.printStackTrace()
            Log.e(TAG, "════════════════════════════════════════════════════")
        }
    }

    /**
     * Check if transaction exceeds ANY threshold (single, daily, weekly, monthly)
     */
    private suspend fun checkAllThresholds(
        db: FirebaseFirestore,
        childUid: String,
        transaction: com.example.child_safety_app_version1.data.PaymentTransaction,
        threshold: PaymentThreshold
    ): Boolean {
        try {
            Log.d(TAG, "")
            Log.d(TAG, "   🔍 Checking thresholds...")

            // 1. Check single transaction limit
            Log.d(TAG, "   1️⃣ Single Transaction Check:")
            Log.d(TAG, "      Amount: ₹${transaction.amount}")
            Log.d(TAG, "      Limit:  ₹${threshold.singleTransactionLimit}")
            if (transaction.amount > threshold.singleTransactionLimit) {
                Log.d(TAG, "      ❌ EXCEEDS single transaction limit!")
                return true
            }
            Log.d(TAG, "      ✅ OK")

            // Get today's start timestamp
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val todayStart = calendar.timeInMillis

            // 2. Check daily limit
            Log.d(TAG, "")
            Log.d(TAG, "   2️⃣ Daily Limit Check:")
            Log.d(TAG, "      Today Start: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(todayStart)}")

            val dailySnapshot = db.collection("users")
                .document(childUid)
                .collection("paymentTransactions")
                .whereGreaterThanOrEqualTo("timestamp", todayStart)
                .get()
                .await()

            val dailyTotal = dailySnapshot.documents.sumOf {
                (it.getDouble("amount") ?: 0.0)
            } + transaction.amount

            Log.d(TAG, "      Existing today: ₹${dailyTotal - transaction.amount}")
            Log.d(TAG, "      This transaction: ₹${transaction.amount}")
            Log.d(TAG, "      New total: ₹$dailyTotal")
            Log.d(TAG, "      Limit: ₹${threshold.dailyLimit}")

            if (dailyTotal > threshold.dailyLimit) {
                Log.d(TAG, "      ❌ EXCEEDS daily limit!")
                return true
            }
            Log.d(TAG, "      ✅ OK")

            // Get this week's start timestamp
            calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val weekStart = calendar.timeInMillis

            // 3. Check weekly limit
            Log.d(TAG, "")
            Log.d(TAG, "   3️⃣ Weekly Limit Check:")
            Log.d(TAG, "      Week Start: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(weekStart)}")

            val weeklySnapshot = db.collection("users")
                .document(childUid)
                .collection("paymentTransactions")
                .whereGreaterThanOrEqualTo("timestamp", weekStart)
                .get()
                .await()

            val weeklyTotal = weeklySnapshot.documents.sumOf {
                (it.getDouble("amount") ?: 0.0)
            } + transaction.amount

            Log.d(TAG, "      Existing this week: ₹${weeklyTotal - transaction.amount}")
            Log.d(TAG, "      This transaction: ₹${transaction.amount}")
            Log.d(TAG, "      New total: ₹$weeklyTotal")
            Log.d(TAG, "      Limit: ₹${threshold.weeklyLimit}")

            if (weeklyTotal > threshold.weeklyLimit) {
                Log.d(TAG, "      ❌ EXCEEDS weekly limit!")
                return true
            }
            Log.d(TAG, "      ✅ OK")

            // Get this month's start timestamp
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val monthStart = calendar.timeInMillis

            // 4. Check monthly limit
            Log.d(TAG, "")
            Log.d(TAG, "   4️⃣ Monthly Limit Check:")
            Log.d(TAG, "      Month Start: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(monthStart)}")

            val monthlySnapshot = db.collection("users")
                .document(childUid)
                .collection("paymentTransactions")
                .whereGreaterThanOrEqualTo("timestamp", monthStart)
                .get()
                .await()

            val monthlyTotal = monthlySnapshot.documents.sumOf {
                (it.getDouble("amount") ?: 0.0)
            } + transaction.amount

            Log.d(TAG, "      Existing this month: ₹${monthlyTotal - transaction.amount}")
            Log.d(TAG, "      This transaction: ₹${transaction.amount}")
            Log.d(TAG, "      New total: ₹$monthlyTotal")
            Log.d(TAG, "      Limit: ₹${threshold.monthlyLimit}")

            if (monthlyTotal > threshold.monthlyLimit) {
                Log.d(TAG, "      ❌ EXCEEDS monthly limit!")
                return true
            }
            Log.d(TAG, "      ✅ OK")

            Log.d(TAG, "")
            Log.d(TAG, "   ✅ ALL THRESHOLDS PASSED")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error checking thresholds", e)
            e.printStackTrace()
            return false
        }
    }
}