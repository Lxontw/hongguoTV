// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    val background=Color.rgb(11,15,21)
    val surface=Color.rgb(23,30,40)
    val raised=Color.rgb(32,42,54)
    val accent=Color.rgb(255,137,100)
    val text=Color.rgb(243,244,246)
    val muted=Color.rgb(147,160,178)
    val outline=Color.rgb(43,55,70)
    val orientationBadgeBg=Color.argb(215,15,23,42)
    val orientationBadgeBorder=Color.argb(45,255,255,255)
    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) raised else surface,if(focused) accent else Color.TRANSPARENT)
    fun badge(context: Context,color: Int,radius: Int=4,border: Int=Color.TRANSPARENT,strokeDp: Float=0f)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        if(strokeDp>0f && border!=Color.TRANSPARENT) {
            setStroke((strokeDp*context.resources.displayMetrics.density).toInt().coerceAtLeast(1),border)
        }
    }
    fun screenFrame(context: Context,isLandscape: Boolean): GradientDrawable {
        val density=context.resources.displayMetrics.density
        val strokeWidth=(1f*density).toInt().coerceAtLeast(1)
        val width=((if(isLandscape) 13f else 8f)*density).toInt()
        val height=((if(isLandscape) 8.5f else 12f)*density).toInt()
        val corner=1.5f*density
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth,Color.rgb(203,213,225))
            cornerRadius=corner
            setBounds(0,0,width,height)
        }
    }
}
