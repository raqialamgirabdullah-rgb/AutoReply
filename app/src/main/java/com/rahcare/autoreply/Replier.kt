package com.rahcare.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
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
    // মান = (প্রথম কোন পথে এসেছিল: a11y কি না, কখন, অন্য পথের কপি আগেই ধরা পড়েছে কি না)। প্রতিটা মেসেজের কপি একবারই বাদ যায়;
    // কপি ধরা পড়ার পর (বা একই পথে) আবার একই লেখা এলে সেটা নতুন মেসেজ, তাই সাথে সাথে পাঠানো একই লেখার দ্বিতীয় মেসেজেও উত্তর যায়
    private val lastPath = object : LinkedHashMap<String, Triple<Boolean, Long, Boolean>>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<Boolean, Long, Boolean>>?): Boolean = size > 200
    }

    // Facebook Lite-এর Reply বাটন কী ধরনের তা বোঝার জন্য প্রযুক্তিগত বিবরণ (শুধু Facebook Lite-এর লগে যোগ হয়; মেসেজের লেখা নয়)
    @Volatile private var diagTxt = ""

    private fun diag(a: Notification.Action, ri: RemoteInput, ris: Array<RemoteInput>): String {
        val pi = a.actionIntent
        val t = if (Build.VERSION.SDK_INT >= 31) when {
            pi.isActivity -> "activity"
            pi.isBroadcast -> "broadcast"
            pi.isService -> "service"
            pi.isForegroundService -> "fgservice"
            else -> "?"
        } else "?"
        return "PI=$t ঘর=${ris.size} মুক্ত=${ri.allowFreeFormInput} বাছাই=${ri.choices?.size ?: 0} বাটন=${a.title}"
    }

    // লগে দেখানোর জন্য শুধু অ্যাপের নাম (সুইচের দল নয়, তাই Business Suite আলাদা দেখায়)
    private fun appName(pkg: String): String {
        val l = pkg.lowercase()
        return when {
            l.startsWith("com.facebook.pages.app") -> "Business Suite"
            l.startsWith("com.facebook.orca") -> "Messenger"
            l.startsWith("com.facebook.lite") -> "Facebook Lite"
            else -> Store.family(pkg) ?: "অন্য অ্যাপ"
        }
    }

    // শেষ ঘটনার রেকর্ড, অ্যাপের সাইডবারে দেখা যায়
    private fun log(c: Context, pkg: String, uid: Int, who: String, text: String, res: String) {
        // শুধু অ্যাপের নাম + ফলাফল/কারণ রাখা হয়: মেসেজের লেখা, প্রেরকের নাম, উত্তরের লেখা, গ্রুপের নাম — কিছুই নয়
        val short = when {
            res.startsWith("উত্তর পাঠানো হয়েছে") || res.startsWith("AI উত্তর পাঠানো হয়েছে") -> "মেসেজ গিয়েছে"
            res.startsWith("টেলিগ্রামের চ্যানেল/বট") -> "টেলিগ্রামের চ্যানেল/বট, তাই বাদ"
            res.startsWith("গ্রুপ ধরা হয়েছে") -> "গ্রুপ মেসেজ, তাই বাদ (কোনো নিয়মে গ্রুপ সুইচ চালু নেই)"
            res.startsWith("Reply বাটন পাওয়া যায়নি") -> "Reply বাটন পাওয়া যায়নি"
            else -> res
        }
        val extra = if (pkg.lowercase().startsWith("com.facebook.lite") && diagTxt.isNotEmpty()) " [" + diagTxt + "]" else ""
        Store.setLog(c, appName(pkg) + " — " + short + extra)
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

        val dk = "$pkg|$sender|$text|$mid"
        val pk = "$pkg|$sender|$text"
        if (!catchUp) {
            val now = System.currentTimeMillis()
            val last = recent[dk]
            val fromA11y = key.startsWith("a11y|")
            val lp = lastPath[pk]
            // সময়ছাপ থাকলে: একই মেসেজ আগে দেখা হলে বাদ (সময় যতই পার হোক)। সময়ছাপ একেবারেই না থাকলে: শুধু ৩ সেকেন্ডে একই মেসেজ দুই পথে এলে বাদ
            if (last != null && (mid.isNotEmpty() || now - last < 3000L)) {
                // অন্য পথের কপি ধরা পড়ল: এই মেসেজের কপি ব্যবহার হয়ে গেছে বলে চিহ্ন দিই
                if (lp != null && lp.first != fromA11y) lastPath[pk] = Triple(lp.first, lp.second, true)
                Store.ev(c, "একই মেসেজের কপি বাদ (সময়ছাপ মিলেছে): $pkg")
                return
            }
            recent[dk] = now
            if (lp != null && lp.first != fromA11y && !lp.third && now - lp.second < 1500L) {
                lastPath[pk] = Triple(lp.first, lp.second, true)
                Store.ev(c, "একই মেসেজের অন্য পথের কপি বাদ: $pkg")
                return
            }
            lastPath[pk] = Triple(fromA11y, now, false)
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
        if (catchUp && System.currentTimeMillis() - postTime > 2 * 60 * 1000L) Store.missed(c) // মেসেজ ২ মিনিটের বেশি অপেক্ষায় ছিল, মানে আগে উত্তর যায়নি

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
            if (reply == null) { replyWithAi(c, pkg, uid, sender, text, a, ris, ri, dk, pk, key); return }
            if (sent.size > 50) sent.clear()
            sent.add(reply)
            diagTxt = diag(a, ri, ris)
            val err = send(c, a, ris, ri, reply)
            Store.ev(c, "উত্তর পাঠানোর ফল: " + (err ?: "সফল"))
            if (err == null) Store.stamp(c, "reply") else forget(dk, pk, key)
            log(c, pkg, uid, sender, text, if (err == null) "উত্তর পাঠানো হয়েছে: $reply" else "পাঠাতে ব্যর্থ ($err)")
            return
        }
        // কেন পাওয়া গেল না বোঝার জন্য প্রতিটা বাটনের বিবরণ লিখে রাখে
        val d = acts.joinToString(" | ") { a ->
            val r = a.remoteInputs
            "${a.title}[" + (if (r == null) "ইনপুট ঘর নেই" else r.joinToString(",") { "মুক্ত=${it.allowFreeFormInput} বাছাই=${it.choices?.size ?: 0}" }) + "]"
        }
        diagTxt = d
        log(c, pkg, uid, sender, text, "Reply বাটন পাওয়া যায়নি — বাটন: $d")
    }

    // AI-এর উত্তর আসতে সময় লাগে, তাই আলাদা থ্রেডে API কল করে তারপর একই Reply বাটনে পাঠায়
    private fun replyWithAi(c: Context, pkg: String, uid: Int, sender: String, text: String, a: Notification.Action, ris: Array<RemoteInput>, ri: RemoteInput, dk: String, pk: String, key: String) {
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
                    forget(dk, pk, key)
                    log(c, pkg, uid, sender, text, "AI ব্যর্থ: ${res.second}")
                    return@Thread
                }
                synchronized(this@Replier) {
                    if (sent.size > 50) sent.clear()
                    sent.add(ans)
                }
                diagTxt = diag(a, ri, ris)
                val err = send(c, a, ris, ri, ans)
                Store.ev(c, "AI উত্তরের ফল: " + (err ?: "সফল"))
                if (err == null) Store.stamp(c, "reply") else forget(dk, pk, key)
                log(c, pkg, uid, sender, text, if (err == null) "AI উত্তর পাঠানো হয়েছে: ${ans.take(80)}" else "পাঠাতে ব্যর্থ ($err)")
            } catch (e: Exception) {
                forget(dk, pk, key)
                log(c, pkg, uid, sender, text, "AI ব্যর্থ: ${e.javaClass.simpleName}")
            } finally {
                try { if (wl != null && wl.isHeld) wl.release() } catch (e: Exception) {}
            }
        }.start()
    }

    // পাঠাতে ব্যর্থ হলে এই মেসেজের "দেখা হয়ে গেছে" চিহ্ন মুছে দিই, যেন একই নোটিফিকেশন আবার এলে নতুন করে উত্তর দেওয়ার চেষ্টা হয়
    private fun forget(dk: String, pk: String, key: String) {
        synchronized(this) {
            recent.remove(dk)
            lastPath.remove(pk)
            lastSeen.remove(key)
        }
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
