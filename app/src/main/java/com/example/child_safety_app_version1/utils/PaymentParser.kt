package com.example.child_safety_app_version1.utils

import android.util.Log
import com.example.child_safety_app_version1.data.PaymentTransaction
import com.example.child_safety_app_version1.data.TransactionCategory
import com.example.child_safety_app_version1.data.TransactionType
import java.util.UUID

object PaymentParser {
    private const val TAG = "PaymentParser"

    // List of known bank/payment sender IDs in India
    private val KNOWN_SENDERS = listOf(
        // Banks
        "HDFCBK", "ICICIB", "SBIIN", "AXISNB", "KOTAKB", "PNBSMS", "BOISMS",
        // UPI Apps
        "GOOGLEPAY", "PAYTM", "PHONEPE", "BHIM", "AMAZONPAY",
        // Wallets
        "PAYTMW", "MOBIKWIK", "FREECHARGE",
        // Credit Cards
        "HDFC", "ICICI", "SBI", "AXIS", "AMEX"
    )

    // Keywords for identifying transactions
    private val DEBIT_KEYWORDS = listOf(
        "debited", "debit", "spent", "purchase", "paid", "payment", "withdrawn"
    )

    private val CREDIT_KEYWORDS = listOf(
        "credited", "credit", "received", "refund", "cashback"
    )

    private val UPI_KEYWORDS = listOf(
        "upi", "vpa", "@paytm", "@okaxis", "@ybl", "@oksbi", "@okicici"
    )

    // 🆕 Enhanced currency patterns
    private val CURRENCY_PATTERNS = listOf(
        "₹", "rs.", "rs ", "inr", "rupees", "rupee"
    )

    /**
     * 🔧 FIXED: More flexible payment SMS detection
     * Now accepts if ANY 2 of 3 conditions are met:
     * 1. Known sender OR phone number
     * 2. Payment keyword (debited/credited)
     * 3. Currency indicator OR amount pattern
     */
    fun isPaymentSms(sender: String, message: String): Boolean {
        val normalizedSender = sender.uppercase().replace("-", "")
        val normalizedMessage = message.uppercase()

        Log.d(TAG, "🔍 Enhanced Payment SMS Check:")
        Log.d(TAG, "   Sender: $sender")
        Log.d(TAG, "   Normalized Sender: $normalizedSender")

        // Condition 1: Known sender OR looks like phone number
        val isSenderKnown = KNOWN_SENDERS.any { normalizedSender.contains(it) }
        val isPhoneNumber = sender.matches(Regex("^[+]?[0-9]{10,15}$"))
        val condition1 = isSenderKnown || isPhoneNumber

        Log.d(TAG, "   ✓ Condition 1 (Sender): $condition1")
        Log.d(TAG, "      - Known Sender: $isSenderKnown")
        Log.d(TAG, "      - Phone Number: $isPhoneNumber")

        // Condition 2: Has payment keyword
        val condition2 = (DEBIT_KEYWORDS + CREDIT_KEYWORDS + UPI_KEYWORDS)
            .any { normalizedMessage.contains(it.uppercase()) }

        Log.d(TAG, "   ✓ Condition 2 (Payment Keyword): $condition2")

        // Condition 3: Has currency OR amount pattern
        val hasCurrency = CURRENCY_PATTERNS.any { normalizedMessage.contains(it.uppercase()) }
        val hasAmountPattern = normalizedMessage.contains(Regex("\\d{1,3}(,\\d{3})*(\\.\\d{2})?")) ||
                normalizedMessage.contains(Regex("\\d+[,.]?\\d*"))

        val condition3 = hasCurrency || hasAmountPattern

        Log.d(TAG, "   ✓ Condition 3 (Currency/Amount): $condition3")
        Log.d(TAG, "      - Has Currency: $hasCurrency")
        Log.d(TAG, "      - Has Amount Pattern: $hasAmountPattern")

        // Count how many conditions are met
        val conditionsMet = listOf(condition1, condition2, condition3).count { it }
        val isPaymentSms = conditionsMet >= 2

        Log.d(TAG, "   📊 Conditions Met: $conditionsMet/3")
        Log.d(TAG, "   ✅ Is Payment SMS: $isPaymentSms")

        return isPaymentSms
    }

