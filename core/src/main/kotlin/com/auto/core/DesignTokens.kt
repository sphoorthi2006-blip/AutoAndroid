package com.auto.core

import kotlin.math.pow

/**
 * Section 4: Midnight Violet Design System.
 * Centralizes all colors, typography, spacing, and WCAG contrast helpers.
 * No orange anywhere. Violet is primary; cyan is sparing.
 */
object DesignTokens {
    // Section 4 exact Midnight Violet surface & border tokens
    const val BG = 0xFF0B1020.toInt()           // #0B1020 Midnight Navy (Main background)
    const val SURFACE = 0xFF121A2B.toInt()      // #121A2B Deep Slate (Navigation/surfaces)
    const val SURFACE_HI = 0xFF192338.toInt()   // #192338 Dark Slate (Cards/panels)
    const val LINE = 0xFF273449.toInt()         // #273449 Slate Border (Borders/dividers)

    // Section 4 exact text tokens
    const val TEXT = 0xFFF8FAFC.toInt()         // #F8FAFC Soft White (Primary text)
    const val TEXT_MUTED = 0xFF94A3B8.toInt()   // #94A3B8 Cool Gray (Secondary text)

    // Section 4 exact accent & status tokens
    const val PRIMARY = 0xFF8B5CF6.toInt()      // #8B5CF6 Electric Violet (Main accent/selected)
    const val PRIMARY_DEEP = 0xFF6D28D9.toInt() // #6D28D9 Deep Violet for high-contrast filled button surfaces (white contrast >= 4.5:1)
    const val PRIMARY_HI = 0xFF8B5CF6.toInt()   // Alias for compatibility
    const val PRIMARY_LIGHT = 0xFFC4B5FD.toInt()// #C4B5FD Lighter Violet for small text on dark cards (contrast >= 8.5:1 on SURFACE_HI)
    const val LAVENDER = PRIMARY_LIGHT          // Alias for compatibility
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()   // #FFFFFF Soft White on primary buttons
    const val CYAN = 0xFF38BDF8.toInt()         // #38BDF8 Cyan Blue (Rare secondary accent)
    const val SUCCESS = 0xFF34D399.toInt()      // #34D399 Emerald (Success)
    const val ERROR = 0xFFF87171.toInt()        // #F87171 Red (Error)
    const val DANGER = ERROR                    // Alias for compatibility
    const val WARNING = 0xFFFBBF24.toInt()      // #FBBF24 Amber (Warning)

    // Type scale (sp)
    const val TITLE = 26
    const val SECTION = 17
    const val CARD = 15
    const val BODY = 13
    const val LABEL = 11
    val TITLE_RANGE = 24..28
    val SECTION_RANGE = 16..19
    val CARD_RANGE = 14..16
    val BODY_RANGE = 13..14
    val LABEL_RANGE = 11..12

    // Spacing scale 4/8/12/16/24/32 dp; shared corner radius and minimum touch target (44 dp)
    val SPACING = listOf(4, 8, 12, 16, 24, 32)
    const val RADIUS_S = 10
    const val RADIUS_M = 16
    const val RADIUS_L = 22
    const val MIN_TOUCH_DP = 44

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * lin((argb shr 16) and 255) + 0.7152 * lin((argb shr 8) and 255) + 0.0722 * lin(argb and 255)

    /** WCAG contrast ratio 1..21. Small body text requires >= 4.5:1, large text / bold UI components >= 3.0:1. */
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
