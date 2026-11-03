package com.auto.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auto.core.*
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Shared application & editor state across Home, Projects, Settings, and all 10 Editing Workspaces.
 * Preserves deterministic Auto Create core behavior while supporting:
 * - Bounded streaming waveform generation (AudioDecoder.streamPeaks)
 * - Ordered autosave (AutosaveCoordinator) and cancellation-safe background tasks (TaskToken)
 * - Navigation-independent export & upscale jobs (`AutoAppHolder` + `ExportForegroundService` + `ExportJobStore`)
 * - Preflight upscale memory/format check (`pendingPreflightWarning`) with explicit user options (never silent 4x -> 2x reduction)
 * - Solid-colour scene replacement (Black, White, Custom ARGB) alongside picture replacement
 * - Blank Manual Editor projects and non-destructive Edit a Copy / Save As for Auto Create projects
 * - Design Studio layer management (visibility, lock, z-order, safe-area align, >=3 item distribution)
 * - All 10 WorkspaceModes over one shared project/timeline/playhead/undo state
 */
const val PEAKS_PER_SEC = 20

class AutoState(private var act: ComponentActivity) {
    private val inFiles = ArrayList<File>()      // script, captions, narration
    private val zips = ArrayList<File>()
    private val bundleZips = ArrayList<File>()   // ZIPs picked in step 1: text/audio are taken out, the pictures stay inside
    private val pics = ArrayList<File>()
    private var analysis: Pipeline.Analysis? = null
    private var narration: File? = null
    private var editor: TimelineEditor? = null
    private var player: MediaPlayer? = null
    private val autosaveCoordinator = AutosaveCoordinator()
    private var activeTaskToken = TaskToken()

    var pick: (Int) -> Unit = {}                 // 1 = files/ZIP, 2 = pictures, 3 = replace one picture, 4 = caption file, 5 = music file

    var status by mutableStateOf("Choose your files in Inputs, then press Analyse.")
    var kinds by mutableStateOf(setOf<String>())  // which inputs are present: Timestamps, Audio, Pictures, Script
    var busy by mutableStateOf(false)
    var warnings by mutableStateOf<List<String>>(emptyList())
    var timeline by mutableStateOf<Timeline?>(null)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var playMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var selectedId by mutableStateOf<Int?>(null)
    var sheet by mutableStateOf<String?>("inputs")
    var pendingReplacementPath by mutableStateOf<String?>(null)
    var pendingReplacementColor by mutableStateOf<Int?>(null)
    var exportPct by mutableStateOf<Int?>(null)
    var jumpKey by mutableIntStateOf(0)
    var captionsOn by mutableStateOf(true)
    var resP by mutableIntStateOf(720)
    var fps by mutableIntStateOf(30)
    var aspect by mutableStateOf(AspectRatio.R16_9)
    var upscale by mutableStateOf(UpscaleFactor.OFF)
    var upscaleFormat by mutableStateOf(IntermediateFormat.PNG)
    var upscaleProfile by mutableStateOf(UpscaleProfile.MANUAL)
    var strictUpscaleMode by mutableStateOf(false)
    var upscaleCacheLimitMb by mutableIntStateOf(512)
    var onHome: () -> Unit = {}
    val hasProject get() = timeline != null

    // Priority 1: Explicit user preflight warning when a requested upscale/format combination exceeds safe bounds
    var pendingPreflightWarning by mutableStateOf<UpscalePreflightResult?>(null)

    // Workspace & preview/timeline view state (shared across all 10 workspaces)
    var workspaceMode by mutableStateOf(WorkspaceMode.CLASSIC_TIMELINE)
    var rememberWorkspace by mutableStateOf(true)
    var zoomDpPerSec by mutableFloatStateOf(60f)
    var previewFitMode by mutableStateOf(true)       // true = Fit (letterbox), false = Fill (crop preview)
    var showSafeAreaGuides by mutableStateOf(false)
    var snappingEnabled by mutableStateOf(true)
    var highContrastBorders by mutableStateOf(false)
    var defaultBlankClipMs by mutableLongStateOf(3000L)

    // Design Studio & multi-select text layer state
    var selectedTextId by mutableStateOf<Int?>(null)
    var selectedTextLayerIds by mutableStateOf<Set<Int>>(emptySet())
    var designStudioTab by mutableStateOf("media")   // media, images, text, audio, layers, align
    var styleLib by mutableStateOf(StyleLibrary())
    private val styleFile get() = File(act.filesDir, "styles.json")
    private val exportPrefsStore get() = act.getSharedPreferences("export_prefs", Context.MODE_PRIVATE)

    // Export jobs & diagnostics state
    var exportJobs by mutableStateOf<List<ExportJob>>(emptyList())
    var latestDiagnostics by mutableStateOf<DiagnosticRunReport?>(null)

    init {
        styleLib = try { if (styleFile.exists()) StyleLibrary.parse(styleFile.readText()) else StyleLibrary() } catch (_: Exception) { StyleLibrary() }
        loadPersistedExportSettings()
        recoverExportJobsAndDiagnostics()
    }

    /** Rebinds the current ComponentActivity when MainActivity is recreated (Priority 2). */
    fun attachActivity(newAct: ComponentActivity) {
        this.act = newAct
    }

    fun appContext(): Context = act.applicationContext

    private fun loadPersistedExportSettings() {
        try {
            val p = exportPrefsStore
            if (p.contains("export.res")) resP = p.getInt("export.res", 720).takeIf { x -> Resolution.values().any { it.shortSide == x } } ?: 720
            if (p.contains("export.fps")) fps = p.getInt("export.fps", 30).takeIf { it in ExportSettings.FPS } ?: 30
            p.getString("export.aspect", null)?.let { n -> AspectRatio.values().firstOrNull { it.name == n }?.let { aspect = it } }
            if (p.contains("export.captions")) captionsOn = p.getBoolean("export.captions", true)
            p.getString("export.upscale", null)?.let { upscale = UpscaleFactor.fromString(it) }
            p.getString("export.upscaleFormat", null)?.let { upscaleFormat = IntermediateFormat.fromString(it) }
            p.getString("export.upscaleProfile", null)?.let { upscaleProfile = UpscaleProfile.fromString(it) }
            strictUpscaleMode = p.getBoolean("upscale.strict", false)
            upscaleCacheLimitMb = p.getInt("upscale.cacheLimitMb", 512).coerceIn(128, 4096)
            rememberWorkspace = p.getBoolean("ui.rememberWorkspace", true)
            showSafeAreaGuides = p.getBoolean("ui.safeArea", false)
            snappingEnabled = p.getBoolean("ui.snapping", true)
            highContrastBorders = p.getBoolean("ui.highContrast", false)
            defaultBlankClipMs = p.getLong("editor.defaultClipMs", 3000L).coerceIn(500L, 60_000L)
            if (rememberWorkspace) {
                workspaceMode = WorkspaceMode.fromNameOrDefault(p.getString("ui.workspaceMode", null))
            }
        } catch (_: Exception) {}
    }

