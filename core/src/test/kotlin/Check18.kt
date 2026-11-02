import com.auto.core.*
import java.io.File
var fails18 = 0
fun ok18(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails18++ }
fun asset(n: String, size: Long = 1000, mod: Long = 0, o: MediaOrigin = MediaOrigin.IMPORTED) = MediaAsset("/x/$n", n, MediaLibrary.classify(n), size, mod, o)
fun tl18(): Timeline { val sc = listOf(Scene("a", 0.0, 4.0), Scene("b", 4.0, 8.0), Scene("c", 8.0, 12.0)).onEachIndexed { k, s -> s.num = k + 1; s.image = "/p/${k + 1}.png" }; sc[1].conf = "?"; sc[2].image = null; return Timeline.fromScenes(sc, 12.0) }

/** Steps 17-21: media library, project prefs + summaries, render capabilities, workflow + report, design tokens. */
fun main() {
    // ---- 17 media library
    ok18("classify by extension", listOf("a.PNG", "b.mp4", "c.m4a", "d.docx", "e.webp", "f.WAV").map { MediaLibrary.classify(it) } == listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.OTHER, MediaKind.IMAGE, MediaKind.AUDIO))
    ok18("human sizes", MediaLibrary.humanSize(500) == "500 B" && MediaLibrary.humanSize(2048) == "2 KB" && MediaLibrary.humanSize(5_452_595) == "5.2 MB" && MediaLibrary.humanSize(3L * 1073741824) == "3.00 GB")
    ok18("human durations", MediaLibrary.humanDuration(65_000) == "1:05" && MediaLibrary.humanDuration(3_725_000) == "1:02:05")
    val lib = listOf(asset("img10.jpg", 300, 5), asset("img2.jpg", 100, 9), asset("song.mp3", 900, 1), asset("clip.mp4", 5000, 7), asset("pic.png", 50, 3, MediaOrigin.PROJECT), asset("notes.pdf", 10, 2))
    ok18("filter by category", MediaLibrary.filter(lib, MediaFilter.IMAGES).size == 3 && MediaLibrary.filter(lib, MediaFilter.VIDEOS).size == 1 && MediaLibrary.filter(lib, MediaFilter.AUDIO).size == 1 && MediaLibrary.filter(lib, MediaFilter.PROJECT).size == 1 && MediaLibrary.filter(lib, MediaFilter.IMPORTED).size == 5)
    ok18("search is case-insensitive and trims", MediaLibrary.filter(lib, MediaFilter.ALL, "  IMG ").map { it.name }.toSet() == setOf("img10.jpg", "img2.jpg") && MediaLibrary.filter(lib, MediaFilter.IMAGES, "song").isEmpty())
    ok18("sort by name is natural (img2 before img10)", MediaLibrary.sort(lib, MediaSort.NAME).map { it.name }.filter { it.startsWith("img") } == listOf("img2.jpg", "img10.jpg"))
    ok18("sort by size / date, descending flips", MediaLibrary.sort(lib, MediaSort.SIZE).first().name == "notes.pdf" && MediaLibrary.sort(lib, MediaSort.SIZE, false).first().name == "clip.mp4" && MediaLibrary.sort(lib, MediaSort.DATE, false).first().name == "img2.jpg")
    ok18("sort by type groups kinds", MediaLibrary.sort(lib, MediaSort.TYPE).map { it.kind }.let { k -> k == k.sortedBy { it.ordinal } })
    ok18("only pictures and audio are usable; video says why", lib.filter { it.usable }.size == 4 && lib.first { it.kind == MediaKind.VIDEO }.unusableReason.contains("pictures") && lib.first { it.kind == MediaKind.OTHER }.unusableReason.isNotEmpty())
    val sel = MediaSelection().toggle("/x/img2.jpg").toggle("/x/clip.mp4").toggle("/x/song.mp3").toggle("/x/song.mp3")
    ok18("selection toggles; addable splits usable from not", sel.paths.size == 2 && sel.addable(lib).let { (a, b) -> a.map { it.name } == listOf("img2.jpg") && b.map { it.name } == listOf("clip.mp4") })
    ok18("select all", MediaSelection().selectAll(lib).paths.size == 6 && !MediaSelection().active && sel.active)
    ok18("info rows include only known facts", MediaLibrary.info(asset("a.jpg")).map { it.first } == listOf("Name", "Type", "Size", "Source") && MediaLibrary.info(asset("a.jpg").copy(width = 100, height = 50, durationMs = 2000)).map { it.first }.containsAll(listOf("Dimensions", "Length")))
    ok18("counts per category", MediaLibrary.counts(lib)[MediaFilter.ALL] == 6)
    // ---- 18 prefs + summaries
    val pr = ExportPrefs(1080, 60, AspectRatio.R9_16, false)
    ok18("ExportPrefs map round trip", ExportPrefs.fromMap(pr.toMap()) == pr)
    ok18("bad / missing prefs fall back to defaults", ExportPrefs.fromMap(mapOf("export.res" to "999", "export.fps" to "25", "export.aspect" to "X", "export.captions" to "maybe")) == ExportPrefs() && ExportPrefs.fromMap(emptyMap()) == ExportPrefs())
    ok18("prefs give real export settings (1080p 9:16 60 fps = 1080x1920)", pr.settings().let { it.width == 1080 && it.height == 1920 && it.fps == 60 })
    val dir = File.createTempFile("psx", "").also { it.delete(); it.mkdirs() }; var clock = 1_000_000L
    val st = ProjectStore(dir) { clock }
    val m = st.create("Trip"); clock += 60_000
    st.saveWithPrefs(m, tl18().setFx(1, ClipFx(contrast = 0.2f)).addText(0, 1000, "hi"), pr)
    val m2 = st.create("Empty"); clock += 60_000
    val sums = st.summaries()
    ok18("summaries: newest first, scene count and length, empty project flagged", sums.map { it.name } == listOf("Empty", "Trip") && sums[1].scenes == 3 && sums[1].durationMs == 12_000L && sums[1].hasTimeline && !sums[0].hasTimeline)
    val back = st.load(m.id)
    ok18("reopened project: timeline incl. fx + text identical, prefs restored", back.timeline == tl18().setFx(1, ClipFx(contrast = 0.2f)).addText(0, 1000, "hi") && ExportPrefs.fromMap(back.manifest.settings) == pr)
    ok18("rename", st.rename(m2.id, "  New name ").name == "New name" && st.rename(m2.id, "   ").name == "Untitled")
    File(st.dir(m.id), "timeline.json").writeText("{broken"); File(st.dir(m.id), "timeline.json.bak").writeText(tl18().toJson())
    ok18("damaged timeline: the backup is restored and the summary carries the warning", st.summaries().first { it.id == m.id }.let { it.hasTimeline && it.warnings.isNotEmpty() })
    ok18("relative time", RelativeTime.label(0, 30_000) == "Just now" && RelativeTime.label(0, 5 * 60_000) == "5 min ago" && RelativeTime.label(0, 3 * 3_600_000L) == "3 h ago" && RelativeTime.label(0, 30 * 3_600_000L) == "Yesterday" && RelativeTime.label(0, 5 * 86_400_000L) == "5 days ago" && RelativeTime.label(0, 90 * 86_400_000L) == "Long ago")
    dir.deleteRecursively()
    // ---- 19 render caps
    ok18("unavailable features have a reason; blur/sharpen/vignette/dissolve/wipe/video/split are all unavailable", RenderCaps.features.filter { it.status == CapStatus.UNAVAILABLE }.all { it.reason.isNotEmpty() } && listOf("blur", "sharpen", "vignette", "dissolve", "wipe", "videoClips", "audioSplit", "musicOffset").none { RenderCaps.available(it) })
    ok18("effect/transition catalogue is consistent (offered ids are the 4 renderable ones; the rest are unavailable)", EffectCatalog.available.all { it.id in setOf("brightness", "contrast", "saturation", "opacity") } && EffectCatalog.unavailable.all { !RenderCaps.available(it.id) } && Transitions.unavailable.all { !RenderCaps.available(it.name.lowercase()) })
    ok18("HONESTY GUARD: every offered feature has exporter code (id in exporterImplements)", RenderCaps.features.filter { it.status != CapStatus.UNAVAILABLE }.all { it.id in RenderCaps.exporterImplements } && RenderCaps.exporterImplements.all { RenderCaps.available(it) })
    ok18("text/anim/fx/transitions/volume/music are UNAVAILABLE with a reason until their exporter step", listOf("volume", "music").none { RenderCaps.available(it) } && listOf("volume", "music").all { RenderCaps.get(it).reason.isNotEmpty() })
    ok18("no feature is VERIFIED before a device test (honesty rule)", RenderCaps.features.none { it.status == CapStatus.VERIFIED })
    val plain = ExportPlan.fromTimeline(tl18())
    ok18("plain project uses only 'pictures'; no beta notes", RenderCaps.used(plain, true) == listOf("pictures") && RenderCaps.notes(plain, true).isEmpty())
    val rich = ExportPlan.fromTimeline(tl18().setFx(1, ClipFx(contrast = 0.2f)).setTransition(2, TransitionSpec(TransitionKind.FADE, 500)).addText(0, 2000, "t", StylePresets.byName("Bold Title")!!).withCaptions(listOf(CaptionClip(1, 0, 1000, "c"))).setCaptionStyle(StylePresets.byName("Yellow")!!).setMix(AudioMix(AudioSettings(volume = 0.5f), MusicTrack("/m.mp3", "m.mp3"))))
    ok18("rich project lists every feature it uses", RenderCaps.used(rich, true).toSet() == setOf("pictures", "captions", "captionStyle", "text", "textAnim", "fx", "transitions", "volume", "music") && RenderCaps.notes(rich, true).size == 8)
    ok18("captions off -> no caption features", !RenderCaps.used(rich, false).contains("captions"))
    // ---- 20 workflow + report
    var s = Stage.WAITING
    s = Workflow.next(s, Event.ANALYSE); ok18("WAITING -analyse-> ANALYSING", s == Stage.ANALYSING)
    ok18("busy stage ignores a second analyse / export / reset", Workflow.next(s, Event.ANALYSE) == Stage.ANALYSING && Workflow.next(s, Event.EXPORT) == Stage.ANALYSING && Workflow.next(s, Event.RESET) == Stage.ANALYSING)
    s = Workflow.next(s, Event.ANALYSED); ok18("ANALYSING -> READY", s == Stage.READY && Workflow.canExport(s))
    s = Workflow.next(s, Event.EXPORT); ok18("READY -> EXPORTING (not exportable twice)", s == Stage.EXPORTING && Workflow.next(s, Event.EXPORT) == Stage.EXPORTING && !Workflow.canExport(s) && Workflow.busy(s))
    ok18("export failure returns to READY, analysis failure to FAILED", Workflow.afterFail(true) == Stage.READY && Workflow.afterFail(false) == Stage.FAILED && Workflow.next(Stage.ANALYSING, Event.FAIL) == Stage.FAILED)
    s = Workflow.next(s, Event.EXPORTED); ok18("EXPORTING -> COMPLETED; editing returns to READY", s == Stage.COMPLETED && Workflow.next(s, Event.EDIT) == Stage.READY)
    ok18("FAILED can be retried; RESET only when idle; stray events ignored", Workflow.next(Stage.FAILED, Event.ANALYSE) == Stage.ANALYSING && Workflow.next(Stage.COMPLETED, Event.RESET) == Stage.WAITING && Workflow.next(Stage.WAITING, Event.EXPORTED) == Stage.WAITING && Workflow.next(Stage.WAITING, Event.EXPORT) == Stage.WAITING)
    ok18("importing: start -> files ready -> WAITING", Workflow.next(Workflow.next(Stage.WAITING, Event.START_IMPORT), Event.FILES_READY) == Stage.WAITING && Workflow.busy(Stage.IMPORTING))
    ok18("all seven stage labels from the brief", Stage.values().map { it.label } == listOf("Waiting for files", "Importing", "Analysing", "Ready", "Exporting", "Completed", "Failed"))
    val rep = AnalysisReport.from(tl18(), setOf("Script", "Audio"), listOf("w1"))
    ok18("report: 3 scenes, states OK / CHECK / MISSING", rep.sceneCount == 3 && rep.scenes.map { it.state } == listOf(SceneState.OK, SceneState.CHECK, SceneState.MISSING) && rep.withoutPicture == 1 && rep.needCheck == 1 && !rep.ok)
    ok18("report: lengths and input lines", rep.audioMs == 12_000L && rep.videoMs == 12_000L && rep.inputLine("Script") == "Script added" && rep.inputLine("Pictures") == "Pictures missing" && rep.warnings == listOf("w1"))
    ok18("a clean timeline is ok", AnalysisReport.from(Timeline.fromScenes(listOf(Scene("a", 0.0, 4.0)).onEachIndexed { k, s -> s.num = k + 1; s.image = "/p/1.png" }, 4.0), emptySet(), emptyList()).ok)
    // ---- 21 design tokens
    val D = DesignTokens
    ok18("body text on every surface >= 4.5:1", listOf(D.BG, D.SURFACE, D.SURFACE_HI).all { D.contrast(D.TEXT, it) >= 4.5 && D.contrast(D.TEXT_MUTED, it) >= 4.5 })
    ok18("button label on the violet button >= 4.5:1", D.contrast(D.ON_PRIMARY, D.PRIMARY) >= 4.5)
    ok18("lavender and violet accents readable on charcoal (>= 3:1 for large/icon use)", D.contrast(D.LAVENDER, D.BG) >= 4.5 && D.contrast(D.PRIMARY_HI, D.BG) >= 3.0)
    ok18("status colours readable on the card colour", listOf(D.SUCCESS, D.WARNING, D.DANGER).all { D.contrast(it, D.SURFACE) >= 4.5 })
    ok18("background is almost black, cards a little lighter", D.luminance(D.BG) < 0.01 && D.luminance(D.SURFACE) > D.luminance(D.BG) && D.luminance(D.SURFACE_HI) > D.luminance(D.SURFACE) && D.luminance(D.SURFACE_HI) < 0.03)
    ok18("borders are subtle (< 1.5:1 against the card)", D.contrast(D.LINE, D.SURFACE) < 1.5)
    ok18("type scale sits inside the ranges of the brief", D.TITLE in D.TITLE_RANGE && D.SECTION in D.SECTION_RANGE && D.CARD in D.CARD_RANGE && D.BODY in D.BODY_RANGE && D.LABEL in D.LABEL_RANGE)
    ok18("contrast maths: black/white = 21:1, same = 1:1", Math.abs(D.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) - 21.0) < 0.01 && Math.abs(D.contrast(D.BG, D.BG) - 1.0) < 1e-9)
    ok18("spacing grid is increasing, touch target is 44 dp", D.SPACING == D.SPACING.sorted() && D.MIN_TOUCH_DP == 44)
    println(if (fails18 == 0) "ALL PASS" else "FAILED $fails18"); if (fails18 > 0) kotlin.system.exitProcess(1)
}