    /**
     * Parse SMS message to extract transaction details
     */
    fun parseTransaction(sender: String, message: String, childUid: String): PaymentTransaction? {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "PARSING PAYMENT SMS")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "Sender: $sender")
            Log.d(TAG, "Message: $message")
            Log.d(TAG, "Child UID: ${childUid.take(10)}...")

            // Extract amount
            val amount = extractAmount(message)
            if (amount == null || amount <= 0) {
                Log.e(TAG, "❌ Failed to extract valid amount")
                return null
            }
            Log.d(TAG, "✅ Amount extracted: ₹$amount")

            // Determine transaction type
            val transactionType = determineTransactionType(message)
            Log.d(TAG, "✅ Transaction Type: $transactionType")

            // Extract merchant name
            val merchant = extractMerchant(message)
            Log.d(TAG, "✅ Merchant: $merchant")

            // Extract bank name
            val bankName = extractBankName(sender, message)
            Log.d(TAG, "✅ Bank: $bankName")

            // Extract card last 4 digits
            val cardLast4 = extractCardLast4(message)
            Log.d(TAG, "✅ Card Last 4: $cardLast4")

            // Determine category
            val category = categorizeTransaction(merchant, message)
            Log.d(TAG, "✅ Category: $category")

            val transaction = PaymentTransaction(
                id = UUID.randomUUID().toString(),
                amount = amount,
                merchant = merchant,
                timestamp = System.currentTimeMillis(),
                transactionType = transactionType,
                bankName = bankName,
                cardLast4Digits = cardLast4,
                category = category,
                childUid = childUid,
                notifiedParent = false,
                exceedsThreshold = false,
                rawSmsText = message
            )

            Log.d(TAG, "✅ Transaction parsed successfully")
            Log.d(TAG, "════════════════════════════════════════")

            return transaction

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing transaction", e)
            e.printStackTrace()
            return null
        }
    }

    /**
     * 🔧 ENHANCED: Extract amount with support for "rupees" text
     */
    private fun extractAmount(message: String): Double? {
        try {
            // Pattern 1: ₹1,234.56 or Rs.1,234.56 or Rs 1234.56
            val pattern1 = Regex("""[₹Rs.]+\s?([0-9,]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
            val match1 = pattern1.find(message)
            if (match1 != null) {
                val amountStr = match1.groupValues[1].replace(",", "")
                Log.d(TAG, "   Amount Pattern 1 matched: $amountStr")
                return amountStr.toDoubleOrNull()
            }

            // Pattern 2: INR 1234.56 or INR 1,234.56
            val pattern2 = Regex("""INR\s?([0-9,]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
            val match2 = pattern2.find(message)
            if (match2 != null) {
                val amountStr = match2.groupValues[1].replace(",", "")
                Log.d(TAG, "   Amount Pattern 2 matched: $amountStr")
                return amountStr.toDoubleOrNull()
            }

            // Pattern 3: of Rs 1234 or of Rs. 1234
            val pattern3 = Regex("""of\s+Rs\.?\s?([0-9,]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
            val match3 = pattern3.find(message)
            if (match3 != null) {
                val amountStr = match3.groupValues[1].replace(",", "")
                Log.d(TAG, "   Amount Pattern 3 matched: $amountStr")
                return amountStr.toDoubleOrNull()
            }

            // 🆕 Pattern 4: "rupees 50,000" or "rupees 50000"
            val pattern4 = Regex("""rupees?\s+([0-9,]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
            val match4 = pattern4.find(message)
            if (match4 != null) {
                val amountStr = match4.groupValues[1].replace(",", "")
                Log.d(TAG, "   Amount Pattern 4 matched (rupees): $amountStr")
                return amountStr.toDoubleOrNull()
            }

            // 🆕 Pattern 5: "with 50,000" (fallback for simple messages)
            val pattern5 = Regex("""with\s+([0-9,]+\.?[0-9]*)""", RegexOption.IGNORE_CASE)
            val match5 = pattern5.find(message)
            if (match5 != null) {
                val amountStr = match5.groupValues[1].replace(",", "")
                Log.d(TAG, "   Amount Pattern 5 matched (with): $amountStr")
                return amountStr.toDoubleOrNull()
            }

            // 🆕 Pattern 6: Just find any number with comma/decimal
            val pattern6 = Regex("""([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{2})?)""")
            val match6 = pattern6.find(message)
            if (match6 != null) {
                val amountStr = match6.groupValues[1].replace(",", "")
                Log.d(TAG, "   Amount Pattern 6 matched (number): $amountStr")
                return amountStr.toDoubleOrNull()
            }

            Log.w(TAG, "⚠️ No amount pattern matched")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting amount", e)
            return null
        }
    }

    /**
     * Determine transaction type from message
     */
    private fun determineTransactionType(message: String): TransactionType {
        val normalizedMessage = message.uppercase()

        return when {
            UPI_KEYWORDS.any { normalizedMessage.contains(it.uppercase()) } -> TransactionType.UPI
            normalizedMessage.contains("CARD") -> TransactionType.CARD
            normalizedMessage.contains("NET BANKING") || normalizedMessage.contains("NETBANKING") -> TransactionType.NET_BANKING
            normalizedMessage.contains("WALLET") -> TransactionType.WALLET
            CREDIT_KEYWORDS.any { normalizedMessage.contains(it.uppercase()) } -> TransactionType.CREDIT
            else -> TransactionType.DEBIT
        }
    }

    /**
     * Extract merchant name from message
     */
    private fun extractMerchant(message: String): String {
        try {
            // Pattern 1: "at MERCHANT_NAME"
            val pattern1 = Regex("""at\s+([A-Z][A-Z0-9\s&-]+?)(?:\s+on|\s+with|\s+using|$)""")
            val match1 = pattern1.find(message)
            if (match1 != null) {
                return match1.groupValues[1].trim()
            }

            // Pattern 2: "to MERCHANT_NAME"
            val pattern2 = Regex("""to\s+([A-Z][A-Z0-9\s&-]+?)(?:\s+on|\s+with|\s+using|$)""")
            val match2 = pattern2.find(message)
            if (match2 != null) {
                return match2.groupValues[1].trim()
            }

            // Pattern 3: "VPA: someone@bank" (for UPI)
            val pattern3 = Regex("""(?:VPA|UPI ID):\s?([a-zA-Z0-9._]+@[a-zA-Z]+)""")
            val match3 = pattern3.find(message)
            if (match3 != null) {
                return match3.groupValues[1].trim()
            }

            return "Unknown Merchant"

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting merchant", e)
            return "Unknown Merchant"
        }
    }

    /**
     * Extract bank name from sender or message
     */
    private fun extractBankName(sender: String, message: String): String {
        val normalizedSender = sender.uppercase()

        return when {
            normalizedSender.contains("HDFC") -> "HDFC Bank"
            normalizedSender.contains("ICICI") -> "ICICI Bank"
            normalizedSender.contains("SBI") -> "SBI"
            normalizedSender.contains("AXIS") -> "Axis Bank"
            normalizedSender.contains("KOTAK") -> "Kotak Bank"
            normalizedSender.contains("PNB") -> "Punjab National Bank"
            normalizedSender.contains("BOI") -> "Bank of India"
            normalizedSender.contains("PAYTM") -> "Paytm"
            normalizedSender.contains("PHONEPE") -> "PhonePe"
            normalizedSender.contains("GOOGLEPAY") -> "Google Pay"
            message.uppercase().contains("HDFC") -> "HDFC Bank"
            message.uppercase().contains("ICICI") -> "ICICI Bank"
            message.uppercase().contains("SBI") -> "SBI"
            else -> "Unknown Bank"
        }
    }

    /**
     * Extract last 4 digits of card from message
     */
    private fun extractCardLast4(message: String): String {
        try {
            // Pattern: Card ending with 1234 or Card XXXX1234
            val pattern = Regex("""(?:ending|ending with|card|XXXX)[\s*]*(\d{4})""")
            val match = pattern.find(message)
            return match?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * Categorize transaction based on merchant name and message content
     */
    private fun categorizeTransaction(merchant: String, message: String): TransactionCategory {
        val combined = (merchant + " " + message).uppercase()

        return when {
            combined.contains("SWIGGY") || combined.contains("ZOMATO") ||
                    combined.contains("FOOD") || combined.contains("RESTAURANT") ||
                    combined.contains("CAFE") -> TransactionCategory.FOOD

            combined.contains("AMAZON") || combined.contains("FLIPKART") ||
                    combined.contains("MYNTRA") || combined.contains("SHOPPING") ||
                    combined.contains("STORE") -> TransactionCategory.SHOPPING

            combined.contains("NETFLIX") || combined.contains("HOTSTAR") ||
                    combined.contains("PRIME") || combined.contains("SPOTIFY") ||
                    combined.contains("YOUTUBE") || combined.contains("MOVIE") ||
                    combined.contains("THEATRE") || combined.contains("CINEMA") -> TransactionCategory.ENTERTAINMENT

            combined.contains("SCHOOL") || combined.contains("COLLEGE") ||
                    combined.contains("EDUCATION") || combined.contains("COURSE") ||
                    combined.contains("TUITION") || combined.contains("UDEMY") ||
                    combined.contains("COURSERA") -> TransactionCategory.EDUCATION

            combined.contains("UBER") || combined.contains("OLA") ||
                    combined.contains("RAPIDO") || combined.contains("METRO") ||
                    combined.contains("TRANSPORT") || combined.contains("FUEL") ||
                    combined.contains("PETROL") -> TransactionCategory.TRANSPORTATION

            combined.contains("STEAM") || combined.contains("PLAYSTATION") ||
                    combined.contains("XBOX") || combined.contains("GAME") ||
                    combined.contains("PUBG") || combined.contains("FREEFIRE") -> TransactionCategory.GAMING

            combined.contains("SUBSCRIPTION") || combined.contains("MONTHLY") ||
                    combined.contains("ANNUAL") || combined.contains("PLAN") -> TransactionCategory.SUBSCRIPTION

            else -> TransactionCategory.OTHER
        }
    }
}