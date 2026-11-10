// SPDX-License-Identifier: GPL-3.0-only
// Comment signing & parsing adapted from dawei233/hongguo-drama & jackotom/hongguo-drama (GPL-3.0-only).
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DanmakuRaw(val offsetMs: Long, val text: String)

data class DanmakuItem(val offsetMs: Long, val text: String, val count: Int = 1) {
    val displayText: String
        get() = if (count > 1) "$text × $count" else text
}

object DanmakuRepository {
    const val MAX_ITEMS_PER_EPISODE = 270
    const val MAX_TEXT_LENGTH = 100
    const val MAX_OFFSET_MS = 10_800_000L // 3 hours
    const val MAX_PAGES = 3
    const val PAGE_COUNT = 90
    const val EFFECTIVE_DURATION_MS = 3_600_000L
    const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
    const val BASE_URL = "https://api5-normal-sinfonlineb.fqnovel.com"
    const val USER_AGENT =
        "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
            "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    const val NONCE_BYTES = 11

    private val stripRegex = Regex("[\\s\\p{P}\\p{S}\\p{C}]+")
    private val argusKey = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
    private val secureRandom = SecureRandom()

    internal fun normalizeKey(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        return stripRegex.replace(nfkc, "")
    }

    private class Bucket(val minOffsetMs: Long, val firstText: String, var count: Int)

    fun mergeAndSort(raw: List<DanmakuRaw>): List<DanmakuItem> {
        val buckets = LinkedHashMap<String, Bucket>()
        for (item in raw) {
            if (item.offsetMs < 0L || item.offsetMs > MAX_OFFSET_MS) continue
            val cleanText = cleanControl(item.text)
            if (cleanText.isEmpty() || cleanText.length > MAX_TEXT_LENGTH) continue
            val key = normalizeKey(cleanText).ifEmpty { cleanText }
            val existing = buckets[key]
            if (existing == null) {
                buckets[key] = Bucket(item.offsetMs, cleanText, 1)
            } else {
                existing.count += 1
                if (item.offsetMs < existing.minOffsetMs) {
                    buckets[key] = Bucket(item.offsetMs, existing.firstText, existing.count)
                }
            }
        }
        return buckets.values
            .sortedBy { it.minOffsetMs }
            .take(MAX_ITEMS_PER_EPISODE)
            .map { DanmakuItem(it.minOffsetMs, it.firstText, it.count) }
    }

    internal fun cleanControl(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (cp < 0x20 || cp == 0x7f || cp == 0x2028 || cp == 0x2029) {
                sb.append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }
        return sb.toString().trim()
    }

    fun buildBody(vid: String, bookId: String, startMs: Long = 0L, durationMs: Long = EFFECTIVE_DURATION_MS): String {
        val bp = JSONObject()
            .put("book_id", bookId)
            .put("start_offset_time", startMs)
            .put("playlet_item_duration", durationMs)
            .put("need_danmaku_guide_type", JSONArray(listOf(1, 3, 4, 2)))
        return JSONObject()
            .put("comment_source", 601)
            .put("server_channel", 1000)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("comment_type", 20)
            .put("sort", 1)
            .put("count", PAGE_COUNT)
            .put("cursor", "")
            .put("aid", 8662)
            .put("compliance_status", 0)
            .put("business_param", bp)
            .toString()
    }

