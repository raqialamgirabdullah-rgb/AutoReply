package com.rahcare.autoreply

import android.content.ComponentName
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReplyService : NotificationListenerService() {
    companion object {
        // সিস্টেম সত্যিই লিসেনার জুড়ে দিয়েছে কি না (মূল পাতায় আসল অবস্থা দেখানোর জন্য)
        @Volatile var connected = false
    }

    override fun onCreate() {
        super.onCreate()
        Store.ev(applicationContext, "ReplyService তৈরি (প্রসেস চালু)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = applicationContext
        if (Store.family(sbn.packageName) != null) {
            val ra = sbn.notification.actions?.any { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } == true
            val pm = getSystemService(PowerManager::class.java)
            val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
            Store.ev(c, "নোটিফিকেশন এসেছে: ${sbn.packageName} reply-বাটন=$ra তৈরি=${tf.format(Date(sbn.postTime))} এখন=${tf.format(Date())} স্ক্রিন-চালু=${pm.isInteractive} ডজ=${pm.isDeviceIdleMode}")
        }
        // উত্তর পাঠানোর সময় সিপিইউ যেন ঘুমিয়ে না পড়ে
        var wl: PowerManager.WakeLock? = null
        try {
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreply:reply")
            wl.acquire(15000L)
        } catch (e: Exception) {}
        try {
            Replier.handle(c, sbn.packageName, sbn.userId, sbn.key, sbn.postTime, sbn.notification, false, false)
        } catch (e: Exception) {
            Store.ev(c, "handle ব্যর্থ: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try { if (wl != null && wl.isHeld) wl.release() } catch (e: Exception) {}
        }
    }

    // সিস্টেম সংযোগ কেটে দিলে নিজে থেকে আবার সংযোগ চায়
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        Store.ev(applicationContext, "লিসেনার বিচ্ছিন্ন হয়েছে")
        try { requestRebind(ComponentName(this, ReplyService::class.java)) } catch (e: Exception) {}
    }

    // সংযোগ ফিরলে (ফোন ঘুম থেকে উঠলে/অ্যাপ আবার চালু হলে) এর মধ্যে আসা মেসেজগুলোতে উত্তর দেয়
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        val c = applicationContext
        Store.ev(c, "লিসেনার সংযুক্ত হয়েছে")
        // সিস্টেম লিসেনার জুড়লেই কিপ-অ্যালাইভ সার্ভিস ও ওয়াচডগও চালু (অ্যাপ খুলতে হবে না)
        Watchdog.schedule(c)
        if (Store.enabled(c)) KeepAliveService.start(c)
        try {
            val first = !Store.catchInit(c)
            activeNotifications?.forEach { Replier.handle(c, it.packageName, it.userId, it.key, it.postTime, it.notification, true, first) }
            if (first) Store.setCatchInit(c)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        connected = false
        Store.ev(applicationContext, "ReplyService ধ্বংস হয়েছে")
        super.onDestroy()
    }
}
