package com.ast.android

import java.io.ByteArrayOutputStream

// Hand-written protobuf wire format, mirroring Windows volc_ast2.py.
// TranslateRequest: 1=request_meta (6=SessionID), 2=event, 3=user (1=uid,2=did),
// 4=source_audio (4=format,7=rate,8=bits,9=channel,14=binary_data),
// 6=request on Start only (1=mode,2=source_language,3=target_language).
// TranslateResponse: 1=response_meta (3=status,4=message), 2=event, 3=data,
// 4=text, 7=spk_chg.
object Proto {
    fun varint(n: Long): ByteArray {
        var v = n
        val out = ByteArrayOutputStream()
        while (true) {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); break }
        }
        return out.toByteArray()
    }
    fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())
    fun bytesField(field: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 2)); out.write(varint(data.size.toLong())); out.write(data)
        return out.toByteArray()
    }
    fun strField(field: Int, s: String): ByteArray = bytesField(field, s.toByteArray(Charsets.UTF_8))
    fun varintField(field: Int, n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 0)); out.write(varint(n))
        return out.toByteArray()
    }
    fun encodeRequest(sessionId: String, event: Long, src: String, tgt: String, audio: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytesField(1, strField(6, sessionId)))
        out.write(varintField(2, event))
        val user = ByteArrayOutputStream()
        user.write(strField(1, "ast-android")); user.write(strField(2, "ast-android"))
        out.write(bytesField(3, user.toByteArray()))
        val sa = ByteArrayOutputStream()
        sa.write(strField(4, "wav")); sa.write(varintField(7, 16000)); sa.write(varintField(8, 16)); sa.write(varintField(9, 1))
        if (audio != null) sa.write(bytesField(14, audio))
        out.write(bytesField(4, sa.toByteArray()))
        if (event == 100L) {
            val req = ByteArrayOutputStream()
            req.write(strField(1, "s2t")); req.write(strField(2, src)); req.write(strField(3, tgt))
            out.write(bytesField(6, req.toByteArray()))
        }
        return out.toByteArray()
    }

    data class Resp(val event: Long, val text: String, val status: Long, val message: String, val spkChg: Boolean)

    fun decodeResponse(buf: ByteArray): Resp {
        var ev = 0L; var text = ""; var status = 0L; var msg = ""; var spk = false
        var pos = 0
        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (pos < buf.size) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        while (pos < buf.size) {
            val tag = readVarint(); val field = (tag shr 3).toInt(); val wire = (tag and 7).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint()
                    if (field == 2) ev = v
                    if (field == 7) spk = v != 0L
                }
                2 -> {
                    val ln = readVarint().toInt()
                    val v = buf.copyOfRange(pos, minOf(pos + ln, buf.size)); pos += ln
                    if (field == 4) text = String(v, Charsets.UTF_8)
                    if (field == 1) {
                        // nested response_meta
                        var p2 = 0
                        fun rv2(): Long {
                            var r = 0L; var s = 0
                            while (p2 < v.size) {
                                val b = v[p2++].toInt() and 0xFF
                                r = r or ((b and 0x7F).toLong() shl s)
                                if (b and 0x80 == 0) return r
                                s += 7
                            }
                            return r
                        }
                        while (p2 < v.size) {
                            val t2 = rv2(); val f2 = (t2 shr 3).toInt(); val w2 = (t2 and 7).toInt()
                            if (w2 == 0) {
                                val vv = rv2()
                                if (f2 == 3) status = vv
                            } else if (w2 == 2) {
                                val l2 = rv2().toInt()
                                val vv = v.copyOfRange(p2, minOf(p2 + l2, v.size)); p2 += l2
                                if (f2 == 4) msg = String(vv, Charsets.UTF_8)
                            } else break
                        }
                    }
                }
                else -> break
            }
        }
        return Resp(ev, text, status, msg, spk)
    }
}
