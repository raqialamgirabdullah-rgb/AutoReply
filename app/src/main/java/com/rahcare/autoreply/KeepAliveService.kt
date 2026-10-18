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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

// ব্যাকগ্রাউন্ডে ফোন যেন অ্যাপটাকে ঘুম পাড়িয়ে/বন্ধ করে না দেয়, তাই একটা নীরব স্থায়ী নোটিফিকেশনসহ চালু থাকে
class KeepAliveService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    private val h = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var wl: PowerManager.WakeLock? = null

    // প্রতি ১০ সেকেন্ডে টিক; দুই টিকের মাঝে ৪০ সেকেন্ডের বেশি ফাঁক পড়লে বোঝা যায় ফোন প্রসেসটাকে ঘুম পাড়িয়েছিল
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (lastTick != 0L && now - lastTick > 15000L) {
                Store.ev(this@KeepAliveService, "প্রসেস ${(now - lastTick) / 1000} সেকেন্ড থেমে/ঘুমিয়ে ছিল (স্ক্রিন-চালু=${getSystemService(PowerManager::class.java).isInteractive})")
            }
            lastTick = now
            h.postDelayed(this, 10000L)
        }
    }

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
        Store.ev(this, "KeepAlive চালু আছে (শক্তিশালী মোড=${Store.strong(this)})")
        h.removeCallbacks(tick)
        lastTick = System.currentTimeMillis()
        h.postDelayed(tick, 10000L)
        try {
            if (Store.strong(this)) {
                if (wl == null) wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreply:keep")
                if (wl?.isHeld != true) wl?.acquire()
            } else if (wl?.isHeld == true) wl?.release()
        } catch (e: Exception) {}
        return START_STICKY
    }

    // রিসেন্টস থেকে সোয়াইপ করে সরালে বা ফোন সার্ভিস মেরে দিলে অ্যালার্ম দিয়ে আবার তোলা হয়
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Store.ev(this, "অ্যাপ রিসেন্টস থেকে সরানো হয়েছে")
        if (Store.enabled(this)) Watchdog.schedule(this, 3000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacks(tick)
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        Store.ev(this, "KeepAlive বন্ধ হয়েছে")
        if (Store.enabled(this)) Watchdog.schedule(this, 5000L)
    }

    companion object {
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (e: Exception) {
                Store.ev(c, "KeepAlive চালু করা ব্যর্থ: ${e.javaClass.simpleName}")
            }
        }
        fun stop(c: Context) {
            try { c.stopService(Intent(c, KeepAliveService::class.java)) } catch (e: Exception) {}
        }
    }
}
