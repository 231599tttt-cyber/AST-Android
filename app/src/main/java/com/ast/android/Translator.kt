package com.ast.android

import kotlin.random.Random

// Dual + zhen logic, mirroring Windows DualTranslator / ZhenTranslator.
// Dual: two sessions on the same audio, direction guessed from source script.
// Zhen: single session src=zhen tgt=zhen.
class Translator(
    private val apiKey: String,
    private val mode: String, // zh-zhen, zh-en, zh-ja, zh-ko, zh-ru, zh-it, zh-es, zh-fr, zh-de
    private val onUtterance: (src: String, tgt: String, color: Int, seq: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val palette = listOf(0xFF7FC8FF.toInt(), 0xFF7BD88F.toInt(), 0xFFFFB86B.toInt(), 0xFFD9A7FF.toInt(), 0xFFFF8FA3.toInt(), 0xFFFFD166.toInt(), 0xFF4DD0E1.toInt(), 0xFFFF6B6B.toInt(), 0xFFA3E635.toInt(), 0xFFA78BFA.toInt())
    private var colorIdx = Random.nextInt(palette.size)
    private var recent = mutableListOf(colorIdx)
    private var seq = 0
    private var lastStartMs = 0L
    private var lastSpkMs = 0L
    // True when the previous sentence has ended (a SubtitleEnd event arrived).
    // Fast-speech fix: previously a new Start within 1000ms was always ignored
    // as a duplicate from the second session, so when someone spoke fast the
    // second sentence overwrote the first one the user had already seen.
    // Now a Start advances to a new line immediately if the previous sentence
    // already ended, no matter how fast it came; only a Start that arrives
    // within 200ms while the previous sentence is still in progress is treated
    // as the duplicate from the other session.
    private var sentenceEnded = true
    private val acc = mutableMapOf("them" to mutableMapOf("src" to "", "tgt" to ""), "me" to mutableMapOf("src" to "", "tgt" to ""))
    private var direction = "them"
    private var s1: AstSession? = null
    private var s2: AstSession? = null

    private fun foreign(): String = when (mode) {
        "zh-ja" -> "ja"; "zh-ko" -> "ko"; "zh-ru" -> "ru"; "zh-it" -> "it"
        "zh-es" -> "es"; "zh-fr" -> "fr"; "zh-de" -> "de"; else -> "en"
    }

    private fun srcLang(text: String): String {
        if (text.isEmpty()) return "unknown"
        if (text.any { it in '\u3040'..'\u309f' || it in '\u30a0'..'\u30ff' }) return "ja"
        if (text.any { it in '\uac00'..'\ud7a3' || it in '\u1100'..'\u11ff' || it in '\u3130'..'\u318f' }) return "ko"
        if (text.any { it in '\u0400'..'\u04ff' || it in '\u0500'..'\u052f' }) return "ru"
        val cjk = text.count { it in '\u4e00'..'\u9fff' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' || it in '\u00c0'..'\u024f' || it in '\u1e00'..'\u1eff' }
        if (cjk == 0 && latin == 0) return "unknown"
        if (cjk > latin) return "zh"
        if (latin > cjk) return foreign()
        return "unknown"
    }

    private fun onSentenceStart() {
        val now = System.currentTimeMillis()
        if (now - lastStartMs < 200 && !sentenceEnded) return
        lastStartMs = now
        val hasPrev = acc.values.any { it.values.any { v -> v.isNotEmpty() } }
        if (hasPrev) {
            seq++
            acc["them"]!!["src"] = ""; acc["them"]!!["tgt"] = ""
            acc["me"]!!["src"] = ""; acc["me"]!!["tgt"] = ""
        }
        sentenceEnded = false
    }

    private fun markEnded() { sentenceEnded = true }

    private fun onSpk(spkChg: Boolean) {
        if (!spkChg) return
        val now = System.currentTimeMillis()
        if (now - lastSpkMs < 500) return
        lastSpkMs = now
        val banned = (recent.takeLast(2) + colorIdx).toSet()
        val others = palette.indices.filter { it !in banned }
        colorIdx = if (others.isNotEmpty()) others.random() else palette.indices.first { it != colorIdx }
        recent.add(colorIdx)
    }

    private fun onDelta(who: String, kind: String, text: String, isEnd: Boolean) {
        if (isEnd) sentenceEnded = true
        acc[who]!![kind] = text
        // Direction: only emit the session whose source matches the detected language.
        // For zhen, there is only one session, handled separately.
        val srcText = acc[who]!!["src"] ?: ""
        val lang = srcLang(srcText)
        val expectedWho = if (lang == "zh") "me" else if (lang == foreign()) "them" else direction
        if (lang != "unknown") direction = expectedWho
        if (who == expectedWho) {
            val s = acc[who]!!["src"] ?: ""; val t = acc[who]!!["tgt"] ?: ""
            if (s.isNotEmpty() || t.isNotEmpty()) onUtterance(s, t, palette[colorIdx], seq)
        }
        if (isEnd) { /* keep last utterance on screen; next Start will advance */ }
    }

    fun start(): Boolean {
        return if (mode == "zh-zhen") {
            val s = AstSession(apiKey, "zhen", "zhen",
                onSrc = { t, f -> if (f) markEnded(); acc["them"]!!["src"] = t; onUtterance(t, acc["them"]!!["tgt"] ?: "", palette[colorIdx], seq) },
                onTgt = { t, f -> if (f) markEnded(); acc["them"]!!["tgt"] = t; val srcNow = acc["them"]!!["src"] ?: ""; onUtterance(srcNow, t, palette[colorIdx], seq) },
                onStart = { onSpk(it) }, onSentenceStart = { onSentenceStart() }, onStatus = onStatus)
            // Simpler zhen handling: keep a single accumulator via onUtterance above is approximate;
            // MainActivity also receives incremental src/tgt and replaces the last line.
            s1 = s
            val ok = s.connect()
            if (ok) onStatus("中英混单会话已连接")
            ok
        } else {
            val f = foreign()
            val a = AstSession(apiKey, f, "zh",
                onSrc = { t, e -> onDelta("them", "src", t, e) },
                onTgt = { t, e -> onDelta("them", "tgt", t, e) },
                onStart = { onSpk(it) }, onSentenceStart = { onSentenceStart() }, onStatus = onStatus)
            val b = AstSession(apiKey, "zh", f,
                onSrc = { t, e -> onDelta("me", "src", t, e) },
                onTgt = { t, e -> onDelta("me", "tgt", t, e) },
                onStart = { onSpk(it) }, onSentenceStart = { onSentenceStart() }, onStatus = onStatus)
            s1 = a; s2 = b
            val ok1 = a.connect(); val ok2 = b.connect()
            val n = (if (ok1) 1 else 0) + (if (ok2) 1 else 0)
            if (n > 0) onStatus("同传已连接（$n/2 个方向）")
            n > 0
        }
    }

    fun pushPcm(pcm: ByteArray) {
        s1?.pushPcm(pcm); s2?.pushPcm(pcm)
    }

    fun close() { s1?.close(); s2?.close(); s1 = null; s2 = null }
}
