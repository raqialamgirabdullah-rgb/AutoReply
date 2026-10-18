package com.rahcare.autoreply

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

// ফোন চালু হলে বা অ্যাপ আপডেট হলে নিজে থেকে আবার চালু হয়
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Store.enabled(c)) return
        KeepAliveService.start(c)
        try { NotificationListenerService.requestRebind(ComponentName(c, ReplyService::class.java)) } catch (e: Exception) {}
    }
}
