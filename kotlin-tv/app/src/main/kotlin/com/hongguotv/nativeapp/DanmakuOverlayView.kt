// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.hongguotv.core.DanmakuItem
import kotlin.math.max

class DanmakuOverlayView(context: Context) : View(context) {
    private class ActiveItem(
        val text: String,
        val isRepeated: Boolean,
        val lane: Int,
        val textWidth: Float,
        var x: Float,
        val speedPxPerSec: Float
    )

    companion object {
        private const val LANE_COUNT = 3
        private const val DURATION_SEC = 7.5f
    }

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
        setShadowLayer(4f * density, 0f, 1.5f * density, Color.argb(220, 0, 0, 0))
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 20, 22, 30)
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 245, 194, 107)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val badgeRect = RectF()

    private var timeline: List<DanmakuItem> = emptyList()
    private var cursorIndex = 0
    private val active = ArrayList<ActiveItem>(16)
    private var enabledFlag = false
    private var isPlayingFlag = false
    private var lastPositionMs = 0L
    private var lastFrameUptimeMs = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        if (enabledFlag == enabled) return
        enabledFlag = enabled
        if (!enabled) {
            active.clear()
        }
        visibility = if (enabled) VISIBLE else GONE
        invalidate()
    }

    fun loadTimeline(items: List<DanmakuItem>, currentPositionMs: Long) {
        timeline = items
        active.clear()
        seekToPosition(currentPositionMs)
        invalidate()
    }

    fun clearTimeline() {
        timeline = emptyList()
        cursorIndex = 0
        active.clear()
        invalidate()
    }

    private fun seekToPosition(positionMs: Long) {
        lastPositionMs = positionMs
        var lo = 0
        var hi = timeline.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeline[mid].offsetMs < positionMs) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        cursorIndex = lo
        // If user just loaded/enabled danmaku, step back slightly so comments near current position can appear immediately
        while (cursorIndex > 0 && positionMs - timeline[cursorIndex - 1].offsetMs in 0..2000L) {
            cursorIndex--
        }
    }

    fun syncPlayback(positionMs: Long, isPlaying: Boolean) {
        val delta = positionMs - lastPositionMs
        if (delta < -500L || delta > 2500L) {
            // User sought or jumped; clear active items and advance cursor to prevent avalanche
            active.clear()
            seekToPosition(positionMs)
        } else {
            lastPositionMs = max(0L, positionMs)
        }
        val wasPlaying = isPlayingFlag
        isPlayingFlag = isPlaying
        if (enabledFlag && timeline.isNotEmpty()) {
            val spawned = spawnDueItems(lastPositionMs)
            if ((isPlaying && !wasPlaying) || spawned > 0) {
                lastFrameUptimeMs = SystemClock.uptimeMillis()
                postInvalidateOnAnimation()
            } else if (active.isNotEmpty()) {
                postInvalidateOnAnimation()
            }
        }
    }

    private fun spawnDueItems(positionMs: Long): Int {
        val w = width.toFloat()
        if (w <= 0f) return 0
        val minGap = 36f * density
        var spawned = 0
        while (cursorIndex < timeline.size) {
            val candidate = timeline[cursorIndex]
            if (candidate.offsetMs > positionMs) break
            cursorIndex++
            if (positionMs - candidate.offsetMs > 2200L) continue
            val lane = findAvailableLane(w, minGap) ?: continue
            val display = candidate.displayText
            val tw = textPaint.measureText(display)
            val speed = (w + tw + 48f * density) / DURATION_SEC
            active.add(
                ActiveItem(
                    text = display,
                    isRepeated = candidate.count > 1,
                    lane = lane,
                    textWidth = tw,
                    x = if (isPlayingFlag) w + 12f * density else (w - tw - (spawned * 48f * density) - 32f * density).coerceAtLeast(24f * density),
                    speedPxPerSec = speed
                )
            )
            spawned++
        }
        return spawned
    }

    private fun findAvailableLane(viewWidth: Float, minGap: Float): Int? {
        for (lane in 0 until LANE_COUNT) {
            var rightmost = -1e9f
            for (i in 0 until active.size) {
                val item = active[i]
                if (item.lane == lane) {
                    val rightEdge = item.x + item.textWidth
                    if (rightEdge > rightmost) rightmost = rightEdge
                }
            }
            if (rightmost < viewWidth - minGap) {
                return lane
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledFlag || active.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        val dtSec = if (isPlayingFlag && lastFrameUptimeMs > 0L) {
            ((now - lastFrameUptimeMs).coerceIn(0L, 100L)) / 1000f
        } else {
            0f
        }
        lastFrameUptimeMs = now

        val topMargin = 24f * density
        val laneHeight = 34f * density
        val padH = 10f * density
        val padV = 4f * density
        val corner = 12f * density

        val iter = active.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (dtSec > 0f) {
                item.x -= item.speedPxPerSec * dtSec
            }
            if (item.x + item.textWidth < -40f * density) {
                iter.remove()
                continue
            }
            val baselineY = topMargin + (item.lane + 1) * laneHeight
            if (item.isRepeated) {
                val fm = textPaint.fontMetrics
                badgeRect.set(
                    item.x - padH,
                    baselineY + fm.ascent - padV,
                    item.x + item.textWidth + padH,
                    baselineY + fm.descent + padV
                )
                canvas.drawRoundRect(badgeRect, corner, corner, badgeBgPaint)
                canvas.drawRoundRect(badgeRect, corner, corner, badgeStrokePaint)
                textPaint.color = Color.rgb(255, 224, 140)
            } else {
                textPaint.color = Color.argb(235, 255, 255, 255)
            }
            canvas.drawText(item.text, item.x, baselineY, textPaint)
        }

        if (isPlayingFlag && active.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }
}