    fun persistExportSettings() {
        try {
            exportPrefsStore.edit()
                .putInt("export.res", resP)
                .putInt("export.fps", fps)
                .putString("export.aspect", aspect.name)
                .putBoolean("export.captions", captionsOn)
                .putString("export.upscale", upscale.name)
                .putString("export.upscaleFormat", upscaleFormat.name)
                .putString("export.upscaleProfile", upscaleProfile.name)
                .putBoolean("upscale.strict", strictUpscaleMode)
                .putInt("upscale.cacheLimitMb", upscaleCacheLimitMb)
                .putBoolean("ui.rememberWorkspace", rememberWorkspace)
                .putBoolean("ui.safeArea", showSafeAreaGuides)
                .putBoolean("ui.snapping", snappingEnabled)
                .putBoolean("ui.highContrast", highContrastBorders)
                .putLong("editor.defaultClipMs", defaultBlankClipMs)
                .putString("ui.workspaceMode", workspaceMode.name)
                .apply()
        } catch (_: Exception) {}
        scheduleSave()
    }

    fun recoverExportJobsAndDiagnostics() {
        thread {
            val jobs = try { Exporter.jobStore(act).recoverInterruptedJobs() } catch (_: Exception) { emptyList() }
            val diag = try { DiagnosticsLog.latestReport(act) } catch (_: Exception) { null }
            act.runOnUiThread {
                exportJobs = jobs
                latestDiagnostics = diag
            }
        }
    }

    fun refreshDiagnostics() {
        latestDiagnostics = DiagnosticsLog.latestReport(act)
    }

    fun switchWorkspace(mode: WorkspaceMode) {
        workspaceMode = mode
        if (mode == WorkspaceMode.VERTICAL_EDITING && aspect != AspectRatio.R9_16) {
            aspect = AspectRatio.R9_16
        }
        persistExportSettings()
    }

    fun switchContextually(contextAction: String) {
        switchWorkspace(WorkspaceTransitions.targetForContext(contextAction))
    }

    /** Storage health check via StatFs (warns on Home only when genuinely low: < 2 GB or < 10% free). */
    fun checkDeviceStorage(): Triple<Long, Long, Boolean> = try {
        val stat = StatFs(act.filesDir.path)
        val free = stat.availableBytes
        val total = stat.totalBytes.coerceAtLeast(1L)
        val low = free < 2L * 1024L * 1024L * 1024L || (free.toDouble() / total.toDouble()) < 0.10
        Triple(free, total, low)
    } catch (_: Throwable) {
        Triple(0L, 1L, false)
    }

    fun upscaleCacheSizeBytes(): Long = try {
        SafelightExportProcessor.cacheInstance(act).currentSizeBytes()
    } catch (_: Throwable) {
        0L
    }

    fun clearUpscaleCache(done: (Long) -> Unit = {}) {
        thread {
            val freed = try { SafelightExportProcessor.cacheInstance(act).clear() } catch (_: Throwable) { 0L }
            act.runOnUiThread {
                status = "Cleared AI upscale cache (${MediaLibrary.humanSize(freed)} freed)."
                done(freed)
            }
        }
    }

    fun totalMs(): Long { val t = timeline ?: return 0L; return max(t.audioMs, t.endMs) }
    fun hasNarration() = narration != null
    fun playEndMs(): Long { val t = timeline ?: return 0L; return if (narration != null && t.audioMs > 0L) t.audioMs else max(t.audioMs, t.endMs) }
    fun selectedClip(): ImageClip? { val id = selectedId ?: return null; return timeline?.clips?.firstOrNull { it.id == id } }

    fun openReplace() {
        val clip = selectedClip() ?: return
        pendingReplacementPath = null
        pendingReplacementColor = clip.replacementColor
        sheet = "replace"
    }

    fun cancelReplace() {
        pendingReplacementPath = null
        pendingReplacementColor = null
        sheet = null
    }

    fun confirmReplacement(id: Int, path: String) {
        edit { it.replaceImage(id, path) }
        pendingReplacementPath = null
        pendingReplacementColor = null
        sheet = null
        status = "Picture replaced non-destructively."
        toast(status)
    }

    /** Phase 3 (Section 12): Confirm solid-colour replacement (Black, White, or Custom ARGB). */
    fun confirmColorReplacement(id: Int, argbColor: Int) {
        edit { it.replaceColor(id, argbColor) }
        pendingReplacementPath = null
        pendingReplacementColor = null
        sheet = null
        status = "Scene replaced with solid colour (${ColorText.hex(argbColor)})."
        toast(status)
    }

    fun deleteReplacement(id: Int? = selectedId) {
        if (id == null) return
        edit { it.deleteReplacement(id) }
        pendingReplacementPath = null
        pendingReplacementColor = null
        status = "Replacement removed. Original restored."
        toast(status)
    }

    // ---------- timeline edits ----------
    private fun sync() {
        val e = editor ?: return
        timeline = e.current; canUndo = e.canUndo; canRedo = e.canRedo
        val id = selectedId
        if (id != null && e.current.clips.none { it.id == id }) selectedId = null
        val tid = selectedTextId
        if (tid != null && e.current.texts.none { it.id == tid }) selectedTextId = null
        selectedTextLayerIds = selectedTextLayerIds.filter { lid -> e.current.texts.any { it.id == lid } }.toSet()
        scheduleSave()
    }

    private fun edit(f: (Timeline) -> Timeline) {
        val e = editor ?: return
        try { e.edit(f) } catch (x: Exception) { status = "Edit not possible: ${x.message}"; toast("Edit not possible: ${x.message}") }
        sync()
    }

    fun undo() { editor?.undo(); sync() }
    fun redo() { editor?.redo(); sync() }
    private fun toast(msg: String) { android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show() }

    fun split() {
        val id = selectedId ?: return; val c = timeline?.clips?.firstOrNull { it.id == id } ?: return; val at = playMs
        if (at < c.startMs + Timeline.MIN_CLIP_MS || at > c.endMs - Timeline.MIN_CLIP_MS) { toast("Move the playhead inside this scene (drag the timeline), then tap Split"); return }
        edit { it.split(id, at) }
    }

