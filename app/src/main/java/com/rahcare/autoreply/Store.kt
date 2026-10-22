package com.rahcare.autoreply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// mode: কীভাবে মেসেজ মিলবে (Store.M_...); আগের নিয়মগুলো M_CONTAINS (মেসেজে শব্দটা থাকলেই) হিসেবে চলে
class Rule(var keys: String, var reply: String, var on: Boolean = true, var group: Boolean = false, var person: Boolean = true, var mode: Int = Store.M_CONTAINS)

object Store {
    // নিয়ম মেলানোর ধরন
    const val M_CONTAINS = 0 // মেসেজে শব্দ থাকলেই (আগের আচরণ)
    const val M_EXACT = 1    // পুরো মেসেজ হুবহু এক (বড়-ছোট হাতের অক্ষর, ফাঁকা ও চিহ্ন বাদ)
    const val M_SIMILAR = 2  // পুরো মেসেজ প্রায় এক (বানান ভুল সহ্য করে)
    const val M_PATTERN = 3  // * চিহ্ন দিয়ে প্যাটার্ন
    const val M_REGEX = 4    // রেগুলার এক্সপ্রেশন

    // নতুন অ্যাপ যোগ করতে এই তালিকায় একটা লাইন বাড়ালেই হবে (প্যাকেজ নাম to দেখানোর নাম)
    val APPS = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.facebook.orca" to "Messenger",
        "com.facebook.pages.app" to "Meta Business Suite",
        "org.telegram.messenger" to "Telegram"
    )

    // ফোনে থাকলে "কোন অ্যাপে চলবে" তালিকায় আসা চেনা মেসেজিং/সোশ্যাল অ্যাপ (প্যাকেজ → নাম)।
    // প্রতিটির আলাদা সুইচ, ডিফল্টে বন্ধ: ব্যবহারকারী চালু করলে তবেই উত্তর যায়।
    // SMS অ্যাপ ইচ্ছে করেই নেই: তাতে উত্তর গেলে সিমের টাকা কাটে।
    // উত্তর যেতে হলে অ্যাপের নোটিফিকেশনে Reply বাটন থাকতে হয় (সব অ্যাপে থাকে না)।
    // নতুন অ্যাপ যোগ করতে এখানে একটা লাইন বাড়ালেই হবে। ফোনের নিজের "সোশ্যাল" বিভাগের অ্যাপও (এই তালিকায় না থাকলেও) দেখানো হয়।
    val KNOWN_CHAT = linkedMapOf(
        "com.instagram.android" to "Instagram",
        "com.viber.voip" to "Viber",
        "org.thoughtcrime.securesms" to "Signal",
        "com.skype.raider" to "Skype",
        "jp.naver.line.android" to "LINE",
        "com.tencent.mm" to "WeChat",
        "com.discord" to "Discord",
        "com.snapchat.android" to "Snapchat",
        "com.Slack" to "Slack",
        "com.microsoft.teams" to "Microsoft Teams",
        "com.google.android.apps.dynamite" to "Google Chat",
        "com.twitter.android" to "X",
        "com.linkedin.android" to "LinkedIn",
        "com.kakao.talk" to "KakaoTalk",
        "com.zing.zalo" to "Zalo",
        "im.thebot.messenger" to "Botim",
        "im.vector.app" to "Element",
        "org.thunderdog.challegram" to "Telegram X",
        "com.facebook.mlite" to "Messenger Lite"
    )

    private fun p(c: Context) = c.getSharedPreferences("s", Context.MODE_PRIVATE)
    fun sp(c: Context) = p(c)

    fun enabled(c: Context) = p(c).getBoolean("on", true) // ডিফল্টভাবে চালু; ব্যবহারকারী চাইলে ম্যানুয়ালি বন্ধ করতে পারে
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()

    // পুরোনো (শুধু প্যাকেজ ধরে) চালু/বন্ধ — আগের সেটিং যেন হারিয়ে না যায়
    fun appOn(c: Context, pkg: String) = p(c).getBoolean("app_$pkg", true)
    fun setAppOn(c: Context, pkg: String, v: Boolean) = p(c).edit().putBoolean("app_$pkg", v).apply()

    // কোন অ্যাপ-পরিবারের প্যাকেজ (ক্লোন/ডুয়াল কপিসহ); অচেনা হলে null
    fun family(pkg: String): String? {
        val l = pkg.lowercase()
        return when {
            l.contains("w4b") -> "WhatsApp Business"
            l.contains("whatsapp") -> "WhatsApp"
            l.startsWith("com.facebook.orca") || l.startsWith("com.facebook.pages.app") -> "Facebook" // Messenger ও Meta Business Suite (ক্লোনসহ) সব এক
            l.startsWith("org.telegram.messenger") -> "Telegram"
            else -> null
        }
    }

    // একটা কপি = প্যাকেজ + ইউজার আইডি (ডুয়াল অ্যাপ/ওয়ার্ক প্রোফাইলে একই প্যাকেজ আলাদা ইউজারে চলে)
    fun label(pkg: String, uid: Int): String {
        val base = family(pkg) ?: "Unknown app"
        val parts = ArrayList<String>()
        if (!APPS.containsKey(pkg)) parts.add(pkg)
        if (uid != 0) parts.add("ইউজার $uid")
        return if (parts.isEmpty()) base else "$base (${parts.joinToString(", ")})"
    }

    // একই সুইচে চলা দল: WhatsApp (Business ও ক্লোনসহ) = "WhatsApp"; Messenger ও Meta Business Suite (ক্লোনসহ) = "Facebook"; অচেনা অ্যাপ হলে null
    fun grp(pkg: String): String? = family(pkg)?.let { if (it == "WhatsApp Business") "WhatsApp" else it }

    // আগের আলাদা আলাদা সেটিং (থাকলে); নতুন দলের সুইচ প্রথমবার আগের পছন্দ থেকে শুরু হয়
    private fun oldOn(c: Context, pkg: String, uid: Int): Boolean? {
        val sp = p(c)
        val k = "inst_$pkg@$uid"
        if (sp.contains(k)) return sp.getBoolean(k, false)
        if (uid == 0 && sp.contains("app_$pkg")) return sp.getBoolean("app_$pkg", true)
        return null
    }

    // দলের সুইচ: সেভ করা থাকলে সেটাই; না থাকলে আগের সেটিংয়ের কোনোটা চালু থাকলে চালু, সবই বন্ধ থাকলে বন্ধ, কিছু না থাকলে চালু
    fun grpOn(c: Context, g: String): Boolean {
        val sp = p(c)
        if (sp.contains("grp_$g")) return sp.getBoolean("grp_$g", true)
        val old = ArrayList<Boolean>()
        for (pkg in APPS.keys) if (grp(pkg) == g) oldOn(c, pkg, 0)?.let { old.add(it) }
        for (i in insts(c)) if (grp(i.pkg) == g) oldOn(c, i.pkg, i.uid)?.let { old.add(it) }
        return old.isEmpty() || old.any { it }
    }
    fun setGrpOn(c: Context, g: String, v: Boolean) = p(c).edit().putBoolean("grp_$g", v).apply()

    // দলের ভেতরে প্রতিটি আলাদা ভেরিয়েন্ট (সাধারণ/Business/ক্লোন...) এর নিজস্ব সুইচ; ডিফল্ট চালু। কার্যকর = দলের সুইচ চালু এবং ভেরিয়েন্ট চালু
    fun varOn(c: Context, pkg: String, uid: Int) = p(c).getBoolean("var_$pkg@$uid", true)
    fun setVarOn(c: Context, pkg: String, uid: Int, v: Boolean) = p(c).edit().putBoolean("var_$pkg@$uid", v).apply()

    // চেনা অ্যাপ দলের সুইচ অনুযায়ী; অচেনা অ্যাপ ব্যবহারকারী নিজে টিক না দিলে বন্ধ
    fun instOn(c: Context, pkg: String, uid: Int): Boolean {
        grp(pkg)?.let { return grpOn(c, it) && varOn(c, pkg, uid) }
        return p(c).getBoolean("inst_$pkg@$uid", false)
    }
    fun setInstOn(c: Context, pkg: String, uid: Int, v: Boolean) {
        val g = grp(pkg)
        if (g != null) setGrpOn(c, g, v) else p(c).edit().putBoolean("inst_$pkg@$uid", v).apply()
    }

    // যেসব কপি থেকে নোটিফিকেশন এসেছে তার তালিকা (সর্বশেষ ২০টা), মূল পাতায় দেখানোর জন্য
    class Inst(val pkg: String, val uid: Int, var t: Long)
    private val touched = HashMap<String, Long>()

    // যে অ্যাপগুলো একেবারেই বাদ (তালিকায় আসে না, উত্তরও যায় না): IMO-তে Reply বাটন নেই; Facebook Lite সরানো হয়েছে
    fun ignored(pkg: String) = pkg.startsWith("com.imo.") || pkg.startsWith("com.facebook.lite")

    fun insts(c: Context): MutableList<Inst> {
        val s = p(c).getString("insts", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { val o = a.getJSONObject(it); Inst(o.getString("p"), o.getInt("u"), o.getLong("t")) }.filter { !ignored(it.pkg) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    fun touch(c: Context, pkg: String, uid: Int) {
        val k = "$pkg@$uid"
        val now = System.currentTimeMillis()
        val last = touched[k]
        if (last != null && now - last < 60000L) return
        touched[k] = now
        val l = insts(c)
        val e = l.firstOrNull { it.pkg == pkg && it.uid == uid }
        if (e != null) e.t = now else l.add(Inst(pkg, uid, now))
        l.sortByDescending { it.t }
        val a = JSONArray()
        l.take(20).forEach { a.put(JSONObject().put("p", it.pkg).put("u", it.uid).put("t", it.t)) }
        p(c).edit().putString("insts", a.toString()).apply()
    }

    // বন্ধ থাকার সময় আসা মেসেজে পরে উত্তর দেওয়ার সময় একই মেসেজে দুবার যেন না যায়
    fun handled(c: Context, id: String) = (p(c).getString("hid", "") ?: "").split('\n').contains(id)
    fun mark(c: Context, id: String) {
        val l = (p(c).getString("hid", "") ?: "").split('\n').filter { it.isNotEmpty() && it != id }
        p(c).edit().putString("hid", (l + id).takeLast(60).joinToString("\n")).apply()
    }
    fun catchInit(c: Context) = p(c).getBoolean("ci", false)
    fun setCatchInit(c: Context) = p(c).edit().putBoolean("ci", true).apply()

    // ডায়াগনস্টিক ঘটনা-লগ (শেষ ৬০টা): প্রসেস শুরু, লিসেনার সংযোগ, সার্ভিস চালু/বন্ধ, নোটিফিকেশন আসা, উত্তর পাঠানো
    @Synchronized
    fun ev(c: Context, msg: String) {
        try {
            val t = java.text.SimpleDateFormat("dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val l = (p(c).getString("ev", "") ?: "").split('\n').filter { it.isNotEmpty() }
            p(c).edit().putString("ev", (l + "$t $msg").takeLast(60).joinToString("\n")).apply()
        } catch (e: Exception) {}
    }
    // "শক্তিশালী মোড": CPU জাগিয়ে রাখে (ব্যাটারি বেশি খরচ), ফোন যে অ্যাপকে ঘুম পাড়ায় তার জন্য
    fun strong(c: Context) = p(c).getBoolean("strong", true)
    fun setStrong(c: Context, v: Boolean) = p(c).edit().putBoolean("strong", v).apply()

    // প্রথমবার চালুর ধাপগুলো একবারই চেষ্টা করা হয়েছে কি না, তার চিহ্ন
    fun flag(c: Context, k: String) = p(c).getBoolean("f_$k", false)
    fun setFlag(c: Context, k: String) = p(c).edit().putBoolean("f_$k", true).apply()

    // পরীক্ষার জন্য সময়ছাপ: proc=প্রসেস নতুন করে চালু, disc=লিসেনার বিচ্ছিন্ন, notif/a11y=মেসেজের নোটিফিকেশন ধরা, reply=উত্তর গেছে
    fun stamp(c: Context, k: String) = p(c).edit().putLong("t_$k", System.currentTimeMillis()).apply()
    fun stampOf(c: Context, k: String) = p(c).getLong("t_$k", 0L)
    fun testStart(c: Context) = p(c).getLong("tst", 0L)
    fun setTestStart(c: Context, v: Long) = p(c).edit().putLong("tst", v).apply()
    fun testRes(c: Context) = p(c).getString("tst_res", "") ?: ""
    fun setTestRes(c: Context, v: String) = p(c).edit().putString("tst_res", v).apply()
    fun need(c: Context): Set<String> = (p(c).getString("need", "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
    fun setNeed(c: Context, v: Set<String>) = p(c).edit().putString("need", v.joinToString(",")).apply()

    // কোনো মেসেজের উত্তর দেরিতে (অ্যাপ জেগে ওঠার পর) যাচ্ছে মানে ফোন অ্যাপটা বন্ধ করে রেখেছিল: কারণ ধরে শুধু সেই সেটিং সামনে আনে
    fun missed(c: Context) {
        val n = HashSet<String>()
        if (!c.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)) n.add("battery")
        val m = android.os.Build.MANUFACTURER.lowercase()
        if (!flag(c, "s_auto") && listOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor", "tecno", "infinix", "itel", "transsion", "asus").any { m.contains(it) }) n.add("auto")
        if (n.isNotEmpty()) setNeed(c, n)
        ev(c, "উত্তর দেরিতে গেছে; সম্ভাব্য কারণ: $n")
    }

    fun events(c: Context) = p(c).getString("ev", "") ?: ""

    // নতুন কী "log2": শুধু ফলাফল থাকে। পুরোনো "log"-এ মেসেজের লেখা ছিল, তাই পেলেই মুছে দেওয়া হয়
    fun log(c: Context): String {
        if (p(c).contains("log")) p(c).edit().remove("log").apply()
        return p(c).getString("log2", "") ?: ""
    }
    fun setLog(c: Context, v: String) = p(c).edit().remove("log").putString("log2", v).apply()

    // ---------- AI স্বয়ংক্রিয় চ্যাট: নিয়ম না মিললে প্রতিষ্ঠানের তথ্য থেকে AI উত্তর দেয় ----------
    fun aiOn(c: Context) = p(c).getBoolean("ai_on", false)
    fun setAiOn(c: Context, v: Boolean) = p(c).edit().putBoolean("ai_on", v).apply()
    fun aiProvider(c: Context) = p(c).getString("ai_prov", "auto") ?: "auto"
    fun aiKey(c: Context) = p(c).getString("ai_key", "") ?: ""
    fun aiModel(c: Context) = p(c).getString("ai_model", "") ?: ""
    fun aiBase(c: Context) = p(c).getString("ai_base", "") ?: ""
    fun aiInfo(c: Context) = p(c).getString("ai_info", "") ?: ""
    fun aiCmd(c: Context) = p(c).getString("ai_cmd", "") ?: ""
    fun saveAi(c: Context, g: AiCfg) = p(c).edit()
        .putString("ai_prov", g.prov).putString("ai_key", g.key).putString("ai_model", g.model)
        .putString("ai_base", g.base).putString("ai_info", g.info).putString("ai_cmd", g.cmd).apply()

    fun rules(c: Context): MutableList<Rule> {
        val s = p(c).getString("rules", null)
            ?: return mutableListOf(defaultSalam())
        val a = JSONArray(s)
        val l = MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Rule(o.getString("k"), o.getString("r"), o.optBoolean("on", true), o.optBoolean("g", false), o.optBoolean("p", true), o.optInt("m", M_CONTAINS))
        }
        // আগের ডিফল্ট নিয়ম (কেউ বদলায়নি) থাকলে নতুন ডিফল্টে হালনাগাদ হয়; বদলানো নিয়ম ছোঁয়া হয় না
        val i = l.indexOfFirst { (it.keys == "সালাম, salam" && it.reply == "ওয়ালাইকুম আসসালাম") || (it.keys == "আসসালামু আলাইকুম, আসসালামুয়ালাইকুম, সালাম, Assalamu Alaikum, Assalamualaikum, salam, Hi, Hello" && it.reply == "ওয়া আলাইকুম আসসালাম") }
        if (i >= 0) { val o = l[i]; l[i] = defaultSalam().also { it.on = o.on; it.group = o.group; it.person = o.person } }
        // একবারের হালনাগাদ: Receive শুধু "আসসালামু আলাইকুম" থাকা নিয়মে সালাম/শুভেচ্ছার নানা রূপ যোগ হয়; Send-এর উত্তর ছোঁয়া হয় না
        if (!p(c).getBoolean("m_salam_v4", false)) {
            p(c).edit().putBoolean("m_salam_v4", true).apply()
            val j = l.indexOfFirst { it.keys.trim() == "আসসালামু আলাইকুম" }
            if (j >= 0) {
                l[j].keys = defaultSalam().keys
                saveRules(c, l)
            }
        }
        // একবারের হালনাগাদ: ডিফল্ট সালাম নিয়ম "Exact match" হয়; নিজে বদলানো নিয়ম ছোঁয়া হয় না
        if (!p(c).getBoolean("m_mode_v1", false)) {
            p(c).edit().putBoolean("m_mode_v1", true).apply()
            var ch = false
            for (x in l) if (x.keys == defaultSalam().keys && x.mode == M_CONTAINS) { x.mode = M_EXACT; ch = true }
            if (ch) saveRules(c, l)
        }
        return l
    }

    // ডিফল্ট নিয়ম: সালাম ও শুভেচ্ছার নানা রূপে "ওয়া আলাইকুম আসসালাম"
    private fun defaultSalam() = Rule(
        "আসসালামু আলাইকুম, আসসালামুয়ালাইকুম, Assalamu Alaikum, Assalamualaikum, Hi, Hello",
        "ওয়া আলাইকুম আসসালাম",
        mode = M_EXACT
    )

    fun saveRules(c: Context, l: List<Rule>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.keys).put("r", it.reply).put("on", it.on).put("g", it.group).put("p", it.person).put("m", it.mode)) }
        p(c).edit().putString("rules", a.toString()).apply()
    }

    private val PUNCT = Regex("[\\p{P}\\p{S}]+")
    private val SPACES = Regex("\\s+")

    // তুলনার জন্য: ছোট হাতের, যতিচিহ্ন/ইমোজি বাদ, একাধিক ফাঁকা এক
    private fun norm(s: String) = s.lowercase().replace(PUNCT, " ").replace(SPACES, " ").trim()

    // দুই লেখার মিল ০..১ (এডিট দূরত্ব দিয়ে)
    private fun sim(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = maxOf(a.length, b.length)
        if (m == 0 || Math.abs(a.length - b.length) > m * 0.25) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / m
    }

    // একটা নিয়ম এই মেসেজের সাথে মেলে কি না, নিয়মের "ধরন" অনুযায়ী
    private fun matches(r: Rule, text: String): Boolean {
        if (r.mode == M_REGEX) {
            return r.keys.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.any { k ->
                try { Regex(k, RegexOption.IGNORE_CASE).containsMatchIn(text) } catch (e: Exception) { false }
            }
        }
        val t = text.lowercase()
        val ks = r.keys.split(',', '،', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val n = norm(text)
        return when (r.mode) {
            M_EXACT -> n.isNotEmpty() && ks.any { norm(it) == n }
            M_SIMILAR -> n.isNotEmpty() && ks.any { k -> norm(k).let { it.isNotEmpty() && sim(n, it) >= 0.8 } }
            M_PATTERN -> n.isNotEmpty() && ks.any { k ->
                Regex(k.split('*').joinToString(".*") { Regex.escape(norm(it)) }, RegexOption.DOT_MATCHES_ALL).matches(n)
            }
            else -> ks.any { k -> if (k.length <= 5 && k.all { ch -> ch in 'a'..'z' }) Regex("(^|[^a-z])" + k + "($|[^a-z])").containsMatchIn(t) else t.contains(k) }
        }
    }

    // মেসেজে কোনো নিয়মের শব্দ থাকলে সেই নিয়মের উত্তর ফেরত দেয়; ওপরের নিয়ম আগে মেলে
    // group=true হলে (গ্রুপ চ্যাটের মেসেজ) শুধু যেসব নিয়মে "গ্রুপ" সুইচ চালু আছে সেগুলো মেলানো হয়
    fun match(c: Context, text: String, group: Boolean = false): String? {
        val t = text.lowercase()
        for (r in rules(c)) {
            if (!r.on) continue
            // গ্রুপের মেসেজে শুধু "গ্রুপ" চালু নিয়ম, সরাসরি (ব্যক্তির) চ্যাটে শুধু "ব্যক্তি" চালু নিয়ম
            if (group && !r.group) continue
            if (!group && !r.person) continue
            val hit = matches(r, text)
            if (hit) return r.reply
        }
        return null
    }
}
