package com.rahcare.autoreply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Rule(var keys: String, var reply: String, var on: Boolean = true, var group: Boolean = false, var person: Boolean = true)

object Store {
    // নতুন অ্যাপ যোগ করতে এই তালিকায় একটা লাইন বাড়ালেই হবে (প্যাকেজ নাম to দেখানোর নাম)
    val APPS = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.facebook.orca" to "Messenger",
        "com.facebook.lite" to "Facebook Lite",
        "com.facebook.pages.app" to "Meta Business Suite",
        "org.telegram.messenger" to "Telegram"
    )

    private fun p(c: Context) = c.getSharedPreferences("s", Context.MODE_PRIVATE)
    fun sp(c: Context) = p(c)

    fun enabled(c: Context) = p(c).getBoolean("on", false)
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
            l.startsWith("com.facebook.orca") || l.startsWith("com.facebook.lite") || l.startsWith("com.facebook.pages.app") -> "Facebook" // Messenger, Facebook Lite ও Meta Business Suite (ক্লোনসহ) সব এক
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

    // একই সুইচে চলা দল: WhatsApp (Business ও ক্লোনসহ) = "WhatsApp"; Messenger ও Facebook Lite (ক্লোনসহ) = "Facebook"; অচেনা অ্যাপ হলে null
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

    // চেনা অ্যাপ দলের সুইচ অনুযায়ী; অচেনা অ্যাপ ব্যবহারকারী নিজে টিক না দিলে বন্ধ
    fun instOn(c: Context, pkg: String, uid: Int): Boolean {
        grp(pkg)?.let { return grpOn(c, it) }
        return p(c).getBoolean("inst_$pkg@$uid", false)
    }
    fun setInstOn(c: Context, pkg: String, uid: Int, v: Boolean) {
        val g = grp(pkg)
        if (g != null) setGrpOn(c, g, v) else p(c).edit().putBoolean("inst_$pkg@$uid", v).apply()
    }

    // যেসব কপি থেকে নোটিফিকেশন এসেছে তার তালিকা (সর্বশেষ ২০টা), মূল পাতায় দেখানোর জন্য
    class Inst(val pkg: String, val uid: Int, var t: Long)
    private val touched = HashMap<String, Long>()

    // যে অ্যাপগুলো একেবারেই বাদ (তালিকায় আসে না, উত্তরও যায় না): IMO-তে Reply বাটন নেই
    fun ignored(pkg: String) = pkg.startsWith("com.imo.")

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

    fun events(c: Context) = p(c).getString("ev", "") ?: ""

    fun log(c: Context) = p(c).getString("log", "") ?: ""
    fun setLog(c: Context, v: String) = p(c).edit().putString("log", v).apply()

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
            ?: return mutableListOf(Rule("সালাম, salam", "ওয়ালাইকুম আসসালাম"))
        val a = JSONArray(s)
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Rule(o.getString("k"), o.getString("r"), o.optBoolean("on", true), o.optBoolean("g", false), o.optBoolean("p", true))
        }
    }

    fun saveRules(c: Context, l: List<Rule>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.keys).put("r", it.reply).put("on", it.on).put("g", it.group).put("p", it.person)) }
        p(c).edit().putString("rules", a.toString()).apply()
    }

    // মেসেজে কোনো নিয়মের শব্দ থাকলে সেই নিয়মের উত্তর ফেরত দেয়; ওপরের নিয়ম আগে মেলে
    // group=true হলে (গ্রুপ চ্যাটের মেসেজ) শুধু যেসব নিয়মে "গ্রুপ" সুইচ চালু আছে সেগুলো মেলানো হয়
    fun match(c: Context, text: String, group: Boolean = false): String? {
        val t = text.lowercase()
        for (r in rules(c)) {
            if (!r.on) continue
            // গ্রুপের মেসেজে শুধু "গ্রুপ" চালু নিয়ম, সরাসরি (ব্যক্তির) চ্যাটে শুধু "ব্যক্তি" চালু নিয়ম
            if (group) { if (!r.group) continue } else if (!r.person) continue
            val hit = r.keys.split(',', '،', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { t.contains(it) }
            if (hit) return r.reply
        }
        return null
    }
}
