package com.ast.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private var tts: TextToSpeech? = null
    @Volatile private var ttsPlaying = false
    private var lastTgtText = ""
    private var lastSrcText = ""
    private var lastMode = "zh-en"
    private lateinit var statusText: TextView
    private lateinit var ttsEngineText: TextView
    private lateinit var subText: TextView
    private lateinit var subContainer: LinearLayout
    private lateinit var subScroll: ScrollView
    private var lastSrcView: TextView? = null
    private var lastTgtView: TextView? = null
    private var lastSpeakBtn: Button? = null
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
        // Keyboard fix: typing leaves the key box focused and the keyboard open.
        // On many phones the first tap on Start is then swallowed to dismiss the
        // keyboard, so the user has to tap Start a second time to connect.
        // Fix: Done on the keyboard hides it and clears focus, and touching any
        // button hides the keyboard on ACTION_DOWN (before the click fires),
        // so the first Start tap actually starts.
        keyEdit.imeOptions = EditorInfo.IME_ACTION_DONE
        keyEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); keyEdit.clearFocus(); true } else false
        }
        val hideOnTouch = android.view.View.OnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) { hideKeyboard(); keyEdit.clearFocus() }
            false
        }
        pairSpinner = findViewById(R.id.pairSpinner)
        startBtn = findViewById(R.id.startBtn)
        clearBtn = findViewById(R.id.clearBtn)
        startBtn.setOnTouchListener(hideOnTouch)
        clearBtn.setOnTouchListener(hideOnTouch)
        ttsEngineText = findViewById(R.id.ttsEngineText)
        tts = TextToSpeech(this) { code ->
            runOnUiThread {
                if (code == TextToSpeech.SUCCESS) {
                    val eng = tts?.defaultEngine ?: "未知"
                    val friendly = when (eng) {
                        "com.google.android.tts" -> "Google 文字转语音"
                        "com.samsung.SMT" -> "三星文字转语音"
                        "com.huawei.hiai" -> "华为语音"
                        "com.iflytek.speechcloud" -> "科大讯飞语音"
                        else -> eng
                    }
                    ttsEngineText.text = "朗读引擎：$friendly"
                } else {
                    ttsEngineText.text = "朗读引擎：初始化失败，朗读不可用"
                }
            }
        }
        // TTS listener set once, not per click: a flushed / failed utterance must
        // always reset ttsPlaying, otherwise the mic stays paused forever and the
        // app looks frozen, and restarting still sends nothing.
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { ttsPlaying = false }
            override fun onError(id: String?) { ttsPlaying = false }
            override fun onError(id: String?, errorCode: Int) { ttsPlaying = false }
        })
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
                tv.text = labels[pos] + "  ▼"
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

    // Playback: Windows has a 🔊 after every translated sentence, tap which
    // sentence reads which sentence. The top "朗读最新一句" button was removed
    // at the user's request; only per-sentence 🔊 buttons remain.
    // Locale is guessed from the translation itself: if it contains CJK, read
    // Chinese; kana -> Japanese; hangul -> Korean; Cyrillic -> Russian;
    // otherwise fall back to the current pair's foreign language.
    private fun guessLocale(text: String, src: String): Locale {
        if (text.any { it in '\u4e00'..'\u9fff' } && !text.any { it in '\u3040'..'\u30ff' } && !text.any { it in '\uac00'..'\ud7a3' }) {
            // Pure CJK without kana/hangul: could be Chinese or Japanese kanji.
            // If the source was Chinese, the translation is foreign, and vice versa.
            if (src.any { it in '\u4e00'..'\u9fff' }) return when (lastMode) {
                "zh-ja" -> Locale.JAPANESE; "zh-ko" -> Locale.KOREAN; "zh-ru" -> Locale("ru")
                "zh-it" -> Locale.ITALIAN; "zh-es" -> Locale("es"); "zh-fr" -> Locale.FRENCH
                "zh-de" -> Locale.GERMAN; else -> Locale.ENGLISH
            } else return Locale.CHINESE
        }
        if (text.any { it in '\u3040'..'\u30ff' }) return Locale.JAPANESE
        if (text.any { it in '\uac00'..'\ud7a3' }) return Locale.KOREAN
        if (text.any { it in '\u0400'..'\u04ff' }) return Locale("ru")
        if (text.any { it in '\u4e00'..'\u9fff' }) return Locale.CHINESE
        return when (lastMode) {
            "zh-ja" -> Locale.JAPANESE; "zh-ko" -> Locale.KOREAN; "zh-ru" -> Locale("ru")
            "zh-it" -> Locale.ITALIAN; "zh-es" -> Locale("es"); "zh-fr" -> Locale.FRENCH
            "zh-de" -> Locale.GERMAN; "zh-zhen" -> Locale.ENGLISH; else -> Locale.ENGLISH
        }
    }
    private fun speakText(text: String, src: String) {
        if (text.isEmpty()) { statusText.text = "还没有译文，先开始说一句话"; return }
        tts?.stop(); ttsPlaying = false
        val locale = guessLocale(text, src)
        val avail = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            statusText.text = "朗读失败：该语言语音包未安装（$locale），请到 设置 → 文字转语音输出 下载该语言语音包"
            ttsPlaying = false
            return
        }
        tts?.language = locale
        ttsPlaying = true
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ast-tts") ?: TextToSpeech.ERROR
        if (rc == TextToSpeech.ERROR) {
            ttsPlaying = false
            statusText.text = "朗读失败：手机语音引擎不可用或该语言未安装，请检查系统语音设置"
        } else {
            val timeoutMs = (text.length * 200L + 5000L).coerceAtMost(60000L)
            Handler(Looper.getMainLooper()).postDelayed({ ttsPlaying = false }, timeoutMs)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(keyEdit.windowToken, 0)
    }

    private fun start() {
        hideKeyboard(); keyEdit.clearFocus()
        val key = keyEdit.text.toString().trim()
        if (key.isEmpty()) { statusText.text = "请先填 API Key（存在本机，不会上传到代码仓库）"; return }
        getSharedPreferences("ast", MODE_PRIVATE).edit().putString("api_key", key).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            statusText.text = "请允许录音权限后再点开始"
            return
        }
        statusText.text = "正在连接…"
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
                        val srcView = TextView(this).apply { textSize = 14f; setTextColor(0xFFAAAAAA.toInt()) }
                        val tgtView = TextView(this).apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) }
                        lastSrcView = srcView; lastTgtView = tgtView
                        // Per-sentence 🔊 like Windows: tap this block's button
                        // reads this block's translation only, not all subtitles.
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(tgtView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        lastSpeakBtn = Button(this).apply { text = "🔊"; setOnClickListener { speakText(tgtView.text.toString(), srcView.text.toString()) } }
                        row.addView(lastSpeakBtn)
                        subContainer.addView(srcView); subContainer.addView(row)
                    }
                    lastSrcView?.text = src
                    lastTgtView?.text = tgt
                    lastTgtView?.setTextColor(color)
                    if (tgt.isNotEmpty()) lastTgtText = tgt
                    if (src.isNotEmpty()) lastSrcText = src
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
        // Stop must also stop TTS and reset the mic-pause flag, otherwise a
        // stuck TTS from the previous run blocks the next run and it looks dead.
        tts?.stop(); ttsPlaying = false
        translator?.close(); translator = null
        startBtn.text = "开始"
        statusText.text = "已停止"
    }

    override fun onDestroy() { recording = false; translator?.close(); tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
