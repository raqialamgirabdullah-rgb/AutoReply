package com.rahcare.autoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

// GitHub-এ নতুন রিলিজ এলে নিজে থেকে ধরে: অ্যাপ খোলা থাকলে ব্যানার, বন্ধ থাকলে নোটিফিকেশন
object Updater {
    const val REPO = "raqialamgirabdullah-rgb/AutoReply"

    class Info(val v: Int, val url: String)

    // অ্যাপের পর্দা এখন সামনে খোলা আছে কি না (MainActivity ঠিক করে)
    @Volatile var fg = false

    // একটি সংস্করণের জন্য ব্যাকগ্রাউন্ডে সর্বোচ্চ কতবার নিজে ইনস্টলের চেষ্টা হবে
    private const val MAX_AUTO_TRIES = 3
    // নীরব ইনস্টল পরপর এতবার ফোন আটকালে আর নীরব চেষ্টা নয়, শুধু নোটিফিকেশন
    private const val MAX_SILENT_FAILS = 3

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

    // নীরব (নিশ্চিত-পর্দা ছাড়া) ইনস্টল চেষ্টা করা যাবে কি না: শুধু Android 12+, এবং ফোন পরপর আটকে না থাকলে
    fun silentAllowed(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && Store.sp(c).getInt("silent_fail", 0) < MAX_SILENT_FAILS

    fun noteSilentFail(c: Context) {
        val sp = Store.sp(c)
        sp.edit().putInt("silent_fail", sp.getInt("silent_fail", 0) + 1).apply()
    }

    // ব্যাকগ্রাউন্ড চেক: নতুন সংস্করণ পেলে নিজে নামিয়ে ইনস্টল করে (ফোন অনুমতি দিলে নীরবে);
    // না পারলে (অনুমতি নেই/ফোন আটকালে/অ্যাপ সামনে খোলা) একবার নোটিফিকেশন দেয়, আপডেট বোতাম তখনও কাজ করে
    // ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে
    fun backgroundCheck(c: Context, minGapMs: Long) {
        val sp = Store.sp(c)
        if (System.currentTimeMillis() - sp.getLong("upd_t", 0L) < minGapMs) return
        val i = checkNow(c) ?: return

        val tries = if (sp.getInt("auto_v", 0) == i.v) sp.getInt("auto_n", 0) else 0
        val can = silentAllowed(c) && !fg && c.packageManager.canRequestPackageInstalls() && tries < MAX_AUTO_TRIES
        if (can) {
            sp.edit().putInt("auto_v", i.v).putInt("auto_n", tries + 1).apply()
            Store.ev(c, "স্বয়ংক্রিয় আপডেট শুরু: v${i.v} (চেষ্টা ${tries + 1})")
            try {
                installNow(c, i.url, true, {}, {})
                return
            } catch (e: Exception) {
                Store.ev(c, "স্বয়ংক্রিয় আপডেট ব্যর্থ: ${e.javaClass.simpleName} ${e.message?.take(60)}")
            }
        }
        if (sp.getInt("upd_n", 0) == i.v) return
        sp.edit().putInt("upd_n", i.v).apply()
        notice(c)
    }

    // APK নামিয়ে PackageInstaller-এ জমা দেয়। silent = true হলে (Android 12+) ফোন অনুমতি দিলে নিশ্চিত-পর্দা ছাড়াই বসে;
    // না দিলে UpdateReceiver নিশ্চিত-পর্দা চায়। ব্যর্থ হলে ব্যতিক্রম ছোঁড়ে।
    // বাতিল বা ব্যর্থ হলে সামনের পর্দা বোতাম আগের অবস্থায় ফেরায়
    @Volatile var onInstallFail: (() -> Unit)? = null

    fun installNow(c: Context, url: String, silent: Boolean, onPct: (Int) -> Unit, onCommit: () -> Unit) {
        val inst = c.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(
                if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            )
        }
        val id = inst.createSession(p)
        try {
            val s = inst.openSession(id)
            val cn = URL(url).openConnection() as HttpURLConnection
            try {
                cn.connectTimeout = 15000
                cn.readTimeout = 30000
                if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
                val total = cn.contentLengthLong
                cn.inputStream.use { ins ->
                    s.openWrite("a", 0, -1).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    onPct(pct)
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
            } finally {
                cn.disconnect()
            }
            val pi = PendingIntent.getBroadcast(
                c, id, Intent(c, UpdateReceiver::class.java).putExtra("silent", silent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            // আপডেটের ফল জানতে: নীরব চেষ্টা ছিল কি না, আর ফোন নিশ্চিত-পর্দা চেয়েছিল কি না (UpdateReceiver ঠিক করে)
            Store.sp(c).edit().putBoolean("inst_silent", silent).putBoolean("inst_pending", false).apply()
            onCommit()
            s.commit(pi.intentSender)
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (x: Exception) {}
            throw e
        }
    }

    // ব্যাকগ্রাউন্ডে নিশ্চিত করার পর্দা খোলা যায় না, তাই নোটিফিকেশনে চাপলে সেটি খোলে
    fun confirmNotice(c: Context, ui: Intent) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("update", "AutoReply আপডেট", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 12, ui, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("AutoReply-এর নতুন সংস্করণ তৈরি")
                .setContentText("চাপুন — ইনস্টল নিশ্চিত করুন")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(12, n)
        } catch (e: Exception) {}
    }

    // আপডেটের পর নতুন সংস্করণ চালু হলে (MY_PACKAGE_REPLACED): ফল মিলিয়ে নীরব ইনস্টল কাজ করছে কি না দেখে,
    // আর অ্যাপের পর্দা খোলা থাকা অবস্থায় আপডেট হলে পর্দা ফিরিয়ে আনার চেষ্টা করে
    fun afterUpdate(c: Context) {
        val sp = Store.sp(c)
        if (sp.getBoolean("inst_silent", false) && !sp.getBoolean("inst_pending", false)) {
            sp.edit().putInt("silent_fail", 0).apply() // নীরব আপডেট কাজ করেছে
        }
        sp.edit().remove("inst_silent").remove("inst_pending").apply()
        clearNotice(c)
        try { c.getSystemService(NotificationManager::class.java).cancel(12) } catch (e: Exception) {}

        if (!sp.getBoolean("reopen", false)) {
            Store.ev(c, "আপডেট শেষ: সংস্করণ ${curVer(c)} (নীরবে)")
            return
        }
        sp.edit().remove("reopen").apply()
        Store.ev(c, "আপডেট শেষ: সংস্করণ ${curVer(c)}; পর্দা ফেরানোর চেষ্টা")
        val go = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // ১) সরাসরি খোলার চেষ্টা (নতুন Android ব্যাকগ্রাউন্ড থেকে খুলতে না দিলে নিঃশব্দে ব্যর্থ হয়)
        try { c.startActivity(go) } catch (e: Exception) {}
        // ২) ব্যর্থ হলে ভরসা: চাপলেই খোলে এমন নোটিফিকেশন
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("updated", "আপডেট সম্পন্ন", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(c, 13, go, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, "updated")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("AutoReply আপডেট হয়েছে")
                .setContentText("সংস্করণ ${curVer(c)} চালু আছে। অ্যাপ খুলতে চাপুন")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(13, n)
        } catch (e: Exception) {}
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

    fun clearNotice(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(8) } catch (e: Exception) {}
    }
}
