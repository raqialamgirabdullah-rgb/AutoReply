package com.rahcare.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReplyService : NotificationListenerService() {
    private val lastSeen = HashMap<String, String>() // নোটিফিকেশন -> শেষ দেখা মেসেজ (একই মেসেজে দুবার নয়)
    private val sent = HashSet<String>()             // আমাদের পাঠানো উত্তর (চক্র ঠেকাতে)

    // শেষ ঘটনার রেকর্ড, অ্যাপের মূল পাতায় দেখা যায়
    private fun log(pkg: String, uid: Int, who: String, text: String, res: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Store.setLog(applicationContext, "$t | ${Store.label(pkg, uid)} | $who | \"$text\" — $res")
    }

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
            handle(sbn, false, false)
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
            activeNotifications?.forEach { handle(it, true, first) }
            if (first) Store.setCatchInit(c)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        connected = false
        Store.ev(applicationContext, "ReplyService ধ্বংস হয়েছে")
        super.onDestroy()
    }

    private fun handle(sbn: StatusBarNotification, catchUp: Boolean, markOnly: Boolean) {
        val c = applicationContext
        if (catchUp && System.currentTimeMillis() - sbn.postTime > 30 * 60 * 1000L) return
        val pkg = sbn.packageName
        val n = sbn.notification
        if (pkg == packageName) return
        val uid = sbn.userId
        val canReply = n.actions?.any { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } == true
        val fam = Store.family(pkg)
        if (fam == null && !canReply) return // চেনা পরিবারের নয় এবং Reply-ও নেই: বাদ
        Store.touch(c, pkg, uid)
        if (fam == null && !Store.instOn(c, pkg, uid)) return // অচেনা অ্যাপ, ব্যবহারকারী চালু করেননি
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras

        var text = ex.getCharSequence("android.text")?.toString() ?: ""
        var sender = ex.getCharSequence("android.title")?.toString() ?: ""
        var stamp = ""
        val msgs = ex.getParcelableArray("android.messages")
        if (msgs != null && msgs.isNotEmpty()) {
            val b = msgs.last() as? Bundle
            if (b != null) {
                b.getCharSequence("text")?.let { text = it.toString() }
                b.getCharSequence("sender")?.let { sender = it.toString() }
                stamp = b.getLong("time").toString()
            }
        }
        if (text.isBlank()) return

        if (!Store.enabled(c)) { log(pkg, uid, sender, text, "মূল সুইচ বন্ধ"); return }
        if (!Store.instOn(c, pkg, uid)) { log(pkg, uid, sender, text, "এই অ্যাপ বন্ধ করা আছে"); return }

        // গ্রুপ চ্যাটে উত্তর পাঠানো হবে না
        val title = ex.getCharSequence("android.conversationTitle")?.toString()
        if (ex.getBoolean("android.isGroupConversation") || (title != null && title != sender)) {
            log(pkg, uid, sender, text, "গ্রুপ ধরা হয়েছে, তাই বাদ (title=$title)")
            return
        }

        if (lastSeen[sbn.key] == stamp + text) return
        lastSeen[sbn.key] = stamp + text
        val hid = sbn.key + "|" + stamp + "|" + text.hashCode()
        if (catchUp && Store.handled(c, hid)) return
        Store.mark(c, hid)
        if (markOnly) return
        if (text in sent) return // এটা আমাদেরই পাঠানো উত্তর

        val reply = Store.match(c, text)
        if (reply == null) { log(pkg, uid, sender, text, "কোনো নিয়ম মেলেনি"); return }

        val acts = n.actions
        if (acts == null) { log(pkg, uid, sender, text, "নোটিফিকেশনে কোনো বাটন নেই"); return }
        for (a in acts) {
            val ris = a.remoteInputs ?: continue
            val ri = ris.firstOrNull { it.allowFreeFormInput } ?: continue
            if (sent.size > 50) sent.clear()
            sent.add(reply)
            val err = send(a, ris, ri, reply)
            Store.ev(c, "উত্তর পাঠানোর ফল: " + (err ?: "সফল"))
            log(pkg, uid, sender, text, if (err == null) "উত্তর পাঠানো হয়েছে: $reply" else "পাঠাতে ব্যর্থ ($err)")
            return
        }
        log(pkg, uid, sender, text, "Reply বাটন পাওয়া যায়নি")
    }

    private fun send(a: Notification.Action, ris: Array<RemoteInput>, ri: RemoteInput, text: String): String? {
        return try {
            val i = Intent()
            val b = Bundle()
            b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(ris, i, b)
            a.actionIntent.send(applicationContext, 0, i)
            null
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
    }
}