    fun delete() { val id = selectedId ?: return; edit { it.delete(id, true) }; selectedId = null }
    fun duplicateClip() { val id = selectedId ?: return; edit { it.duplicate(id) } }
    fun moveClipOrder(delta: Int) {
        val id = selectedId ?: return
        val tl = timeline ?: return
        val curIdx = tl.clips.indexOfFirst { it.id == id }
        if (curIdx < 0) return
        edit { it.reorderClip(id, (curIdx + delta).coerceIn(0, it.clips.size - 1)) }
    }

    // ---------- Phase 6: Blank Manual Project & Edit a Copy / Save As ----------
    var projectType by mutableStateOf(ProjectType.AUTO_CREATE)

    /** Starts a Blank Manual Editor project with an empty timeline (no narration required). */
    fun newBlankProject(initialName: String = "Manual Project") {
        if (busy) { toast("Wait until current analysis finishes."); return }
        stopPlay(); release()
        inFiles.clear(); zips.clear(); bundleZips.clear(); pics.clear()
        analysis = null; narration = null; projectOpen = true
        resetProject()
        projectType = ProjectType.MANUAL
        projectName = initialName
        val blankTl = Timeline.blank(0L)
        editor = TimelineEditor(blankTl)
        timeline = blankTl
        canUndo = false; canRedo = false
        selectedId = null; selectedTextId = null; selectedTextLayerIds = emptySet()
        playMs = 0L; warnings = emptyList(); kinds = emptySet()
        sheet = null
        status = "Blank manual project ready. Add pictures or solid scenes to the timeline."
        saveProject { refreshProjects() }
    }

    /** Inserts a picture or solid-colour clip into the current timeline at playhead or end with `defaultBlankClipMs`. */
    fun insertClipToTimeline(imagePath: String?, atPlayhead: Boolean = false, durationMs: Long = defaultBlankClipMs, solidColor: Int? = null) {
        if (editor == null) {
            val blankTl = Timeline.blank(0L)
            editor = TimelineEditor(blankTl)
            timeline = blankTl
            projectOpen = true
            projectType = ProjectType.MANUAL
        }
        val insertAt = if (atPlayhead) playMs else null
        edit { it.insertClip(imagePath, insertAt, durationMs, replacementColor = solidColor) }
        timeline?.clips?.lastOrNull()?.let { selectedId = it.id }
    }

    /** Phase 6 (Sections 8 & 9): Edit a Copy / Save As — duplicates current or specified project without mutating original. */
    fun editProjectCopy(sourceProjectId: String? = projectId, newTitle: String = "", onOpened: () -> Unit = {}) {
        val srcId = sourceProjectId
        if (srcId == null) {
            saveProject {
                val createdId = projectId
                if (createdId != null) editProjectCopy(createdId, newTitle, onOpened)
            }
            return
        }
        thread {
            try {
                val copyLoaded = store.duplicate(srcId, newTitle, asManualCopy = true)
                val allSummaries = store.summaries()
                act.runOnUiThread {
                    projects = allSummaries
                    openProject(copyLoaded.manifest.id, openAsCopy = false, done = onOpened)
                }
            } catch (e: Exception) {
                act.runOnUiThread { say("Could not create project copy: ${e.message}") }
            }
        }
    }

    // ---------- Phase 7: Design Studio Layer & Alignment Actions ----------
    fun toggleLayerSelection(textId: Int) {
        selectedTextLayerIds = if (textId in selectedTextLayerIds) selectedTextLayerIds - textId else selectedTextLayerIds + textId
        selectedTextId = textId
        selectedId = null
    }

    fun renameLayer(textId: Int, name: String) = edit { LayerTools.renameTextLayer(it, textId, name) }
    fun toggleLayerVisible(textId: Int) = edit { LayerTools.toggleTextVisibility(it, textId) }
    fun toggleLayerLocked(textId: Int) = edit { LayerTools.toggleTextLock(it, textId) }
    fun shiftLayerOrder(textId: Int, delta: Int) = edit { LayerTools.shiftTextZIndex(it, textId, delta) }
    fun setLayerNumericTransform(textId: Int, x: Float? = null, y: Float? = null, sizeFrac: Float? = null, rotationDeg: Float? = null) =
        edit { LayerTools.setNumericTransform(it, textId, x, y, sizeFrac, rotationDeg) }
    fun alignLayerSafe(textId: Int, align: SafeAlign) = edit { LayerTools.alignToSafeArea(it, textId, align) }
    fun distributeSelectedLayers(horizontal: Boolean) {
        if (!LayerTools.canDistribute(selectedTextLayerIds)) {
            say("Select at least 3 text layers in Layers to distribute them evenly.")
            return
        }
        edit { LayerTools.distributeTextLayers(it, selectedTextLayerIds, horizontal) }
    }

    // ---------- status + export extras ----------
    val stage: Stage get() = when {
        exportPct != null -> Stage.EXPORTING
        busy -> Stage.ANALYSING
        status.startsWith("Copying") -> Stage.IMPORTING
        status.startsWith("Export failed") || status.startsWith("Could not") -> Stage.FAILED
        timeline == null -> Stage.WAITING
        status.startsWith("Done:") -> Stage.COMPLETED
        else -> Stage.READY
    }
    var lastExportUri by mutableStateOf<Uri?>(null)

    fun cancelExport() {
        if (exportPct == null && Exporter.activeJob?.status != ExportJobStatus.ACTIVE) return
        Exporter.cancel(act)
        exportPct = null
        status = "Export cancelled."
        recoverExportJobsAndDiagnostics()
    }

    fun shareExport() {
        val u = lastExportUri ?: run { toast("Nothing to share yet: export a video first."); return }
        val i = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try { act.startActivity(Intent.createChooser(i, "Share video")) } catch (e: Exception) { toast("Could not share: ${e.message}") }
    }

    // ---------- projects (step 36 + Phase 1 ordered autosave) ----------
    private val store get() = ProjectStore(File(act.filesDir, "projects"))
    var projectId by mutableStateOf<String?>(null)
    var projectName by mutableStateOf("")
    var projects by mutableStateOf<List<ProjectSummary>>(emptyList())
    private var manifest: ProjectManifest? = null
    private var projectOpen = false
    private val copied = HashMap<String, String>()

    private fun resetProject() { manifest = null; projectId = null; projectName = ""; copied.clear() }
    fun refreshProjects() { thread { val l = try { store.summaries() } catch (_: Exception) { emptyList() }; act.runOnUiThread { projects = l } } }

