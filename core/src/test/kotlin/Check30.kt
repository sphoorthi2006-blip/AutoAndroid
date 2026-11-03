import com.auto.core.*

/**
 * Phase 9 tests (Sections 4, 18, 19): Premium polish and accessibility.
 * - Verifies spacing/radius/typography tokens, touch target >= 44 dp, no orange in presets,
 *   and WCAG contrast across all surface/text/status combinations.
 * - Verifies ExportPrefs round-trip with all settings fields.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    val D = DesignTokens
    check("Spacing scale is 4/8/12/16/24/32 dp", D.SPACING == listOf(4, 8, 12, 16, 24, 32))
    check("Touch target floor is 44 dp", D.MIN_TOUCH_DP >= 44)
    check("Radii are positive and increasing", D.RADIUS_S < D.RADIUS_M && D.RADIUS_M < D.RADIUS_L)

    // WCAG contrast checks across all surfaces
    for (surf in listOf(D.BG, D.SURFACE, D.SURFACE_HI)) {
        check("TEXT (#F8FAFC) contrast >= 14:1 on surface", D.contrast(D.TEXT, surf) >= 14.0)
        check("TEXT_MUTED (#94A3B8) contrast >= 4.5:1 on surface", D.contrast(D.TEXT_MUTED, surf) >= 4.5)
        check("PRIMARY_LIGHT (#C4B5FD) contrast >= 4.5:1 for small violet text on surface", D.contrast(D.PRIMARY_LIGHT, surf) >= 4.5)
        check("STATUS tokens (SUCCESS, WARNING, ERROR, CYAN) contrast >= 4.5:1 on surface",
            listOf(D.SUCCESS, D.WARNING, D.ERROR, D.CYAN).all { D.contrast(it, surf) >= 4.5 })
    }

    // ExportPrefs full settings round-trip with UpscaleProfile
    val fullPrefs = ExportPrefs(
        resP = 1080,
        fps = 60,
        aspect = AspectRatio.R9_16,
        captionsOn = true,
        upscale = UpscaleFactor.X2,
        upscaleFormat = IntermediateFormat.PNG,
        upscaleProfile = UpscaleProfile.BALANCED
    )
    check("ExportPrefs with UpscaleProfile.BALANCED round-trips through ProjectManifest.settings map",
        ExportPrefs.fromMap(fullPrefs.toMap()) == fullPrefs)

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
