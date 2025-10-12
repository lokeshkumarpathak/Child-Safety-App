package com.example.child_safety_app_version1.utils

import android.R.attr.name
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * 100% FREE Email OTP (No limits!)
 * Uses Gmail SMTP - completely free forever
 */


object EmailOtpHelper {

    // TODO: Create a Gmail account for your app and enable "App Password"
    // Steps: Google Account -> Security -> 2-Step Verification -> App Passwords
    private const val SENDER_EMAIL = "pathaklokesh9@gmail.com"
    private const val SENDER_PASSWORD = "mhre kmhs pjhv ixgn" // Use App Password, not regular password
    private const val SENDER_NAME = "Child Safety App"

    /**
     * Send OTP via Email (100% FREE)
     */
    suspend fun sendOtpEmail(
        toEmail: String,
        contactName: String,
        otp: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(SENDER_EMAIL, SENDER_PASSWORD)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(SENDER_EMAIL, SENDER_NAME))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                subject = "Emergency Contact Verification Code"

                // HTML email with better styling
                setContent("""
                    <html>
                    <body style="font-family: Arial, sans-serif; padding: 20px;">
                        <div style="max-width: 600px; margin: 0 auto; background-color: #f5f5f5; padding: 30px; border-radius: 10px;">
                            <h2 style="color: #2196F3;">Emergency Contact Verification</h2>
                            <p>Hello $contactName,</p>
                            <p>You've been added as an emergency contact in the Child Safety App.</p>
                            <p>Your verification code is:</p>
                            <div style="background-color: #2196F3; color: white; padding: 20px; text-align: center; font-size: 32px; font-weight: bold; border-radius: 5px; margin: 20px 0;">
                                $otp
                            </div>
                            <p style="color: #666;">This code will expire in 5 minutes.</p>
                            <p style="color: #666; font-size: 12px;">If you didn't request this, please ignore this email.</p>
                            <hr style="margin: 30px 0; border: none; border-top: 1px solid #ddd;">
                            <p style="color: #999; font-size: 12px;">
                                This is an automated email from Child Safety App. Please do not reply.
                            </p>
                        </div>
                    </body>
                    </html>
                """.trimIndent(), "text/html; charset=utf-8")
            }

            Transport.send(message)

            withContext(Dispatchers.Main) {
                onSuccess()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onFailure("Failed to send email: ${e.message}")
            }
        }
    }

    fun generateOtp(): String {
        return (100000..999999).random().toString()
    }
}