package com.rahcare.autoreply

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val mute = Color.parseColor("#93A6AB")
    private val repo = "raqialamgirabdullah-rgb/AutoReply"
    private var update: Pair<Int, String>? = null // (নতুন সংস্করণ নম্বর, APK লিংক)

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(24), d(16), d(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        build()
        check(false)
    }

    private fun curVer(): Int {
        @Suppress("DEPRECATION")
        return packageManager.getPackageInfo(packageName, 0).versionCode
    }

    // GitHub-এর "latest release" লিংক কোন ট্যাগে যায় সেটা দেখে (API নয়, তাই রেট লিমিটে আটকায় না);
    // ট্যাগের শেষ সংখ্যাই সংস্করণ নম্বর (v1.0.N)
    private fun check(manual: Boolean) {
        Thread {
            try {
                val cn = URL("https://github.com/$repo/releases/latest").openConnection() as HttpURLConnection
                cn.instanceFollowRedirects = false
                cn.connectTimeout = 10000
                cn.readTimeout = 10000
                val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
                val v = tag.substringAfterLast('.').toInt()
                val url = "https://github.com/$repo/releases/download/$tag/AutoReply.apk"
                val now = if (v > curVer()) Pair(v, url) else null
                runOnUiThread {
                    val changed = now?.first != update?.first
                    update = now
                    if (changed || manual) build()
                    if (manual && now == null) Toast.makeText(this, "আপনার সংস্করণই সর্বশেষ", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { Toast.makeText(this, "চেক করা গেল না, ইন্টারনেট দেখুন", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // APK নামিয়ে PackageInstaller দিয়ে ইনস্টল করে; ফোন নিশ্চিত করার পর্দা দেখাবে
    private fun install(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "এই অ্যাপকে \"Install unknown apps\" অনুমতি দিয়ে ফিরে এসে আবার চাপুন", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        Toast.makeText(this, "ডাউনলোড হচ্ছে… শেষ হলে ফোন নিশ্চিত করতে বলবে", Toast.LENGTH_LONG).show()
        Thread {
            val inst = packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = inst.createSession(p)
            try {
                val s = inst.openSession(id)
                val cn = URL(url).openConnection() as HttpURLConnection
                cn.connectTimeout = 15000
                cn.readTimeout = 30000
                cn.inputStream.use { ins ->
                    s.openWrite("a", 0, -1).use { out ->
                        ins.copyTo(out)
                        s.fsync(out)
                    }
                }
                val pi = PendingIntent.getBroadcast(
                    this, id, Intent(this, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                s.commit(pi.intentSender)
            } catch (e: Exception) {
                try { inst.abandonSession(id) } catch (x: Exception) {}
                runOnUiThread { Toast.makeText(this, "ডাউনলোড বা ইনস্টল শুরু করা গেল না", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun granted() =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun add(v: View, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = d(top)
        root.addView(v, lp)
    }

    private fun tv(t: String, sp: Float = 15f, c: Int = Color.WHITE, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp
        v.setTextColor(c)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { f() }
        return b
    }

    private fun build() {
        root.removeAllViews()
        val c = this
        val ok = granted()
        add(tv("AutoReply", 26f, bold = true), 0)
        val up = update
        if (up != null) {
            add(tv("নতুন সংস্করণ আছে (${up.first})", 16f, Color.parseColor("#5CC9BC"), true), 12)
            add(btn("আপডেট করুন") { install(up.second) }, 4)
        }
        add(
            tv(
                if (ok) "✔ নোটিফিকেশন অ্যাক্সেস দেওয়া আছে" else "✘ নোটিফিকেশন অ্যাক্সেস দেওয়া নেই", 15f,
                Color.parseColor(if (ok) "#5CC9BC" else "#F2867F"), true
            ), 10
        )
        if (!ok) {
            add(
                tv(
                    "অপশনটা ধূসর থাকলে: ফোনের Settings → Apps → এই অ্যাপ → ওপরে ডানে ⋮ → \"Allow restricted settings\" দিন, তারপর আবার চেষ্টা করুন।",
                    13f, mute
                ), 4
            )
        }
        add(btn(if (ok) "নোটিফিকেশন অ্যাক্সেস সেটিংস" else "নোটিফিকেশন অ্যাক্সেস দিন") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, 8)
        add(btn("ব্যাটারি সেভার থেকে বাদ দিন") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, 2)

        val sw = Switch(this)
        sw.text = "AutoReply চালু"
        sw.textSize = 17f
        sw.setTextColor(Color.WHITE)
        sw.isChecked = Store.enabled(c)
        sw.setOnCheckedChangeListener { _, on ->
            Store.setEnabled(c, on)
            if (on && !granted()) Toast.makeText(c, "আগে নোটিফিকেশন অ্যাক্সেস দিন", Toast.LENGTH_LONG).show()
        }
        add(sw, 18)

        add(tv("শেষ ঘটনা (কেন উত্তর গেল বা গেল না)", 17f, bold = true), 22)
        add(tv(Store.log(c).ifEmpty { "এখনো কোনো মেসেজের নোটিফিকেশন আসেনি" }, 13f, mute), 2)
        add(btn("রিফ্রেশ") { build() }, 4)

        add(tv("কোন কোন অ্যাপে চলবে", 17f, bold = true), 22)
        add(
            tv(
                "একই অ্যাপের একাধিক কপি (ক্লোন/ডুয়াল অ্যাপ) থাকলে প্রতিটা থেকে একটা নোটিফিকেশন আসার পর এখানে নিজে থেকে যোগ হবে। WhatsApp, WhatsApp Business ও Messenger-এর কপি আপনা-আপনি চালু থাকে; অন্য অচেনা অ্যাপ (Reply আছে এমন) টিক দিয়ে নিজে চালু করতে হবে।",
                12f, mute
            ), 2
        )
        val shown = HashSet<String>()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        fun row(pkg: String, uid: Int, extra: String) {
            val cb = CheckBox(this)
            cb.text = Store.label(pkg, uid) + extra
            cb.setTextColor(Color.WHITE)
            cb.isChecked = Store.instOn(c, pkg, uid)
            cb.setOnCheckedChangeListener { _, v -> Store.setInstOn(c, pkg, uid, v) }
            add(cb, 2)
            shown.add("$pkg@$uid")
        }
        for ((pkg, _) in Store.APPS) row(pkg, 0, "")
        for (i in Store.insts(c)) {
            if ("${i.pkg}@${i.uid}" in shown) continue
            row(i.pkg, i.uid, "  · শেষ নোটিফিকেশন " + fmt.format(Date(i.t)))
        }

        add(tv("একই ব্যক্তিকে আবার উত্তর দেওয়ার আগে অপেক্ষা (সেকেন্ড, ০ = অপেক্ষা নেই)", 14f, mute), 18)
        val cd = EditText(this)
        cd.inputType = InputType.TYPE_CLASS_NUMBER
        cd.setText(Store.cooldown(c).toString())
        cd.setTextColor(Color.WHITE)
        cd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Store.setCooldown(c, (s.toString().toIntOrNull() ?: 0).coerceAtLeast(0))
            }
        })
        add(cd, 2)

        val rules = Store.rules(c)
        add(tv("উত্তরের নিয়ম", 17f, bold = true), 22)
        add(tv("মেসেজে এই শব্দগুলোর কোনো একটা থাকলেই উত্তর যাবে। ওপর থেকে নিচে যে নিয়ম আগে মিলবে সেটাই কাজ করবে। গ্রুপ চ্যাটে উত্তর যায় না।", 13f, mute), 2)
        for (r in rules) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setBackgroundColor(Color.parseColor("#1A2224"))
            box.setPadding(d(12), d(10), d(12), d(10))
            val cb = CheckBox(this)
            cb.text = "যদি: " + r.keys
            cb.setTextColor(Color.WHITE)
            cb.isChecked = r.on
            cb.setOnCheckedChangeListener { _, v ->
                r.on = v
                Store.saveRules(c, rules)
            }
            box.addView(cb)
            box.addView(tv("উত্তর: " + r.reply, 14f, mute))
            val row = LinearLayout(this)
            row.addView(btn("সম্পাদনা") { edit(r, rules) })
            row.addView(btn("মুছুন") {
                rules.remove(r)
                Store.saveRules(c, rules)
                build()
            })
            box.addView(row)
            add(box, 8)
        }
        add(btn("+ নতুন নিয়ম") { edit(null, rules) }, 10)

        add(tv("পরীক্ষা: মেসেজ লিখে দেখুন কী উত্তর যেত", 17f, bold = true), 26)
        val ti = EditText(this)
        ti.hint = "যেমন: আসসালামু আলাইকুম"
        ti.setTextColor(Color.WHITE)
        ti.setHintTextColor(mute)
        val res = tv("", 14f, mute)
        add(ti, 4)
        add(btn("দেখুন") {
            val m = Store.match(c, ti.text.toString())
            res.text = if (m != null) "উত্তর যেত: $m" else "কোনো নিয়ম মেলেনি, উত্তর যাবে না"
        }, 4)
        add(res, 4)

        add(tv("সংস্করণ ${curVer()}", 12f, mute), 30)
        add(btn("আপডেট আছে কি দেখুন") { check(true) }, 4)
    }

    private fun edit(r: Rule?, rules: MutableList<Rule>) {
        val k = EditText(this)
        k.hint = "কী লিখলে মিলবে (কমা দিয়ে আলাদা করুন)"
        k.setText(r?.keys ?: "")
        val rp = EditText(this)
        rp.hint = "উত্তর"
        rp.setText(r?.reply ?: "")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(d(20), d(8), d(20), 0)
        box.addView(k)
        box.addView(rp)
        AlertDialog.Builder(this)
            .setTitle(if (r == null) "নতুন নিয়ম" else "নিয়ম সম্পাদনা")
            .setView(box)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                val ks = k.text.toString().trim()
                val rs = rp.text.toString().trim()
                if (ks.isNotEmpty() && rs.isNotEmpty()) {
                    if (r == null) rules.add(Rule(ks, rs)) else {
                        r.keys = ks
                        r.reply = rs
                    }
                    Store.saveRules(this, rules)
                    build()
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }
}
