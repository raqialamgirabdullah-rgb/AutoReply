package com.rahcare.autoreply

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService

// প্রতি ~১০ মিনিটে নিজে জেগে দেখে সার্ভিসগুলো বেঁচে আছে কি না; মরে গেলে তুলে আনে, আর নতুন আপডেট খোঁজে।
// অ্যালার্ম অ্যাপের প্রসেস মরে গেলেও টিকে থাকে, তাই এটাই শেষ ভরসা।
object Watchdog {
    private const val EVERY = 10 * 60 * 1000L

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 11, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, delay: Long = EVERY) {
        try {
            c.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pi(c))
        } catch (e: Exception) {}
    }

    fun granted(c: Context) =
        (Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: "").contains(c.packageName)

    // নোটিফিকেশন লিসেনারকে আবার সংযুক্ত হতে বলে; force হলে আটকে থাকা লিসেনারকে বন্ধ-চালু করে ঠেলে দেয়
    fun rebind(c: Context, force: Boolean) {
        if (!granted(c)) return
        val cn = ComponentName(c, ReplyService::class.java)
        try { NotificationListenerService.requestRebind(cn) } catch (e: Exception) {}
        if (force && !ReplyService.connected) {
            try {
                val pm = c.packageManager
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {}
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Watchdog.schedule(c)
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        Store.ev(c, "ওয়াচডগ জেগেছে: সংযুক্ত=${ReplyService.connected} স্ক্রিন-চালু=${pm.isInteractive} ডজ=${pm.isDeviceIdleMode}")
        if (Store.enabled(c)) {
            KeepAliveService.start(c)
            Watchdog.rebind(c, true)
        }
        val pr = goAsync()
        val app = c.applicationContext
        Thread {
            try { Updater.backgroundCheck(app, 20 * 60 * 1000L) } catch (e: Exception) {} finally { pr.finish() }
        }.start()
    }
}
