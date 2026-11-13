package com.hifz.checker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : Activity() {
    private val SR = 16000
    private lateinit var juzSpinner: Spinner
    private lateinit var btn: Button
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var tone: ToneGenerator

    @Volatile private var running = false
    @Volatile private var pointer = -1
    private var ctx = 0L
    private var loadedJuz = -1
    private var words: List<Aligner.W> = emptyList()
    private val worker = Executors.newSingleThreadExecutor()
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
        root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(-1, 0, 1f))
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
        val juz = juzSpinner.selectedItemPosition + 1
        getSharedPreferences("p", MODE_PRIVATE).edit().putInt("juz", juz - 1).apply()
        lines.clear(); report.text = ""; pointer = -1
        running = true
        btn.text = "إيقاف"
        status.text = "جاري تجهيز النموذج… (أول مرة قد تستغرق دقيقة)"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute {
            try {
                prepare(juz)
                if (!running) { runOnUiThread { endUi() }; return@execute }
                runOnUiThread { status.text = "اقرأ الآن…" }
                Thread { recordLoop() }.start()
            } catch (e: Throwable) {
                runOnUiThread { endUi(); status.text = "تعذر التجهيز: ${e.message}" }
            }
        }
    }

    private fun prepare(juz: Int) {
        if (ctx == 0L) {
            val f = File(filesDir, "model.bin")
            val size = assets.openFd("model.bin").use { it.length }
            if (!f.exists() || f.length() != size) {
                assets.open("model.bin").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            ctx = Whisper.nativeInit(f.absolutePath)
            if (ctx == 0L) throw RuntimeException("فشل تحميل النموذج")
        }
        if (loadedJuz != juz) {
            val txt = assets.open("juz/$juz.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            val list = ArrayList<Triple<Int, Int, String>>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                list.add(Triple(a.getInt(0), a.getInt(1), a.getString(2)))
            }
            words = Aligner.buildWords(list)
            loadedJuz = juz
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
        val frame = SR / 10
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
        worker.execute { runOnUiThread { showReport() } }
    }

    private fun sendSeg(frames: List<ShortArray>) {
        val total = frames.sumOf { it.size }
        val audio = FloatArray(total)
        var k = 0
        for (f in frames) for (x in f) audio[k++] = x / 32768f
        worker.execute {
            try { process(audio) }
            catch (e: Throwable) { runOnUiThread { status.text = "خطأ: ${e.message}" } }
        }
    }

    private fun process(audio: FloatArray) {
        val threads = minOf(4, Runtime.getRuntime().availableProcessors())
        val bytes = Whisper.nativeRun(ctx, audio, threads)
        val text = String(bytes, Charsets.UTF_8)
        val res = Aligner.evaluate(words, text, pointer)
        pointer = res.pointer
        if (res.errors.isNotEmpty()) {
            tone.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
            for (e in res.errors) lines.add(fmt(e))
        }
        if (res.status == "finished") running = false
    }

    private fun fmt(e: Aligner.Err): String {
        val r = if (e.ref.isNotEmpty()) "[${e.ref}] " else ""
        return when (e.type) {
            "harakat" -> r + "خطأ في الحركة: الصحيح «${e.expected}» وقلت «${e.got}»"
            "letter" -> r + "خطأ في حرف: الصحيح «${e.expected}» وقلت «${e.got}»"
            "word" -> r + "كلمة خاطئة: الصحيح «${e.expected}» وقلت «${e.got}»"
            "missing" -> r + "نسيت كلمة: «${e.expected}»"
            else -> "كلمة زائدة: «${e.got}»"
        }
    }

    override fun onDestroy() {
        running = false
        tone.release()
        super.onDestroy()
    }
}
