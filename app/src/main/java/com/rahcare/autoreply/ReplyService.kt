package com.rahcare.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReplyService : NotificationListenerService() {
    private val lastReply = HashMap<String, Long>()  // প্রেরক -> শেষ উত্তরের সময়
    private val lastSeen = HashMap<String, String>() // নোটিফিকেশন -> শেষ দেখা মেসেজ (একই মেসেজে দুবার নয়)
    private val sent = HashSet<String>()             // আমাদের পাঠানো উত্তর (চক্র ঠেকাতে)

    // শেষ ঘটনার রেকর্ড, অ্যাপের মূল পাতায় দেখা যায়
    private fun log(pkg: String, who: String, text: String, res: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Store.setLog(applicationContext, "$t | ${Store.APPS[pkg] ?: pkg} | $who | \"$text\" → $res")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = applicationContext
        val pkg = sbn.packageName
        if (pkg !in Store.APPS) return
        val n = sbn.notification
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

        if (!Store.enabled(c)) { log(pkg, sender, text, "মূল সুইচ বন্ধ"); return }
        if (!Store.appOn(c, pkg)) { log(pkg, sender, text, "এই অ্যাপ বন্ধ করা আছে"); return }

        // গ্রুপ চ্যাটে উত্তর পাঠানো হবে না
        val title = ex.getCharSequence("android.conversationTitle")?.toString()
        if (ex.getBoolean("android.isGroupConversation") || (title != null && title != sender)) {
            log(pkg, sender, text, "গ্রুপ ধরা হয়েছে, তাই বাদ (title=$title)")
            return
        }

        if (lastSeen[sbn.key] == stamp + text) return
        lastSeen[sbn.key] = stamp + text
        if (text in sent) return // এটা আমাদেরই পাঠানো উত্তর

        val reply = Store.match(c, text)
        if (reply == null) { log(pkg, sender, text, "কোনো নিয়ম মেলেনি"); return }

        val key = "$pkg|$sender"
        val now = System.currentTimeMillis()
        if (now - (lastReply[key] ?: 0L) < Store.cooldown(c) * 1000L) {
            log(pkg, sender, text, "অপেক্ষার সময় চলছে")
            return
        }

        val acts = n.actions
        if (acts == null) { log(pkg, sender, text, "নোটিফিকেশনে কোনো বাটন নেই"); return }
        for (a in acts) {
            val ris = a.remoteInputs ?: continue
            val ri = ris.firstOrNull { it.allowFreeFormInput } ?: continue
            lastReply[key] = now
            if (sent.size > 50) sent.clear()
            sent.add(reply)
            val err = send(a, ris, ri, reply)
            log(pkg, sender, text, if (err == null) "উত্তর পাঠানো হয়েছে: $reply" else "পাঠাতে ব্যর্থ ($err)")
            return
        }
        log(pkg, sender, text, "Reply বাটন পাওয়া যায়নি")
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
