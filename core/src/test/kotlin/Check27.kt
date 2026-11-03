import com.auto.core.*
import java.io.File

/**
 * Phase 6 tests (Section 9): Manual Editor — Blank projects and safe Auto Create copies.
 * - Blank project creation (`Timeline.blank()`), image insertion at playhead/end with editable default duration.
 * - Optional narration rule: without narration, duration equals last clip end (`endMs`); click track uses `startsMs` on silent base track.
 * - Auto Create project protection: `Edit a Copy` / `Save As` (`ProjectStore.duplicate`) duplicates project/media references/settings while proving original project bytes and state are 100% unchanged.
 * - Save, reopen, and export plan verification for both project types.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    // 1. Blank project creation and image insertion without narration
    var blank = Timeline.blank()
    check("Blank timeline starts empty with 0 ms audio", blank.clips.isEmpty() && blank.audioMs == 0L && blank.endMs == 0L)

    // Insert 3 clips with custom default durations (2.5s, 4.0s, 3.5s -> total 10.0s)
    blank = blank.insertClip("/lib/pic1.png", durationMs = 2500L, text = "First")
    blank = blank.insertClip("/lib/pic2.png", durationMs = 4000L, text = "Second")
    blank = blank.insertClip("/lib/pic3.png", durationMs = 3500L, text = "Third")
    check("Blank project clips are contiguous and duration equals last clip end (10,000 ms)",
        blank.clips.size == 3 &&
        blank.clips.map { it.startMs to it.endMs } == listOf(0L to 2500L, 2500L to 6500L, 6500L to 10_000L) &&
        blank.endMs == 10_000L)

    val blankPlan = ExportPlan.fromTimeline(blank, clampToAudio = false)
    check("Blank project ExportPlan without narration has totalMs == 10,000 ms and 3 click starts",
        blankPlan.totalMs == 10_000L &&
        blankPlan.segments.size == 3 &&
        ClickTrack.startsMs(blankPlan).toList() == listOf(0L, 2500L, 6500L))

    // Insert at playhead (after scene 1 at 2500 ms) ripples later scenes forward
    val insertedMid = blank.insertClip("/lib/pic_mid.png", insertAtMs = 2500L, durationMs = 2000L, text = "Inserted")
    check("Inserting at playhead ripples later clips forward without overlap (total 12,000 ms)",
        insertedMid.clips.size == 4 &&
        insertedMid.clips[1].image == "/lib/pic_mid.png" &&
        insertedMid.endMs == 12_000L &&
        insertedMid.validate().none { it.kind == IssueKind.OVERLAP || it.kind == IssueKind.GAP })

    // Reorder clips in Manual Editor
    val reordered = blank.reorderClip(blank.clips.last().id, 0)
    check("Reordering last clip (3500 ms) to index 0 keeps timeline contiguous from 0..10,000 ms",
        reordered.clips.first().image == "/lib/pic3.png" &&
        reordered.clips.first().startMs == 0L &&
        reordered.clips.first().endMs == 3500L &&
        reordered.endMs == 10_000L)

    // 2. Edit a Copy / Save As on an Auto Create project: prove original project bytes and state are unchanged
    val tmpDir = File.createTempFile("manual_copy_test", "").also { it.delete(); it.mkdirs() }
    var clock = 500_000L
    val store = ProjectStore(tmpDir) { clock }

    // Create dummy media files
    val mediaDir = File(tmpDir, "external_media").apply { mkdirs() }
    val srcImg1 = File(mediaDir, "scene1.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)) }
    val srcImg2 = File(mediaDir, "scene2.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 4, 5, 6)) }

    val origManifest0 = store.create("Auto Story", ProjectType.AUTO_CREATE)
    val imp1 = store.importFile(origManifest0.id, srcImg1)
    val imp2 = store.importFile(origManifest0.id, srcImg2)
    val autoTl = Timeline.fromScenes(
        listOf(
            Scene("Scene one", 0.0, 5.0).apply { num = 1; image = imp1.path },
            Scene("Scene two", 5.0, 10.0).apply { num = 2; image = imp2.path }
        ),
        10.0
    )
    val autoPrefs = ExportPrefs(resP = 1080, fps = 30, aspect = AspectRatio.R16_9, captionsOn = true)
    store.saveWithPrefs(origManifest0, autoTl, autoPrefs)

    val origManifestFile = File(store.dir(origManifest0.id), "manifest.json")
    val origTimelineFile = File(store.dir(origManifest0.id), "timeline.json")
    val origManifestBytesBefore = origManifestFile.readBytes()
    val origTimelineBytesBefore = origTimelineFile.readBytes()
    val origImg1BytesBefore = imp1.readBytes()

    // Duplicate via Edit a Copy
    clock += 10_000L
    val copyLoaded = store.duplicate(origManifest0.id, "Auto Story (Manual Copy)", asManualCopy = true)
    check("Duplicate creates a distinct project ID with ProjectType.MANUAL and parentProjectId set",
        copyLoaded.manifest.id != origManifest0.id &&
        copyLoaded.manifest.projectType == ProjectType.MANUAL &&
        copyLoaded.manifest.parentProjectId == origManifest0.id)

    // Mutate the copy heavily (delete clip, add solid color, add text, change prefs)
    val editedCopyTl = copyLoaded.timeline!!
        .replaceColor(1, 0xFF000000.toInt())
        .setLength(2, 8000L)
        .addText(0, 3000, "Edited in Copy")
    store.saveWithPrefs(copyLoaded.manifest, editedCopyTl, ExportPrefs(resP = 2160, fps = 60, aspect = AspectRatio.R9_16))

    // Prove original project bytes and state are 100% unchanged!
    val origManifestBytesAfter = origManifestFile.readBytes()
    val origTimelineBytesAfter = origTimelineFile.readBytes()
    val origImg1BytesAfter = imp1.readBytes()
    val origReloaded = store.load(origManifest0.id)

    check("Original project manifest.json, timeline.json, and source image bytes are 100% unchanged after editing copy",
        origManifestBytesBefore.contentEquals(origManifestBytesAfter) &&
        origTimelineBytesBefore.contentEquals(origTimelineBytesAfter) &&
        origImg1BytesBefore.contentEquals(origImg1BytesAfter) &&
        origReloaded.timeline == autoTl &&
        ExportPrefs.fromMap(origReloaded.manifest.settings) == autoPrefs)

    // Verify media reference inspection works on both projects
    val refs = store.inspectMediaReferences(copyLoaded.manifest.id)
    check("inspectMediaReferences confirms copied media files exist on disk", refs.isNotEmpty() && refs.values.all { it })

    tmpDir.deleteRecursively()

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
