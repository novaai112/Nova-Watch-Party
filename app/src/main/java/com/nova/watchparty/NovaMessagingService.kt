package com.nova.watchparty

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives push messages (room invites). Needs google-services.json, see API_KEYS_GUIDE.md. */
class NovaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("nova", MODE_PRIVATE).edit().putString("fcm", token).putBoolean("fcmSent", false).apply()
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val code = m.data["code"] ?: return
        val i = Intent(this, MainActivity::class.java).putExtra("code", code).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, code.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "nova_invites").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(m.notification?.title ?: "Nova invite").setContentText(m.notification?.body ?: "Tap to join")
            .setAutoCancel(true).setContentIntent(pi).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(code.hashCode(), n) } catch (_: SecurityException) {}
    }
}
