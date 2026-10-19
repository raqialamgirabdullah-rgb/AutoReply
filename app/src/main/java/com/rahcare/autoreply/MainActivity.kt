package com.rahcare.autoreply

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.text.TextUtils
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var root: LinearLayout     // মূল পাতা: শুধু উত্তরের নিয়মের কার্ড
    private lateinit var side: LinearLayout     // সাইডবার: চালু/বন্ধ কার্ড ও বাকি সব অপশন
    private lateinit var sideSv: ScrollView
    private lateinit var scrim: View
    private lateinit var dot: View
    private var menuOpen = false
    private lateinit var wiz: ScrollView        // সেটআপ উইজার্ড (পুরো পর্দা)
    private lateinit var wizBox: LinearLayout
    private var wizDismissed = false
    private var pendingInstallUrl: String? = null
    private var update: Updater.Info? = null
    private var wantInstall = false             // আপডেট-নোটিফিকেশনে চেপে ঢুকলে সরাসরি ইনস্টল শুরু হবে
    private var statusTv: TextView? = null
    private var lastCheck = 0L

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
            if (System.currentTimeMillis() - lastCheck > 5 * 60 * 1000L) refreshUpdate()
            handler.postDelayed(this, 3000)
        }
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    // সাইডবারের চওড়া: পর্দার ৮৮%, তবে সর্বোচ্চ ৪০০dp
    private fun sideW() = minOf((resources.displayMetrics.widthPixels * 0.88f).toInt(), d(400))

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
        frame.addView(sideSv, FrameLayout.LayoutParams(sideW(), mp, Gravity.END))
        wizBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(52), d(24), d(40))
        }
        wiz = ScrollView(this).apply {
            addView(wizBox)
            setBackgroundColor(Color.parseColor("#0B0F14"))
            visibility = View.GONE
            isClickable = true
        }
        frame.addView(wiz, FrameLayout.LayoutParams(mp, mp))
        setContentView(frame)
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()
        sideSv.visibility = View.VISIBLE
        sideSv.translationX = sideW().toFloat()
        sideSv.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        scrim.animate().alpha(0f).setDuration(160).withEndAction { if (!menuOpen) scrim.visibility = View.GONE }.start()
        sideSv.animate().translationX(sideW().toFloat()).setDuration(180).withEndAction { if (!menuOpen) sideSv.visibility = View.GONE }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (wiz.visibility == View.VISIBLE) dismissWizard() else if (menuOpen) closeMenu() else super.onBackPressed()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (i != null) setIntent(i)
        if (i?.getBooleanExtra("upd", false) == true) wantInstall = true
    }

    override fun onResume() {
        super.onResume()
        Watchdog.schedule(this)
        Store.ev(this, "অ্যাপ খোলা হয়েছে")
        if (Store.enabled(this)) KeepAliveService.start(this)
        Watchdog.rebind(this, false)
        if (wiz.visibility == View.VISIBLE) renderWizard() else wizardCheck()
        val pu = pendingInstallUrl
        if (pu != null && packageManager.canRequestPackageInstalls()) {
            pendingInstallUrl = null
            install(pu)
        }
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
            // অনুমতি দিয়ে ফিরে এলেই আপডেট নিজে থেকে শুরু হবে (আবার চাপতে হবে না)
            pendingInstallUrl = url
            Toast.makeText(this, "পরের পর্দায় \"Allow from this source\" চালু করে ফিরে আসুন — আপডেট নিজেই শুরু হবে", Toast.LENGTH_LONG).show()
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

    private fun a11yOn() = (Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: "").contains("$packageName/")

    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)


    // ================= সেটআপ উইজার্ড =================
    // একটা-একটা করে ধাপ দেখায়; ব্যবহারকারী শুধু "অনুমতি দিন" চাপে, অ্যাপ ঠিক সেই সেটিং পেজে নিয়ে যায়।
    // ফিরে এলে ধাপটা হয়েছে কি না নিজে যাচাই করে পরের ধাপে চলে যায়।
    // (ফোন এসব অনুমতি অ্যাপকে নিজে দিয়ে নিতে দেয় না; ব্যবহারকারীর একটা চাপ লাগেই, আমরা শুধু পথটা সহজ করি।)
    private class WStep(
        val id: String, val title: String, val body: String, val btn: String, val optional: Boolean,
        val done: () -> Boolean, val act: () -> Unit, val helpText: String? = null, val help: (() -> Unit)? = null
    )

    private fun hasAutostartScreen(): Boolean {
        val m = brand()
        return listOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor", "samsung", "asus", "infinix", "tecno", "itel").any { m.contains(it) }
    }

    private fun requestBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) {}
        }
    }

    // নোটিফিকেশন-অ্যাক্সেসের পেজে সরাসরি AutoReply-এর সুইচে নিয়ে যায় (Android 11+), না পারলে সাধারণ তালিকায়
    private fun openListenerSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, ReplyService::class.java).flattenToString())
                startActivity(i)
                return
            }
        } catch (e: Exception) {}
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) {}
    }

    // সুইচ ধূসর থাকলে (APK থেকে ইনস্টল করা অ্যাপে ফোন "restricted settings" আটকে রাখে): অ্যাপের ইনফো পেজ খুলে কী করতে হবে বলে
    private fun openRestrictedHelp() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "ওপরে ডানে ⋮ চাপুন → \"Allow restricted settings\" দিন। তারপর ফিরে এসে আবার \"অনুমতি দিন\" চাপুন।", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {}
    }

    private fun wsteps(): List<WStep> {
        val l = ArrayList<WStep>()
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(WStep("post", "নোটিফিকেশন দেখানোর অনুমতি",
                "অ্যাপ চালু আছে কি না জানাতে ও নতুন আপডেটের খবর দিতে এটা লাগে। পরের পর্দায় \"Allow\" (অনুমতি দিন) চাপুন।",
                "অনুমতি দিন", false,
                { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }))
        }
        l.add(WStep("listen", "মেসেজ পড়ার অনুমতি",
            "মেসেজ এলে উত্তর দিতে AutoReply-কে নোটিফিকেশন দেখতে দিতে হবে। পরের পর্দায় AutoReply-এর সুইচটা চালু করুন, তারপর \"Allow\" (অনুমতি দিন) চাপুন।",
            "অনুমতি দিন", false, { granted() }, { openListenerSettings() },
            "সুইচটা ধূসর হয়ে আছে?", { openRestrictedHelp() }))
        l.add(WStep("batt", "ব্যাকগ্রাউন্ডে চলার অনুমতি",
            "অ্যাপ বন্ধ থাকলেও যেন ফোন একে ঘুম পাড়িয়ে না দেয়। পরের পর্দায় \"Allow\" (অনুমতি দিন) চাপুন।",
            "অনুমতি দিন", false, { batteryFree() }, { requestBattery() }))
        if (hasAutostartScreen()) {
            l.add(WStep("auto", "অটো-স্টার্ট চালু করুন",
                "আপনার ফোন ব্যাকগ্রাউন্ডের অ্যাপ নিজে বন্ধ করে দিতে পারে। পরের পর্দায় AutoReply-এর জন্য অটো-স্টার্ট বা \"ব্যাকগ্রাউন্ডে চলুক\" চালু করে ফিরে আসুন।",
                "খুলুন", true, { Store.flag(this, "w_auto") }, { Store.setFlag(this, "w_auto"); openAutostart() }))
        }
        l.add(WStep("a11y", "দ্রুত উত্তরের সহায়ক (ঐচ্ছিক)",
            "কিছু ফোন স্ক্রিন বন্ধ থাকলে অ্যাপ থামিয়ে রাখে। এটা চালু করলে সেই সমস্যা কমতে পারে। শুধু WhatsApp/Messenger-এর নোটিফিকেশন-ঘটনা শোনে, পর্দার কিছু পড়ে না। পরের পর্দায় ইনস্টল করা অ্যাপের তালিকা থেকে AutoReply খুঁজে চালু করুন।",
            "চালু করুন", true, { a11yOn() }, { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
        return l
    }

    // বাকি ধাপ: হয়নি এবং (ঐচ্ছিক হলে) বাদ দেওয়া হয়নি
    private fun pending(): List<WStep> = wsteps().filter { !it.done() && !(it.optional && Store.flag(this, "w_skip_${it.id}")) }

    private fun wizardCheck() {
        if (wizDismissed || Store.flag(this, "w_dismissed")) return
        if (pending().isNotEmpty()) showWizard()
    }

    private fun showWizard() {
        wiz.visibility = View.VISIBLE
        renderWizard()
    }

    private fun dismissWizard() {
        wizDismissed = true
        Store.setFlag(this, "w_dismissed")
        wiz.visibility = View.GONE
        build()
    }

    private fun renderWizard() {
        wizBox.removeAllViews()
        val all = wsteps()
        val pend = pending()
        if (pend.isEmpty()) { renderDone(); return }
        val s = pend[0]
        val idx = all.indexOfFirst { it.id == s.id }
        wizBox.addView(tv("সেটআপ · ধাপ ${idx + 1} / ${all.size}", 12f, mute))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (k in all.indices) {
            val seg = View(this)
            seg.background = rounded(if (k < idx) accent else line, 3)
            bar.addView(seg, LinearLayout.LayoutParams(0, d(4), 1f).apply { if (k > 0) leftMargin = d(4) })
        }
        wizBox.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(10)
            bottomMargin = d(28)
        })
        wizBox.addView(tv(s.title, 24f, bold = true))
        val b = tv(s.body, 15f, mute)
        b.setPadding(0, d(12), 0, d(24))
        b.setLineSpacing(0f, 1.25f)
        wizBox.addView(b)
        addTo(wizBox, primary(s.btn) { s.act() }, 0)
        val ht = s.helpText
        val hf = s.help
        if (ht != null && hf != null) addTo(wizBox, ghost(ht) { hf() }, 12)
        if (s.optional) addTo(wizBox, ghost("এখন নয়, বাদ দিন", mute) {
            Store.setFlag(this, "w_skip_${s.id}")
            renderWizard()
        }, 12)
        addTo(wizBox, ghost("পরে করব", mute) { dismissWizard() }, 4)
    }

    private fun renderDone() {
        wizBox.removeAllViews()
        wizBox.addView(tv("✔", 48f, accent))
        wizBox.addView(tv("সব ঠিক আছে", 26f, bold = true))
        val b = tv("AutoReply এখন মেসেজের অপেক্ষায়। উত্তরের নিয়ম মূল পাতা থেকে বদলাতে পারবেন।", 15f, mute)
        b.setPadding(0, d(12), 0, d(24))
        wizBox.addView(b)
        addTo(wizBox, primary("শুরু করুন") { finishWizard() }, 0)
    }

    private fun finishWizard() {
        Store.setFlag(this, "w_dismissed")
        wiz.visibility = View.GONE
        // নতুন ইনস্টলে সব ধাপ শেষে মূল সুইচ নিজে চালু হয়
        if (!Store.sp(this).contains("on")) {
            Store.setEnabled(this, true)
            KeepAliveService.start(this)
            Watchdog.schedule(this)
            Watchdog.rebind(this, false)
        }
        build()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (wiz.visibility == View.VISIBLE) renderWizard()
    }

    private fun granted() = Watchdog.granted(this)

    // মূল পাতার সুইচের নিচের লেখা: নোটিফিকেশন-লিসেনার আসলেই সংযুক্ত কি না
    private fun statusLine(): Pair<String, Int> {
        return when {
            !granted() -> Pair("✘ নোটিফিকেশন অ্যাক্সেস দেওয়া নেই", warn)
            !Store.enabled(this) -> Pair("✔ অ্যাক্সেস আছে · এখন Off", mute)
            ReplyService.connected -> Pair("✔ সংযুক্ত — ব্যাকগ্রাউন্ডে মেসেজের অপেক্ষায়", accent)
            else -> Pair("⚠ সংযোগ নেই — আবার জোড়ার চেষ্টা চলছে", warn)
        }
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
        v.setLineSpacing(0f, 1.15f)
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

    // ছোট কার্ডগুলোর গোষ্ঠী: নিজে কোনো কার্ড/বর্ডার নয় (শুধু ব্যাকগ্রাউন্ড), ভেতরের কার্ডগুলোই কার্ড — কার্ডের ভেতরে কার্ড হবে না
    private fun section(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
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
        b.background = rounded(Color.parseColor("#1A252B"), 10)
        b.setPadding(d(12), d(10), d(12), d(10))
        b.setOnClickListener { f() }
        return b
    }

    // নিজের আঁকা পিল-আকৃতির অন/অফ সুইচ: মসৃণ স্লাইড, রং ধীরে বদলায়; সব জায়গায় এটাই ব্যবহার হয়
    private inner class PillSwitch(ctx: android.content.Context, on: Boolean, private val f: (Boolean) -> Unit) : View(ctx) {
        private var checked = on
        private var pos = if (on) 1f else 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ev = ArgbEvaluator()
        private val trackOff = Color.parseColor("#27353B")
        private val thumbOff = Color.parseColor("#A9B8BC")

        init {
            isClickable = true
            setOnClickListener {
                checked = !checked
                ValueAnimator.ofFloat(pos, if (checked) 1f else 0f).apply {
                    duration = 180
                    addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                    start()
                }
                f(checked)
            }
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(d(52), d(32))

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            p.color = ev.evaluate(pos, trackOff, accent) as Int
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
            val m = d(3).toFloat()
            val tr = r - m
            val cx = r + (w - 2 * r) * pos
            p.color = ev.evaluate(pos, thumbOff, Color.WHITE) as Int
            c.drawCircle(cx, r, tr, p)
        }
    }

    private fun sw(on: Boolean, f: (Boolean) -> Unit): View = PillSwitch(this, on, f)

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

    // লম্বা লেখা এক অনুচ্ছেদে না রেখে লাইন ধরে সাজায়: প্রতিটি লাইনের সামনে ছোট বিন্দু, দ্বিতীয় লাইন লেখার নিচেই বসে
    private fun bullets(p: LinearLayout, items: List<String>, top: Int = 6, c: Int = mute) {
        var first = true
        for (t in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val dot = tv("•", 12.5f, accent, true)
            dot.setPadding(0, 0, d(8), 0)
            row.addView(dot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(tv(t, 12.5f, c), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addTo(p, row, if (first) top else 4)
            first = false
        }
    }

    // সাইডবারের অ্যাপের ঘর (দুই কলামের গ্রিডের জন্য): ওপরে নাম, নিচে খুঁটিনাটি, সবার নিচে অবস্থা ও সুইচ
    private fun swCell(t: String, details: List<String>, on: Boolean, f: (Boolean) -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.background = rounded(panel, 16, line)
        r.setPadding(d(14), d(12), d(12), d(12))
        r.addView(tv(t, 14f, bold = true))
        for (x in details) {
            val dv = tv(x, 10.5f, mute)
            dv.maxLines = 1
            dv.ellipsize = TextUtils.TruncateAt.MIDDLE
            addTo(r, dv, 2)
        }
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        val st = tv(if (on) "On" else "Off", 12.5f, if (on) accent else mute, true)
        bottom.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(sw(on) { v ->
            st.text = if (v) "On" else "Off"
            st.setTextColor(if (v) accent else mute)
            f(v)
        })
        addTo(r, bottom, 8)
        return r
    }

    // ঘরগুলো একই সারিতে দুই কলামে সাজায়; সংখ্যা বিজোড় হলে শেষ সারির ডান পাশ ফাঁকা থাকে
    private fun grid(p: LinearLayout, cells: List<View>, top: Int = 8) {
        var i = 0
        while (i < cells.size) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.addView(cells[i], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = d(4) })
            val b = cells.getOrNull(i + 1) ?: View(this)
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = d(4) })
            addTo(p, r, if (i == 0) top else 8)
            i += 2
        }
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

    private fun build() {
        root.removeAllViews()
        side.removeAllViews()
        val c = this
        val ok = granted()
        val on = Store.enabled(c)
        val up = update
        dot.visibility = View.GONE // আপডেটের কার্ড মূল পাতায় থাকে, তাই মেনুতে আলাদা লাল বিন্দু লাগে না
        val free = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        // ========== সাইডবার: মাথা ও চালু/বন্ধ কার্ড ==========
        val hd = LinearLayout(this)
        hd.orientation = LinearLayout.HORIZONTAL
        hd.gravity = Gravity.CENTER_VERTICAL
        hd.addView(tv("Menu", 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = tv("✕", 20f, mute)
        x.setPadding(d(12), d(6), d(4), d(6))
        x.setOnClickListener { closeMenu() }
        hd.addView(x)
        addTo(side, hd, 0)

        val main = card()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val mc = LinearLayout(this)
        mc.orientation = LinearLayout.VERTICAL
        mc.addView(tv(if (on) "Auto-reply On" else "Auto-reply Off", 20f, bold = true))
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
            bullets(main, listOf("অপশনটা ধূসর থাকলে ফোনের Settings → Apps → এই অ্যাপ খুলুন", "ওপরে ডানে ⋮ চেপে \"Allow restricted settings\" দিন", "তারপর আবার চেষ্টা করুন"), 10)
            addTo(main, primary("নোটিফিকেশন অ্যাক্সেস দিন") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, 10)
        }
        addSide(main, 16)

        // ========== মূল পাতা: শুধু উত্তরের নিয়ম ==========
        // উত্তরের নিয়ম
        val rules = Store.rules(c)
        val rc = section()
        title(rc, "উত্তরের নিয়ম")
        bullets(rc, listOf("মেসেজে শব্দগুলোর একটা থাকলেই উত্তর যায়", "ওপরের নিয়ম আগে মেলে", "গ্রুপ চ্যাটে উত্তর যায় না"), 6)
        for (r in rules) {
            val rb = LinearLayout(this)
            rb.orientation = LinearLayout.VERTICAL
            rb.background = rounded(panel, 16, line)
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

        // নতুন সংস্করণ এলে শুধু তখনই মূল পাতার একদম নিচে আপডেটের কার্ড দেখায়
        if (up != null) {
            val u = card(accent)
            title(u, "নতুন সংস্করণ এসেছে (${up.v})", "একবার চাপলেই ডাউনলোড ও ইনস্টল শুরু হবে।")
            addTo(u, primary("আপডেট করুন") { install(up.url) }, 12)
            add(u, 12)
        }

        // ========== সাইডবারের বাকি অংশ ==========
        // অনুমতি: শুধু যেগুলো বাকি আছে সেগুলোর বোতাম দেখায়; সব ঠিক থাকলে কার্ডটাই থাকে না।
        // শক্তিশালী ব্যাকগ্রাউন্ড মোড সবসময় চালু (আলাদা সুইচ নেই)
        val todo = pending()
        if (todo.isNotEmpty()) {
            val perm = card(warn)
            title(perm, "সেটআপ বাকি আছে", "${todo.size}টা ধাপ বাকি। এক-এক করে অনুমতি দিন, অ্যাপ প্রতিটি ধাপে সঠিক জায়গায় নিয়ে যাবে।")
            addTo(perm, primary("সেটআপ শেষ করুন") {
                closeMenu()
                wizDismissed = false
                showWizard()
            }, 12)
            addSide(perm, 16)
        }

        // AI স্বয়ংক্রিয় চ্যাট: নিয়ম না মিললে প্রতিষ্ঠানের তথ্য থেকে AI উত্তর দেয়
        val aiCard = card()
        addTo(aiCard, swRow("AI স্বয়ংক্রিয় চ্যাট", null, Store.aiOn(c)) { v ->
            Store.setAiOn(c, v)
            if (v && !Ai.configured(Ai.cfg(c))) Toast.makeText(c, "আগে \"AI সেটিংস\"-এ API কী ও প্রতিষ্ঠানের তথ্য দিন", Toast.LENGTH_LONG).show()
        }, 0)
        addTo(aiCard, ghost("AI সেটিংস") { aiDialog() }, 10)
        addSide(aiCard, 14)

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

        // অ্যাপ/কপির তালিকা (দুই কলামের গ্রিড)
        val apps = section()
        title(apps, "কোন অ্যাপে চলবে")
        bullets(apps, listOf("প্রতিটি অ্যাপ আলাদা করে চালু বা বন্ধ করা যায়"), 6)
        val cells = ArrayList<View>()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        val shownG = HashSet<String>()
        for (pkg in Store.APPS.keys) {
            val g = Store.grp(pkg) ?: continue
            if (!shownG.add(g)) continue
            cells.add(swCell(g, emptyList(), Store.grpOn(c, g)) { v -> Store.setGrpOn(c, g, v) })
        }
        val shown = HashSet<String>()
        for (i in Store.insts(c)) {
            if (Store.grp(i.pkg) != null || "${i.pkg}@${i.uid}" in shown) continue
            shown.add("${i.pkg}@${i.uid}")
            // অচেনা অ্যাপ: নাম (পেলে), প্যাকেজ, ইউজার ও শেষ নোটিফিকেশনের সময়সহ
            val nm = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(i.pkg, 0)).toString() } catch (e: Exception) { "" }
            val det = ArrayList<String>()
            if (nm.isNotBlank()) det.add(nm)
            det.add(i.pkg)
            if (i.uid != 0) det.add("ইউজার ${i.uid}")
            det.add(fmt.format(Date(i.t)))
            cells.add(swCell("অচেনা অ্যাপ", det, Store.instOn(c, i.pkg, i.uid)) { v -> Store.setInstOn(c, i.pkg, i.uid, v) })
        }
        grid(apps, cells, 12)
        addSide(apps, 14)

        // সংস্করণ (আপডেটের বোতাম শুধু মূল পাতায়; সাইডবারে কেবল সংস্করণ দেখায়)
        val ver = card()
        title(ver, "সংস্করণ ${Updater.curVer(c)}")
        if (up == null) bullets(ver, listOf("আপনি সর্বশেষ সংস্করণে আছেন", "নতুন সংস্করণ এলে অ্যাপ নিজেই জানাবে"), 6)
        addTo(ver, ghost("Auto Reply শেয়ার করুন") { shareApp() }, 12)
        addSide(ver, 14)
    }

    // অ্যাপের APK "Auto Reply.apk" নামে শেয়ার (ফাইলের নাম ApkShareProvider ঠিক করে)
    private fun shareApp() {
        try {
            val u = ApkShareProvider.uri(this)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/vnd.android.package-archive"
            i.putExtra(Intent.EXTRA_STREAM, u)
            i.putExtra(Intent.EXTRA_SUBJECT, "Auto Reply")
            i.clipData = android.content.ClipData.newRawUri(ApkShareProvider.NAME, u)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Auto Reply শেয়ার করুন"))
        } catch (e: Exception) {
            Toast.makeText(this, "শেয়ার করা যায়নি", Toast.LENGTH_LONG).show()
        }
    }

    // AI সেটিংস: একটাই API কী-র ঘর (চাবি দেখে প্রোভাইডার নিজে চেনা হয়), প্রতিষ্ঠানের তথ্য, পরীক্ষা; বাকি সব "উন্নত"-এর ভেতরে
    private fun aiDialog() {
        val cur = Ai.cfg(this)
        val dlg = AlertDialog.Builder(this).create()

        fun field(hint: String, text: String, lines: Int = 1, secret: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(text)
            e.setTextColor(ink)
            e.setHintTextColor(Color.parseColor("#5F7378"))
            e.textSize = 14.5f
            e.background = rounded(inner, 12, line)
            e.setPadding(d(14), d(12), d(14), d(12))
            if (lines > 1) {
                e.minLines = lines
                e.gravity = Gravity.TOP
                e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                e.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            }
            return e
        }
        fun label(p: LinearLayout, t: String, sub: String? = null, top: Int = 18) {
            addTo(p, tv(t, 14f, accent, true), top)
            if (sub != null) addTo(p, tv(sub, 12f, mute), 3)
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(panel, 24, line)
        box.setPadding(d(20), d(20), d(20), d(18))

        // ওপরের অংশ: ছোট আইকন + শিরোনাম
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val badge = tv("\u2726", 20f, Color.parseColor("#06201C"), true)
        badge.gravity = Gravity.CENTER
        badge.background = rounded(accent, 14)
        head.addView(badge, LinearLayout.LayoutParams(d(44), d(44)))
        val ht = LinearLayout(this)
        ht.orientation = LinearLayout.VERTICAL
        ht.addView(tv("AI সহকারী", 20f, bold = true))
        ht.addView(tv("নিয়ম না মিললে AI প্রতিষ্ঠানের তথ্য থেকে উত্তর দেবে", 12f, mute))
        val hlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        hlp.leftMargin = d(12)
        head.addView(ht, hlp)
        addTo(box, head, 0)

        // ১. API কী
        label(box, "API কী", "যেকোনো AI-র চাবি বসান (Gemini, Claude, OpenAI…): কোনটা, অ্যাপ নিজে চিনে নেবে")
        val keyRow = LinearLayout(this)
        keyRow.orientation = LinearLayout.HORIZONTAL
        keyRow.gravity = Gravity.CENTER_VERTICAL
        val key = field("এখানে পেস্ট করুন", cur.key, secret = true)
        keyRow.addView(key, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var shown = false
        val eye = ghost("দেখান") {}
        eye.setOnClickListener {
            shown = !shown
            val t = if (shown) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.inputType = android.text.InputType.TYPE_CLASS_TEXT or t
            key.setSelection(key.text.length)
            eye.text = if (shown) "লুকান" else "দেখান"
        }
        val elp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.leftMargin = d(8)
        keyRow.addView(eye, elp)
        addTo(box, keyRow, 8)
        val chip = tv("", 12.5f, mute, true)
        addTo(box, chip, 8)

        // ২. প্রতিষ্ঠানের তথ্য
        label(box, "প্রতিষ্ঠানের তথ্য", "নাম, সেবা, দাম, ঠিকানা, খোলার সময়, যোগাযোগ, সাধারণ প্রশ্ন-উত্তর। শুধু মেসেজের লেখা AI-তে যায়, নাম বা নম্বর যায় না।")
        val info = field("যা লিখবেন, AI শুধু সেখান থেকেই উত্তর দেবে", cur.info, lines = 6)
        addTo(box, info, 8)

        // ৩. পরীক্ষা
        label(box, "পরীক্ষা")
        val testQ = field("কাস্টমার যা জিজ্ঞেস করতে পারে", "")
        addTo(box, testQ, 8)
        val testOut = tv("", 13f, ink)
        testOut.background = rounded(inner, 12, line)
        testOut.setPadding(d(14), d(12), d(14), d(12))
        testOut.visibility = View.GONE

        // উন্নত (ঐচ্ছিক)
        val adv = LinearLayout(this)
        adv.orientation = LinearLayout.VERTICAL
        adv.visibility = View.GONE
        addTo(adv, tv("সাধারণত এগুলো লাগে না। শুধু চাবির ধরন চেনা না গেলে বা নির্দিষ্ট মডেল চাইলে ব্যবহার করুন।", 12f, mute), 0)
        val rg = RadioGroup(this)
        rg.orientation = LinearLayout.VERTICAL
        fun rb(t: String): RadioButton {
            val r = RadioButton(this)
            r.id = View.generateViewId()
            r.text = t
            r.setTextColor(ink)
            r.buttonTintList = ColorStateList.valueOf(accent)
            rg.addView(r)
            return r
        }
        val rbA = rb("স্বয়ংক্রিয় (প্রস্তাবিত)")
        val rbG = rb("Gemini (Google)")
        val rbC = rb("Claude (Anthropic)")
        val rbO = rb("OpenAI-সামঞ্জস্যপূর্ণ (DeepSeek, Groq…)")
        rg.check(when (cur.prov) { "gemini" -> rbG.id; "claude" -> rbC.id; "openai" -> rbO.id; else -> rbA.id })
        addTo(adv, rg, 10)
        val model = field("মডেলের নাম (খালি রাখলে নিজে বেছে নেবে)", cur.model)
        addTo(adv, model, 8)
        val base = field("Base URL (শুধু OpenAI-সামঞ্জস্যপূর্ণ হলে)", cur.base)
        addTo(adv, base, 8)

        fun readCfg() = AiCfg(
            when (rg.checkedRadioButtonId) { rbG.id -> "gemini"; rbC.id -> "claude"; rbO.id -> "openai"; else -> "auto" },
            key.text.toString().trim(), model.text.toString().trim(), base.text.toString().trim(), info.text.toString().trim()
        )
        fun refresh() {
            val g = readCfg()
            val e = Ai.eff(g)
            when {
                g.key.isBlank() -> { chip.text = "চাবি বসালে এখানে দেখাবে কোন AI চেনা গেছে"; chip.setTextColor(mute) }
                e != null -> { chip.text = "\u2714  ${e.label} চেনা গেছে" + (if (e.id == "openai" && e.model.isBlank()) " (মডেলের নাম দিন)" else " \u00B7 মডেল: ${e.model}"); chip.setTextColor(accent) }
                else -> { chip.text = "চাবির ধরন চেনা যায়নি; নিচের \"উন্নত\" থেকে বেছে নিন"; chip.setTextColor(warn) }
            }
            base.visibility = if (e?.id == "openai") View.VISIBLE else View.GONE
        }
        val tw = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        }
        key.addTextChangedListener(tw)
        model.addTextChangedListener(tw)
        rg.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()

        addTo(box, ghost("পরীক্ষা করুন") {
            val q = testQ.text.toString().trim()
            if (q.isNotEmpty()) {
                val g = readCfg()
                testOut.visibility = View.VISIBLE
                testOut.setTextColor(mute)
                testOut.text = "অপেক্ষা করুন…"
                Thread {
                    val r = Ai.test(g, q)
                    runOnUiThread {
                        testOut.setTextColor(if (r.first != null) ink else warn)
                        testOut.text = if (r.first != null) "প্রশ্ন: $q\n\n" + r.first else ("ব্যর্থ: " + r.second)
                        // উত্তর এলে ঘর খালি হয় (আবার নতুন প্রশ্ন লেখার জন্য); ব্যর্থ হলে প্রশ্ন থাকে, যেন আবার চেষ্টা করা যায়
                        if (r.first != null && testQ.text.toString().trim() == q) testQ.setText("")
                    }
                }.start()
            }
        }, 10)
        addTo(box, testOut, 8)

        val advBtn = tv("উন্নত সেটিংস  \u25BE", 13f, mute, true)
        advBtn.setPadding(0, d(8), 0, d(8))
        advBtn.setOnClickListener {
            val open = adv.visibility != View.VISIBLE
            adv.visibility = if (open) View.VISIBLE else View.GONE
            advBtn.text = if (open) "উন্নত সেটিংস  \u25B4" else "উন্নত সেটিংস  \u25BE"
        }
        addTo(box, advBtn, 14)
        addTo(box, adv, 0)

        // নিচের দুই বোতাম
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val cancel = ghost("বাতিল", mute) { dlg.dismiss() }
        val save = primary("সংরক্ষণ") {
            Store.saveAi(this, readCfg())
            Toast.makeText(this, "সংরক্ষণ হয়েছে", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        l2.leftMargin = d(10)
        btns.addView(cancel, l1)
        btns.addView(save, l2)
        addTo(box, btns, 18)

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.setPadding(d(12), d(24), d(12), d(24))
        sv.clipToPadding = false
        sv.addView(box)
        dlg.setView(sv)
        dlg.show()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
