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
import android.graphics.Path
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
    private lateinit var barHolder: FrameLayout
    private var menuOpen = false
    private var pendingInstallUrl: String? = null // অনুমতি দিয়ে ফিরলে আপডেট নিজে শুরু হবে
    private var update: Updater.Info? = null
    private var wantInstall = false             // আপডেট-নোটিফিকেশনে চেপে ঢুকলে সরাসরি ইনস্টল শুরু হবে
    private var statusTv: TextView? = null
    private var lastCheck = 0L
    private var updBusy = false                    // আপডেট ডাউনলোড/ইনস্টল চলছে
    private var updStage = 0                       // ০ বসে আছে, ১ ডাউনলোড, ২ ইনস্টল নিশ্চিতকরণের অপেক্ষা
    private var updPct = -1                        // ডাউনলোডের শতাংশ (-১ মানে অজানা)
    private lateinit var editPage: FrameLayout     // রুল এডিটর: আলাদা পুরো পাতা
    private var editDirty: (() -> Boolean)? = null
    private lateinit var fabV: FabView            // নিচে ডানের গোল বোতাম: + (নতুন নিয়ম) ⇄ ✔ (এডিটরে সংরক্ষণ)
    private var editSave: (() -> Unit)? = null    // এডিটর খোলা থাকলে ✔ চাপলে যা চলবে
    private var editPrevMode = 0
    private lateinit var updBtn: TextView        // নিচে মাঝখানে ভাসমান আপডেট বোতাম (আপডেট থাকলেই দেখায়)

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
            setPadding(d(16), d(8), d(16), d(100))
        }
        val sv = ScrollView(this).apply { addView(root) }

        // ওপরের নেভিগেশন বার: বাঁয়ে মেনু, মাঝে নাম, ডানে মূল চালু/বন্ধ সুইচ (build() প্রতিবার নতুন করে আঁকে)
        window.statusBarColor = Color.parseColor("#0E3A36")
        barHolder = FrameLayout(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(barHolder, LinearLayout.LayoutParams(mp, wc))
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
        // নিচে ডানে ভাসমান বোতাম: সাধারণত + (নতুন নিয়ম); এডিটর খুললে অ্যানিমেশনে ✔ (সংরক্ষণ) হয়ে যায়
        fabV = FabView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            elevation = d(6).toFloat()
            contentDescription = "নতুন নিয়ম"
            setOnClickListener {
                if (editPage.visibility == View.VISIBLE) editSave?.invoke() else edit(null, Store.rules(this@MainActivity))
            }
        }
        // নিচে মাঝখানে ভাসমান আপডেট বোতাম, + বোতামের সমান লাইনে
        updBtn = object : TextView(this) {
            private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                if (updBusy) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val r = h / 2
                    fp.color = Color.parseColor("#134A44") // খালি অংশ
                    c.drawRoundRect(0f, 0f, w, h, r, r, fp)
                    if (updPct >= 0) {
                        fp.color = accent                    // ভরা অংশ
                        c.save()
                        c.clipRect(0f, 0f, w * updPct / 100f, h)
                        c.drawRoundRect(0f, 0f, w, h, r, r, fp)
                        c.restore()
                    }
                }
                super.onDraw(c)
            }
        }.apply {
            text = "Update"
            textSize = 15f
            includeFontPadding = false
            setTextColor(Color.parseColor("#06201C"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(accent, 26)
            setPadding(d(26), 0, d(26), 0)
            elevation = d(6).toFloat()
            visibility = View.GONE
        }
        frame.addView(updBtn, FrameLayout.LayoutParams(wc, d(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = d(27)
        })
        editPage = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F14"))
            visibility = View.GONE
            isClickable = true
        }
        frame.addView(editPage, FrameLayout.LayoutParams(mp, mp))   // এডিটর পাতা আপডেট বোতামকে ঢেকে রাখে
        frame.addView(fabV, FrameLayout.LayoutParams(d(58), d(58), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = d(20)
            bottomMargin = d(24)
        })                                                           // + / ✔ বোতাম এডিটর পাতার ওপরেই থাকে
        frame.addView(scrim, FrameLayout.LayoutParams(mp, mp))
        frame.addView(sideSv, FrameLayout.LayoutParams(sideW(), mp, Gravity.START))
        setContentView(frame)
    }

    // ওপরের নেভিগেশন বার
    private fun makeBar(on: Boolean): View {
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E3A36"))
            setPadding(d(6), d(26), d(18), d(8))
            elevation = d(4).toFloat()
        }
        val menuBtn = FrameLayout(this)
        val lines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(14), d(14), d(14))
        }
        for (k in 0 until 3) {
            val b = View(this)
            b.background = rounded(Color.WHITE, 2)
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
        val t = tv("AutoReply", 20f, Color.WHITE, true)
        t.setPadding(d(6), 0, 0, 0)
        bar.addView(t, LinearLayout.LayoutParams(0, wc, 1f))
        bar.addView(sw(on) { v -> setMaster(v) })
        return bar
    }

    // মূল চালু/বন্ধ: ওপরের বার ও মেনু, দুই জায়গার সুইচই এটা ব্যবহার করে
    private fun setMaster(v: Boolean) {
        val c = this
        Store.setEnabled(c, v)
        if (v) {
            KeepAliveService.start(c)
            Watchdog.schedule(c)
            Watchdog.rebind(c, false)
        } else KeepAliveService.stop(c)
        if (v && !granted()) Toast.makeText(c, "আগে নোটিফিকেশন অ্যাক্সেস দিন", Toast.LENGTH_LONG).show()
        handler.post { build() }
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()
        sideSv.visibility = View.VISIBLE
        sideSv.translationX = -sideW().toFloat()
        sideSv.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        scrim.animate().alpha(0f).setDuration(160).withEndAction { if (!menuOpen) scrim.visibility = View.GONE }.start()
        sideSv.animate().translationX(-sideW().toFloat()).setDuration(180).withEndAction { if (!menuOpen) sideSv.visibility = View.GONE }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (editPage.visibility == View.VISIBLE) closeEditor(false) else if (menuOpen) closeMenu() else super.onBackPressed()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (i != null) setIntent(i)
        if (i?.getBooleanExtra("upd", false) == true) wantInstall = true
    }

    override fun onResume() {
        super.onResume()
        if (updStage == 2) resetUpd() // ইনস্টল নিশ্চিতকরণের পর্দা থেকে ফিরলে (বাতিল করলে) বোতাম আগের অবস্থায়
        // App info থেকে ফিরলে (ব্যবহারকারীর নিজের চাপের পরে) একবার সংশ্লিষ্ট সেটিংস পাতা আবার খুলে দেয়
        val pend = Store.sp(this).getString("info_for", "") ?: ""
        if (pend.isNotEmpty()) {
            // তালিকা/App info থেকে ফেরা মানে Restricted settings-এর ধাপ শেষ: "চেষ্টা চলছে" চিহ্নও মুছে দিই, নইলে বোতাম চিরকাল "Restricted settings অনুমতি দিন"-এ আটকে থাকে।
            // এবার বোতাম আবার স্বাভাবিক লেখায় ফেরে; অনুমতি দেওয়ার পরও ব্লক থাকলে পরের চাপে আবার "Restricted settings" দেখাবে।
            Store.sp(this).edit().remove("info_for").remove("try_$pend").apply()
            if (pend == "notif" && !granted()) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            else if (pend == "a11y" && !a11yOn()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Watchdog.schedule(this)
        Store.ev(this, "অ্যাপ খোলা হয়েছে")
        if (Store.enabled(this)) KeepAliveService.start(this)
        Watchdog.rebind(this, false)
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

    private fun startUpd() {
        updBusy = true
        updStage = 1
        updPct = 0
        updBtn.minWidth = d(250)
        updBtn.setTextColor(Color.WHITE)
        updBtn.setShadowLayer(d(3).toFloat(), 0f, 0f, Color.parseColor("#CC000000"))
        updBtn.text = "Downloading… 0%"
        updBtn.invalidate()
    }

    private fun setUpd(pct: Int, label: String) {
        updPct = pct
        updBtn.text = label
        updBtn.invalidate()
    }

    private fun resetUpd() {
        updBusy = false
        updStage = 0
        updPct = -1
        updBtn.minWidth = 0
        updBtn.setTextColor(Color.parseColor("#06201C"))
        updBtn.setShadowLayer(0f, 0f, 0f, 0)
        updBtn.text = if (packageManager.canRequestPackageInstalls()) "Update" else "Allow update"
        updBtn.invalidate()
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
        if (updBusy) return
        startUpd() // আলাদা লেখা নয়: আপডেট বোতামটাই প্রগ্রেস বার হয়ে যায়
        Thread {
            val inst = packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED) // নীরব ইনস্টল চাওয়া হয় না: কিছু ফোন সেটা আটকে INSTALL_FAILED_ABORTED দেয়
            val id = inst.createSession(p)
            try {
                val s = inst.openSession(id)
                val cn = URL(url).openConnection() as HttpURLConnection
                cn.connectTimeout = 15000
                cn.readTimeout = 30000
                if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
                val total = cn.contentLengthLong
                cn.inputStream.use { ins ->
                    s.openWrite("a", 0, -1).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    runOnUiThread { setUpd(pct, "Downloading… $pct%") }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
                val pi = PendingIntent.getBroadcast(
                    this, id, Intent(this, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                runOnUiThread {
                    updStage = 2
                    setUpd(100, "Installing…")
                    handler.postDelayed({ if (updStage == 2) resetUpd() }, 60000L)
                }
                s.commit(pi.intentSender)
            } catch (e: Exception) {
                try { inst.abandonSession(id) } catch (x: Exception) {}
                runOnUiThread { resetUpd(); Toast.makeText(this, "ডাউনলোড বা ইনস্টল শুরু করা গেল না (${e.javaClass.simpleName}: ${e.message?.take(70)}) — নিচের ব্রাউজার বোতামটা ব্যবহার করুন", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun a11yOn() = (Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: "").contains("$packageName/")

    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

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
        val t = statusTv
        if (t != null) {
            val st = statusLine()
            t.text = st.first
            t.setTextColor(st.second)
        }
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

        // আঁকা মাপ ৩৬×২১ dp; চারপাশে অদৃশ্য ফাঁক রাখা হয়েছে যাতে আঙুলে চাপতে সুবিধা হয় (পুরো মাপ ৪৮×৩৫ dp)
        private val padX = d(6)
        private val padY = d(7)

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(d(36) + 2 * padX, d(21) + 2 * padY)

        override fun onDraw(c: Canvas) {
            val w = d(36).toFloat()
            val h = d(21).toFloat()
            val r = h / 2
            c.save()
            c.translate(padX.toFloat(), padY.toFloat())
            p.color = ev.evaluate(pos, trackOff, accent) as Int
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
            val m = 2.5f * resources.displayMetrics.density
            val tr = r - m
            val cx = r + (w - 2 * r) * pos
            p.color = ev.evaluate(pos, thumbOff, Color.WHITE) as Int
            c.drawCircle(cx, r, tr, p)
            c.restore()
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

    // একটা দলের (WhatsApp/Facebook/Telegram) সব ভেরিয়েন্ট: ফোনে ইনস্টল থাকা + যেগুলো থেকে নোটিফিকেশন এসেছে (ক্লোন/ডুয়াল সহ)
    private fun variantsOf(g: String): List<Triple<String, Int, String>> {
        val out = LinkedHashMap<String, Triple<String, Int, String>>()
        fun nameOf(pkg: String, uid: Int): String {
            val base = Store.APPS[pkg] ?: try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
            return if (uid != 0) "$base (ইউজার $uid)" else base
        }
        @Suppress("DEPRECATION")
        val ris = try {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } catch (e: Exception) { emptyList() }
        for (ri in ris) {
            val pkg = ri.activityInfo.packageName
            if (Store.grp(pkg) == g && !Store.ignored(pkg)) out.putIfAbsent("$pkg@0", Triple(pkg, 0, nameOf(pkg, 0)))
        }
        for (i in Store.insts(this)) {
            if (Store.grp(i.pkg) == g) out.putIfAbsent("${i.pkg}@${i.uid}", Triple(i.pkg, i.uid, nameOf(i.pkg, i.uid)))
        }
        return out.values.sortedBy { it.third.lowercase() }
    }

    // দলের নামে চাপলে খোলে: ওই দলের প্রতিটি ভেরিয়েন্টের পাশে আলাদা সুইচ
    private fun variantsDialog(g: String) {
        val list = variantsOf(g)
        val dlg = AlertDialog.Builder(this).create()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(panel, 24, line)
        box.setPadding(d(20), d(20), d(20), d(18))
        addTo(box, tv(g, 18f, bold = true), 0)
        val sub = if (list.isEmpty()) "এই ফোনে এই অ্যাপের কোনো ভেরিয়েন্ট পাওয়া যায়নি। কোনো কপি থেকে প্রথম নোটিফিকেশন এলে এখানে দেখা যাবে।"
        else "প্রতিটি ভেরিয়েন্ট আলাদা করে চালু বা বন্ধ করা যায়। ওপরের মূল সুইচ বন্ধ থাকলে কোনোটিই চলবে না।"
        addTo(box, tv(sub, 12f, mute), 4)
        for ((pkg, uid, name) in list) {
            val pk: String? = if (Store.APPS.containsKey(pkg)) null else pkg
            addTo(box, swRow(name, pk, Store.varOn(this, pkg, uid)) { v -> Store.setVarOn(this, pkg, uid, v) }, 14)
        }
        addTo(box, ghost("ঠিক আছে") { dlg.dismiss() }, 16)
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(box)
        dlg.setView(sv)
        dlg.show()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // সাইডবারের অ্যাপের ঘর (দুই কলামের গ্রিডের জন্য): ওপরে নাম, নিচে খুঁটিনাটি, সবার নিচে অবস্থা ও সুইচ
    private fun swCell(t: String, details: List<String>, on: Boolean, onTap: (() -> Unit)? = null, f: (Boolean) -> Unit): LinearLayout {
        val r = LinearLayout(this)
        if (onTap != null) r.setOnClickListener { onTap() }
        r.orientation = LinearLayout.VERTICAL
        r.gravity = Gravity.CENTER_HORIZONTAL
        r.background = rounded(panel, 16, line)
        r.setPadding(d(8), d(12), d(8), d(10))
        // নাম এক লাইনে থাকে; জায়গা কম হলে অক্ষর নিজে ছোট হয়, ভাঙে না
        val name = tv(t, 13f, bold = true)
        name.gravity = Gravity.CENTER
        name.maxLines = 1
        name.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        r.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // সুইচ নামের ঠিক নিচেই
        val s = sw(on) { v -> f(v) }
        addTo(r, s, 8)
        (s.layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for (x in details) {
            val dv = tv(x, 10f, mute)
            dv.maxLines = 1
            dv.gravity = Gravity.CENTER
            dv.ellipsize = TextUtils.TruncateAt.MIDDLE
            addTo(r, dv, 4)
        }
        return r
    }

    private fun grid(p: LinearLayout, cells: List<View>, top: Int = 8, cols: Int = 2) {
        var i = 0
        while (i < cells.size) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until cols) {
                val v = cells.getOrNull(i + k) ?: View(this)
                r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (k > 0) leftMargin = d(4)
                    if (k < cols - 1) rightMargin = d(4)
                })
            }
            addTo(p, r, if (i == 0) top else 8)
            i += cols
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
        barHolder.removeAllViews()
        barHolder.addView(makeBar(on))
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

        statusTv = null // মূল সুইচ এখন শুধু ওপরের বারে; সাইডবারে আলাদা কার্ড নেই
        fillStatus()

        // ========== মূল পাতা: শুধু উত্তরের নিয়ম ==========
        // উত্তরের নিয়ম
        val rules = Store.rules(c)
        val rc = section()
        for (r in rules) {
            // ছোট একটা সারি: লেখায় চাপলেই সম্পাদনা (মুছুনও সেখানে), একেবারে ডানে সুইচ
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.background = rounded(panel, 14, if (r.on) accent else line)
            top.setPadding(d(14), d(10), d(10), d(10))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val rcv = tv("Receive:  " + r.keys, 14f, bold = true)
            rcv.maxLines = 2
            rcv.ellipsize = TextUtils.TruncateAt.END
            col.addView(rcv)
            val ln = View(this)
            ln.setBackgroundColor(mute and 0x00FFFFFF or 0x55000000)
            col.addView(ln, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = d(6) })
            val rep = tv("Send:  " + r.reply, 13f, mute)
            rep.maxLines = 2
            rep.ellipsize = TextUtils.TruncateAt.END
            rep.setPadding(0, d(6), 0, 0)
            col.addView(rep)
            col.setOnClickListener { edit(r, rules) }
            top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // তীর চিহ্ন: চাপলে Send-এর লেখাটা শেয়ার অপশনে (যেকোনো অ্যাপে, যে কাউকে) পাঠানো যায়
            val plane = object : View(this) {
                override fun onDraw(cv: Canvas) {
                    val w = width.toFloat(); val h = height.toFloat()
                    val pt = Paint(Paint.ANTI_ALIAS_FLAG); pt.color = mute
                    val path = Path()
                    path.moveTo(w * 0.12f, h * 0.15f); path.lineTo(w * 0.92f, h * 0.5f); path.lineTo(w * 0.12f, h * 0.85f)
                    path.lineTo(w * 0.28f, h * 0.5f); path.close()
                    cv.drawPath(path, pt)
                }
            }
            plane.contentDescription = "শেয়ার"
            plane.setOnClickListener {
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TEXT, r.reply)
                try { startActivity(Intent.createChooser(i, "পাঠান")) } catch (e: Exception) {}
            }
            top.addView(plane, LinearLayout.LayoutParams(d(30), d(30)).apply { rightMargin = d(10); leftMargin = d(6) })
            top.addView(sw(r.on) { v ->
                r.on = v
                Store.saveRules(c, rules)
                top.background = rounded(panel, 14, if (v) accent else line)
            })
            addTo(rc, top, if (r === rules.first()) 0 else 10)
        }
        add(rc, 0)

        // নতুন সংস্করণ এলে শুধু তখনই নিচে মাঝখানে "আপডেট করুন" বোতাম দেখায় (কোনো কার্ড নয়)
        if (up != null) {
            if (!updBusy) updBtn.text = if (packageManager.canRequestPackageInstalls()) "Update" else "Allow update"
            updBtn.setOnClickListener { if (!updBusy) install(up.url) }
            updBtn.visibility = if (editPage.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        } else {
            updBtn.visibility = View.GONE
        }

        // ========== সাইডবারের বাকি অংশ ==========
        // অনুমতি: শুধু যেগুলো বাকি আছে সেগুলোর বোতাম দেখায়; সব ঠিক থাকলে কার্ডটাই থাকে না।
        // শক্তিশালী ব্যাকগ্রাউন্ড মোড সবসময় চালু (আলাদা সুইচ নেই)
        val missing = ArrayList<View>()
        // ধাপ ১: প্রথম চাপে সরাসরি সেটিংস; ব্লক থাকলে ফিরে এলে বোতামটা "Restricted settings অনুমতি দিন" হয়ে App info খোলে;
        // App info থেকে ফিরলে অ্যাপ নিজে আবার ওই সেটিংস পাতা খুলে দেয় (onResume দেখুন)
        fun gate(label: String, key: String, tried: Boolean, settings: String) = ghost(
            if (tried) "Restricted settings অনুমতি দিন" else label, warn
        ) {
            if (tried) {
                // হোম স্ক্রিনের পথ বাদ: বোতামে চাপলেই সরাসরি ফোনের অ্যাপ তালিকা খোলে (App info সরাসরি খুললে অনেক ফোনে ⋮ মেনু আসে না, তালিকা থেকে খুললে আসে)।
                // তালিকা থেকে Auto Reply-এ চেপে ⋮ → Allow restricted settings; ফিরে এলে অ্যাপ নিজে আবার সেটিংস পাতা খুলে দেয় (onResume)
                Store.sp(this).edit().putString("info_for", key).apply()
                Toast.makeText(this, "তালিকায় \"Auto Reply\" খুঁজে চাপুন, ওপরে ডানে ⋮ চেপে \"Allow restricted settings\" দিন, তারপর ফিরে আসুন", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                }
            } else {
                Store.sp(this).edit().putBoolean("try_$key", true).apply()
                startActivity(Intent(settings))
            }
        }
        if (ok) Store.sp(this).edit().remove("try_notif").apply()
        if (a11yOn()) Store.sp(this).edit().remove("try_a11y").apply()
        if (!ok) missing.add(gate("নোটিফিকেশন অ্যাক্সেস দিন", "notif", Store.sp(this).getBoolean("try_notif", false), Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        if (!a11yOn()) missing.add(gate("Accessibility চালু করুন", "a11y", Store.sp(this).getBoolean("try_a11y", false), Settings.ACTION_ACCESSIBILITY_SETTINGS))
        if (!free) missing.add(ghost("ব্যাটারি সেভার বাদ দিন", warn) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        // Auto-start চালু আছে কি না ফোন থেকে জানা যায় না, তাই একবার চাপলেই তালিকা থেকে সরে যায়
        if (!Store.flag(this, "s_auto")) {
            missing.add(ghost("Auto-start অনুমতি", warn) {
                Store.setFlag(this, "s_auto")
                openAutostart()
            })
        }
        dot.visibility = if (missing.isNotEmpty()) View.VISIBLE else View.GONE // মেনুর লাল বিন্দু: কোনো অনুমতি বাকি আছে
        if (missing.isNotEmpty()) {
            val perm = card(warn)
            title(perm, "অনুমতি বাকি আছে", "এগুলো চালু করুন")
            grid(perm, missing, 12)
            addSide(perm, 16)
        }

        if (Store.need(c).isNotEmpty() && missing.isEmpty()) Store.setNeed(c, emptySet()) // সমাধান হয়ে গেছে

        // AI স্বয়ংক্রিয় চ্যাট: নিয়ম না মিললে প্রতিষ্ঠানের তথ্য থেকে AI উত্তর দেয়
        val aiCard = card()
        val aiRow = LinearLayout(this)
        aiRow.orientation = LinearLayout.HORIZONTAL
        aiRow.gravity = Gravity.CENTER_VERTICAL
        aiRow.addView(tv("AI চ্যাট", 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        aiRow.addView(ghost("সেটিংস") { aiDialog() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = d(12) })
        aiRow.addView(sw(Store.aiOn(c)) { v ->
            Store.setAiOn(c, v)
            if (v && !Ai.configured(Ai.cfg(c))) Toast.makeText(c, "আগে \"সেটিংস\"-এ API কী এবং নির্দেশ বা তথ্য দিন", Toast.LENGTH_LONG).show()
        })
        aiCard.addView(aiRow)
        addSide(aiCard, 16)

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
        bullets(apps, listOf("প্রতিটি অ্যাপ আলাদা করে চালু বা বন্ধ করা যায়", "নামে চাপলে ওই অ্যাপের সব ভেরিয়েন্ট (Business, ক্লোন...) আলাদা সুইচসহ দেখা যায়"), 6)
        val cells = ArrayList<View>()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        val shownG = HashSet<String>()
        for (pkg in Store.APPS.keys) {
            val g = Store.grp(pkg) ?: continue
            if (!shownG.add(g)) continue
            cells.add(swCell(g, listOf("ভেরিয়েন্ট দেখুন ›"), Store.grpOn(c, g), { variantsDialog(g) }) { v -> Store.setGrpOn(c, g, v) })
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
        grid(apps, cells, 12, 3)
        addSide(apps, 14)

        // সংস্করণ (আপডেটের বোতাম শুধু মূল পাতায়; সাইডবারে কেবল সংস্করণ দেখায়)
        val ver = card()
        @Suppress("DEPRECATION")
        val vName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        title(ver, "Version $vName.${Updater.curVer(c)}")
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
        val hlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        hlp.leftMargin = d(12)
        head.addView(ht, hlp)
        addTo(box, head, 0)

        // ১. API কী
        label(box, "API কী")
        val keyRow = LinearLayout(this)
        keyRow.orientation = LinearLayout.HORIZONTAL
        keyRow.gravity = Gravity.CENTER_VERTICAL
        val key = field("API কী পেস্ট করুন", cur.key, secret = true)
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

        // ২. নির্দেশ: কী করবে, কী করবে না
        label(box, "নির্দেশ")
        val cmd = field("কী করবে, কী করবে না", cur.cmd, lines = 4)
        addTo(box, cmd, 8)

        // ৩. প্রতিষ্ঠানের তথ্য
        label(box, "প্রতিষ্ঠানের তথ্য")
        val info = field("নাম, সেবা, দাম, ঠিকানা, সময়, যোগাযোগ", cur.info, lines = 5)
        addTo(box, info, 8)

        // ৩. পরীক্ষা
        label(box, "পরীক্ষা")
        val testQ = field("প্রশ্ন লিখুন", "")
        addTo(box, testQ, 8)
        val testOut = tv("", 13f, ink)
        testOut.background = rounded(inner, 12, line)
        testOut.setPadding(d(14), d(12), d(14), d(12))
        testOut.visibility = View.GONE

        // উন্নত (ঐচ্ছিক)
        val adv = LinearLayout(this)
        adv.orientation = LinearLayout.VERTICAL
        adv.visibility = View.GONE
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
        val rbA = rb("স্বয়ংক্রিয়")
        val rbG = rb("Gemini (Google)")
        val rbC = rb("Claude (Anthropic)")
        val rbO = rb("OpenAI-compatible")
        rg.check(when (cur.prov) { "gemini" -> rbG.id; "claude" -> rbC.id; "openai" -> rbO.id; else -> rbA.id })
        addTo(adv, rg, 10)
        val model = field("মডেল (ঐচ্ছিক)", cur.model)
        addTo(adv, model, 8)
        val base = field("Base URL", cur.base)
        addTo(adv, base, 8)

        fun readCfg() = AiCfg(
            when (rg.checkedRadioButtonId) { rbG.id -> "gemini"; rbC.id -> "claude"; rbO.id -> "openai"; else -> "auto" },
            key.text.toString().trim(), model.text.toString().trim(), base.text.toString().trim(), info.text.toString().trim(), cmd.text.toString().trim()
        )
        fun refresh() {
            val g = readCfg()
            val e = Ai.eff(g)
            when {
                g.key.isBlank() -> { chip.text = ""; chip.visibility = View.GONE }
                e != null -> { chip.visibility = View.VISIBLE; chip.text = "\u2714  ${e.label}" + (if (e.id == "openai" && e.model.isBlank()) " · মডেল দিন" else " \u00B7 ${e.model}"); chip.setTextColor(accent) }
                else -> { chip.visibility = View.VISIBLE; chip.text = "চেনা যায়নি"; chip.setTextColor(warn) }
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

    // ছোট আঁকা আইকন (তীর, তিন-ডট, ✔): ফন্টের চিহ্নের ওপর নির্ভর করে না
    private fun icon(sizeDp: Int, draw: (Canvas, Float, Float, Paint) -> Unit): View {
        return object : View(this) {
            private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = d(3).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = Color.WHITE
            }
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(d(sizeDp), d(sizeDp))
            override fun onDraw(c: Canvas) = draw(c, width.toFloat(), height.toFloat(), pt)
        }
    }

    // রুল এডিটর: আলাদা পুরো পাতা। ওপরে ব্যাক-তীর, "Rule N", তিন-ডট মেনু (মুছুন); কার্ড: Received message, Automatic reply, Reply to; নিচে ডানে ✔ (সংরক্ষণ)
    private fun edit(r: Rule?, rules: MutableList<Rule>) {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        editPage.removeAllViews()
        val num = if (r == null) rules.size + 1 else rules.indexOf(r) + 1

        fun field(hint: String, text: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(text)
            e.setTextColor(ink)
            e.setHintTextColor(Color.parseColor("#5E6F74"))
            e.textSize = 17f
            e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.minLines = 1
            e.maxLines = 6
            e.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6B7B80"))
            e.setPadding(d(4), d(12), d(4), d(12))
            return e
        }
        // কার্ড: বড় সবুজ শিরোনাম, ছোট লেখা, নিচে সরু সবুজ দাগ, তারপর ভেতরের অংশ
        fun sect(title: String, sub: String, body: View): LinearLayout {
            val cd = card()
            cd.addView(tv(title, 21f, accent))
            val st = tv(sub, 12f, accent)
            st.setPadding(0, d(2), 0, d(10))
            cd.addView(st)
            val ln = View(this)
            ln.setBackgroundColor(accent)
            cd.addView(ln, LinearLayout.LayoutParams(mp, d(2)))
            addTo(cd, body, 10)
            return cd
        }

        val md = intArrayOf(r?.mode ?: Store.M_EXACT) // নতুন নিয়ম "Exact match" দিয়ে শুরু হয়
        fun hintFor(m: Int) = when (m) {
            Store.M_REGEX -> "Type a RegEx (one per line)"
            Store.M_PATTERN -> "Type a pattern, use * for any text (separate with commas)"
            else -> "Type the message (separate variants with commas)"
        }
        val k = field(hintFor(md[0]), r?.keys ?: "")
        val modes = listOf(
            Triple(Store.M_EXACT, "Exact match",
                "The whole message must be the same as one of your messages. Capital letters, extra spaces and punctuation (! ? । .) are ignored.\n\nExample: \"assalamu alaikum\" matches \"Assalamu Alaikum!\" but NOT \"Karim told Rahim assalamu alaikum\"."),
            Triple(Store.M_SIMILAR, "Similarity match",
                "The whole message only needs to be about 80% similar to one of your messages, so small spelling mistakes still match.\n\nExample: \"assalamualaikum\" matches \"assalamu alaikum\"."),
            Triple(Store.M_PATTERN, "Pattern matching",
                "Use * for any text. The whole message must fit the pattern.\n\n\"hello*\" matches anything that starts with hello.\n\"*price*\" matches any message that contains price."),
            Triple(Store.M_REGEX, "Expert pattern matching (RegEx)",
                "Write a regular expression, one per line. It matches when it is found anywhere in the message; use ^ and \$ to match the whole message."),
            Triple(Store.M_CONTAINS, "Contains",
                "The message matches if it contains one of your words anywhere (the old behavior). Short English words like \"hi\" must stand alone.")
        )
        val dots = ArrayList<RadioDot>()
        fun refreshModes() {
            for ((i, m) in modes.withIndex()) dots[i].checked = md[0] == m.first
            k.hint = hintFor(md[0])
        }
        val recv = LinearLayout(this)
        recv.orientation = LinearLayout.VERTICAL
        recv.addView(k)
        for ((i, m) in modes.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val dot = RadioDot(this, accent)
            dots.add(dot)
            row.addView(dot)
            val lb = tv(m.second, 16f)
            lb.setPadding(d(12), d(10), d(8), d(10))
            row.addView(lb, LinearLayout.LayoutParams(0, wc, 1f))
            val info = TextView(this)
            info.text = "i"
            info.gravity = Gravity.CENTER
            info.textSize = 13f
            info.setTypeface(info.typeface, Typeface.BOLD)
            info.setTextColor(Color.parseColor("#06201C"))
            info.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            info.setOnClickListener {
                AlertDialog.Builder(this).setTitle(m.second).setMessage(m.third).setPositiveButton("OK", null).show()
            }
            row.addView(info, LinearLayout.LayoutParams(d(24), d(24)))
            row.setOnClickListener { md[0] = m.first; refreshModes() }
            addTo(recv, row, if (i == 0) 14 else 0)
        }
        refreshModes()
        val rp = field("Type the reply", r?.reply ?: "")
        val st = booleanArrayOf(r?.person ?: true, r?.group ?: false) // [People, Groups]
        val who = LinearLayout(this)
        who.orientation = LinearLayout.HORIZONTAL
        who.addView(swRow("People", "Individual chats", st[0]) { st[0] = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = d(10) })
        who.addView(swRow("Groups", "Group chats", st[1]) { st[1] = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = d(10) })

        editDirty = {
            k.text.toString().trim() != (r?.keys ?: "") || rp.text.toString().trim() != (r?.reply ?: "") ||
                st[0] != (r?.person ?: true) || st[1] != (r?.group ?: false) || md[0] != (r?.mode ?: Store.M_EXACT)
        }

        // ওপরের বার
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#0E3A36"))
        bar.setPadding(d(6), d(26), d(6), d(8))
        bar.elevation = d(4).toFloat()
        val back = icon(48) { c, w, h, p ->
            val path = Path()
            path.moveTo(w * 0.58f, h * 0.28f)
            path.lineTo(w * 0.36f, h * 0.5f)
            path.lineTo(w * 0.58f, h * 0.72f)
            c.drawPath(path, p)
            c.drawLine(w * 0.36f, h * 0.5f, w * 0.7f, h * 0.5f, p)
        }
        back.setOnClickListener { closeEditor(false) }
        bar.addView(back)
        val ttl = tv(if (r == null) "New rule" else if (num > 0) "Rule $num" else "Rule", 20f, Color.WHITE, true)
        ttl.setPadding(d(8), 0, 0, 0)
        bar.addView(ttl, LinearLayout.LayoutParams(0, wc, 1f))
        if (r != null) {
            val more = icon(48) { c, w, h, p ->
                p.style = Paint.Style.FILL
                for (f in floatArrayOf(0.3f, 0.5f, 0.7f)) c.drawCircle(w * 0.5f, h * f, d(2).toFloat() + 0.5f, p)
                p.style = Paint.Style.STROKE
            }
            more.setOnClickListener {
                val pm = android.widget.PopupMenu(this, more)
                pm.menu.add("Delete rule")
                pm.setOnMenuItemClickListener {
                    AlertDialog.Builder(this)
                        .setMessage("Delete this rule?")
                        .setPositiveButton("Delete") { _, _ ->
                            rules.remove(r)
                            Store.saveRules(this, rules)
                            closeEditor(true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                pm.show()
            }
            bar.addView(more)
        }

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(d(12), d(12), d(12), d(110))
        body.addView(sect("Received message", "The incoming message that triggers this reply", recv))
        addTo(body, sect("Automatic reply", "What gets sent back automatically", rp), 12)
        addTo(body, sect("Reply to", "Where this reply works", who), 12)
        val sv = ScrollView(this)
        sv.addView(body)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(bar, LinearLayout.LayoutParams(mp, wc))
        col.addView(sv, LinearLayout.LayoutParams(mp, 0, 1f))
        editPage.addView(col, FrameLayout.LayoutParams(mp, mp))

        // সংরক্ষণ: নিচে ডানের মূল বোতাম (এডিটর খুললে যেটা ✔ হয়ে যায়) চাপলে এটা চলে
        editSave = save@{
            val ks = k.text.toString().trim()
            val rs = rp.text.toString().trim()
            if (ks.isEmpty() || rs.isEmpty()) {
                Toast.makeText(this, "Fill in both the received message and the reply", Toast.LENGTH_SHORT).show()
                return@save
            }
            if (!st[0] && !st[1]) {
                Toast.makeText(this, "Turn on People or Groups (at least one)", Toast.LENGTH_SHORT).show()
                return@save
            }
            if (md[0] == Store.M_REGEX && ks.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.any { x -> try { Regex(x); false } catch (e: Exception) { true } }) {
                Toast.makeText(this, "This RegEx is not valid", Toast.LENGTH_SHORT).show()
                return@save
            }
            if (r == null) rules.add(Rule(ks, rs, true, st[1], st[0], md[0])) else {
                r.mode = md[0]
                r.keys = ks
                r.reply = rs
                r.group = st[1]
                r.person = st[0]
            }
            Store.saveRules(this, rules)
            closeEditor(true)
        }

        editPrevMode = window.attributes.softInputMode
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editPage.visibility = View.VISIBLE
        updBtn.visibility = View.GONE
        fabV.morph(true)   // + → ✔
    }

    // এডিটর বন্ধ: বদল থাকলে (এবং force না হলে) আগে জিজ্ঞেস করে
    private fun closeEditor(force: Boolean) {
        if (!force && editDirty?.invoke() == true) {
            AlertDialog.Builder(this)
                .setMessage("Discard your changes?")
                .setPositiveButton("Discard") { _, _ -> closeEditor(true) }
                .setNegativeButton("Keep editing", null)
                .show()
            return
        }
        try {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(editPage.windowToken, 0)
        } catch (e: Exception) {}
        editPage.visibility = View.GONE
        editPage.removeAllViews()
        editDirty = null
        editSave = null
        fabV.morph(false)  // ✔ → +
        window.setSoftInputMode(editPrevMode)
        build()
    }
}

/** গোল রেডিও বোতাম: ফাঁপা বৃত্ত, বাছাই হলে ভেতরে ভরাট বিন্দু */
class RadioDot(ctx: android.content.Context, private val col: Int) : View(ctx) {
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    var checked = false
        set(v) {
            field = v
            invalidate()
        }

    override fun onMeasure(w: Int, h: Int) {
        val z = (24 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(z, z)
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val r = width / 2f
        pt.color = col
        pt.style = Paint.Style.STROKE
        pt.strokeWidth = 2 * d
        c.drawCircle(r, r, r - 1.5f * d, pt)
        if (checked) {
            pt.style = Paint.Style.FILL
            c.drawCircle(r, r, r - 6.5f * d, pt)
        }
    }
}

/** + ও ✔ একই দুটি দাগের রূপান্তর: প্রগতি p = 0 হলে +, p = 1 হলে ✔ (অ্যানিমেশনে বদলায়) */
class FabView(ctx: android.content.Context) : View(ctx) {
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private var p = 0f
    private var anim: android.animation.ValueAnimator? = null

    init {
        pt.color = Color.parseColor("#06201C")
        pt.style = Paint.Style.STROKE
        pt.strokeCap = Paint.Cap.ROUND
    }

    fun morph(toCheck: Boolean) {
        anim?.cancel()
        val a = android.animation.ValueAnimator.ofFloat(p, if (toCheck) 1f else 0f)
        a.duration = 240
        a.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        a.addUpdateListener {
            p = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
        contentDescription = if (toCheck) "সংরক্ষণ" else "নতুন নিয়ম"
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        pt.strokeWidth = w * 0.055f
        fun m(a: Float, b: Float) = a + (b - a) * p
        // অনুভূমিক দাগ → ✔-এর ছোট টান
        c.drawLine(w * m(0.36f, 0.36f), h * m(0.5f, 0.513f), w * m(0.64f, 0.462f), h * m(0.5f, 0.615f), pt)
        // খাড়া দাগ → ✔-এর লম্বা টান
        c.drawLine(w * m(0.5f, 0.654f), h * m(0.36f, 0.398f), w * m(0.5f, 0.462f), h * m(0.64f, 0.615f), pt)
    }
}
