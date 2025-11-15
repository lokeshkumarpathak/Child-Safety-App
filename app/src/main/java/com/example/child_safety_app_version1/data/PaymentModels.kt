package com.example.child_safety_app_version1.data

/**
 * Data class representing a payment/transaction
 */
data class PaymentTransaction(
    val id: String = "",
    val amount: Double = 0.0,
    val merchant: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val transactionType: TransactionType = TransactionType.DEBIT,
    val bankName: String = "",
    val cardLast4Digits: String = "",
    val category: TransactionCategory = TransactionCategory.OTHER,
    val childUid: String = "",
    val notifiedParent: Boolean = false,
    val exceedsThreshold: Boolean = false,
    val rawSmsText: String = ""
) {
    /**
     * Converts to Firestore-compatible map
     */
    fun toFirestoreMap(): HashMap<String, Any> {
        return hashMapOf(
            "id" to id,
            "amount" to amount,
            "merchant" to merchant,
            "timestamp" to timestamp,
            "transactionType" to transactionType.name,
            "bankName" to bankName,
            "cardLast4Digits" to cardLast4Digits,
            "category" to category.name,
            "childUid" to childUid,
            "notifiedParent" to notifiedParent,
            "exceedsThreshold" to exceedsThreshold,
            "rawSmsText" to rawSmsText
        )
    }

    companion object {
        fun fromFirestore(id: String, data: Map<String, Any>): PaymentTransaction {
            return PaymentTransaction(
                id = id,
                amount = (data["amount"] as? Number)?.toDouble() ?: 0.0,
                merchant = data["merchant"] as? String ?: "",
                timestamp = (data["timestamp"] as? Number)?.toLong() ?: 0L,
                transactionType = TransactionType.valueOf(
                    data["transactionType"] as? String ?: "DEBIT"
                ),
                bankName = data["bankName"] as? String ?: "",
                cardLast4Digits = data["cardLast4Digits"] as? String ?: "",
                category = TransactionCategory.valueOf(
                    data["category"] as? String ?: "OTHER"
                ),
                childUid = data["childUid"] as? String ?: "",
                notifiedParent = data["notifiedParent"] as? Boolean ?: false,
                exceedsThreshold = data["exceedsThreshold"] as? Boolean ?: false,
                rawSmsText = data["rawSmsText"] as? String ?: ""
            )
        }
    }
}

/**
 * Enum for transaction types
 */
enum class TransactionType {
    DEBIT,
    CREDIT,
    UPI,
    CARD,
    NET_BANKING,
    WALLET
}

/**
 * Enum for transaction categories
 */
enum class TransactionCategory {
    FOOD,
    SHOPPING,
    ENTERTAINMENT,
    EDUCATION,
    TRANSPORTATION,
    GAMING,
    SUBSCRIPTION,
    OTHER
}

/**
 * Data class for payment threshold configuration
 */
data class PaymentThreshold(
    val childUid: String = "",
    val singleTransactionLimit: Double = 500.0,
    val dailyLimit: Double = 2000.0,
    val weeklyLimit: Double = 5000.0,
    val monthlyLimit: Double = 10000.0,
    val enableNotifications: Boolean = true,
    val notifyOnEveryTransaction: Boolean = false,
    val blockedCategories: List<TransactionCategory> = emptyList()
) {
    fun toFirestoreMap(): HashMap<String, Any> {
        return hashMapOf(
            "childUid" to childUid,
            "singleTransactionLimit" to singleTransactionLimit,
            "dailyLimit" to dailyLimit,
            "weeklyLimit" to weeklyLimit,
            "monthlyLimit" to monthlyLimit,
            "enableNotifications" to enableNotifications,
            "notifyOnEveryTransaction" to notifyOnEveryTransaction,
            "blockedCategories" to blockedCategories.map { it.name }
        )
    }

    companion object {
        fun fromFirestore(data: Map<String, Any>): PaymentThreshold {
            return PaymentThreshold(
                childUid = data["childUid"] as? String ?: "",
                singleTransactionLimit = (data["singleTransactionLimit"] as? Number)?.toDouble() ?: 500.0,
                dailyLimit = (data["dailyLimit"] as? Number)?.toDouble() ?: 2000.0,
                weeklyLimit = (data["weeklyLimit"] as? Number)?.toDouble() ?: 5000.0,
                monthlyLimit = (data["monthlyLimit"] as? Number)?.toDouble() ?: 10000.0,
                enableNotifications = data["enableNotifications"] as? Boolean ?: true,
                notifyOnEveryTransaction = data["notifyOnEveryTransaction"] as? Boolean ?: false,
                blockedCategories = (data["blockedCategories"] as? List<*>)?.mapNotNull {
                    try {
                        TransactionCategory.valueOf(it as String)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
            )
        }
    }
}