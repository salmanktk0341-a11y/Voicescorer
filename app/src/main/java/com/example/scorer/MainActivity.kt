package com.example.scorer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

const val TOTAL_OVERS = 20      // overs ki hadd yahan badlo
const val BALLS_PER_OVER = 6
const val PLAYERS = 11
const val MIN_CONF = 0.5        // awaz ka yaqeen: barha do to sakhti barhegi

class State {
    var total = 0; var wickets = 0; var balls = 0; var extras = 0; var cur = 0
    var runs = IntArray(11); var bl = IntArray(11)
    var f = IntArray(11); var s = IntArray(11)
    var out = BooleanArray(11)
    fun copy(): State {
        val c = State()
        c.total = total; c.wickets = wickets; c.balls = balls; c.extras = extras; c.cur = cur
        c.runs = runs.clone(); c.bl = bl.clone(); c.f = f.clone(); c.s = s.clone(); c.out = out.clone()
        return c
    }
}

class MainActivity : Activity(), RecognitionListener {
    // Sirf ye lafz kaam karte hain
    private val REAL = listOf("dot", "zero", "one", "two", "three", "four", "five", "six",
        "out", "wide", "no ball", "undo")
    // Milte-julte lafz: sunay jayen to ignore ho jayen
    private val DECOYS = listOf("x", "sex", "sick", "sit", "seeks", "sax", "fix", "floor",
        "door", "want", "tree", "free", "ten", "nine", "eight", "seven", "now", "wild")
    private val COMMANDS = REAL.filter { it != "no ball" }.toSet()
    private val GRAMMAR: String = JSONArray(REAL + DECOYS + listOf("[unk]")).toString()

    private val GREEN = Color.parseColor("#2E7D32")
    private var st = State()
    private val history = ArrayList<State>()
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var micOn = true

    private lateinit var tvScore: TextView
    private lateinit var tvOvers: TextView
    private lateinit var tvCrr: TextView
    private lateinit var tvExtras: TextView
    private lateinit var tvProj: TextView
    private lateinit var tvLeft: TextView
    private lateinit var tvHeard: TextView
    private lateinit var tableBox: LinearLayout
    private lateinit var btnMic: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cell(text: String, weight: Float, bold: Boolean, color: Int, gravity: Int): TextView {
        val t = TextView(this)
        t.text = text; t.textSize = 14f; t.setTextColor(color); t.gravity = gravity
        if (bold) t.setTypeface(null, Typeface.BOLD)
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        return t
    }

