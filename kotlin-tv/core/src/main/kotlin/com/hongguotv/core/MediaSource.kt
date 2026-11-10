// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.net.URI

enum class MediaSource(val storedValue: String, val label: String) {
    HONGGUO("hongguo", "红果"),
    XIFAN("xifan", "稀饭"),
    HEMA("hema", "河马");

    companion object {
        fun fromStored(value: String?): MediaSource =
            entries.firstOrNull { it.storedValue == value } ?: HONGGUO

        fun fromSeriesOrEpisodeId(id: String): MediaSource = when {
            id.startsWith("xifan:") -> XIFAN
            id.startsWith("hema:") -> HEMA
            else -> HONGGUO
        }
    }
}

internal object MediaUrlGuard {
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