    fun newDeviceId(randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also(secureRandom::nextBytes) }): String {
        val bytes = randomBytes(8)
        val raw = java.math.BigInteger(1, bytes)
        val base = java.math.BigInteger.valueOf(1_000_000_000_000_000_000L)
        val mod = java.math.BigInteger.valueOf(8_000_000_000_000_000_000L)
        return base.add(raw.mod(mod)).toString()
    }

    private fun quotePlus(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("*", "%2A")
            .replace("%7E", "~")

    fun buildQuery(stampMs: Long, deviceId: String, installId: String): String {
        val devId = deviceId.takeIf { it.isNotBlank() } ?: newDeviceId()
        val iid = installId.takeIf { it.isNotBlank() } ?: newDeviceId()
        val params = sortedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "73532",
            "version_name" to "7.3.5.32",
            "manifest_version_code" to "73532",
            "update_version_code" to "73532",
            "channel" to "update_64",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "resolution" to "1280*2772",
            "dpi" to "520",
            "ac" to "wifi",
            "device_id" to devId,
            "iid" to iid,
            "_rticket" to stampMs.toString()
        )
        return params.entries.joinToString("&") { (k, v) -> "${quotePlus(k)}=${quotePlus(v)}" }
    }

    private fun rotl8(x: Int, n: Int): Int {
        val v = x and 0xFF
        val s = n and 7
        return ((v shl s) or (v ushr ((8 - s) and 7))) and 0xFF
    }

    private fun reverse8(x: Int): Int {
        var v = x and 0xFF
        v = ((v and 0xF0) ushr 4) or ((v and 0x0F) shl 4)
        v = ((v and 0xCC) ushr 2) or ((v and 0x33) shl 2)
        v = ((v and 0xAA) ushr 1) or ((v and 0x55) shl 1)
        return v and 0xFF
    }

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val padding = 16 - (data.size % 16)
        return data + ByteArray(padding) { padding.toByte() }
    }

    private fun uvarint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return out.toByteArray()
            }
        }
    }

    internal fun commentXGorgon(rawQuery: String, stampSec: Long, nonce: ByteArray): String {
        val head2 = (((nonce[0].u() % 7) + 1) shl 5) and 0xFF
        val head3 = (0xE0 or (nonce[1].u() and 0x0F)) and 0xFF

        val seed = ByteArray(20)
        val queryMd5 = digest("MD5", rawQuery.toByteArray(Charsets.UTF_8))
        queryMd5.copyInto(seed, 0, 0, 4)
        seed[12] = 0
        seed[13] = 1
        seed[14] = 7
        seed[15] = 4
        be32((stampSec and 0xFFFFFFFFL).toInt()).copyInto(seed, 16)

        val key = intArrayOf(0x4A, 0x00, 0x16, head3, 0x47, 0x6C, 0x00, head2)
        val box = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + box[i] + key[i and 7]) and 0xFF
            box[i] = box[j]
        }

        j = 0
        for (offset in 0 until 20) {
            val i = (offset + 1) and 0xFF
            j = (j + box[i]) and 0xFF
            box[i] = box[j]
            seed[offset] = (seed[offset].u() xor box[(box[i] + box[j]) and 0xFF]).toByte()
        }

        for (i in 0 until 20) {
            val next = seed[(i + 1) % 20].u()
            seed[i] = ((reverse8(rotl8(seed[i].u(), 4) xor next) xor 0xEB) and 0xFF).toByte()
        }

        val prefix = byteArrayOf(0x84.toByte(), 0x04, head2.toByte(), head3.toByte(), 0x00, 0x00)
        return (prefix + seed).hex()
    }

    internal fun commentLadon(aid: String, stampSec: Long, prefix4: ByteArray): String {
        val md5Hex = digest("MD5", prefix4 + aid.toByteArray(Charsets.UTF_8)).hex().toByteArray(Charsets.US_ASCII)
        var left = md5Hex.i64(0, true)
        var right = md5Hex.i64(8, true)
        val queue = ArrayDeque<Long>()
        queue.addLast(md5Hex.i64(16, true))
        queue.addLast(md5Hex.i64(24, true))

        val keys = LongArray(34)
        for (i in 0 until 34) {
            keys[i] = left
            val mixed = (java.lang.Long.rotateRight(right, 8) + left) xor i.toLong()
            queue.addLast(mixed)
            left = mixed xor java.lang.Long.rotateLeft(left, 3)
            right = queue.removeFirst()
        }

        val data = pkcs7Pad("$stampSec-1611921764-3019".toByteArray(Charsets.UTF_8))
        for (offset in data.indices step 16) {
            var bl = data.i64(offset, true)
            var br = data.i64(offset + 8, true)
            for (k in keys) {
                br = k xor (bl + java.lang.Long.rotateRight(br, 8))
                bl = br xor java.lang.Long.rotateLeft(bl, 3)
            }
            le64(bl).copyInto(data, offset)
            le64(br).copyInto(data, offset + 8)
        }
        return (prefix4 + data).base64()
    }

    internal fun commentArgus(rawQuery: String, stampSec: Long, nonce: ByteArray): String {
        val parts = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) {
            parts.write(uvarint(field.toLong() shl 3))
            parts.write(uvarint(value))
        }
        fun bytesField(field: Int, value: ByteArray) {
            parts.write(uvarint((field.toLong() shl 3) + 2L))
            parts.write(uvarint(value.size.toLong()))
            parts.write(value)
        }

        varint(1, 0x20200929L * 2L)
        varint(2, 2L)
        val nonceU32Le = ByteBuffer.wrap(nonce, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        varint(3, nonceU32Le)
        bytesField(4, "3019".toByteArray(Charsets.UTF_8))
        bytesField(6, "1611921764".toByteArray(Charsets.UTF_8))
        bytesField(7, "6.8.1.32".toByteArray(Charsets.UTF_8))
        bytesField(8, "v04.07.01-ml-android".toByteArray(Charsets.UTF_8))
        varint(9, 135135744L)
        bytesField(10, ByteArray(8))
        varint(12, stampSec * 2L)

        val bodyHash = sm3(ByteArray(16))
        val queryHash = sm3(rawQuery.toByteArray(Charsets.UTF_8))
        bytesField(13, bodyHash.copyOfRange(0, 6))
        bytesField(14, queryHash.copyOfRange(0, 6))

        val nested = ByteArrayOutputStream()
        nested.write(0x08)
        nested.write((2 + (nonce[10].u() % 49) * 2) and 0xFF)
        for (f in intArrayOf(2, 3, 4)) {
            nested.write(uvarint(f.toLong() shl 3))
            nested.write(uvarint(1388734L))
        }
        bytesField(15, nested.toByteArray())
        bytesField(20, "none".toByteArray(Charsets.UTF_8))
        varint(21, 738L)

        val data = pkcs7Pad(parts.toByteArray())
        val derived = sm3(argusKey + byteArrayOf(0x3C, 0xCC.toByte(), 0x56, 0x7B) + argusKey)

        val rounds = LongArray(72)
        for (i in 0 until 4) {
            rounds[i] = derived.i64(i * 8, true)
        }
        for (i in 4 until 72) {
            var mixed = java.lang.Long.rotateRight(rounds[i - 1], 3) xor rounds[i - 3]
            mixed = mixed xor java.lang.Long.rotateRight(mixed, 1)
            val bit = (0x3DC94C3A046D678BL ushr ((i - 4) % 62)) and 1L
            rounds[i] = rounds[i - 4].inv() xor mixed xor bit xor 3L
        }

        for (offset in data.indices step 16) {
            var left = data.i64(offset, true)
            var right = data.i64(offset + 8, true)
            for (r in rounds) {
                val next = left xor (java.lang.Long.rotateLeft(right, 1) and java.lang.Long.rotateLeft(right, 8)) xor java.lang.Long.rotateLeft(right, 2) xor r
                left = right
                right = next
            }
            le64(left).copyInto(data, offset)
            le64(right).copyInto(data, offset + 8)
        }

        val xorMask = byteArrayOf(0xD0.toByte(), 0x4F, 0xFD.toByte(), 0xFF.toByte(), 0xD0.toByte(), 0x4F, 0xFD.toByte(), 0xFF.toByte())
        for (i in data.indices) {
            data[i] = (data[i].u() xor xorMask[i % 8].u()).toByte()
        }
        val reversed = (xorMask + data).reversedArray()

        val container = pkcs7Pad(
            byteArrayOf(0xA6.toByte(), 0xE7.toByte(), 0x83.toByte(), 0xEE.toByte(), 0x70, 0x01, 0x10, 0x09, 0x18) +
                reversed +
                byteArrayOf(0x56, 0x7B)
        )

        val aesKey = digest("MD5", argusKey.copyOfRange(0, 16))
        val aesIv = digest("MD5", argusKey.copyOfRange(16, argusKey.size))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        val encrypted = cipher.doFinal(container)
        return (byteArrayOf(0x3C, 0xCC.toByte()) + encrypted).base64()
    }

    fun signCommentHeaders(rawQuery: String, stampSec: Long, nonce: ByteArray, aid: String = "8662"): Map<String, String> = linkedMapOf(
        "X-Gorgon" to commentXGorgon(rawQuery, stampSec, nonce),
        "X-Khronos" to stampSec.toString(),
        "X-SS-Req-Ticket" to (stampSec * 1000L).toString(),
        "X-SS-STUB" to "",
        "X-Argus" to commentArgus(rawQuery, stampSec, nonce),
        "X-Ladon" to commentLadon(aid, stampSec, nonce.copyOfRange(2, 6))
    )

    internal fun buildSignedCommentRequest(
        vid: String,
        bookId: String,
        startMs: Long = 0L,
        durationMs: Long = EFFECTIVE_DURATION_MS,
        deviceId: String = "",
        installId: String = "",
        stampMs: Long = System.currentTimeMillis(),
        nonce: ByteArray = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
    ): Signer.Request {
        val stampSec = stampMs / 1000L
        val rawQuery = buildQuery(stampMs, deviceId, installId)
        val url = "$BASE_URL/novel/commentapi/comment/list/$vid/v1/?$rawQuery"
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "Content-Type" to "application/json; charset=utf-8",
            "Comment-Source" to "601",
            "Server-Channel" to "1000"
        ).apply {
            putAll(signCommentHeaders(rawQuery, stampSec, nonce))
        }
        val body = buildBody(vid, bookId, startMs, durationMs).toByteArray(Charsets.UTF_8)
        return Signer.Request(url, headers, body)
    }

    fun parsePage(rawJson: String, expectedVid: String = ""): Triple<List<DanmakuRaw>, Boolean, Int?> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return Triple(emptyList(), false, null)
        val root = JSONObject(trimmed)
        if (root.optInt("code", -1) != 0) return Triple(emptyList(), false, null)
        val data = root.optJSONObject("data") ?: root
        val list = data.optJSONArray("data_list")
            ?: data.optJSONArray("comment_list")
            ?: data.optJSONArray("list")
            ?: return Triple(emptyList(), false, null)

        var nextMs: Int? = null
        var hasMore: Boolean? = null

        val extra = data.optJSONObject("extra")
        if (extra != null) {
            val rawNext = extra.str("next_query_danmaku_list_time")
            rawNext.toIntOrNull()?.takeIf { it > 0 }?.let { nextMs = it }
        }

        val info = data.optJSONObject("common_list_info")
        if (info != null) {
            if (info.has("has_more")) {
                val rawFlag = info.opt("has_more")
                hasMore = when (rawFlag) {
                    is Boolean -> rawFlag
                    is Number -> rawFlag.toInt() != 0
                    is String -> rawFlag.trim().lowercase() in setOf("1", "true", "yes")
                    else -> null
                }
            }
            val cursorObj = when (val c = info.opt("cursor")) {
                is JSONObject -> c
                is String -> if (c.trim().startsWith("{")) runCatching { JSONObject(c.trim()) }.getOrNull() else null
                else -> null
            }
            if (cursorObj != null) {
                for (key in listOf("end_offset_time", "next_offset_time", "offset")) {
                    val candidate = cursorObj.str(key).toIntOrNull() ?: continue
                    if (candidate > 0 && (nextMs == null || candidate > nextMs!!)) {
                        nextMs = candidate
                    }
                    break
                }
            }
        }

        if (hasMore == null && data.has("has_more")) {
            hasMore = data.optBoolean("has_more", false)
        }
        if (nextMs == null) {
            nextMs = data.optInt("offset", -1).takeIf { it > 0 }
                ?: data.optInt("next_offset", -1).takeIf { it > 0 }
        }
        if (hasMore == false) {
            nextMs = null
        }

        val items = mutableListOf<DanmakuRaw>()
        for (obj in list.objects()) {
            val comment = obj.optJSONObject("comment") ?: obj
            val common = comment.optJSONObject("common")
            if (common != null) {
                val groupId = common.str("group_id")
                if (expectedVid.isNotEmpty() && groupId.isNotEmpty() && groupId != expectedVid) continue
                val status = common.str("status")
                if (status.isNotEmpty() && status != "1") continue
            }
            val contentObj = common?.optJSONObject("content")
                ?: comment.optJSONObject("content_info")
                ?: comment
            val text = contentObj.str("text")
                .ifEmpty { contentObj.str("content") }
                .ifEmpty { comment.str("text") }
                .trim()
            if (text.isEmpty()) continue
            val expand = comment.optJSONObject("expand")
            val posMs = when {
                expand != null && expand.has("offset_time") -> expand.optLong("offset_time", -1L)
                comment.has("offset_time") -> comment.optLong("offset_time", -1L)
                comment.has("video_position") -> {
                    val v = comment.optDouble("video_position", -1.0)
                    if (v > 10000.0 && !comment.str("video_position").contains('.')) v.toLong() else (v * 1000.0).toLong()
                }
                comment.has("offset_ms") -> comment.optLong("offset_ms", -1L)
                comment.has("danmu_time") -> comment.optLong("danmu_time", -1L)
                else -> (items.size * 2500L) + 1200L
            }
            if (posMs in 0..MAX_OFFSET_MS) {
                items += DanmakuRaw(posMs, text)
            }
        }
        val finalHasMore = hasMore ?: (nextMs != null && nextMs!! > 0)
        return Triple(items, finalHasMore, nextMs)
    }
}

