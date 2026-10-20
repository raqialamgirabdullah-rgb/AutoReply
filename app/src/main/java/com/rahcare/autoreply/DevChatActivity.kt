package com.rahcare.autoreply

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.text.util.Linkify
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// GitHub টোকেন ফোনে সাধারণ লেখা হিসেবে না রেখে Android Keystore-এর চাবিতে এনক্রিপ্ট করে রাখে
object Secure {
    private const val ALIAS = "autoreply_dev_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val ex = ks.getKey(ALIAS, null)
        if (ex is SecretKey) return ex
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun dec(s: String): String? {
        return try {
            val all = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

// অ্যাপের ভেতর থেকে Gemini এজেন্টকে কাজ বলার চ্যাট: GitHub-এর একটা Issue-তে "@gemini-cli <কাজ>" কমেন্ট করে,
// এজেন্টের রিপোর্ট কমেন্টগুলো এখানে দেখায়। এজেন্ট একটা Pull Request খোলে; মার্জ আপনি GitHub-এ করবেন।
class DevChatActivity : Activity() {
    private val ink = Color.parseColor("#EAF2F3")
    private val mute = Color.parseColor("#8FA3A8")
    private val panel = Color.parseColor("#121A1F")
    private val inner = Color.parseColor("#0D1418")
    private val line = Color.parseColor("#1F2C32")
    private val accent = Color.parseColor("#5CC9BC")
    private val warn = Color.parseColor("#F2867F")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText
    private var shownSig = ""
    private var busy = false

    private val repo = Updater.REPO
    private val owner = Updater.REPO.substringBefore('/')

    private class Msg(val mine: Boolean, val text: String)

    private val poll = object : Runnable {
        override fun run() {
            if (token() != null) Thread { refresh(false) }.start()
            handler.postDelayed(this, 10000)
        }
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sp() = Store.sp(this)

    private fun token(): String? {
        val s = sp().getString("dev_tok", null) ?: return null
        return Secure.dec(s)
    }

    private fun rounded(fill: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = d(r).toFloat()
        if (stroke != 0) g.setStroke(d(1), stroke)
        return g
    }

    private fun tv(t: String, sp: Float = 15f, c: Int = ink, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp
        v.setTextColor(c)
        v.setLineSpacing(0f, 1.15f)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.parseColor("#0E3A36")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0B1115"))

        // ওপরের বার: পেছনে, নাম, মেনু
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#0E3A36"))
        bar.setPadding(d(8), d(10), d(8), d(10))
        val back = tv("←", 24f, ink, true)
        back.setPadding(d(12), d(4), d(12), d(4))
        back.setOnClickListener { finish() }
        bar.addView(back)
        bar.addView(tv("ডেভ চ্যাট", 20f, ink, true), LinearLayout.LayoutParams(0, wc, 1f))
        val more = tv("⋮", 24f, ink, true)
        more.setPadding(d(14), d(4), d(14), d(4))
        more.setOnClickListener { menu() }
        bar.addView(more)
        root.addView(bar, LinearLayout.LayoutParams(mp, wc))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(d(14), d(12), d(14), d(12))
        scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(mp, 0, 1f))

        // নিচের লেখার ঘর ও পাঠানোর বোতাম
        inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(d(12), d(8), d(12), d(10))
        input = EditText(this)
        input.hint = "কাজ লিখুন…"
        input.setTextColor(ink)
        input.setHintTextColor(mute)
        input.background = rounded(inner, 22, line)
        input.setPadding(d(16), d(10), d(16), d(10))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.maxLines = 5
        inputRow.addView(input, LinearLayout.LayoutParams(0, wc, 1f))
        val send = tv("পাঠান", 15f, Color.parseColor("#06201C"), true)
        send.gravity = Gravity.CENTER
        send.background = rounded(accent, 22)
        send.setPadding(d(18), d(11), d(18), d(11))
        send.setOnClickListener { sendMsg() }
        inputRow.addView(send, LinearLayout.LayoutParams(wc, wc).apply { leftMargin = d(8) })
        root.addView(inputRow, LinearLayout.LayoutParams(mp, wc))

        setContentView(root)
        drawState()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    private fun startPolling() {
        handler.removeCallbacks(poll)
        if (token() != null) {
            Thread { refresh(true) }.start()
            handler.postDelayed(poll, 10000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    // টোকেন না থাকলে টোকেন দেওয়ার পাতা, থাকলে চ্যাট
    private fun drawState() {
        if (token() == null) {
            inputRow.visibility = View.GONE
            list.removeAllViews()
            list.addView(tv("প্রথমে একটা GitHub টোকেন দিন", 17f, ink, true))
            val steps = listOf(
                "GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token",
                "Repository access: শুধু AutoReply রিপো বেছে নিন",
                "Permissions: Issues = Read and write",
                "Generate করে টোকেনটা কপি করুন, তারপর নিচে বসান",
                "এই টোকেন আর কোথাও (এমনকি Claude-এর কাছেও) পাঠাবেন না"
            )
            for (t in steps) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                val dot = tv("•", 13f, accent, true)
                dot.setPadding(0, 0, d(8), 0)
                r.addView(dot)
                r.addView(tv(t, 13f, mute), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = d(6)
                list.addView(r, lp)
            }
            val tok = EditText(this)
            tok.hint = "টোকেন (github_pat_…)"
            tok.setTextColor(ink)
            tok.setHintTextColor(mute)
            tok.background = rounded(inner, 12, line)
            tok.setPadding(d(14), d(12), d(14), d(12))
            tok.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val lp1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp1.topMargin = d(16)
            list.addView(tok, lp1)
            val save = tv("সংরক্ষণ", 15f, Color.parseColor("#06201C"), true)
            save.gravity = Gravity.CENTER
            save.background = rounded(accent, 12)
            save.setPadding(d(16), d(13), d(16), d(13))
            save.setOnClickListener {
                val t = tok.text.toString().trim()
                if (t.length < 20) {
                    Toast.makeText(this, "টোকেনটা ঠিকমতো বসান", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        sp().edit().putString("dev_tok", Secure.enc(t)).putInt("dev_issue", 0).apply()
                        shownSig = ""
                        drawState()
                        startPolling()
                    } catch (e: Exception) {
                        Toast.makeText(this, "টোকেন সংরক্ষণ করা গেল না", Toast.LENGTH_LONG).show()
                    }
                }
            }
            val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp2.topMargin = d(12)
            list.addView(save, lp2)
        } else {
            inputRow.visibility = View.VISIBLE
            if (shownSig.isEmpty()) {
                list.removeAllViews()
                list.addView(tv("Gemini-কে কাজ বলুন। সে একটা Pull Request খুলবে; GitHub-এ মার্জ করলে নতুন সংস্করণ আসবে।", 13f, mute))
            }
        }
    }

    private fun menu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("টোকেন মুছুন")) { _, _ ->
                sp().edit().remove("dev_tok").putInt("dev_issue", 0).apply()
                shownSig = ""
                drawState()
            }
            .show()
    }

    private class Resp(val code: Int, val text: String)

    private fun req(tok: String, method: String, path: String, body: String? = null): Resp {
        val cn = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            cn.requestMethod = method
            cn.connectTimeout = 15000
            cn.readTimeout = 30000
            cn.setRequestProperty("Authorization", "Bearer $tok")
            cn.setRequestProperty("Accept", "application/vnd.github+json")
            cn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            cn.setRequestProperty("User-Agent", "AutoReply-app")
            if (body != null) {
                cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/json")
                cn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = cn.responseCode
            val txt = (if (code in 200..299) cn.inputStream else cn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, txt)
        } finally {
            cn.disconnect()
        }
    }

    private fun problem(code: Int): String = when (code) {
        401 -> "টোকেন ভুল বা মেয়াদ শেষ — মেনু থেকে টোকেন মুছে নতুন দিন"
        403, 404 -> "অনুমতি নেই — টোকেনে AutoReply রিপো আর \"Issues: Read and write\" দিয়েছেন তো?"
        else -> "GitHub থেকে সাড়া এল না (কোড $code)"
    }

    // চ্যাটের জন্য একটাই Issue ব্যবহার হয়; না থাকলে একবার তৈরি করে নম্বর মনে রাখে
    private fun issueNo(tok: String): Int {
        val n = sp().getInt("dev_issue", 0)
        if (n > 0) return n
        val b = JSONObject().put("title", "AutoReply অ্যাপ চ্যাট").put("body", "এই Issue অ্যাপের ডেভ চ্যাট থেকে ব্যবহার হয়। এখানে \"@gemini-cli <কাজ>\" কমেন্ট করলে এজেন্ট কাজ করে।")
        val r = req(tok, "POST", "/repos/$repo/issues", b.toString())
        if (r.code !in 200..299) throw Exception(problem(r.code))
        val no = JSONObject(r.text).getInt("number")
        sp().edit().putInt("dev_issue", no).apply()
        return no
    }

    private fun sendMsg() {
        val text = input.text.toString().trim()
        val tok = token()
        if (text.isEmpty() || tok == null || busy) return
        busy = true
        input.setText("")
        Thread {
            try {
                val n = issueNo(tok)
                val b = JSONObject().put("body", "@gemini-cli $text")
                val r = req(tok, "POST", "/repos/$repo/issues/$n/comments", b.toString())
                if (r.code !in 200..299) throw Exception(problem(r.code))
                refresh(true)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "পাঠানো গেল না", Toast.LENGTH_LONG).show()
                    input.setText(text)
                }
            } finally {
                busy = false
            }
        }.start()
    }

    // Issue-র সব কমেন্ট এনে চ্যাট আকারে সাজায়; কিছু বদলালে তবেই পর্দা নতুন আঁকে
    private fun refresh(forceScroll: Boolean) {
        val tok = token() ?: return
        val n = sp().getInt("dev_issue", 0)
        if (n <= 0) return
        try {
            val r = req(tok, "GET", "/repos/$repo/issues/$n/comments?per_page=100")
            if (r.code !in 200..299) {
                runOnUiThread { Toast.makeText(this, problem(r.code), Toast.LENGTH_LONG).show() }
                return
            }
            val a = JSONArray(r.text)
            val msgs = ArrayList<Msg>()
            val sig = StringBuilder()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val body = o.optString("body")
                val login = o.optJSONObject("user")?.optString("login") ?: ""
                val mine = login == owner && !login.endsWith("[bot]")
                val txt = if (mine) body.removePrefix("@gemini-cli").trim() else body.trim()
                msgs.add(Msg(mine, txt))
                sig.append(o.optLong("id")).append(':').append(o.optString("updated_at")).append(';')
            }
            val waiting = msgs.isNotEmpty() && msgs.last().mine
            sig.append(waiting)
            runOnUiThread { draw(msgs, waiting, sig.toString(), forceScroll) }
        } catch (e: Exception) {
            // নেটওয়ার্ক না থাকলে চুপ থাকে; পরের চেষ্টায় আবার হবে
        }
    }

    private fun draw(msgs: List<Msg>, waiting: Boolean, sig: String, forceScroll: Boolean) {
        if (isFinishing || token() == null) return
        if (sig == shownSig) return
        shownSig = sig
        list.removeAllViews()
        if (msgs.isEmpty()) {
            list.addView(tv("Gemini-কে কাজ বলুন। সে একটা Pull Request খুলবে; GitHub-এ মার্জ করলে নতুন সংস্করণ আসবে।", 13f, mute))
            shownSig = ""
            return
        }
        for (m in msgs) {
            val t = tv(if (m.text.length > 3000) m.text.take(3000) + "…" else m.text, 14f, if (m.mine) Color.parseColor("#06201C") else ink)
            t.setTextIsSelectable(true)
            t.setLinkTextColor(if (m.mine) Color.parseColor("#06201C") else accent)
            Linkify.addLinks(t, Linkify.WEB_URLS)
            t.background = if (m.mine) rounded(accent, 16) else rounded(panel, 16, line)
            t.setPadding(d(14), d(10), d(14), d(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = d(10)
            lp.gravity = if (m.mine) Gravity.END else Gravity.START
            if (m.mine) lp.leftMargin = d(48) else lp.rightMargin = d(24)
            list.addView(t, lp)
        }
        if (waiting) {
            val w = tv("⏳ কাজ চলছে… কয়েক মিনিট লাগতে পারে", 13f, mute)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = d(12)
            list.addView(w, lp)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
