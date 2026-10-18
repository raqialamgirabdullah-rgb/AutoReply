package com.rahcare.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// উত্তর দেওয়ার মূল যুক্তি: নোটিফিকেশন পড়ে নিয়ম মেলায় ও Reply বাটনে উত্তর পাঠায়
object Replier {
    private val lastSeen = HashMap<String, String>() // নোটিফিকেশন -> শেষ দেখা মেসেজ (একই মেসেজে দুবার নয়)
    private val sent = HashSet<String>()             // আমাদের পাঠানো উত্তর (চক্র ঠেকাতে)
    private val recent = HashMap<String, Long>()     // প্রেরক+লেখা -> কখন দেখা (দুই পথের মধ্যে দ্বিগুণ উত্তর ঠেকাতে)

    // শেষ ঘটনার রেকর্ড, অ্যাপের সাইডবারে দেখা যায়
    private fun log(c: Context, pkg: String, uid: Int, who: String, text: String, res: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Store.setLog(c, "$t | ${Store.label(pkg, uid)} | $who | \"$text\" — $res")
    }

    // listener ও accessibility — দুই পথ থেকেই এখানে আসে। একসাথে চললে যেন দুবার উত্তর না যায়, তাই synchronized ও সাম্প্রতিক-মেসেজ যাচাই
    @Synchronized
    fun handle(c: Context, pkg: String, uid: Int, key: String, postTime: Long, n: Notification, catchUp: Boolean, markOnly: Boolean) {
        if (catchUp && System.currentTimeMillis() - postTime > 30 * 60 * 1000L) return
        if (pkg == c.packageName) return
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

        if (!Store.enabled(c)) { log(c, pkg, uid, sender, text, "মূল সুইচ বন্ধ"); return }
        if (!Store.instOn(c, pkg, uid)) { log(c, pkg, uid, sender, text, "এই অ্যাপ বন্ধ করা আছে"); return }

        // গ্রুপ চ্যাটে উত্তর পাঠানো হবে না
        val title = ex.getCharSequence("android.conversationTitle")?.toString()
        if (ex.getBoolean("android.isGroupConversation") || (title != null && title != sender)) {
            log(c, pkg, uid, sender, text, "গ্রুপ ধরা হয়েছে, তাই বাদ (title=$title)")
            return
        }

        if (!catchUp) {
            val now = System.currentTimeMillis()
            val dk = "$pkg|$sender|$text"
            val last = recent[dk]
            if (last != null && now - last < 20000L) return
            recent[dk] = now
            if (recent.size > 40) recent.entries.removeAll { now - it.value > 60000L }
        }
        if (lastSeen[key] == stamp + text) return
        lastSeen[key] = stamp + text
        val hid = key + "|" + stamp + "|" + text.hashCode()
        if (catchUp && Store.handled(c, hid)) return
        Store.mark(c, hid)
        if (markOnly) return
        if (text in sent) return // এটা আমাদেরই পাঠানো উত্তর

        val reply = Store.match(c, text)
        if (reply == null) { log(c, pkg, uid, sender, text, "কোনো নিয়ম মেলেনি"); return }

        val acts = n.actions
        if (acts == null) { log(c, pkg, uid, sender, text, "নোটিফিকেশনে কোনো বাটন নেই"); return }
        for (a in acts) {
            val ris = a.remoteInputs ?: continue
            val ri = ris.firstOrNull { it.allowFreeFormInput } ?: continue
            if (sent.size > 50) sent.clear()
            sent.add(reply)
            val err = send(c, a, ris, ri, reply)
            Store.ev(c, "উত্তর পাঠানোর ফল: " + (err ?: "সফল"))
            log(c, pkg, uid, sender, text, if (err == null) "উত্তর পাঠানো হয়েছে: $reply" else "পাঠাতে ব্যর্থ ($err)")
            return
        }
        log(c, pkg, uid, sender, text, "Reply বাটন পাওয়া যায়নি")
    }

    private fun send(c: Context, a: Notification.Action, ris: Array<RemoteInput>, ri: RemoteInput, text: String): String? {
        return try {
            val i = Intent()
            val b = Bundle()
            b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(ris, i, b)
            a.actionIntent.send(c, 0, i)
            null
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
    }
}
