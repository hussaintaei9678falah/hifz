package com.hifz.checker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : Activity() {
    private val SR = 16000
    private lateinit var urlInput: EditText
    private lateinit var juzSpinner: Spinner
    private lateinit var btn: Button
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var tone: ToneGenerator

    @Volatile private var running = false
    @Volatile private var pointer = -1
    @Volatile private var juz = 1
    @Volatile private var base = ""
    private val net = Executors.newSingleThreadExecutor()
    private val lines = java.util.Collections.synchronizedList(ArrayList<String>())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "مراجعة الحفظ"; textSize = 26f; setTextColor(Color.BLACK)
            setPadding(0, 0, 0, pad)
        })
        urlInput = EditText(this).apply {
            hint = "رابط السيرفر (https://...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
            setText(prefs.getString("url", ""))
        }
        root.addView(urlInput)
        juzSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                (1..30).map { "الجزء $it" })
            setSelection(prefs.getInt("juz", 0))
        }
        root.addView(juzSpinner)
        btn = Button(this).apply {
            text = "ابدأ المراجعة"; textSize = 20f
            setOnClickListener { if (running) running = false else startSession() }
        }
        root.addView(btn)
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad); setTextColor(Color.DKGRAY) }
        root.addView(status)
        report = TextView(this).apply { textSize = 18f; setTextColor(Color.BLACK) }
        root.addView(ScrollView(this).apply { addView(report) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startSession()
        else status.text = "يجب السماح باستخدام الميكروفون"
    }

    private fun startSession() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        var u = urlInput.text.toString().trim().trimEnd('/')
        if (u.isEmpty()) { status.text = "اكتب رابط السيرفر أولاً"; return }
        if (!u.startsWith("http")) u = "https://$u"
        base = u
        juz = juzSpinner.selectedItemPosition + 1
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("url", u).putInt("juz", juz - 1).apply()
        lines.clear(); report.text = ""; pointer = -1
        running = true
        btn.text = "إيقاف"
        status.text = "جاري التحضير… (أول مرة قد تستغرق دقيقة)"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        net.execute {
            try {
                val c = URL("$base/prepare?juz=$juz").openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 240000
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                c.inputStream.close()
                if (!running) { runOnUiThread { endUi() }; return@execute }
                runOnUiThread { status.text = "اقرأ الآن…" }
                Thread { recordLoop() }.start()
            } catch (e: Exception) {
                runOnUiThread { endUi(); status.text = "تعذر الاتصال بالسيرفر: ${e.message}" }
            }
        }
    }

    private fun endUi() {
        running = false
        btn.text = "ابدأ المراجعة"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showReport() {
        endUi()
        val copy = synchronized(lines) { ArrayList(lines) }
        status.text = if (copy.isEmpty()) "لا أخطاء مرصودة، ما شاء الله" else "عدد الأخطاء: ${copy.size}"
        report.text = copy.joinToString("\n\n")
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = SR / 10 // 100ms
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 8))
        val buf = ShortArray(frame)
        val seg = ArrayList<ShortArray>()
        val pre = ArrayDeque<ShortArray>()
        var noise = 0.0; var calib = 0
        var speaking = false; var silent = 0; var speech = 0
        rec.startRecording()
        while (running) {
            val n = rec.read(buf, 0, frame)
            if (n <= 0) continue
            val f = buf.copyOf(n)
            var s = 0.0
            for (x in f) s += x.toDouble() * x
            val rms = sqrt(s / n)
            if (calib < 5) { noise += rms / 5; calib++; continue }
            val thr = maxOf(450.0, noise * 3)
            val voiced = rms > thr
            if (!speaking) {
                pre.addLast(f); if (pre.size > 3) pre.removeFirst()
                if (voiced) { speaking = true; seg.addAll(pre); pre.clear(); speech = 1; silent = 0 }
            } else {
                seg.add(f)
                if (voiced) { silent = 0; speech++ } else silent++
                val len = seg.size
                if (silent >= 5 || (len >= 120 && rms < thr * 1.5) || len >= 180) {
                    if (speech >= 6) sendSeg(seg)
                    seg.clear(); speaking = false; silent = 0; speech = 0
                }
            }
        }
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        if (speaking && speech >= 6) sendSeg(seg)
        net.execute { runOnUiThread { showReport() } }
    }

    private fun sendSeg(frames: List<ShortArray>) {
        val total = frames.sumOf { it.size }
        val bb = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in frames) for (x in f) bb.putShort(x)
        val bytes = bb.array()
        net.execute {
            try { upload(bytes) }
            catch (e: Exception) { runOnUiThread { status.text = "خطأ اتصال: ${e.message}" } }
        }
    }

    private fun upload(bytes: ByteArray) {
        val c = URL("$base/check?juz=$juz&pointer=$pointer").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        pointer = j.optInt("pointer", pointer)
        val errs = j.optJSONArray("errors")
        if (errs != null && errs.length() > 0) {
            tone.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
            for (k in 0 until errs.length()) lines.add(fmt(errs.getJSONObject(k)))
        }
        if (j.optString("status") == "finished") running = false
    }

    private fun fmt(o: JSONObject): String {
        val exp = o.optString("expected"); val got = o.optString("got"); val ref = o.optString("ref")
        val r = if (ref.isNotEmpty()) "[$ref] " else ""
        return when (o.optString("type")) {
            "harakat" -> "$r" + "خطأ في الحركة: الصحيح «$exp» وقلت «$got»"
            "letter" -> "$r" + "خطأ في حرف: الصحيح «$exp» وقلت «$got»"
            "word" -> "$r" + "كلمة خاطئة: الصحيح «$exp» وقلت «$got»"
            "missing" -> "$r" + "نسيت كلمة: «$exp»"
            else -> "كلمة زائدة: «$got»"
        }
    }

    override fun onDestroy() {
        running = false
        tone.release()
        super.onDestroy()
    }
}