    private fun scheduleSave() {
        if (timeline == null) return
        val seq = autosaveCoordinator.nextSequence()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (seq == autosaveCoordinator.latestIssued) saveProject(seq = seq)
        }, 1500)
    }

    /** Writes manifest + timeline.json using AutosaveCoordinator so older saves never overwrite newer state. */
    fun saveProject(seq: Long = autosaveCoordinator.nextSequence(), done: () -> Unit = {}) {
        val tl = timeline ?: run { done(); return }
        val prefs = ExportPrefs(resP, fps, aspect, captionsOn, upscale, upscaleFormat, upscaleProfile)
        val nar = narration
        val nm = projectName
        val pType = projectType
        thread {
            try {
                val saved = autosaveCoordinator.runIfNewest(seq) {
                    doSave(tl, prefs, nar, nm, pType)
                }
                act.runOnUiThread {
                    if (saved != null) {
                        projectId = saved.id
                        projectName = saved.name
                    }
                    done()
                }
            } catch (e: Exception) {
                act.runOnUiThread { status = "Could not save the project: ${e.message}"; done() }
            }
        }
    }

    @Synchronized
    private fun doSave(tl: Timeline, prefs: ExportPrefs, nar: File?, nm: String, pType: ProjectType): ProjectManifest {
        var m = manifest ?: store.create(nm.ifBlank { "Project " + java.text.SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(java.util.Date()) }, pType)
        val dir = store.dir(m.id).path
        fun keep(p: String): String = copied.getOrPut(p) { val f = File(p); if (!f.exists() || f.path.startsWith(dir)) p else store.importFile(m.id, f).path }
        var t2 = tl
        for (c in tl.clips) {
            val img = c.image?.let { keep(it) }
            val orig = c.originalImage?.let { keep(it) }
            val repl = c.replacementImage?.let { keep(it) }
            if (img != c.image || orig != c.originalImage || repl != c.replacementImage) {
                t2 = t2.replaceClipImages(c.id, img, orig, repl)
            }
        }
        tl.mix.music?.let { mu -> t2 = t2.setMix(t2.mix.copy(music = mu.copy(path = keep(mu.path)))) }
        val inputs = if (nar != null) mapOf("audio" to listOf(File(keep(nar.path)).name)) else emptyMap()
        val extraSettings = if (pType == ProjectType.AUTO_CREATE) m.settings else m.settings + ("project.type" to pType.name)
        m = store.saveWithPrefs(m.copy(inputs = inputs, settings = extraSettings), t2, prefs)
        manifest = m
        return m
    }

    /**
     * Opens a saved project.
     * Phase 6 (Sections 8 & 9): When opening an Auto Create project in Manual Editor (`openAsCopy = true`),
     * defaults to creating a non-destructive copy so original generated project/scenes remain untouched.
     */
    fun openProject(id: String, openAsCopy: Boolean = false, done: () -> Unit) {
        if (busy) { toast("Wait until current analysis finishes."); return }
        if (openAsCopy) {
            editProjectCopy(id, "", done)
            return
        }
        thread {
            try {
                val lp = store.load(id)
                val tl = lp.timeline ?: throw IllegalStateException("this project has no saved timeline: start a new project and press Analyse")
                val nar = lp.manifest.inputs["audio"]?.firstOrNull()?.let { File(File(store.dir(id), "inputs"), it) }?.takeIf { it.exists() }
                val mp = nar?.let { try { MediaPlayer().apply { setDataSource(it.path); prepare() } } catch (_: Exception) { null } }
                val prefs = ExportPrefs.fromMap(lp.manifest.settings)
                act.runOnUiThread {
                    stopPlay(); release(); player = mp; narration = nar; analysis = null; projectOpen = true; editor = TimelineEditor(tl)
                    manifest = lp.manifest; copied.clear(); projectId = id; projectName = lp.manifest.name; projectType = lp.manifest.projectType
                    resP = prefs.resP; fps = prefs.fps; aspect = prefs.aspect; captionsOn = prefs.captionsOn
                    upscale = prefs.upscale; upscaleFormat = prefs.upscaleFormat; upscaleProfile = prefs.upscaleProfile
                    persistExportSettings()
                    mp?.setOnCompletionListener { playMs = playEndMs(); playing = false }
                    selectedId = null; selectedTextId = null; selectedTextLayerIds = emptySet(); playMs = 0L; playing = false; warnings = lp.warnings; busy = false
                    sync(); jumpKey++; loadPeaks(nar); status = "Opened \"${lp.manifest.name}\"."; sheet = null; done()
                }
            } catch (e: Exception) { act.runOnUiThread { say("Could not open the project: ${e.message}") } }
        }
    }

    fun inspectProjectMedia(id: String): Map<String, Boolean> = try {
        store.inspectMediaReferences(id)
    } catch (_: Exception) {
        emptyMap()
    }

    fun renameProject(id: String, name: String) {
        thread {
            try { val m = store.rename(id, name); act.runOnUiThread { if (id == projectId) { manifest = m; projectName = m.name }; refreshProjects() } }
            catch (e: Exception) { act.runOnUiThread { say("Could not rename: ${e.message}") } }
        }
    }

    fun deleteProject(id: String) {
        val wasOpen = id == projectId
        thread { try { store.delete(id) } catch (_: Exception) {}; act.runOnUiThread { if (wasOpen) newProject(); refreshProjects() } }
    }

    // ---------- media library (step 35) ----------
    fun say(msg: String) { status = msg; toast(msg) }

    fun scanMedia(): List<MediaAsset> {
        val out = LinkedHashMap<String, MediaAsset>()
        fun add(f: File, origin: MediaOrigin) {
            if (!f.isFile) return
            out[f.path] = MediaAsset(f.path, f.name, MediaLibrary.classify(f.name), f.length(), f.lastModified(), origin)
        }
        for (d in listOf(File(act.cacheDir, "Pictures"), File(act.cacheDir, "replaced"), File(act.cacheDir, "in"), File(act.cacheDir, "work"), File(act.filesDir, "music")))
            d.listFiles()?.forEach { add(it, MediaOrigin.IMPORTED) }
        manifest?.let { m ->
            File(store.dir(m.id), "inputs").listFiles()?.forEach { add(it, MediaOrigin.PROJECT) }
        }
        val tl = timeline
        tl?.clips?.forEach { c ->
            c.image?.let { add(File(it), MediaOrigin.PROJECT) }
            c.originalImage?.let { add(File(it), MediaOrigin.PROJECT) }
            c.replacementImage?.let { add(File(it), MediaOrigin.PROJECT) }
        }
        tl?.mix?.music?.let { add(File(it.path), MediaOrigin.PROJECT) }
        pics.forEach { add(it, MediaOrigin.IMPORTED) }
        return out.values.toList()
    }

    /** In Blank/Manual projects with no selected clip, inserts pictures onto the timeline; when a clip is selected, replaces its picture. */
    fun addMediaToProject(list: List<MediaAsset>) {
        val pic = list.filter { it.kind == MediaKind.IMAGE }; val aud = list.filter { it.kind == MediaKind.AUDIO }
        if (pic.isNotEmpty()) {
            val id = selectedId
            if (id != null) {
                edit { it.replaceImage(id, pic.first().path) }
                say(if (pic.size > 1) "First picture used to replace selected scene." else "Picture replaced.")
            } else {
                for (p in pic) {
                    insertClipToTimeline(p.path, atPlayhead = false, durationMs = defaultBlankClipMs)
                }
                say("Added ${pic.size} picture(s) to timeline.")
            }
        }
        aud.firstOrNull()?.let { a ->
            val ms = try { ((AudioDecoder.durationSec(File(a.path)) ?: 0.0) * 1000).toLong() } catch (_: Throwable) { 0L }
            edit { it.setMix(it.mix.copy(music = MusicTrack(a.path, a.name, durationMs = ms))) }; say("Music added: ${a.name}.")
        }
    }

    // ---------- audio (step 34 + Phase 1 streaming waveform) ----------
    fun setNarrationAudio(a: AudioSettings) = edit { it.setMix(it.mix.copy(narration = a)) }
    fun setMusic(m: MusicTrack?) = edit { it.setMix(it.mix.copy(music = m)) }

    var peaks by mutableStateOf<FloatArray?>(null)
    private var peaksFor: String? = null
    private fun loadPeaks(f: File?) {
        if (f == null) { peaks = null; peaksFor = null; return }
        if (peaksFor == f.path && peaks != null) return
        peaks = null; peaksFor = f.path
        val token = activeTaskToken
        val expectedMs = timeline?.audioMs
        thread {
            try {
                // Phase 1 (Section 13): Streaming waveform generation (20 peaks/sec) without full-file FloatArray decode
                val p = AudioDecoder.streamPeaks(f, PEAKS_PER_SEC, expectedMs, cancelled = { token.isCancelled })
                act.runOnUiThread {
                    token.runIfActive {
                        if (peaksFor == f.path) peaks = p
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun setFx(id: Int, fx: com.auto.core.ClipFx) = edit { it.setFx(id, fx) }
    fun setTransition(id: Int, t: com.auto.core.TransitionSpec) = edit { it.setTransition(id, t) }
    fun setTransitionAll(t: com.auto.core.TransitionSpec) = edit { tl -> tl.clips.fold(tl) { acc, c -> acc.setTransition(c.id, t) } }
    fun setLength(ms: Long) { val id = selectedId ?: return; edit { it.setLength(id, ms) } }
    fun addLength(deltaMs: Long) { val c = selectedClip() ?: return; setLength(c.durMs + deltaMs) }
    fun selectClip(id: Int?) {
        selectedId = id; if (id != null) selectedTextId = null
        val c = id?.let { i -> timeline?.clips?.firstOrNull { it.id == i } } ?: return
        seekTo(c.startMs + 1); jumpKey++
    }

    /** Priority 4 (Section 5): Selects the audio track and optionally transitions to AUDIO_FOCUS while preserving session state. */
    fun selectAudioTrack(openAudioFocus: Boolean = false) {
        selectedId = null
        selectedTextId = null
        if (openAudioFocus) {
            switchContextually("audio")
        } else {
            sheet = "audio"
        }
    }

    // ---------- playback ----------
    fun seekTo(ms: Long) {
        playMs = ms.coerceIn(0L, max(0L, totalMs()))
        try { player?.seekTo(playMs.toInt()) } catch (_: Exception) {}
        clickIdx = ClickTrack.firstIndex(clickTimes(), playMs, false)
    }
    private var graceTicks = 0
    fun togglePlay() {
        if (timeline == null) return
        if (playing) { stopPlay(); return }
        if (playEndMs() <= 0L) { status = "Nothing to play yet: the timeline has no length."; return }
        if (playMs >= playEndMs() - 50) seekTo(0)
        clickIdx = ClickTrack.firstIndex(clickTimes(), playMs, true)
        try { player?.let { it.seekTo(playMs.toInt()); it.start() } } catch (e: Exception) { status = "Audio could not start (${e.message}); playing without sound." }
        graceTicks = 3
        playing = true
        fireClicks()
    }
    fun stopPlay() { try { player?.pause() } catch (_: Exception) {}; playing = false }
    fun tick(dtMs: Long) {
        val p = player
        val live = p != null && graceTicks <= 0 && (try { p.isPlaying } catch (_: Exception) { false })
        if (graceTicks > 0) graceTicks--
        playMs = if (live) try { p!!.currentPosition.toLong() } catch (_: Exception) { playMs + dtMs } else playMs + dtMs
        fireClicks()
        if (playMs >= playEndMs()) { playMs = playEndMs(); stopPlay() }
    }
    fun release() { try { player?.release() } catch (_: Exception) {}; player = null }
    fun releaseClick() { clicker.release() }

    // ---------- click sound (step 42) ----------
    private val clicker by lazy { ClickPlayer(act.applicationContext) }
    private var clickStarts = LongArray(0)
    private var clickFor: Timeline? = null
    private var clickIdx = 0
    private fun clickTimes(): LongArray {
        val tl = timeline ?: return LongArray(0)
        if (clickFor !== tl) { clickFor = tl; clickStarts = ClickTrack.startsMs(ExportPlan.fromTimeline(tl, clampToAudio = narration != null)); clickIdx = ClickTrack.firstIndex(clickStarts, playMs, false) }
        return clickStarts
    }
    private fun fireClicks() {
        val tl = timeline ?: return
        val cs = tl.mix.click; val st = clickTimes()
        while (clickIdx < st.size && st[clickIdx] <= playMs) {
            if (cs.enabled && playMs - st[clickIdx] < 250) clicker.play(cs.volume)
            clickIdx++
        }
    }
    fun clickCount() = clickTimes().size
    fun setClick(c: ClickSettings) = edit { it.setMix(it.mix.copy(click = c)) }
    fun hearClick() { clicker.play(timeline?.mix?.click?.volume ?: 0.5f) }

    // ---------- Text Studio ----------
    fun selectedText(): TextClip? { val id = selectedTextId ?: return null; return timeline?.texts?.firstOrNull { it.id == id } }
    fun selectText(id: Int?, openTextFocus: Boolean = false) {
        selectedTextId = id
        if (id == null) return
        selectedId = null
        if (selectedTextLayerIds.isEmpty()) selectedTextLayerIds = setOf(id)
        selectedText()?.let { seekTo(it.startMs + 1); jumpKey++ }
        if (openTextFocus) {
            switchContextually("text")
        }
    }
    fun addText(style: OverlayStyle = StylePresets.byName("Subtitle") ?: OverlayStyle()) {
        val t = timeline ?: return; val id = t.nextTextId
        edit { it.addTextAt(playMs, "Your text", style) }
        if (timeline?.texts?.any { it.id == id } == true) selectText(id)
    }
    fun editText(id: Int, text: String? = null, startMs: Long? = null, endMs: Long? = null, style: OverlayStyle? = null) {
        val cur = timeline?.texts?.firstOrNull { it.id == id }
        if (cur?.locked == true) { toast("Layer is locked. Unlock it in Layers to edit."); return }
        edit { it.editText(id, text, startMs, endMs, style) }
    }
    fun moveText(id: Int, startMs: Long) = edit { it.moveText(id, startMs) }
    fun deleteText(id: Int) { edit { it.deleteText(id) }; selectedTextId = null; selectedTextLayerIds = selectedTextLayerIds - id }
    fun duplicateText(id: Int) { val t = timeline ?: return; val n = t.nextTextId; edit { it.duplicateText(id) }; if (timeline?.texts?.any { it.id == n } == true) selectText(n) }
    fun splitText(id: Int) {
        val c = timeline?.texts?.firstOrNull { it.id == id } ?: return
        if (playMs < c.startMs + Timeline.MIN_CLIP_MS || playMs > c.endMs - Timeline.MIN_CLIP_MS) { toast("Move the playhead inside the text first."); return }
        edit { it.splitText(id, playMs) }
    }
    private fun saveLib(l: StyleLibrary) { styleLib = l; try { styleFile.writeText(l.toJson()) } catch (e: Exception) { toast("Could not save the style: ${e.message}") } }
    fun saveStyle(name: String, s: OverlayStyle) { saveLib(styleLib.with(name, s)); toast("Style saved.") }
    fun deleteSavedStyle(name: String) = saveLib(styleLib.without(name))

    // ---------- Captions sheet ----------
    fun setCaptionStyle(s: OverlayStyle) = edit { it.setCaptionStyle(s) }
    fun editCaption(id: Int, text: String? = null, startMs: Long? = null, endMs: Long? = null) = edit { it.editCaption(id, text, startMs, endMs) }
    fun addCaption() = edit { it.addCaptionAt(playMs) }
    fun deleteCaption(id: Int) = edit { it.deleteCaption(id) }
    fun mergeCaption(id: Int) = edit { it.mergeCaptionWithNext(id) }
    fun shiftCaptions(deltaMs: Long) = edit { it.shiftCaptions(deltaMs) }
    fun clearCaptions() = edit { it.withCaptions(emptyList()) }
    fun captionsFromScenes() = edit { it.captionsFromScenes() }
    fun splitCaption(id: Int) {
        val c = timeline?.captions?.firstOrNull { it.id == id } ?: return
        if (playMs < c.startMs + Timeline.MIN_CLIP_MS || playMs > c.endMs - Timeline.MIN_CLIP_MS) { toast("Move the playhead inside the caption first."); return }
        edit { it.splitCaption(id, playMs) }
    }
    fun shareCaptions(vtt: Boolean) {
        val caps = timeline?.captions.orEmpty()
        if (caps.isEmpty()) { toast("There are no caption cues yet. Tap From scenes or import a file."); return }
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, if (vtt) "captions.vtt" else "captions.srt"); putExtra(Intent.EXTRA_TEXT, if (vtt) CaptionTools.toVtt(caps) else CaptionTools.toSrt(caps)) }
        try { act.startActivity(Intent.createChooser(i, "Share captions")) } catch (e: Exception) { toast("Could not share: ${e.message}") }
    }

    // ---------- picking files (with Phase 0 timers & Phase 1 ZIP limits) ----------
    fun onPicked(mode: Int, uris: List<Uri>) {
        if (uris.isEmpty()) return
        status = "Copying ${uris.size} file(s)..."
        val t0 = System.currentTimeMillis()
        thread {
            try {
                if (mode == 3) {
                    val u = uris.first(); val name = displayName(u)
                    if (name.substringAfterLast('.', "").lowercase() !in Pipeline.IMAGE_EXT) { act.runOnUiThread { status = "That file is not a picture." }; return@thread }
                    val f = unique(File(act.cacheDir, "replaced").apply { mkdirs() }, name)
                    act.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    act.runOnUiThread { val id = selectedId; if (id != null) edit { it.replaceImage(id, f.path) }; status = "Picture replaced." }
                    return@thread
                }
                if (mode == 5) {
                    val u = uris.first(); val name = displayName(u)
                    try {
                        val f = unique(File(act.filesDir, "music").apply { mkdirs() }, name)
                        act.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        val sec = AudioDecoder.durationSec(f) ?: throw IllegalStateException("not an audio file")
                        act.runOnUiThread { edit { it.setMix(it.mix.copy(music = MusicTrack(f.path, name, durationMs = (sec * 1000).toLong()))) }; status = "Music added: $name."; toast(status) }
                    } catch (e: Exception) { act.runOnUiThread { status = "Music not added: ${e.message}"; toast(status) } }
                    return@thread
                }
                if (mode == 4) {
                    val u = uris.first(); val name = displayName(u)
                    try {
                        val text = act.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                        val caps = CaptionTools.parse(text, name)
                        act.runOnUiThread { edit { it.withCaptions(caps) }; status = "${caps.size} captions imported from $name."; toast(status) }
                    } catch (e: Exception) { act.runOnUiThread { status = "Captions not imported: ${e.message}"; toast(status) } }
                    return@thread
                }
                if (mode == 1) { inFiles.clear(); bundleZips.clear(); File(act.cacheDir, "in").deleteRecursively(); File(act.cacheDir, "bundle").deleteRecursively() }
                else { zips.clear(); pics.clear(); File(act.cacheDir, "zips").deleteRecursively(); File(act.cacheDir, "Pictures").deleteRecursively() }
                for (u in uris) {
                    val name = displayName(u)
                    val isImg = name.substringAfterLast('.', "").lowercase() in Pipeline.IMAGE_EXT
                    val isZip = name.endsWith(".zip", true)
                    val dir = File(act.cacheDir, if (mode == 1) (if (isZip) "bundle" else "in") else if (isZip) "zips" else "Pictures").apply { mkdirs() }
                    if (mode == 2 && !isZip && !isImg) continue
                    val f = unique(dir, name)
                    act.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    if (mode == 1 && isZip) { bundleZips.add(f); inFiles.addAll(extractNonPictures(f)) }
                    else if (mode == 1) inFiles.add(f) else if (isZip) zips.add(f) else pics.add(f)
                }
                DiagnosticsLog.recordStage(DiagStage.IMPORT, System.currentTimeMillis() - t0, uris.size, uris.size)
                act.runOnUiThread { kinds = computeKinds(); status = "Files: ${inFiles.size}   ZIPs: ${bundleZips.size + zips.size}   loose pictures: ${pics.size}" }
            } catch (e: Exception) { act.runOnUiThread { status = "Could not copy the files: ${e.message}" } }
        }
    }

    private fun computeKinds(): Set<String> {
        val s = HashSet<String>()
        val audio = com.auto.core.Bundle.AUDIO_EXT.map { it.removePrefix(".").lowercase() }
        for (f in inFiles) when (f.extension.lowercase()) {
            "srt", "vtt", "json" -> s.add("Timestamps")
            "txt", "md", "text", "docx" -> s.add("Script")
            in audio -> s.add("Audio")
        }
        if (pics.isNotEmpty() || zips.isNotEmpty() || bundleZips.isNotEmpty()) s.add("Pictures")
        return s
    }

    /** Phase 1 (Section 13): Enforces zip-slip protection AND entry-count / single-entry / total-expanded-size limits. */
    private fun extractNonPictures(zip: File, limits: ZipLimits = ZipSafety.DEFAULT_LIMITS): List<File> {
        val out = ArrayList<File>(); val dir = File(act.cacheDir, "in").apply { mkdirs() }; val root = dir.canonicalFile
        val ok = setOf("txt", "md", "text", "docx", "srt", "vtt", "json") + com.auto.core.Bundle.AUDIO_EXT.map { it.removePrefix(".") }
        var entryCount = 0
        var totalBytes = 0L
        ZipFile(zip).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw PipelineError("ZIP limit exceeded: archive has more than ${limits.maxEntries} entries.")
                }
                if (e.isDirectory) continue
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { it.startsWith(".") || it == "__MACOSX" }) continue
                if (parts.any { it == ".." }) throw PipelineError("ZIP security error: path traversal '..' in '${e.name}'.")
                if (parts.last().substringAfterLast('.', "").lowercase() !in ok) continue
                val f = unique(root, parts.last())
                val safeDest = ZipSafety.ensureInsideRoot(root, e.name, f)
                z.getInputStream(e).use { input ->
                    safeDest.outputStream().use { output ->
                        totalBytes = ZipSafety.copyBounded(parts.last(), input, output, limits, totalBytes)
                    }
                }
                out.add(safeDest)
            }
        }
        return out
    }

    private fun displayName(u: Uri): String {
        act.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (k >= 0) return c.getString(k) } }
        return u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var n = 1
        while (f.exists()) { f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}"); n++ }
        return f
    }

    // ---------- analyse (Protected Auto Create Core Engine — unchanged pipeline outputs) ----------
    fun analyse() {
        if (inFiles.isEmpty() && bundleZips.isEmpty() && zips.isEmpty() && pics.isEmpty()) { status = "Add at least one of: the captions (timestamps) file, the narration audio, the pictures or the script."; return }
        status = "Analysing..."; busy = true
        val token = TaskToken().also { activeTaskToken = it }
        val t0 = System.currentTimeMillis()
        thread {
            try {
                val entries = inFiles.map { f -> Entry(f.name, f.length(), if (f.extension.lowercase().let { ".$it" } in com.auto.core.Bundle.AUDIO_EXT) AudioDecoder.durationSec(f) else null, { TextFiles.read(f) }) }
                val b = com.auto.core.Bundle.classify(entries, bundleZips.size + zips.size + pics.size)
                fun file(e: Entry?) = e?.let { x -> inFiles.first { it.name == x.name } }
                val scriptText = file(b.script)?.let { TextFiles.read(it) } ?: ""
                val tsF = file(b.timestamps)
                val captionRaw = tsF?.readText()
                val tsName = tsF?.name ?: ""
                val rows = captionRaw?.let { Captions.parseRows(it, tsName) ?: Captions.parseCues(Captions.parse(it, tsName)) }
                val audioF = file(b.audio)
                val audioSec = audioF?.let { AudioDecoder.durationSec(it) } ?: 0.0
                val sources = ArrayList<File>(bundleZips + zips)
                if (pics.isNotEmpty()) sources.add(File(act.cacheDir, "Pictures"))
                val work = File(act.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                val a = CaptionPipeline.analyse(rows, scriptText, null, audioSec, sources, work, hasAudio = audioF != null)
                val v = Validation.check(a)
                val msgs = (v.errors.map { "ERROR: $it" } + v.warnings + b.notes.filterNot { it.startsWith("MISSING: scene details") || it.startsWith("No click sound found") } + listOfNotNull(b.click?.let { "The click file in your files ('${it.name}') is not used: the app has its own built-in click (Music & Audio sheet: on/off and volume)." }) + a.warnings).distinct()
                val mp = audioF?.let { try { MediaPlayer().apply { setDataSource(it.path); prepare() } } catch (_: Exception) { null } }
                val ed = TimelineEditor(Timeline.fromScenes(a.scenes, a.timing.duration))
                DiagnosticsLog.recordStage(DiagStage.ANALYSIS, System.currentTimeMillis() - t0, a.scenes.size, a.scenes.size, errors = v.errors.size)
                act.runOnUiThread {
                    token.runIfActive {
                        release(); resetProject(); projectOpen = false; projectType = ProjectType.AUTO_CREATE
                        player = mp; analysis = a; narration = audioF; editor = ed
                        mp?.setOnCompletionListener { playMs = playEndMs(); playing = false }
                        selectedId = null; selectedTextId = null; selectedTextLayerIds = emptySet(); playMs = 0L; playing = false; warnings = msgs; busy = false
                        sync(); jumpKey++; loadPeaks(audioF)
                        status = "${a.scenes.size} scenes ready."; sheet = null
                    }
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    token.runIfActive {
                        busy = false
                        status = "Problem: ${e.message ?: e.toString()}"
                    }
                }
            }
        }
    }

    // ---------- new project / temporary files ----------
    private val tempDirs = listOf("in", "bundle", "zips", "Pictures", "work", "export", "replaced")

    fun newProject() {
        if (busy) { toast("Wait until the current analysis has finished."); return }
        activeTaskToken.cancel()
        activeTaskToken = TaskToken()
        stopPlay(); release()
        inFiles.clear(); zips.clear(); bundleZips.clear(); pics.clear()
        analysis = null; narration = null; editor = null; projectOpen = false; projectType = ProjectType.AUTO_CREATE; resetProject()
        autosaveCoordinator.nextSequence()
        timeline = null; canUndo = false; canRedo = false; selectedId = null; selectedTextId = null; selectedTextLayerIds = emptySet()
        pendingReplacementPath = null; pendingReplacementColor = null; playMs = 0L; warnings = emptyList(); kinds = emptySet()
        status = "Choose your files in Inputs, then press Analyse."; sheet = "inputs"
        thread {
            for (d in tempDirs) {
                // Never delete export folder while an export job is actively running
                if (d == "export" && exportPct != null) continue
                File(act.cacheDir, d).deleteRecursively()
            }
        }
    }

    fun clearTemp(done: (Long) -> Unit) {
        if (busy || exportPct != null) { toast("Wait until the current work has finished."); return }
        activeTaskToken.cancel()
        activeTaskToken = TaskToken()
        stopPlay(); release()
        inFiles.clear(); zips.clear(); bundleZips.clear(); pics.clear()
        analysis = null; narration = null; editor = null; projectOpen = false; resetProject()
        autosaveCoordinator.nextSequence()
        timeline = null; canUndo = false; canRedo = false; selectedId = null; selectedTextId = null; selectedTextLayerIds = emptySet()
        pendingReplacementPath = null; pendingReplacementColor = null; playMs = 0L; warnings = emptyList(); kinds = emptySet()
        status = "Choose your files in Inputs, then press Analyse."
        thread {
            val n = tempDirs.sumOf { d -> File(act.cacheDir, d).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            for (d in tempDirs) File(act.cacheDir, d).deleteRecursively()
            act.runOnUiThread { done(n) }
        }
    }

    // ---------- export (navigation-independent job lifetime, preflight memory check, storage guard, resume/retry) ----------
    fun currentStorageEstimate(): StorageEstimate? {
        val tl = timeline ?: return null
        val settings = try { ExportOptions.settings(Resolution.of(resP), aspect, fps) } catch (_: Exception) { return null }
        val plan = ExportPlan.fromTimeline(tl, clampToAudio = narration != null)
        return Exporter.checkStorage(act, plan, settings, upscale, upscaleFormat)
    }

    /**
     * Priority 1 (Section 2): Runs preflight memory/dimension check on all unique source images before starting export.
     * Never silently lowers 4× to 2× — if a source image exceeds safe bounds for the selected format/factor,
     * displays `pendingPreflightWarning` with explicit user choices.
     */
    fun requestExportWithPreflight() {
        val tl = timeline ?: return
        if (exportPct != null) return
        val settings = try { ExportOptions.settings(Resolution.of(resP), aspect, fps) } catch (e: Exception) { status = "Export failed: ${e.message}"; return }
        val plan = ExportPlan.fromTimeline(tl, clampToAudio = narration != null)
        val wantsUpscale = (upscaleProfile != UpscaleProfile.MANUAL || upscale != UpscaleFactor.OFF)
        if (wantsUpscale) {
            val preflight = SafelightExportProcessor.preflightPlan(plan, upscale, upscaleFormat, upscaleProfile, settings)
            if (!preflight.safe) {
                pendingPreflightWarning = preflight
                status = preflight.warningMessage
                return
            }
        }
        pendingPreflightWarning = null
        export()
    }

    /** Applies the user's explicit choice from the preflight warning card and starts the export. */
    fun applyPreflightChoiceAndExport(choice: String) {
        when {
            choice.startsWith("Use Streaming PNG", ignoreCase = true) -> {
                upscaleFormat = IntermediateFormat.PNG
                persistExportSettings()
                pendingPreflightWarning = null
                export()
            }
            choice.startsWith("Switch to Balanced", ignoreCase = true) -> {
                upscaleProfile = UpscaleProfile.BALANCED
                upscaleFormat = IntermediateFormat.PNG
                persistExportSettings()
                pendingPreflightWarning = null
                export()
            }
            choice.startsWith("Use 2×", ignoreCase = true) || choice.startsWith("Use 2x", ignoreCase = true) -> {
                upscaleProfile = UpscaleProfile.MANUAL
                upscale = UpscaleFactor.X2
                upscaleFormat = IntermediateFormat.PNG
                persistExportSettings()
                pendingPreflightWarning = null
                export()
            }
            else -> {
                pendingPreflightWarning = null
                status = "Export cancelled at preflight check."
            }
        }
    }

    fun export() {
        val tl = timeline ?: return
        if (exportPct != null) return
        val settings = try { ExportOptions.settings(Resolution.of(resP), aspect, fps) } catch (e: Exception) { status = "Export failed: ${e.message}"; return }
        val upDesc = when {
            upscaleProfile != UpscaleProfile.MANUAL -> " · ${upscaleProfile.label}"
            upscale != UpscaleFactor.OFF -> " · Upscale ${upscale.label} (${upscaleFormat.shortLabel})"
            else -> ""
        }
        lastExportUri = null; exportPct = 0
        status = "Exporting ${resP.let { if (it == 2160) "4K" else "${it}p" }} ${aspect.label} $fps fps$upDesc..."
        stopPlay()
        try {
            val onP = { p: Int -> exportPct = p; if (!status.startsWith("Upscaling") && !status.startsWith("Rendering")) status = "Exporting... $p%" }
            val onS = { msg: String -> status = msg }
            val onJ = { _: ExportJob -> exportJobs = Exporter.jobStore(act).list() }
            val onD = { where: String ->
                exportPct = null
                lastExportUri = Exporter.lastUri
                status = "Done: $where"
                exportJobs = Exporter.jobStore(act).list()
                refreshDiagnostics()
            }
            val onE = { err: String ->
                exportPct = null
                status = "Export failed: $err"
                exportJobs = Exporter.jobStore(act).list()
                refreshDiagnostics()
            }
            Exporter.exportTimeline(
                ctx = act,
                plan = ExportPlan.fromTimeline(tl, clampToAudio = narration != null),
                narration = narration,
                s = settings,
                captions = captionsOn,
                onProgress = onP,
                onDone = onD,
                onError = onE,
                upscale = upscale,
                upscaleFormat = upscaleFormat,
                upscaleProfile = upscaleProfile,
                strictUpscale = strictUpscaleMode,
                projectId = projectId ?: "active",
                projectName = projectName.ifBlank { "Current Project" },
                onStatus = onS,
                onJobUpdate = onJ
            )
        } catch (e: Exception) { exportPct = null; status = "Export failed: ${e.message}" }
    }

    /** Resumes an interrupted or failed export job from Home or the Export Sheet. */
    fun resumeOrRetryJob(job: ExportJob, onNavigateToEditor: () -> Unit = {}) {
        if (job.projectId.isNotBlank() && job.projectId != "active" && job.projectId != projectId) {
            openProject(job.projectId, openAsCopy = false) {
                onNavigateToEditor()
                export()
            }
        } else if (timeline != null) {
            export()
        } else {
            say("Open the project '${job.projectName}' first to resume its export.")
        }
    }
}
