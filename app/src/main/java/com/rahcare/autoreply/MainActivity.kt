package com.rahcare.autoreply

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var sv: ScrollView
    private val repo = "raqialamgirabdullah-rgb/AutoReply"
    private var update: Pair<Int, String>? = null // (নতুন সংস্করণ নম্বর, APK লিংক)

    private val ink = Color.parseColor("#EAF2F3")
    private val mute = Color.parseColor("#8FA3A8")
    private val panel = Color.parseColor("#121A1F")
    private val inner = Color.parseColor("#0D1418")
    private val line = Color.parseColor("#1F2C32")
    private val accent = Color.parseColor("#5CC9BC")
    private val warn = Color.parseColor("#F2867F")

    private val handler = Handler(Looper.getMainLooper())
    private var logBox: LinearLayout? = null
    private var logShown = "\u0000"
    private val ticker = object : Runnable {
        override fun run() {
            fillLog()
            handler.postDelayed(this, 3000)
        }
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(28), d(16), d(40))
        }
        sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Store.enabled(this)) KeepAliveService.start(this)
        try { NotificationListenerService.requestRebind(ComponentName(this, ReplyService::class.java)) } catch (e: Exception) {}
        build()
        check(false)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
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
                    if (manual && now == null) Toast.makeText(this, "আপনার সংস্করণই সর্বশেষ (সর্বশেষ প্রকাশিত: $v)", Toast.LENGTH_SHORT).show()
                    if (manual && now != null) {
                        Toast.makeText(this, "নতুন সংস্করণ পাওয়া গেছে ($v) — একদম ওপরে আপডেটের বোতাম আছে", Toast.LENGTH_LONG).show()
                        sv.post { sv.fullScroll(View.FOCUS_UP) }
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { Toast.makeText(this, "চেক করা গেল না (${e.javaClass.simpleName}: ${e.message?.take(60)}), ইন্টারনেট দেখুন", Toast.LENGTH_LONG).show() }
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
                if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
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
                runOnUiThread { Toast.makeText(this, "ডাউনলোড বা ইনস্টল শুরু করা গেল না (${e.javaClass.simpleName}: ${e.message?.take(70)}) — নিচের ব্রাউজার বোতামটা ব্যবহার করুন", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun granted() =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    // ---------- ডিজাইনের ছোট অংশগুলো ----------
    private fun rounded(fill: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = d(r).toFloat()
        if (stroke != 0) g.setStroke(d(1), stroke)
        return g
    }

    private fun addTo(p: LinearLayout, v: View, top: Int = 8) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = d(top)
        p.addView(v, lp)
    }

    private fun add(v: View, top: Int = 12) = addTo(root, v, top)

    private fun tv(t: String, sp: Float = 15f, c: Int = ink, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp
        v.setTextColor(c)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun card(stroke: Int = line): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = rounded(panel, 18, stroke)
        l.setPadding(d(16), d(14), d(16), d(16))
        return l
    }

    private fun title(p: LinearLayout, t: String, hint: String? = null) {
        addTo(p, tv(t, 17f, bold = true), 0)
        if (hint != null) addTo(p, tv(hint, 12f, mute), 4)
    }

    private fun primary(t: String, f: () -> Unit): TextView {
        val b = tv(t, 15f, Color.parseColor("#06201C"), true)
        b.gravity = Gravity.CENTER
        b.background = rounded(accent, 12)
        b.setPadding(d(16), d(13), d(16), d(13))
        b.setOnClickListener { f() }
        return b
    }

    private fun ghost(t: String, c: Int = accent, f: () -> Unit): TextView {
        val b = tv(t, 13f, c, true)
        b.gravity = Gravity.CENTER
        b.background = rounded(inner, 10, line)
        b.setPadding(d(12), d(10), d(12), d(10))
        b.setOnClickListener { f() }
        return b
    }

    private fun sw(on: Boolean, f: (Boolean) -> Unit): Switch {
        val s = Switch(this)
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(st, intArrayOf(accent, Color.parseColor("#8A9A9E")))
        s.trackTintList = ColorStateList(st, intArrayOf(Color.parseColor("#2C6B64"), Color.parseColor("#2A3437")))
        s.isChecked = on
        s.setOnCheckedChangeListener { _, v -> f(v) }
        return s
    }

    private fun swRow(t: String, sub: String?, on: Boolean, big: Boolean = false, subColor: Int = mute, f: (Boolean) -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(t, if (big) 20f else 15f, bold = big))
        if (sub != null) col.addView(tv(sub, 12f, subColor))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(sw(on, f))
        return r
    }

    // শেষ ঘটনা সাজিয়ে দেখায়; ৩ সেকেন্ড পরপর নিজে থেকে নতুন হয় (রিফ্রেশ বোতাম লাগে না)
    private fun fillLog() {
        val box = logBox ?: return
        val raw = Store.log(this)
        if (raw == logShown) return
        logShown = raw
        box.removeAllViews()
        if (raw.isEmpty()) {
            box.addView(tv("এখনো কোনো মেসেজের নোটিফিকেশন আসেনি", 13f, mute))
            return
        }
        val p = raw.split(" | ", limit = 4)
        if (p.size < 4) {
            box.addView(tv(raw, 13f, mute))
            return
        }
        val done = p[3].contains("উত্তর পাঠানো হয়েছে")
        val head = SpannableStringBuilder("● ")
        head.setSpan(ForegroundColorSpan(if (done) accent else warn), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        head.append(p[1]).append("  ·  ").append(p[0])
        val h = TextView(this)
        h.text = head
        h.textSize = 14f
        h.setTextColor(ink)
        h.setTypeface(h.typeface, Typeface.BOLD)
        box.addView(h)
        val who = tv("প্রেরক: " + p[2], 13f, mute)
        who.setPadding(0, d(4), 0, 0)
        box.addView(who)
        val res = tv(p[3], 13f, mute)
        res.setPadding(0, d(2), 0, 0)
        box.addView(res)
    }

    private fun build() {
        root.removeAllViews()
        val c = this
        val ok = granted()
        val on = Store.enabled(c)

        add(tv("AutoReply", 30f, bold = true), 0)
        add(tv("মেসেজ এলে নিজে থেকে উত্তর", 13f, mute), 2)

        val up = update
        if (up != null) {
            val u = card(accent)
            title(u, "নতুন সংস্করণ আছে (${up.first})")
            addTo(u, primary("আপডেট করুন") { install(up.second) }, 10)
            addTo(u, ghost("ব্রাউজার দিয়ে ডাউনলোড করুন") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(up.second))) }, 8)
            add(u, 16)
        }

        // মূল সুইচ ও অ্যাক্সেসের অবস্থা
        val main = card()
        main.addView(
            swRow(
                if (on) "চালু আছে" else "বন্ধ আছে",
                if (ok) "✔ নোটিফিকেশন অ্যাক্সেস দেওয়া আছে" else "✘ নোটিফিকেশন অ্যাক্সেস দেওয়া নেই",
                on, true, if (ok) accent else warn
            ) { v ->
                Store.setEnabled(c, v)
                if (v) KeepAliveService.start(c) else KeepAliveService.stop(c)
                if (v && !granted()) Toast.makeText(c, "আগে নোটিফিকেশন অ্যাক্সেস দিন", Toast.LENGTH_LONG).show()
                handler.post { build() }
            }
        )
        if (!ok) {
            addTo(main, tv("অপশনটা ধূসর থাকলে: ফোনের Settings → Apps → এই অ্যাপ → ওপরে ডানে ⋮ → \"Allow restricted settings\" দিন, তারপর আবার চেষ্টা করুন।", 12f, mute), 10)
            addTo(main, primary("নোটিফিকেশন অ্যাক্সেস দিন") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, 10)
        }
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        if (ok) {
            val g1 = ghost("অ্যাক্সেস সেটিংস") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            tools.addView(g1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
        }
        val free = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val g2 = ghost(if (free) "✔ ব্যাটারি সেভার বন্ধ" else "ব্যাটারি সেভার বাদ দিন", if (free) accent else warn) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        tools.addView(g2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addTo(main, tools, 14)
        addTo(
            main,
            tv("ব্যাকগ্রাউন্ডে ঠিকমতো চলতে: ফোনের Settings-এ এই অ্যাপের জন্য \"Auto-start\" ও \"ব্যাকগ্রাউন্ডে চলুক\" অনুমতি দিন এবং রিসেন্ট অ্যাপস থেকে অ্যাপটা লক করে রাখুন (ফোনভেদে নাম আলাদা)। ওপরের সুইচ চালু থাকলে ওপরের নোটিফিকেশন বারে একটা ছোট \"AutoReply চালু আছে\" নোটিফিকেশন থাকবে, ওটা সরাবেন না।", 12f, mute), 12
        )
        add(main, 16)

        // শেষ ঘটনা
        val act = card()
        title(act, "সর্বশেষ কার্যকলাপ")
        val lb = LinearLayout(this)
        lb.orientation = LinearLayout.VERTICAL
        addTo(act, lb, 10)
        logBox = lb
        logShown = "\u0000"
        fillLog()
        add(act, 14)

        // অ্যাপ/কপির তালিকা
        val apps = card()
        title(apps, "কোন অ্যাপে চলবে", "একই অ্যাপের ক্লোন/ডুয়াল কপি থেকে একটা নোটিফিকেশন এলে এখানে নিজে যোগ হয়।")
        val shown = HashSet<String>()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        fun row(pkg: String, uid: Int, sub: String?) {
            addTo(apps, swRow(Store.label(pkg, uid), sub, Store.instOn(c, pkg, uid)) { v -> Store.setInstOn(c, pkg, uid, v) }, 12)
            shown.add("$pkg@$uid")
        }
        for ((pkg, _) in Store.APPS) row(pkg, 0, null)
        for (i in Store.insts(c)) {
            if ("${i.pkg}@${i.uid}" in shown) continue
            row(i.pkg, i.uid, "শেষ নোটিফিকেশন " + fmt.format(Date(i.t)))
        }
        add(apps, 14)

        // উত্তরের নিয়ম
        val rules = Store.rules(c)
        val rc = card()
        title(rc, "উত্তরের নিয়ম", "মেসেজে শব্দগুলোর একটা থাকলেই উত্তর যায়। ওপরের নিয়ম আগে মেলে। গ্রুপ চ্যাটে উত্তর যায় না।")
        for (r in rules) {
            val rb = LinearLayout(this)
            rb.orientation = LinearLayout.VERTICAL
            rb.background = rounded(inner, 14, line)
            rb.setPadding(d(14), d(12), d(14), d(12))
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv("যদি: " + r.keys, 15f, bold = true))
            val rep = tv("উত্তর: " + r.reply, 13f, mute)
            rep.setPadding(0, d(3), 0, 0)
            col.addView(rep)
            top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(sw(r.on) { v ->
                r.on = v
                Store.saveRules(c, rules)
            })
            rb.addView(top)
            val acts = LinearLayout(this)
            acts.orientation = LinearLayout.HORIZONTAL
            acts.addView(ghost("সম্পাদনা") { edit(r, rules) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
            acts.addView(ghost("মুছুন", warn) {
                rules.remove(r)
                Store.saveRules(c, rules)
                build()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addTo(rb, acts, 12)
            addTo(rc, rb, 12)
        }
        addTo(rc, primary("+ নতুন নিয়ম") { edit(null, rules) }, 14)
        add(rc, 14)

        val foot = tv("সংস্করণ ${curVer()}", 12f, mute)
        foot.gravity = Gravity.CENTER
        add(foot, 22)
        val chk = tv("আপডেট আছে কি দেখুন", 13f, accent, true)
        chk.gravity = Gravity.CENTER
        chk.setPadding(0, d(10), 0, d(10))
        chk.setOnClickListener { check(true) }
        add(chk, 2)
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
