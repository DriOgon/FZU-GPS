package com.driogon.fzucheckin.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.driogon.fzucheckin.MainActivity
import com.driogon.fzucheckin.R

class ResultNotifier(private val context: Context) {
    fun show(task: AutoTask, text: String, attention: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (attention) "auto_attention" else "auto_result"
        manager.createNotificationChannel(NotificationChannel(channel, if (attention) "签到处理提示" else "签到结果",
            if (attention) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("福大签到助手").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification) } catch (_: SecurityException) { }
    }
}
