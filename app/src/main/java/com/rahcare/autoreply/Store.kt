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

    fun appOn(c: Context, pkg: String) = p(c).getBoolean("app_$pkg", true)
    fun setAppOn(c: Context, pkg: String, v: Boolean) = p(c).edit().putBoolean("app_$pkg", v).apply()

    fun cooldown(c: Context) = p(c).getInt("cd", 0)
    fun setCooldown(c: Context, v: Int) = p(c).edit().putInt("cd", v).apply()

    // তালিকার বাইরের যেসব অ্যাপ থেকে Reply-সহ নোটিফিকেশন এসেছে (সর্বশেষ ৬টা), অচেনা প্যাকেজ ধরতে
    fun seen(c: Context) = p(c).getString("seen", "") ?: ""
    fun addSeen(c: Context, pkg: String) {
        val l = seen(c).split(',').filter { it.isNotEmpty() && it != pkg }
        p(c).edit().putString("seen", (listOf(pkg) + l).take(6).joinToString(",")).apply()
    }

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
