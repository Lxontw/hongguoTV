// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class MediaSource(val storedValue: String, val label: String) {
    HONGGUO("hongguo", "红果"),
    XIFAN("xifan", "稀饭"),
    HEMA("hema", "河马"),
    REELSHORT("reelshort", "ReelShort（海外）"),
    DRAMABOX("dramabox", "DramaBox（海外）");

    companion object {
        val defaultDisabled = setOf(REELSHORT, DRAMABOX)
        fun fromStored(value: String?): MediaSource =
            entries.firstOrNull { it.storedValue == value } ?: HONGGUO

        fun fromSeriesOrEpisodeId(id: String): MediaSource = when {
            id.startsWith("xifan:") -> XIFAN
            id.startsWith("hema:") -> HEMA
            id.startsWith("reelshort:") -> REELSHORT
            id.startsWith("dramabox:") -> DRAMABOX
            else -> HONGGUO
        }
    }
}

internal object MediaUrlGuard {
    private val dohCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val dohClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    fun isFakeIpAddress(addr: InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        val b = addr.address
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 198 && b1 in 18..19
    }

    fun isPublicInetAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return false
        }
        return when (addr) {
            is Inet4Address -> isPublicIpv4(addr.address)
            is Inet6Address -> {
                val b = addr.address
                // Check IPv4-mapped IPv6 ::ffff:0:0/96
                val isV4Mapped = (0 until 10).all { b[it].toInt() == 0 } &&
                    (b[10].toInt() and 0xFF) == 0xFF &&
                    (b[11].toInt() and 0xFF) == 0xFF
                if (isV4Mapped) {
                    return isPublicIpv4(b.copyOfRange(12, 16))
                }
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                val b2 = b[2].toInt() and 0xFF
                val b3 = b[3].toInt() and 0xFF
                // Require 2000::/3 global unicast, excluding ULA (fc00::/7), 2001:db8::/32, 2001::/32 (Teredo), 2002::/16 (6to4)
                if ((b0 and 0xFE) == 0xFC) return false
                if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8) return false
                if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return false
                if (b0 == 0x20 && b1 == 0x02) return false
                (b0 and 0xE0) == 0x20
            }
            else -> false
        }
    }

    private fun isPublicIpv4(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        return when {
            b0 == 0 -> false
            b0 == 10 -> false
            b0 == 100 && (b1 and 0xC0) == 64 -> false
            b0 == 127 -> false
            b0 == 169 && b1 == 254 -> false
            b0 == 172 && b1 in 16..31 -> false
            b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2) -> false
            b0 == 192 && b1 == 168 -> false
            b0 == 198 && (b1 in 18..19 || (b1 == 51 && b2 == 100)) -> false
            b0 == 203 && b1 == 0 && b2 == 113 -> false
            b0 >= 224 -> false
            else -> true
        }
    }

    private fun isOfficialDomain(hostname: String): Boolean {
        val h = hostname.lowercase().trimEnd('.')
        return h == "hongguoduanju.com" ||
            h.endsWith(".hongguoduanju.com") ||
            h.endsWith(".fqnovel.com") ||
            h.endsWith(".snssdk.com") ||
            h.endsWith(".byteimg.com") ||
            h.endsWith(".fqnovelpic.com") ||
            h.endsWith(".qznovelvod.com") ||
            h.endsWith(".fqnovelvod.com") ||
            h.endsWith(".douyinvod.com")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val key = hostname.lowercase().trimEnd('.')
        val now = System.currentTimeMillis()
        dohCache[key]?.takeIf { now < it.first && it.second.isNotEmpty() }?.let { return it.second }
        val encoded = URLEncoder.encode(key, "UTF-8")
        val endpoints = listOf(
            "https://223.5.5.5/resolve?name=$encoded&type=A",
            "https://1.1.1.1/dns-query?name=$encoded&type=A"
        )
        for (endpoint in endpoints) {
            val resolved = runCatching {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-json")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<InetAddress>()
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (json.optInt("Status", -1) != 0) return@use emptyList<InetAddress>()
                    val answers = json.optJSONArray("Answer") ?: return@use emptyList<InetAddress>()
                    val addrs = mutableListOf<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val ans = answers.optJSONObject(i) ?: continue
                        if (ans.optInt("type") != 1) continue
                        val ipStr = ans.optString("data").trim()
                        if (!ipStr.matches(Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"))) continue
                        val addr = InetAddress.getByName(ipStr)
                        if (isPublicInetAddress(addr)) addrs += addr
                    }
                    addrs
                }
            }.getOrDefault(emptyList())
            if (resolved.isNotEmpty()) {
                dohCache[key] = (now + 60_000L) to resolved
                return resolved
            }
        }
        return emptyList()
    }

    object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val systemResult = runCatching { Dns.SYSTEM.lookup(hostname) }
            val systemAddrs = systemResult.getOrNull().orEmpty()
            val publicSystem = systemAddrs.filter(::isPublicInetAddress)
            if (publicSystem.isNotEmpty()) return publicSystem

            val allFakeIp = systemAddrs.isNotEmpty() && systemAddrs.all(::isFakeIpAddress)
            if (allFakeIp || (systemResult.isFailure && isOfficialDomain(hostname))) {
                val dohAddrs = resolveViaDoh(hostname)
                if (dohAddrs.isNotEmpty()) return dohAddrs
            }
            throw UnknownHostException("已拦截非公网或无效域名解析: $hostname")
        }
    }

    fun assertSafeMediaUrl(rawUrl: String): String {
        val cleaned = cleanUrl(rawUrl).trim()
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: throw IOException("外部片源地址无效")
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme != "https" ||
            uri.userInfo != null ||
            host.isEmpty() ||
            host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.matches(Regex("[0-9.]+")) ||
            host.contains(':')
        ) {
            throw IOException("已拦截不安全的外部播放地址")
        }
        return cleaned
    }
}
