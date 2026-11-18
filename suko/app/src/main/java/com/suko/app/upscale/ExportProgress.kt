package com.suko.app.upscale

/**
 * Step 13: ONE progress bar for an export with two phases (1 upscale, 2 render).
 * Each phase gets a share of the bar equal to its estimated time. The estimate for upscaling is corrected from
 * the pictures already done. The bar never moves backwards.
 */
class ExportProgress(
    private val todoPictures: Int,
    private val renderSeconds: Double,
    secondsPerPicture: Double = 9.0,     // measured by the old Sukko app on a big-RAM phone
) {
    enum class Phase { UPSCALE, RENDER }

    private var perPicture = secondsPerPicture
    private var measured = 0
    private var last = 0.0

    private val upscaleSeconds: Double get() = todoPictures * perPicture

    /** Call after each finished picture (not for failed ones that took no time) to sharpen the estimate. */
    fun pictureDone(seconds: Double) {
        if (seconds <= 0.0) return
        measured++
        perPicture += (seconds - perPicture) / (measured + 1)   // running average, the default counts as one sample
    }

    /** Fraction (0..1) of the upscale phase: pictures done plus the tiles of the current one. */
    fun upscaleFraction(picturesDone: Int, tileDone: Int, tileTotal: Int): Double {
        if (todoPictures <= 0) return 1.0
        val tile = if (tileTotal > 0) tileDone.toDouble() / tileTotal else 0.0
        return ((picturesDone + tile) / todoPictures).coerceIn(0.0, 1.0)
    }

    /** Whole-export fraction 0..1 for "fraction of the given phase done". */
    fun overall(phase: Phase, fraction: Double): Double {
        val f = fraction.coerceIn(0.0, 1.0)
        val up = upscaleSeconds
        val total = up + renderSeconds
        if (total <= 0.0) return last
        val v = when (phase) {
            Phase.UPSCALE -> up * f / total
            Phase.RENDER -> (up + renderSeconds * f) / total
        }
        last = maxOf(last, v)
        return last
    }

    fun percent(phase: Phase, fraction: Double): Int = (overall(phase, fraction) * 100).toInt().coerceIn(0, 100)

    fun label(phase: Phase, picturesDone: Int): String = when (phase) {
        Phase.UPSCALE -> "Upscaling picture ${minOf(picturesDone + 1, maxOf(todoPictures, 1))} of $todoPictures"
        Phase.RENDER -> "Rendering video"
    }
}
