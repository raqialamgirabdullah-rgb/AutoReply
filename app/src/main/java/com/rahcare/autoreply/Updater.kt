package com.rahcare.autoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL

// GitHub-এ নতুন রিলিজ এলে নিজে থেকে ধরে: অ্যাপ খোলা থাকলে ব্যানার, বন্ধ থাকলে নোটিফিকেশন
object Updater {
    const val REPO = "raqialamgirabdullah-rgb/AutoReply"

    class Info(val v: Int, val url: String)

    fun curVer(c: Context): Int {
        @Suppress("DEPRECATION")
        return c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }

    // "latest release" লিংক কোন ট্যাগে যায় সেটা দেখে (API নয়, তাই রেট লিমিট নেই); ট্যাগের শেষ সংখ্যাই সংস্করণ নম্বর (v1.0.N)
    private fun fetch(): Info {
        val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            cn.instanceFollowRedirects = false
            cn.connectTimeout = 10000
            cn.readTimeout = 10000
            val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
            val v = tag.substringAfterLast('.').toInt()
            return Info(v, "https://github.com/$REPO/releases/download/$tag/AutoReply.apk")
        } finally {
            cn.disconnect()
        }
    }

    // শেষবার জানা নতুন সংস্করণ (বর্তমানের চেয়ে বড় হলে)
    fun saved(c: Context): Info? {
        val sp = Store.sp(c)
        val v = sp.getInt("upd_v", 0)
        val u = sp.getString("upd_u", null)
        return if (u != null && v > curVer(c)) Info(v, u) else null
    }

    // নেটওয়ার্কে চেক করে (ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে); ব্যর্থ হলে আগের জানা অবস্থাই ফেরত দেয়
    fun checkNow(c: Context): Info? {
        try {
            val i = fetch()
            Store.sp(c).edit().putInt("upd_v", i.v).putString("upd_u", i.url).putLong("upd_t", System.currentTimeMillis()).apply()
        } catch (e: Exception) {}
        return saved(c)
    }

    // ব্যাকগ্রাউন্ড চেক: নতুন সংস্করণ পেলে একবার নোটিফিকেশন দেয়
    fun checkAndNotify(c: Context, minGapMs: Long) {
        val sp = Store.sp(c)
        if (System.currentTimeMillis() - sp.getLong("upd_t", 0L) < minGapMs) return
        val i = checkNow(c) ?: return
        if (sp.getInt("upd_n", 0) == i.v) return
        sp.edit().putInt("upd_n", i.v).apply()
        notice(c)
    }

    private fun notice(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("update", "AutoReply আপডেট", NotificationManager.IMPORTANCE_DEFAULT))
            val go = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("upd", true)
            val pi = PendingIntent.getActivity(c, 8, go, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("AutoReply-এর নতুন সংস্করণ এসেছে")
                .setContentText("চাপুন — আপডেট শুরু হবে")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(8, n)
        } catch (e: Exception) {}
    }

    // আপডেট ইনস্টল হওয়ার পর (নতুন প্রসেসে) ডাকা হয়: অ্যাপ আগে খোলা থাকলে নিজে আবার খুলতে চেষ্টা করে (ফোন ব্যাকগ্রাউন্ড থেকে খুলতে না দিলে হয় না),
    // আর সবসময় "আপডেট হয়ে গেছে" নোটিফিকেশন দেয়; চাপলে অ্যাপ খোলে
    fun afterUpdate(c: Context) {
        val sp = Store.sp(c)
        val wasOpen = sp.getBoolean("ui_open", false)
        sp.edit().putInt("just_upd", curVer(c)).putBoolean("ui_open", false).apply()
        val go = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (wasOpen) {
            try { c.startActivity(go) } catch (e: Exception) {}
        }
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("update", "AutoReply আপডেট", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 9, go, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("আপডেট হয়ে গেছে")
                .setContentText("সংস্করণ ${curVer(c)} — চাপুন, অ্যাপ খুলবে")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(9, n)
        } catch (e: Exception) {}
    }

    fun clearUpdated(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(9) } catch (e: Exception) {}
    }

    fun clearNotice(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(8) } catch (e: Exception) {}
    }
}
