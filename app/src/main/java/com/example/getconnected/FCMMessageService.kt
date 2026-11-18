package com.example.getconnected

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMMessageService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Save the token to Firestore
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null && token.isNotBlank()) {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .update("fcmTokens", com.google.firebase.firestore.FieldValue.arrayUnion(token))
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Handle the message
        remoteMessage.notification?.let {
            showNotification(it.title, it.body)
        }

        // If message contains data, handle it
        remoteMessage.data.isNotEmpty().let {
            // Handle data message
        }
    }

    private fun showNotification(title: String?, body: String?) {
        val channelId = "chat_notifications"
        val channelName = "Chat Notifications"
        val importance = NotificationManager.IMPORTANCE_HIGH

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, importance)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((System.currentTimeMillis() / 1000).toInt(), notification)
    }
}