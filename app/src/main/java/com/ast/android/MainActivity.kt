package com.ast.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.*
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var keyEdit: EditText
    private lateinit var pairSpinner: Spinner
    private lateinit var startBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var speakBtn: Button
    private var tts: TextToSpeech? = null
    @Volatile private var ttsPlaying = false
    private var lastTgtText = ""
    private var lastMode = "zh-en"
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var subContainer: LinearLayout
    private lateinit var subScroll: ScrollView
    private var lastSrcView: TextView? = null
    private var lastTgtView: TextView? = null
    private var lastSeq = -1
    private var spinnerInit = false
    // Windows-style scroll logic: force to bottom once at Start, then only
    // follow when the user is already near the bottom, so scrolling up to
    // read old subtitles is not hijacked by new subtitles.
    private var forceScrollOnce = true
    private var nearBottom = true
    private var translator: Translator? = null
    private var recording = false
    private var recordThread: Thread? = null
    private val modes = listOf("zh-zhen", "zh-en", "zh-ja", "zh-ko", "zh-ru", "zh-it", "zh-es", "zh-fr", "zh-de")
    private val labels = listOf("中英混 Mixed (auto)", "中 ⇄ 英", "中 ⇄ 日", "中 ⇄ 韩", "中 ⇄ 俄", "中 ⇄ 意", "中 ⇄ 西", "中 ⇄ 法", "中 ⇄ 德")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keyEdit = findViewById(R.id.keyEdit)
        pairSpinner = findViewById(R.id.pairSpinner)
        startBtn = findViewById(R.id.startBtn)
        clearBtn = findViewById(R.id.clearBtn)
        speakBtn = findViewById(R.id.speakBtn)
        tts = TextToSpeech(this) { }
        speakBtn.setOnClickListener {
            val text = lastTgtText
            if (text.isEmpty()) { statusText.text = "还没有译文，先开始说一句话"; return@setOnClickListener }
            val locale = when {
                lastMode == "zh-zhen" -> Locale.ENGLISH
                lastMode.endsWith("ja") || lastMode.startsWith("ja") -> Locale.JAPANESE
                lastMode.endsWith("ko") -> Locale.KOREAN
                lastMode.endsWith("ru") -> Locale("ru")
                lastMode.endsWith("it") -> Locale.ITALIAN
                lastMode.endsWith("es") -> Locale("es")
                lastMode.endsWith("fr") -> Locale.FRENCH
                lastMode.endsWith("de") -> Locale.GERMAN
                else -> Locale.ENGLISH
            }
            tts?.language = locale
            ttsPlaying = true
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { ttsPlaying = false }
                override fun onError(id: String?) { ttsPlaying = false }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ast-tts")
        }
        statusText = findViewById(R.id.statusText)
        subText = findViewById(R.id.subText)
        subContainer = findViewById(R.id.subContainer)
        subScroll = findViewById(R.id.subScroll)
        subScroll.isScrollbarFadingEnabled = false
        subScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentH = subContainer.height
            val viewH = subScroll.height
            nearBottom = scrollY + viewH >= contentH - 200
        }
        val prefs = getSharedPreferences("ast", MODE_PRIVATE)
        keyEdit.setText(prefs.getString("api_key", ""))
        // Spinner text must be white on the dark background, otherwise it is
        // invisible (the default layout uses black text).
        val spinnerAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val tv = super.getView(pos, v, parent) as TextView
                tv.setTextColor(0xFFFFFFFF.toInt()); tv.textSize = 18f
                return tv
            }
            override fun getDropDownView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val tv = super.getDropDownView(pos, v, parent) as TextView
                tv.setTextColor(0xFFFFFFFF.toInt()); tv.setBackgroundColor(0xFF222222.toInt()); tv.textSize = 18f
                return tv
            }
        }
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        pairSpinner.adapter = spinnerAdapter
        pairSpinner.setSelection(1)
        pairSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (!spinnerInit) { spinnerInit = true; return }
                val newMode = modes[pos]
                if (translator != null && newMode != lastMode) {
                    // Language switch while running: stop and restart with the new pair,
                    // otherwise the switch appears to do nothing.
                    stop()
                    pairSpinner.postDelayed({ start() }, 300)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        startBtn.setOnClickListener {
            if (translator == null) start() else stop()
        }
        clearBtn.setOnClickListener { runOnUiThread { subContainer.removeAllViews(); lastSrcView = null; lastTgtView = null; lastSeq = -1 } }
    }

    private fun start() {
        val key = keyEdit.text.toString().trim()
        if (key.isEmpty()) { statusText.text = "请先填火山同声传译 2.0 的 API Key（存在本机，不会上传到代码仓库）"; return }
        getSharedPreferences("ast", MODE_PRIVATE).edit().putString("api_key", key).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            statusText.text = "请允许录音权限后再点开始"
            return
        }
        statusText.text = "正在连接火山…"
        forceScrollOnce = true
        nearBottom = true
        lastSeq = -1
        val mode = modes[pairSpinner.selectedItemPosition]
        lastMode = mode
        val tr = Translator(key, mode,
            onUtterance = { src, tgt, color, seq ->
                runOnUiThread {
                    // Windows-style subtitle block: original small grey on top,
                    // translation large bold in speaker colour below.
                    // Incremental updates replace the last block, new sentences
                    // (new seq) append a fresh block instead of overwriting.
                    // Previous bug: seq was ignored, so every sentence overwrote
                    // the single block and no scrollbar ever appeared.
                    if (lastSrcView == null || seq != lastSeq) {
                        lastSeq = seq
                        lastSrcView = TextView(this).apply { textSize = 14f; setTextColor(0xFFAAAAAA.toInt()) }
                        lastTgtView = TextView(this).apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) }
                        subContainer.addView(lastSrcView); subContainer.addView(lastTgtView)
                    }
                    lastSrcView?.text = src
                    lastTgtView?.text = tgt
                    lastTgtView?.setTextColor(color)
                    if (tgt.isNotEmpty()) lastTgtText = tgt
                    if (forceScrollOnce || nearBottom) {
                        subScroll.post { subScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                        forceScrollOnce = false
                    }
                }
            },
            onStatus = { s -> runOnUiThread { statusText.text = s } })
        Thread {
            val ok = tr.start()
            runOnUiThread {
                if (ok) {
                    translator = tr
                    startBtn.text = "停止"
                    statusText.text = "已连接，正在收音…"
                    startRecording(tr)
                } else {
                    statusText.text = "连接失败，请检查密钥与网络"
                }
            }
        }.start()
    }

    private fun startRecording(tr: Translator) {
        recording = true
        recordThread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 6400))
            try {
                rec.startRecording()
                val buf = ByteArray(2560)
                var offset = 0
                val tmp = ByteArray(4096)
                while (recording) {
                    val n = rec.read(tmp, 0, tmp.size)
                    if (n <= 0) continue
                    if (ttsPlaying) continue
                    var p = 0
                    while (p < n) {
                        val take = minOf(2560 - offset, n - p)
                        System.arraycopy(tmp, p, buf, offset, take); offset += take; p += take
                        if (offset == 2560) { tr.pushPcm(buf.copyOf()); offset = 0 }
                    }
                }
            } catch (_: Exception) { } finally {
                try { rec.stop(); rec.release() } catch (_: Exception) { }
            }
        }
        recordThread?.start()
    }

    private fun stop() {
        recording = false
        translator?.close(); translator = null
        startBtn.text = "开始"
        statusText.text = "已停止"
    }

    override fun onDestroy() { recording = false; translator?.close(); tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
