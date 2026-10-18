package com.rahcare.autoreply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Rule(var keys: String, var reply: String, var on: Boolean = true)

object Store {
    // নতুন অ্যাপ যোগ করতে এই তালিকায় একটা লাইন বাড়ালেই হবে (প্যাকেজ নাম to দেখানোর নাম)
    val APPS = linkedMapOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "com.facebook.orca" to "Messenger")

    private fun p(c: Context) = c.getSharedPreferences("s", Context.MODE_PRIVATE)

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
            l.startsWith("com.facebook.orca") -> "Messenger"
            else -> null
        }
    }

    // একটা কপি = প্যাকেজ + ইউজার আইডি (ডুয়াল অ্যাপ/ওয়ার্ক প্রোফাইলে একই প্যাকেজ আলাদা ইউজারে চলে)
    fun label(pkg: String, uid: Int): String {
        val base = family(pkg) ?: "অচেনা অ্যাপ"
        val parts = ArrayList<String>()
        if (!APPS.containsKey(pkg)) parts.add(pkg)
        if (uid != 0) parts.add("ইউজার $uid")
        return if (parts.isEmpty()) base else "$base (${parts.joinToString(", ")})"
    }

    // চেনা পরিবারের কপি নিজে থেকে চালু; অচেনা অ্যাপ ব্যবহারকারী নিজে টিক না দিলে বন্ধ
    fun instOn(c: Context, pkg: String, uid: Int): Boolean {
        val sp = p(c)
        val k = "inst_$pkg@$uid"
        if (sp.contains(k)) return sp.getBoolean(k, false)
        if (uid == 0 && sp.contains("app_$pkg")) return sp.getBoolean("app_$pkg", true)
        return family(pkg) != null
    }
    fun setInstOn(c: Context, pkg: String, uid: Int, v: Boolean) = p(c).edit().putBoolean("inst_$pkg@$uid", v).apply()

    // যেসব কপি থেকে নোটিফিকেশন এসেছে তার তালিকা (সর্বশেষ ২০টা), মূল পাতায় দেখানোর জন্য
    class Inst(val pkg: String, val uid: Int, var t: Long)
    private val touched = HashMap<String, Long>()

    fun insts(c: Context): MutableList<Inst> {
        val s = p(c).getString("insts", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { val o = a.getJSONObject(it); Inst(o.getString("p"), o.getInt("u"), o.getLong("t")) }
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

    fun cooldown(c: Context) = p(c).getInt("cd", 0)
    fun setCooldown(c: Context, v: Int) = p(c).edit().putInt("cd", v).apply()

    fun log(c: Context) = p(c).getString("log", "") ?: ""
    fun setLog(c: Context, v: String) = p(c).edit().putString("log", v).apply()

    fun rules(c: Context): MutableList<Rule> {
        val s = p(c).getString("rules", null)
            ?: return mutableListOf(Rule("সালাম, salam", "ওয়ালাইকুম আসসালাম"))
        val a = JSONArray(s)
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Rule(o.getString("k"), o.getString("r"), o.optBoolean("on", true))
        }
    }

    fun saveRules(c: Context, l: List<Rule>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.keys).put("r", it.reply).put("on", it.on)) }
        p(c).edit().putString("rules", a.toString()).apply()
    }

    // মেসেজে কোনো নিয়মের শব্দ থাকলে সেই নিয়মের উত্তর ফেরত দেয়; ওপরের নিয়ম আগে মেলে
    fun match(c: Context, text: String): String? {
        val t = text.lowercase()
        for (r in rules(c)) {
            if (!r.on) continue
            val hit = r.keys.split(',', '،', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { t.contains(it) }
            if (hit) return r.reply
        }
        return null
    }
}
