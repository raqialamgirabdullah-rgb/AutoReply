package com.rahcare.autoreply

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ফোন চালু হলে বা অ্যাপ আপডেট হলে নিজে থেকে আবার চালু হয়
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.afterUpdate(c)
        Store.ev(c, "বুট/আপডেটে চালু: ${i.action}")
        Watchdog.schedule(c)
        if (!Store.enabled(c)) return
        KeepAliveService.start(c)
        Watchdog.rebind(c, false)
    }
}
