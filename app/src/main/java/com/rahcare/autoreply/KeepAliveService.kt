package com.rahcare.autoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// ব্যাকগ্রাউন্ডে ফোন যেন অ্যাপটাকে ঘুম পাড়িয়ে/বন্ধ করে না দেয়, তাই একটা নীরব স্থায়ী নোটিফিকেশনসহ চালু থাকে
class KeepAliveService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("keep", "AutoReply চালু আছে", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "keep")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("AutoReply চালু আছে")
            .setContentText("মেসেজের অপেক্ষায় — ব্যাকগ্রাউন্ডে কাজ করছে")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
        Watchdog.schedule(this)
        return START_STICKY
    }

    // রিসেন্টস থেকে সোয়াইপ করে সরালে বা ফোন সার্ভিস মেরে দিলে অ্যালার্ম দিয়ে আবার তোলা হয়
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Store.enabled(this)) Watchdog.schedule(this, 3000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Store.enabled(this)) Watchdog.schedule(this, 5000L)
    }

    companion object {
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (e: Exception) {}
        }
        fun stop(c: Context) {
            try { c.stopService(Intent(c, KeepAliveService::class.java)) } catch (e: Exception) {}
        }
    }
}
