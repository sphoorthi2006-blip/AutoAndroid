package com.auto.core

import kotlin.math.pow

/** The dark premium look as plain numbers (the Compose theme reads them), so contrast and the type scale can be tested without a phone. */
object DesignTokens {
    // charcoal surfaces
    const val BG = 0xFF0B0B0F.toInt(); const val SURFACE = 0xFF15151B.toInt(); const val SURFACE_HI = 0xFF1E1E26.toInt(); const val LINE = 0xFF2B2B35.toInt()
    // text
    const val TEXT = 0xFFF5F5F7.toInt(); const val TEXT_MUTED = 0xFFA3A3B0.toInt()
    // violet + lavender
    const val PRIMARY = 0xFF7C3AED.toInt(); const val PRIMARY_HI = 0xFF8B5CF6.toInt(); const val LAVENDER = 0xFFC4B5FD.toInt(); const val ON_PRIMARY = 0xFFFFFFFF.toInt()
    // status
    const val SUCCESS = 0xFF4ADE80.toInt(); const val WARNING = 0xFFFBBF24.toInt(); const val DANGER = 0xFFF87171.toInt()

    // type scale (sp) - the ranges are the ones in the design brief
    const val TITLE = 26; const val SECTION = 17; const val CARD = 15; const val BODY = 13; const val LABEL = 11
    val TITLE_RANGE = 24..28; val SECTION_RANGE = 16..19; val CARD_RANGE = 14..16; val BODY_RANGE = 13..14; val LABEL_RANGE = 11..12
    // spacing and corner radius (dp)
    val SPACING = listOf(4, 8, 12, 16, 24, 32); val RADIUS_S = 10; val RADIUS_M = 16; val RADIUS_L = 22
    const val MIN_TOUCH_DP = 44

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    fun luminance(argb: Int) = 0.2126 * lin((argb shr 16) and 255) + 0.7152 * lin((argb shr 8) and 255) + 0.0722 * lin(argb and 255)
    /** WCAG contrast ratio 1..21. Body text needs 4.5, large text 3. */
    fun contrast(a: Int, b: Int): Double { val x = luminance(a); val y = luminance(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
}
