package com.example.scorer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Vibrator
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

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
    private val N = 11
    // Sirf yehi lafz suna jayega, baqi sab [unk] ban kar ignore hota hai
    private val GRAMMAR = "[\"dot\",\"zero\",\"one\",\"two\",\"three\",\"four\",\"five\",\"six\"," +
            "\"out\",\"wide\",\"no ball\",\"undo\",\"[unk]\"]"

    private var st = State()
    private val history = ArrayList<State>()
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var micOn = true

    private lateinit var tvTotal: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBat: TextView
    private lateinit var tvHeard: TextView
    private lateinit var tvList: TextView
    private lateinit var btnMic: Button

    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        if (bold) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101820"))
            setPadding(32, 48, 32, 24)
        }
        tvTotal = tv(60f, Color.WHITE, true)
        tvInfo = tv(18f, Color.LTGRAY)
        tvBat = tv(20f, Color.parseColor("#7CFC9A"), true)
        tvHeard = tv(16f, Color.parseColor("#FFD166"))
        tvList = tv(15f, Color.WHITE).apply { typeface = Typeface.MONOSPACE }
        root.addView(tvTotal); root.addView(tvInfo); root.addView(tvBat); root.addView(tvHeard)
        val sv = ScrollView(this); sv.addView(tvList)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMic = Button(this).apply { text = "Mic: ON"; setOnClickListener { toggleMic() } }
        val btnUndo = Button(this).apply { text = "Undo"; setOnClickListener { apply("undo") } }
        val btnNew = Button(this).apply { text = "New Innings"; setOnClickListener { confirmNew() } }
        for (x in listOf(btnMic, btnUndo, btnNew)) row.addView(x, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
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

    // ---- awaz ke natayej ----
    override fun onResult(h: String?) {
        if (h == null) return
        val text = JSONObject(h).optString("text", "")
        if (text.isBlank()) return
        runOnUiThread { handle(text) }
    }
    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) {}
    override fun onError(e: Exception?) { runOnUiThread { tvHeard.text = "Error: " + e?.message } }
    override fun onTimeout() {}

    private fun handle(text: String) {
        val t = text.trim().split(" ").filter { it.isNotEmpty() }
        var k = 0
        while (k < t.size) {
            val w = t[k]
            if (w == "no") {
                apply("noball"); k++
                if (k < t.size && t[k] == "ball") k++
                continue
            }
            if (w != "[unk]" && w != "ball") apply(w)
            k++
        }
    }

    // ---- score ka hisab ----
    private fun apply(cmd: String) {
        if (cmd == "undo") {
            if (history.isNotEmpty()) st = history.removeAt(history.size - 1)
            tvHeard.text = "Suna: undo"; refresh(); return
        }
        if (st.wickets >= N) return
        val snap = st.copy()
        val i = st.cur
        when (cmd) {
            "wide", "noball" -> { st.total++; st.extras++ }   // 1 run, ball dobara hogi
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
        tvHeard.text = "Suna: $cmd"
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(60)
        refresh()
    }

    private fun refresh() {
        tvTotal.text = "${st.total}/${st.wickets}"
        tvInfo.text = "Overs ${st.balls / 6}.${st.balls % 6}    Extras ${st.extras}"
        tvBat.text = if (st.wickets >= N) "INNINGS KHATAM (sab out)"
        else "Player ${st.cur + 1}: ${st.runs[st.cur]} (${st.bl[st.cur]})  4s:${st.f[st.cur]}  6s:${st.s[st.cur]}"
        val sb = StringBuilder()
        for (i in 0 until N) {
            val mark = if (st.out[i]) "OUT" else if (i == st.cur && st.wickets < N) "<-" else ""
            sb.append(String.format("P%-2d %3d (%2d)  4s:%d 6s:%d  %s\n",
                i + 1, st.runs[i], st.bl[i], st.f[i], st.s[i], mark))
        }
        tvList.text = sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.stop(); speech?.shutdown(); model?.close()
    }
}
