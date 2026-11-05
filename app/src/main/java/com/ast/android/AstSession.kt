package com.ast.android

import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// One Volcano AST v2 s2t session, mirroring Windows Ast2Session.
class AstSession(
    private val apiKey: String,
    private val src: String,
    private val tgt: String,
    private val onSrc: (String, Boolean) -> Unit,
    private val onTgt: (String, Boolean) -> Unit,
    private val onStart: (Boolean) -> Unit,
    private val onSentenceStart: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val sessionId = UUID.randomUUID().toString()
    private var ws: WebSocket? = null
    private val started = CountDownLatch(1)
    @Volatile var alive = false
        private set

    fun connect(): Boolean {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v4/ast/v2/translate")
            .addHeader("X-Api-Key", apiKey)
            .addHeader("X-Api-Resource-Id", "volc.service_type.10053")
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                alive = true
                send(Proto.encodeRequest(sessionId, 100, src, tgt, null))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val r = Proto.decodeResponse(bytes.toByteArray())
                    when (r.event) {
                        150L -> started.countDown()
                        153L -> { onStatus("会话失败：${r.status} ${r.message}"); started.countDown(); alive = false }
                        152L -> alive = false
                        650L -> { onStart(r.spkChg); onSentenceStart() }
                        651L, 652L -> if (r.text.isNotEmpty()) onSrc(r.text, r.event == 652L)
                        654L, 655L -> if (r.text.isNotEmpty()) onTgt(r.text, r.event == 655L)
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (alive) onStatus("连接中断：${t.message}")
                alive = false; started.countDown()
            }
        }
        client.newWebSocket(req, listener)
        val ok = started.await(10, TimeUnit.SECONDS) && alive
        if (!ok) { close(); onStatus("[$src→$tgt] 等不到 SessionStarted，请检查密钥与 2.0 开通状态") }
        return ok
    }

    fun pushPcm(pcm: ByteArray) {
        if (!alive) return
        try { ws?.send(ByteString.of(*Proto.encodeRequest(sessionId, 200, src, tgt, pcm))) } catch (_: Exception) { }
    }

    fun close() {
        alive = false
        try { ws?.send(ByteString.of(*Proto.encodeRequest(sessionId, 102, src, tgt, null))) } catch (_: Exception) { }
        try { ws?.close(1000, "done") } catch (_: Exception) { }
    }
}
