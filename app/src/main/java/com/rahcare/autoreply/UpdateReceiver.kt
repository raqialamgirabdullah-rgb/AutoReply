package com.rahcare.autoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

// PackageInstaller-এর ফল এখানে আসে; নিশ্চিত করার পর্দা দরকার হলে সেটা খোলে
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ui = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                // ফোন নিশ্চিত-পর্দা চাইল মানে নীরব ইনস্টল এবার হয়নি
                Store.sp(c).edit().putBoolean("inst_pending", true).apply()
                if (i.getBooleanExtra("silent", false)) Updater.noteSilentFail(c)
                if (ui != null) {
                    ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Updater.fg) {
                        try {
                            c.startActivity(ui)
                        } catch (e: Exception) {
                            Toast.makeText(c, "নিশ্চিত করার পর্দা খুলল না — অ্যাপে ফিরে \"ব্রাউজার দিয়ে ডাউনলোড করুন\" চাপুন", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // অ্যাপ সামনে নেই: ব্যাকগ্রাউন্ড থেকে পর্দা খোলা যায় না, তাই নোটিফিকেশন
                        Updater.confirmNotice(c, ui)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Store.ev(c, "আপডেট ব্যর্থ: $msg")
                try { Updater.onInstallFail?.invoke() } catch (e: Exception) {}
                Store.sp(c).edit().putBoolean("inst_pending", true).apply()
                if (i.getBooleanExtra("silent", false)) Updater.noteSilentFail(c)
                if (Updater.fg) Toast.makeText(c, "আপডেট হয়নি: $msg", Toast.LENGTH_LONG).show()
                // টোস্টে লেখা কেটে যায়, তাই পুরো বার্তা নোটিফিকেশনেও দেখানো হয়
                try {
                    val nm = c.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("update", "AutoReply আপডেট", NotificationManager.IMPORTANCE_DEFAULT))
                    nm.notify(9, Notification.Builder(c, "update")
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("আপডেট হয়নি")
                        .setContentText(msg)
                        .setStyle(Notification.BigTextStyle().bigText(msg))
                        .setAutoCancel(true)
                        .build())
                } catch (e: Exception) {}
            }
        }
    }
}
