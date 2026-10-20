package com.rahcare.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// উত্তর দেওয়ার মূল যুক্তি: নোটিফিকেশন পড়ে নিয়ম মেলায় ও Reply বাটনে উত্তর পাঠায়
object Replier {
    private val lastSeen = HashMap<String, String>() // নোটিফিকেশন -> শেষ দেখা মেসেজ (একই মেসেজে দুবার নয়)
    private val sent = HashSet<String>()             // আমাদের পাঠানো উত্তর (চক্র ঠেকাতে)
    // মেসেজের পরিচয় (প্রেরক+লেখা+মেসেজের নিজের সময়ছাপ) -> কখন দেখা। কাজ: একই মেসেজ দুই পথে (listener + accessibility) বা একই নোটিফিকেশন বারবার পোস্ট হলে দুবার উত্তর ঠেকানো।
    // কোনো সময়সীমা নেই: নতুন মেসেজের সময়ছাপ আলাদা, তাই একই লেখা সাথে সাথে আবার এলেও সাথে সাথে উত্তর যায়। শুধু সংখ্যায় সীমিত (শেষ ৫০০টা মনে রাখে)।
    private val recent = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 500
    }
    // দুই পথের একটিতে সময়ছাপ না থাকলে একই মেসেজ আলাদা ভাবা হতে পারে: তাই শুধু "অন্য পথ থেকে ১.৫ সেকেন্ডের মধ্যে একই মেসেজ" দ্বিতীয়বার ধরা হয় না (একই পথের পরপর মেসেজে এর কোনো প্রভাব নেই)
    private val lastPath = object : LinkedHashMap<String, Pair<Boolean, Long>>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Boolean, Long>>?): Boolean = size > 200
    }

    // শেষ ঘটনার রেকর্ড, অ্যাপের সাইডবারে দেখা যায়
    private fun log(c: Context, pkg: String, uid: Int, who: String, text: String, res: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Store.setLog(c, "$t | ${Store.label(pkg, uid)} | $who | \"$text\" — $res")
    }

    // listener ও accessibility — দুই পথ থেকেই এখানে আসে। একসাথে চললে যেন দুবার উত্তর না যায়, তাই synchronized ও সাম্প্রতিক-মেসেজ যাচাই
    @Synchronized
    fun handle(c: Context, pkg: String, uid: Int, key: String, postTime: Long, n: Notification, catchUp: Boolean, markOnly: Boolean) {
        if (catchUp && System.currentTimeMillis() - postTime > 30 * 60 * 1000L) return
        if (pkg == c.packageName || Store.ignored(pkg)) return
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
        // এই মেসেজের নিজস্ব সময়ছাপ (না থাকলে নোটিফিকেশনের when); একই মেসেজ হলে সবসময় একই, নতুন মেসেজ হলে আলাদা
        val mid = if (stamp.isNotEmpty() && stamp != "0") stamp else if (n.`when` > 0L) "w" + n.`when` else ""

        if (!Store.enabled(c)) { log(c, pkg, uid, sender, text, "মূল সুইচ বন্ধ"); return }
        if (!Store.instOn(c, pkg, uid)) { log(c, pkg, uid, sender, text, "এই অ্যাপ বন্ধ করা আছে"); return }

        // গ্রুপ চ্যাট চেনা: এখানে থামানো হয় না; শুধু যে নিয়মে "গ্রুপ" সুইচ চালু আছে সেটাই গ্রুপে উত্তর দেয় (নিচে match-এ)
        val title = ex.getCharSequence("android.conversationTitle")?.toString()
        var isGroup = ex.getBoolean("android.isGroupConversation") || (title != null && title != sender)

        // টেলিগ্রামের চ্যানেল, গ্রুপ ও বটে উত্তর পাঠানো হবে না (শুধু ব্যক্তির সাথে সরাসরি চ্যাটে)
        if (Store.grp(pkg) == "Telegram") {
            val cid = (n.channelId ?: "").lowercase()
            if (cid.contains("channel") || sender.trim().lowercase().endsWith("bot")) {
                log(c, pkg, uid, sender, text, "টেলিগ্রামের চ্যানেল/বট, তাই বাদ (channelId=$cid)")
                return
            }
            if (cid.contains("group")) isGroup = true
        }

        if (!catchUp) {
            val now = System.currentTimeMillis()
            val dk = "$pkg|$sender|$text|$mid"
            val last = recent[dk]
            // সময়ছাপ থাকলে: একই মেসেজ আগে দেখা হলে বাদ (সময় যতই পার হোক)। সময়ছাপ একেবারেই না থাকলে: শুধু ৩ সেকেন্ডে একই মেসেজ দুই পথে এলে বাদ
            if (last != null && (mid.isNotEmpty() || now - last < 3000L)) return
            recent[dk] = now
            val fromA11y = key.startsWith("a11y|")
            val pk = "$pkg|$sender|$text"
            val lp = lastPath[pk]
            if (lp != null && lp.first != fromA11y && now - lp.second < 1500L) return
            lastPath[pk] = Pair(fromA11y, now)
        }
        if (mid.isNotEmpty()) {
            if (lastSeen[key] == mid + text) return
            lastSeen[key] = mid + text
        }
        val hid = key + "|" + mid + "|" + text.hashCode()
        if (catchUp && Store.handled(c, hid)) return
        Store.mark(c, hid)
        if (markOnly) return
        if (text in sent) return // এটা আমাদেরই পাঠানো উত্তর

        // আগে নিয়ম; কোনো নিয়ম না মিললে (এবং AI চালু ও সাজানো থাকলে) AI
        val reply = Store.match(c, text, isGroup)
        if (isGroup && reply == null) { log(c, pkg, uid, sender, text, "গ্রুপ ধরা হয়েছে, তাই বাদ (কোনো নিয়মে গ্রুপ সুইচ চালু নেই, title=$title)"); return }
        if (reply == null && !Ai.ready(c)) { log(c, pkg, uid, sender, text, "কোনো নিয়ম মেলেনি"); return }

        val acts = n.actions
        if (acts == null) { log(c, pkg, uid, sender, text, "নোটিফিকেশনে কোনো বাটন নেই"); return }
        for (a in acts) {
            val ris = a.remoteInputs ?: continue
            // আগে মুক্ত-লেখার ঘর; না পেলে যেকোনো সাধারণ (ডেটা-নয়) ইনপুট ঘর, কারণ কিছু অ্যাপ (যেমন Facebook Lite) এভাবে দেয়
            val ri = ris.firstOrNull { it.allowFreeFormInput } ?: ris.firstOrNull { it.allowedDataTypes.isEmpty() } ?: continue
            if (reply == null) { replyWithAi(c, pkg, uid, sender, text, a, ris, ri); return }
            if (sent.size > 50) sent.clear()
            sent.add(reply)
            val err = send(c, a, ris, ri, reply)
            Store.ev(c, "উত্তর পাঠানোর ফল: " + (err ?: "সফল"))
            if (err == null) Store.stamp(c, "reply")
            log(c, pkg, uid, sender, text, if (err == null) "উত্তর পাঠানো হয়েছে: $reply" else "পাঠাতে ব্যর্থ ($err)")
            return
        }
        // কেন পাওয়া গেল না বোঝার জন্য প্রতিটা বাটনের বিবরণ লিখে রাখে
        val d = acts.joinToString(" | ") { a ->
            val r = a.remoteInputs
            "${a.title}[" + (if (r == null) "ইনপুট ঘর নেই" else r.joinToString(",") { "মুক্ত=${it.allowFreeFormInput} বাছাই=${it.choices?.size ?: 0}" }) + "]"
        }
        log(c, pkg, uid, sender, text, "Reply বাটন পাওয়া যায়নি — বাটন: $d")
    }

    // AI-এর উত্তর আসতে সময় লাগে, তাই আলাদা থ্রেডে API কল করে তারপর একই Reply বাটনে পাঠায়
    private fun replyWithAi(c: Context, pkg: String, uid: Int, sender: String, text: String, a: Notification.Action, ris: Array<RemoteInput>, ri: RemoteInput) {
        log(c, pkg, uid, sender, text, "AI উত্তর তৈরি হচ্ছে…")
        val conv = "$pkg@$uid|$sender"
        Thread {
            var wl: PowerManager.WakeLock? = null
            try {
                wl = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreply:ai")
                wl.acquire(70000L)
            } catch (e: Exception) {}
            try {
                val res = Ai.answer(c, conv, text)
                val ans = res.first
                if (ans == null) {
                    Store.ev(c, "AI ব্যর্থ: ${res.second}")
                    log(c, pkg, uid, sender, text, "AI ব্যর্থ: ${res.second}")
                    return@Thread
                }
                synchronized(this@Replier) {
                    if (sent.size > 50) sent.clear()
                    sent.add(ans)
                }
                val err = send(c, a, ris, ri, ans)
                Store.ev(c, "AI উত্তরের ফল: " + (err ?: "সফল"))
                if (err == null) Store.stamp(c, "reply")
                log(c, pkg, uid, sender, text, if (err == null) "AI উত্তর পাঠানো হয়েছে: ${ans.take(80)}" else "পাঠাতে ব্যর্থ ($err)")
            } catch (e: Exception) {
                log(c, pkg, uid, sender, text, "AI ব্যর্থ: ${e.javaClass.simpleName}")
            } finally {
                try { if (wl != null && wl.isHeld) wl.release() } catch (e: Exception) {}
            }
        }.start()
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
