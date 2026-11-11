// SPDX-License-Identifier: GPL-3.0-only
// Playback-only 551 Argus/Ladon signing adapted from N3urda/hongguoTV & jackotom/hongguo-drama (GPL-3.0-only).
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object PlayerSigner {
    const val LICENSE_ID = 1611921764L
    private val SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val AES_KEY = hex("d31e3718288a1027baab59f146a09a9c")
    private val AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54")
    private val MAGIC8 = hex("f2f7fcfff2f7fcff")
    private val TAIL9 = hex("eafb2cfe8568519154")
    private val PREFIX2 = hex("f281")
    private val secureRandom = SecureRandom()

    fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    fun pythonUrlEncode(values: Map<String, String>): String =
        values.entries.joinToString("&") { (key, value) ->
            "${pythonEncodeComponent(key)}=${pythonEncodeComponent(value)}"
        }

    private fun pythonEncodeComponent(item: String): String =
        URLEncoder.encode(item, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("!", "%21")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")

    fun varint32(value: Long): ByteArray {
        var n = value and 0xFFFFFFFFL
        val out = ByteArrayOutputStream()
        while (n >= 128L) {
            out.write(((n and 0x7FL) or 0x80L).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }

    class ProtoBuf {
        private val out = ByteArrayOutputStream()

        fun putVarint(tag: Int, value: Long): ProtoBuf {
            out.write(varint32((tag.toLong() shl 3)))
            out.write(varint32(value))
            return this
        }

        fun putBytes(tag: Int, bytes: ByteArray): ProtoBuf {
            out.write(varint32((tag.toLong() shl 3) or 2L))
            out.write(varint32(bytes.size.toLong()))
            out.write(bytes)
            return this
        }

        fun putUtf8(tag: Int, value: String): ProtoBuf =
            putBytes(tag, value.toByteArray(Charsets.UTF_8))

        fun putProtobuf(tag: Int, nested: ProtoBuf): ProtoBuf =
            putBytes(tag, nested.toBuf())

        fun toBuf(): ByteArray = out.toByteArray()
    }

    object Simon {
        fun keyExpansion(key: LongArray): LongArray {
            require(key.size == 4) { "Four Simon key words required" }
            val expanded = LongArray(72)
            for (i in 0 until 4) expanded[i] = key[i]
            for (i in 4 until 72) {
                var tmp = java.lang.Long.rotateRight(expanded[i - 1], 3) xor expanded[i - 3]
                tmp = tmp xor java.lang.Long.rotateRight(tmp, 1)
                val bit = (0x3dc94c3a046d678bL ushr ((i - 4) % 62)) and 1L
                expanded[i] = expanded[i - 4].inv() xor tmp xor bit xor 3L
            }
            return expanded
        }

        fun encrypt(left: Long, right: Long, key: LongArray): LongArray {
            var x = left
            var y = right
            for (roundKey in keyExpansion(key)) {
                val previous = y
                y = x xor (java.lang.Long.rotateLeft(y, 1) and java.lang.Long.rotateLeft(y, 8)) xor java.lang.Long.rotateLeft(y, 2) xor roundKey
                x = previous
            }
            return longArrayOf(x, y)
        }
    }

    private fun pad16(data: ByteArray): ByteArray {
        val pad = 16 - (data.size % 16)
        return data + ByteArray(pad) { pad.toByte() }
    }

    fun queryParam(query: String, name: String): String? {
        fun decode(v: String): String = try {
            URLDecoder.decode(v, "UTF-8")
        } catch (_: Exception) {
            v
        }
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val at = part.indexOf('=')
            val key = if (at < 0) part else part.substring(0, at)
            if (decode(key) == name) {
                return decode(if (at < 0) "" else part.substring(at + 1))
            }
        }
        return null
    }

    object Argus {
        private val hexRegex = Regex("^(?:[0-9a-fA-F]{2})+$")

        fun bodyHash(stubHex: String): ByteArray {
            if (stubHex.isEmpty()) return sm3(ByteArray(16)).copyOfRange(0, 6)
            val bytes = if (hexRegex.matches(stubHex)) hex(stubHex) else ByteArray(0)
            return sm3(bytes).copyOfRange(0, 6)
        }

        fun queryHash(query: String): ByteArray =
            sm3(if (query.isNotEmpty()) query.toByteArray(Charsets.UTF_8) else ByteArray(16)).copyOfRange(0, 6)

        fun sign(
            query: String,
            stubHex: String,
            ts: Long,
            aid: Long,
            licenseId: Long = LICENSE_ID,
            deviceId: String,
            installId: String = "",
            randField: Long
        ): String {
            val effectiveAid = if (aid == 0L) 1967L else aid
            val effectiveLicense = if (licenseId == 0L) LICENSE_ID else licenseId
            val bean = ProtoBuf()
                .putVarint(1, 1077940818L)
                .putVarint(2, 2L)
                .putVarint(3, randField)
                .putUtf8(4, effectiveAid.toString())
                .putUtf8(5, deviceId)
                .putUtf8(6, effectiveLicense.toString())
                .putUtf8(7, queryParam(query, "version_name").orEmpty())
                .putUtf8(8, "v04.04.05-ov-android")
                .putVarint(9, 134744640L)
                .putBytes(10, ByteArray(8))
                .putVarint(11, 0L)
                .putVarint(12, ts shl 1)
                .putBytes(13, bodyHash(stubHex))
                .putBytes(14, queryHash(query))
                .putProtobuf(
                    15,
                    ProtoBuf()
                        .putVarint(1, 1L)
                        .putVarint(2, 1L)
                        .putVarint(3, 1L)
                        .putVarint(7, 0xc792ecccL)
                )
                .putUtf8(16, installId)
                .putUtf8(20, "none")
                .putVarint(21, 738L)
                .putProtobuf(
                    23,
                    ProtoBuf()
                        .putUtf8(1, "NX551J")
                        .putVarint(2, 8196L)
                        .putVarint(4, 0x80e0d800L)
                )
                .putVarint(25, 2L)

            val protobuf = pad16(bean.toBuf())
            val key = LongArray(4) { i -> SIGN_KEY.i64(i * 8, true) }
            val blocks = ByteArrayOutputStream(protobuf.size)
            for (at in protobuf.indices step 16) {
                val encrypted = Simon.encrypt(protobuf.i64(at, true), protobuf.i64(at + 8, true), key)
                blocks.write(le64(encrypted[0]))
                blocks.write(le64(encrypted[1]))
            }
            val framed = MAGIC8 + blocks.toByteArray() + TAIL9
            for (i in 8 until framed.size) {
                framed[i] = (framed[i].u() xor MAGIC8[i % 8].u()).toByte()
            }
            val padded = pad16(framed.reversedArray() + "ao".toByteArray(Charsets.US_ASCII))
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
            val encrypted = cipher.doFinal(padded)
            return (PREFIX2 + encrypted).base64()
        }
    }

    object Ladon {
        fun encrypt(ts: Long, licenseId: Long, aid: Long, randomBytes: ByteArray): String {
            require(randomBytes.size == 4) { "Four Ladon random bytes required" }
            val aidText = aid.toString()
            val digestHex = digest("MD5", randomBytes + aidText.toByteArray(Charsets.UTF_8)).hex()
            val ascii = digestHex.toByteArray(Charsets.US_ASCII)
            val words = LongArray(4) { i -> ascii.i64(i * 8, true) }
            val table = LongArray(35)
            table[0] = words[0]
            val queue = ArrayDeque<Long>()
            queue.addLast(words[2])
            queue.addLast(words[3])
            var b0 = words[0]
            var b8 = words[1]
            for (i in 0 until 34) {
                var x8 = (java.lang.Long.rotateRight(b8, 8) + b0) xor i.toLong()
                queue.addLast(x8)
                x8 = x8 xor java.lang.Long.rotateLeft(b0, 3)
                table[i + 1] = x8
                b0 = x8
                b8 = queue.removeFirst()
            }
            val input = pad16("$ts-$licenseId-$aidText".toByteArray(Charsets.US_ASCII))
            val out = ByteArray(input.size)
            for (at in input.indices step 16) {
                var a = input.i64(at, true)
                var b = input.i64(at + 8, true)
                for (i in 0 until 34) {
                    b = table[i] xor (a + java.lang.Long.rotateRight(b, 8))
                    a = b xor java.lang.Long.rotateLeft(a, 3)
                }
                le64(a).copyInto(out, at)
                le64(b).copyInto(out, at + 8)
            }
            return (randomBytes + out).base64()
        }
    }

    fun signPlayer(
        query: String,
        body: ByteArray? = null,
        ts: Long,
        randField: Long = ByteBuffer.wrap(ByteArray(4).also(secureRandom::nextBytes)).order(ByteOrder.LITTLE_ENDIAN).int.toLong(),
        ladonRandom: ByteArray = ByteArray(4).also(secureRandom::nextBytes)
    ): Map<String, String> {
        val aidValue = queryParam(query, "aid").orEmpty()
        val aid = aidValue.toLongOrNull() ?: 1967L
        val stub = if (body != null && body.isNotEmpty()) digest("MD5", body).hex() else ""
        val headers = linkedMapOf(
            "x-ss-req-ticket" to (ts * 1000L).toString(),
            "x-khronos" to ts.toString(),
            "x-ladon" to Ladon.encrypt(ts, LICENSE_ID, aid, ladonRandom),
            "x-argus" to Argus.sign(
                query = query,
                stubHex = stub,
                ts = ts,
                aid = aid,
                licenseId = LICENSE_ID,
                deviceId = queryParam(query, "device_id").orEmpty(),
                installId = "",
                randField = randField
            )
        )
        if (stub.isNotEmpty()) {
            headers["x-ss-stub"] = stub
        }
        return headers
    }
}
