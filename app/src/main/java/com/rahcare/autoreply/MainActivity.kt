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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var root: LinearLayout     // মূল পাতা: ব্যানার, মূল সুইচ, নিয়মের কার্ড
    private lateinit var side: LinearLayout     // সাইডবার: বাকি সব অপশন
    private lateinit var sideSv: ScrollView
    private lateinit var scrim: View
    private lateinit var dot: View
    private var menuOpen = false
    private var update: Updater.Info? = null
    private var wantInstall = false             // আপডেট-নোটিফিকেশনে চেপে ঢুকলে সরাসরি ইনস্টল শুরু হবে
    private var statusTv: TextView? = null
    private var lastCheck = 0L
    private var evBox: TextView? = null

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
            fillStatus()
            fillEv()
            if (System.currentTimeMillis() - lastCheck > 5 * 60 * 1000L) refreshUpdate()
            handler.postDelayed(this, 3000)
        }
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        wantInstall = intent?.getBooleanExtra("upd", false) == true
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(4), d(16), d(40))
        }
        val sv = ScrollView(this).apply { addView(root) }

        // ওপরের বার: বাঁয়ে নাম, ডানে থ্রি-লাইন মেনু
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(26), d(6), d(6))
        }
        val ttl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ttl.addView(tv("AutoReply", 26f, bold = true))
        ttl.addView(tv("মেসেজ এলে নিজে থেকে উত্তর", 12f, mute))
        bar.addView(ttl, LinearLayout.LayoutParams(0, wc, 1f))

        val menuBtn = FrameLayout(this)
        val lines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(14), d(14), d(14))
        }
        for (k in 0 until 3) {
            val b = View(this)
            b.background = rounded(ink, 2)
            lines.addView(b, LinearLayout.LayoutParams(d(22), d(3)).apply { if (k > 0) topMargin = d(5) })
        }
        menuBtn.addView(lines)
        dot = View(this)
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(warn) }
        dot.visibility = View.GONE
        menuBtn.addView(dot, FrameLayout.LayoutParams(d(10), d(10), Gravity.TOP or Gravity.END).apply {
            topMargin = d(8)
            rightMargin = d(8)
        })
        menuBtn.setOnClickListener { if (menuOpen) closeMenu() else openMenu() }
        bar.addView(menuBtn, LinearLayout.LayoutParams(wc, wc))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(bar, LinearLayout.LayoutParams(mp, wc))
        col.addView(sv, LinearLayout.LayoutParams(mp, 0, 1f))

        // সাইডবার (ডান পাশ থেকে স্লাইড করে আসে) ও তার পেছনের আবছা পর্দা
        side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(26), d(16), d(40))
        }
        sideSv = ScrollView(this).apply {
            addView(side)
            setBackgroundColor(Color.parseColor("#0B1115"))
            visibility = View.GONE
            isClickable = true
        }
        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            setOnClickListener { closeMenu() }
        }
        val frame = FrameLayout(this)
        frame.addView(col, FrameLayout.LayoutParams(mp, mp))
        frame.addView(scrim, FrameLayout.LayoutParams(mp, mp))
        frame.addView(sideSv, FrameLayout.LayoutParams(d(310), mp, Gravity.END))
        setContentView(frame)
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()
        sideSv.visibility = View.VISIBLE
        sideSv.translationX = d(310).toFloat()
        sideSv.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        scrim.animate().alpha(0f).setDuration(160).withEndAction { if (!menuOpen) scrim.visibility = View.GONE }.start()
        sideSv.animate().translationX(d(310).toFloat()).setDuration(180).withEndAction { if (!menuOpen) sideSv.visibility = View.GONE }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (menuOpen) closeMenu() else super.onBackPressed()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (i != null) setIntent(i)
        if (i?.getBooleanExtra("upd", false) == true) wantInstall = true
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Watchdog.schedule(this)
        Store.ev(this, "অ্যাপ খোলা হয়েছে")
        if (Store.enabled(this)) KeepAliveService.start(this)
        Watchdog.rebind(this, false)
        update = Updater.saved(this)
        if (update == null) Updater.clearNotice(this)
        build()
        refreshUpdate()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // নতুন সংস্করণ নিজে থেকে খোঁজে (অ্যাপ খুললে ও খোলা থাকলে ৫ মিনিট পরপর); কোনো বোতাম চাপতে হয় না
    private fun refreshUpdate() {
        lastCheck = System.currentTimeMillis()
        val app = applicationContext
        Thread {
            val now = Updater.checkNow(app)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val changed = now?.v != update?.v
                update = now
                if (changed) build()
                val u = update
                if (wantInstall) {
                    wantInstall = false
                    if (u != null) install(u.url)
                }
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

    private fun granted() = Watchdog.granted(this)

    // মূল পাতার সুইচের নিচের লেখা: নোটিফিকেশন-লিসেনার আসলেই সংযুক্ত কি না
    private fun statusLine(): Pair<String, Int> {
        return when {
            !granted() -> Pair("✘ নোটিফিকেশন অ্যাক্সেস দেওয়া নেই", warn)
            !Store.enabled(this) -> Pair("✔ অ্যাক্সেস আছে · এখন বন্ধ", mute)
            ReplyService.connected -> Pair("✔ সংযুক্ত — ব্যাকগ্রাউন্ডে মেসেজের অপেক্ষায়", accent)
            else -> Pair("⚠ সংযোগ নেই — আবার জোড়ার চেষ্টা চলছে", warn)
        }
    }

    private fun evText(): String {
        val head = "ফোন: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), সংস্করণ ${Updater.curVer(this)}\n" +
            "অ্যাক্সেস=${granted()} চালু=${Store.enabled(this)} সংযুক্ত=${ReplyService.connected} ব্যাটারি-ছাড়=${getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)}\n"
        return head + Store.events(this).split('\n').takeLast(30).joinToString("\n")
    }

    private fun fillEv() {
        evBox?.text = evText()
    }

    private fun fillStatus() {
        val t = statusTv ?: return
        val st = statusLine()
        t.text = st.first
        t.setTextColor(st.second)
        if (Store.enabled(this) && !ReplyService.connected) Watchdog.rebind(this, false)
    }

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

    private fun addSide(v: View, top: Int = 14) = addTo(side, v, top)

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

    private fun brand() = Build.MANUFACTURER.lowercase()

    // ফোনের ব্র্যান্ড ধরে সেই ফোনের অটো-স্টার্ট/ব্যাকগ্রাউন্ড সেটিং পেজ সরাসরি খোলে; না পেলে অ্যাপের সেটিংস
    private fun openAutostart() {
        val m = brand()
        val l = ArrayList<ComponentName>()
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            l.add(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        }
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) {
            l.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            l.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
            l.add(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
        }
        if (m.contains("vivo") || m.contains("iqoo")) {
            l.add(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            l.add(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        }
        if (m.contains("huawei") || m.contains("honor")) {
            l.add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            l.add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
        }
        if (m.contains("samsung")) {
            l.add(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            l.add(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"))
        }
        if (m.contains("asus")) {
            l.add(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"))
        }
        for (cn in l) {
            try {
                startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {}
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "অ্যাপের সেটিংস খোলা হয়েছে — সেখানে ব্যাটারি/Auto-start/ব্যাকগ্রাউন্ড অনুমতি চালু করুন", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {}
    }

    private fun guideUrl(): String {
        val m = brand()
        val slug = when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "xiaomi"
            m.contains("oppo") -> "oppo"
            m.contains("realme") -> "realme"
            m.contains("vivo") || m.contains("iqoo") -> "vivo"
            m.contains("huawei") || m.contains("honor") -> "huawei"
            m.contains("samsung") -> "samsung"
            m.contains("oneplus") -> "oneplus"
            else -> ""
        }
        return "https://dontkillmyapp.com/$slug"
    }

    private fun build() {
        root.removeAllViews()
        side.removeAllViews()
        val c = this
        val ok = granted()
        val on = Store.enabled(c)
        val up = update
        dot.visibility = if (up != null) View.VISIBLE else View.GONE
        val free = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        // ========== মূল পাতা ==========
        if (up != null) {
            val u = card(accent)
            title(u, "নতুন সংস্করণ এসেছে (${up.v})", "একবার চাপলেই ডাউনলোড ও ইনস্টল শুরু হবে।")
            addTo(u, primary("আপডেট করুন") { install(up.url) }, 12)
            add(u, 4)
        }

        val main = card()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val mc = LinearLayout(this)
        mc.orientation = LinearLayout.VERTICAL
        mc.addView(tv(if (on) "চালু আছে" else "বন্ধ আছে", 20f, bold = true))
        val st = tv("", 12f, mute)
        mc.addView(st)
        statusTv = st
        row.addView(mc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw(on) { v ->
            Store.setEnabled(c, v)
            if (v) {
                KeepAliveService.start(c)
                Watchdog.schedule(c)
                Watchdog.rebind(c, false)
            } else KeepAliveService.stop(c)
            if (v && !granted()) Toast.makeText(c, "আগে নোটিফিকেশন অ্যাক্সেস দিন", Toast.LENGTH_LONG).show()
            handler.post { build() }
        })
        main.addView(row)
        fillStatus()
        if (!ok) {
            addTo(main, tv("অপশনটা ধূসর থাকলে: ফোনের Settings → Apps → এই অ্যাপ → ওপরে ডানে ⋮ → \"Allow restricted settings\" দিন, তারপর আবার চেষ্টা করুন।", 12f, mute), 10)
            addTo(main, primary("নোটিফিকেশন অ্যাক্সেস দিন") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, 10)
        } else if (on && !free) {
            addTo(main, tv("অ্যাপ বন্ধ থাকলেও উত্তর যেতে ব্যাকগ্রাউন্ড সেটিং ঠিক করা দরকার।", 12f, mute), 10)
            addTo(main, ghost("ব্যাকগ্রাউন্ড সেটিং ঠিক করুন", warn) { openMenu() }, 8)
        }
        add(main, 12)

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
        add(rc, 12)

        // ========== সাইডবার ==========
        val hd = LinearLayout(this)
        hd.orientation = LinearLayout.HORIZONTAL
        hd.gravity = Gravity.CENTER_VERTICAL
        hd.addView(tv("মেনু", 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = tv("✕", 20f, mute)
        x.setPadding(d(12), d(6), d(4), d(6))
        x.setOnClickListener { closeMenu() }
        hd.addView(x)
        addTo(side, hd, 0)

        // নোটিফিকেশন অ্যাক্সেস
        val acc = card()
        title(acc, "নোটিফিকেশন অ্যাক্সেস", if (ok) "✔ দেওয়া আছে" else "✘ দেওয়া নেই — এটা ছাড়া উত্তর যাবে না")
        addTo(acc, if (ok) ghost("অ্যাক্সেস সেটিংস") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        else primary("নোটিফিকেশন অ্যাক্সেস দিন") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, 10)
        addSide(acc, 16)

        // ব্যাকগ্রাউন্ডে চালু রাখা
        val bg = card()
        title(bg, "ব্যাকগ্রাউন্ডে চালু রাখা", "অ্যাপ বন্ধ থাকলেও উত্তর যেতে এই অনুমতিগুলো দরকার।")
        addTo(bg, ghost(if (free) "✔ ব্যাটারি সেভার বন্ধ" else "ব্যাটারি সেভার বাদ দিন", if (free) accent else warn) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }, 12)
        addTo(bg, ghost("Auto-start / ব্যাকগ্রাউন্ড অনুমতি") { openAutostart() }, 8)
        addTo(bg, swRow("শক্তিশালী ব্যাকগ্রাউন্ড মোড", "ফোন ঘুমালেও জেগে থাকে। ব্যাটারি বেশি খরচ হয়।", Store.strong(c)) { v ->
            Store.setStrong(c, v)
            KeepAliveService.start(c)
        }, 12)
        for ((pkg, name) in Store.APPS) {
            val has = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            if (has) addTo(bg, ghost("$name-এর ব্যাটারি সেটিং") {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (e: Exception) {}
            }, 8)
        }
        addTo(bg, tv("মেসেজ পেতে দেরি হলে: ওপরের বোতামে চেপে অ্যাপের সেটিংসে গিয়ে Battery → \"Unrestricted / Allow background activity\" দিন এবং Auto-start চালু করুন।", 12f, mute), 8)
        addTo(bg, ghost("আমার ফোনের জন্য গাইড") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guideUrl()))) }, 8)
        addTo(
            bg,
            tv("ফোনের Settings-এ এই অ্যাপের জন্য \"Auto-start\" ও \"ব্যাকগ্রাউন্ডে চলুক\" দিন এবং রিসেন্ট অ্যাপস থেকে অ্যাপটা লক করে রাখুন (ফোনভেদে নাম আলাদা)। সুইচ চালু থাকলে ওপরের বারে একটা ছোট \"AutoReply চালু আছে\" নোটিফিকেশন থাকবে, ওটা সরাবেন না।", 12f, mute), 12
        )
        addSide(bg, 14)

        // শেষ ঘটনা
        val act = card()
        title(act, "সর্বশেষ কার্যকলাপ")
        val lb = LinearLayout(this)
        lb.orientation = LinearLayout.VERTICAL
        addTo(act, lb, 10)
        logBox = lb
        logShown = "\u0000"
        fillLog()
        addSide(act, 14)

        // অ্যাপ/কপির তালিকা
        val apps = card()
        title(apps, "কোন অ্যাপে চলবে", "একই অ্যাপের ক্লোন/ডুয়াল কপি থেকে একটা নোটিফিকেশন এলে এখানে নিজে যোগ হয়।")
        val shown = HashSet<String>()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        fun arow(pkg: String, uid: Int, sub: String?) {
            addTo(apps, swRow(Store.label(pkg, uid), sub, Store.instOn(c, pkg, uid)) { v -> Store.setInstOn(c, pkg, uid, v) }, 12)
            shown.add("$pkg@$uid")
        }
        for ((pkg, _) in Store.APPS) arow(pkg, 0, null)
        for (i in Store.insts(c)) {
            if ("${i.pkg}@${i.uid}" in shown) continue
            arow(i.pkg, i.uid, "শেষ নোটিফিকেশন " + fmt.format(Date(i.t)))
        }
        addSide(apps, 14)

        // ডায়াগনস্টিক
        val dg = card()
        title(dg, "ডায়াগনস্টিক লগ", "অ্যাপ বন্ধ থাকলে কী ঘটছে তার রেকর্ড। সমস্যা হলে কপি করে পাঠান।")
        val eb = tv("", 11f, mute)
        eb.typeface = Typeface.MONOSPACE
        addTo(dg, eb, 10)
        evBox = eb
        fillEv()
        addTo(dg, ghost("কপি করুন") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("autoreply-log", evText()))
            Toast.makeText(c, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
        }, 10)
        addSide(dg, 14)

        // সংস্করণ ও আপডেট
        val ver = card()
        title(ver, "সংস্করণ ${Updater.curVer(c)}", if (up == null) "✔ আপনি সর্বশেষ সংস্করণে আছেন। নতুন সংস্করণ এলে অ্যাপ নিজেই জানাবে।" else "নতুন সংস্করণ আছে (${up.v})")
        if (up != null) {
            addTo(ver, primary("আপডেট করুন") { install(up.url) }, 10)
            addTo(ver, ghost("ব্রাউজার দিয়ে ডাউনলোড করুন") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(up.url))) }, 8)
        }
        addSide(ver, 14)
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