    private fun tableRow(vals: List<String>, bg: Int, fg: Int, bold: Boolean): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setBackgroundColor(bg)
        r.setPadding(dp(10), dp(10), dp(10), dp(10))
        for ((i, v) in vals.withIndex()) {
            r.addView(cell(v, if (i == 0) 4f else 1f, bold, fg,
                if (i == 0) Gravity.START else Gravity.CENTER))
        }
        return r
    }

    private fun stat(label: String, holder: LinearLayout): TextView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val l = TextView(this)
        l.text = label; l.textSize = 12f; l.setTextColor(Color.GRAY); l.gravity = Gravity.CENTER
        val v = TextView(this)
        v.textSize = 18f; v.setTypeface(null, Typeface.BOLD); v.setTextColor(Color.BLACK)
        v.gravity = Gravity.CENTER
        col.addView(l); col.addView(v); holder.addView(col)
        return v
    }

    private fun key(label: String, bg: Int, fg: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label; b.textSize = 18f; b.isAllCaps = false
        b.setTextColor(fg); b.setBackgroundColor(bg)
        val lp = LinearLayout.LayoutParams(0, dp(56), 1f)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun keyRow(vararg keys: Button): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (k in keys) r.addView(k)
        return r
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#FFFBFE"))
        root.setPadding(dp(12), dp(28), dp(12), dp(8))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Voice Scorer"; title.textSize = 22f
        title.setTextColor(GREEN); title.setTypeface(null, Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val btnNew = Button(this)
        btnNew.text = "New"; btnNew.setOnClickListener { confirmNew() }
        top.addView(title); top.addView(btnNew)
        root.addView(top)

        tvScore = TextView(this)
        tvScore.textSize = 46f; tvScore.setTypeface(null, Typeface.BOLD)
        tvScore.setTextColor(Color.BLACK)
        tvOvers = TextView(this)
        tvOvers.textSize = 16f; tvOvers.setTextColor(Color.DKGRAY)
        root.addView(tvScore); root.addView(tvOvers)

        val stats = LinearLayout(this)
        stats.orientation = LinearLayout.HORIZONTAL
        stats.setPadding(0, dp(8), 0, dp(8))
        tvCrr = stat("CRR", stats)
        tvExtras = stat("Extras", stats)
        tvProj = stat("Projected", stats)
        tvLeft = stat("Balls left", stats)
        root.addView(stats)

        tableBox = LinearLayout(this)
        tableBox.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(tableBox)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        tvHeard = TextView(this)
        tvHeard.textSize = 15f; tvHeard.setTextColor(Color.parseColor("#6D4C00"))
        tvHeard.setPadding(dp(6), dp(6), dp(6), dp(6))
        root.addView(tvHeard)

        val neutral = Color.parseColor("#E7E0EC")
        val dark = Color.parseColor("#1D1B20")
        fun num(label: String, cmd: String) = key(label, neutral, dark) { apply(cmd) }
        btnMic = key("Mic: ON", neutral, dark) { toggleMic() }
        root.addView(keyRow(num("0", "dot"), num("1", "one"), num("2", "two"), num("3", "three")))
        root.addView(keyRow(
            key("4", Color.parseColor("#A5D6A7"), Color.parseColor("#1B5E20")) { apply("four") },
            num("5", "five"),
            key("6", Color.parseColor("#8E24AA"), Color.WHITE) { apply("six") },
            key("W", Color.parseColor("#C62828"), Color.WHITE) { apply("out") }))
        root.addView(keyRow(num("NB", "noball"), num("WD", "wide"), num("Undo", "undo"), btnMic))
        setContentView(root)
        refresh()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) initModel()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) initModel()
        else tvHeard.text = "Mic ki ijazat chahiye (Settings se do)"
    }

    private fun initModel() {
        tvHeard.text = "Awaz ka model load ho raha hai..."
        StorageService.unpack(this, "model-en-us", "model",
            { m: Model -> model = m; startMic() },
            { e: java.io.IOException -> tvHeard.text = "Model error: " + e.message })
    }

    private fun startMic() {
        val rec = Recognizer(model, 16000.0f, GRAMMAR)
        rec.setWords(true)
        speech = SpeechService(rec, 16000.0f)
        speech!!.startListening(this)
        tvHeard.text = "Tayyar. Bolo: one, two, four, six, out, wide, no ball, undo"
    }

    private fun toggleMic() {
        micOn = !micOn
        speech?.setPause(!micOn)
        btnMic.text = if (micOn) "Mic: ON" else "Mic: OFF"
    }

    private fun confirmNew() {
        AlertDialog.Builder(this).setMessage("Naya innings? Purana score mit jayega.")
            .setPositiveButton("Haan") { _, _ -> st = State(); history.clear(); refresh() }
            .setNegativeButton("Nahi", null).show()
    }

    // ---- awaz ----
    override fun onResult(h: String?) {
        if (h == null) return
        val j = JSONObject(h)
        val words = ArrayList<String>()
        val arr = j.optJSONArray("result")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optDouble("conf", 1.0) >= MIN_CONF) words.add(o.optString("word"))
            }
        } else {
            val t = j.optString("text", "")
            if (t.isNotBlank()) words.addAll(t.trim().split(" "))
        }
        if (words.isEmpty()) return
        runOnUiThread { handle(words) }
    }
    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) {}
    override fun onError(e: Exception?) { runOnUiThread { tvHeard.text = "Error: " + e?.message } }
    override fun onTimeout() {}

    private fun handle(words: List<String>) {
        var k = 0
        while (k < words.size) {
            val w = words[k]
            if (w == "no" && k + 1 < words.size && words[k + 1] == "ball") {
                apply("noball"); k += 2; continue
            }
            if (w in COMMANDS) apply(w)
            else if (w.isNotEmpty() && w != "[unk]") tvHeard.text = "Ignore: $w"
            k++
        }
    }

    // ---- score ----
    private fun finished() = st.wickets >= PLAYERS || st.balls >= TOTAL_OVERS * BALLS_PER_OVER

    private fun apply(cmd: String) {
        if (cmd == "undo") {
            if (history.isNotEmpty()) st = history.removeAt(history.size - 1)
            tvHeard.text = "Suna: undo"; refresh(); return
        }
        if (finished()) { tvHeard.text = "Innings khatam"; return }
        val snap = st.copy()
        val i = st.cur
        var legal = true
        when (cmd) {
            "wide", "noball" -> { st.total++; st.extras++; legal = false }
            "out" -> { st.out[i] = true; st.bl[i]++; st.balls++; st.wickets++; st.cur++ }
            else -> {
                val r = when (cmd) {
                    "dot", "zero" -> 0; "one" -> 1; "two" -> 2; "three" -> 3
                    "four" -> 4; "five" -> 5; "six" -> 6; else -> -1
                }
                if (r < 0) return
                st.runs[i] += r; st.bl[i]++; st.balls++; st.total += r
                if (r == 4) st.f[i]++
                if (r == 6) st.s[i]++
            }
        }
        history.add(snap)
        var msg = "Suna: $cmd"
        if (legal && st.balls > 0 && st.balls % BALLS_PER_OVER == 0) msg += "  (over khatam)"
        if (finished()) msg = "Innings khatam"
        tvHeard.text = msg
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(60)
        refresh()
    }

    private fun refresh() {
        tvScore.text = "${st.total}/${st.wickets}"
        tvOvers.text = "(${st.balls / BALLS_PER_OVER}.${st.balls % BALLS_PER_OVER}/$TOTAL_OVERS overs)"
        val crr = if (st.balls > 0) st.total * BALLS_PER_OVER.toDouble() / st.balls else 0.0
        tvCrr.text = "%.2f".format(crr)
        tvExtras.text = st.extras.toString()
        tvProj.text = Math.round(crr * TOTAL_OVERS).toString()
        tvLeft.text = (TOTAL_OVERS * BALLS_PER_OVER - st.balls).toString()

        tableBox.removeAllViews()
        tableBox.addView(tableRow(listOf("BATSMAN", "R", "B", "4s", "6s", "SR"), GREEN, Color.WHITE, true))
        val last = if (finished()) minOf(st.cur, PLAYERS - 1) else minOf(st.cur, PLAYERS - 1)
        for (i in 0..last) {
            val isCur = (i == st.cur && !finished())
            val name = (if (isCur) "▶ " else "") + "Player ${i + 1}" + (if (st.out[i]) " (out)" else "")
            val sr = if (st.bl[i] > 0) "%.1f".format(st.runs[i] * 100.0 / st.bl[i]) else "0.0"
            val fg = if (st.out[i]) Color.parseColor("#757575") else Color.BLACK
            val bg = if (isCur) Color.parseColor("#E8F5E9") else Color.TRANSPARENT
            tableBox.addView(tableRow(
                listOf(name, "${st.runs[i]}", "${st.bl[i]}", "${st.f[i]}", "${st.s[i]}", sr),
                bg, fg, isCur))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.stop(); speech?.shutdown(); model?.close()
    }
}
