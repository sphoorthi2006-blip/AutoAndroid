package com.auto.app

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
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
 * Everything the editor screen shows and does. The picking / analysing / exporting code is the same as the old text screen
 * (moved here from MainActivity); the timeline edits go through core's TimelineEditor, so undo/redo and the rules are the tested ones.
 */
const val PEAKS_PER_SEC = 20

class AutoState(private val act: ComponentActivity) {
    private val inFiles = ArrayList<File>()      // script, captions, narration
    private val zips = ArrayList<File>()
    private val bundleZips = ArrayList<File>()   // ZIPs picked in step 1: text/audio are taken out, the pictures stay inside
    private val pics = ArrayList<File>()
    private var analysis: Pipeline.Analysis? = null
    private var narration: File? = null
    private var editor: TimelineEditor? = null
    private var player: MediaPlayer? = null

    var pick: (Int) -> Unit = {}                 // 1 = files/ZIP, 2 = pictures, 3 = replace one picture, 4 = caption file, 5 = music file (set by MainActivity)

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
    var exportPct by mutableStateOf<Int?>(null)
    var jumpKey by mutableIntStateOf(0)           // bumped when the timeline must scroll to playMs
    var captionsOn by mutableStateOf(true)        // captions switch: shown over the pictures in the preview AND burned into the exported video
    var resP by mutableIntStateOf(720)            // export height: 720, 1080 or 2160 (4K)
    var fps by mutableIntStateOf(30)              // export frame rate: 30 or 60
    var aspect by mutableStateOf(AspectRatio.R16_9)   // export shape: 16:9, 9:16, 1:1 or 4:5 (pictures are scaled to fit, black bars)
    var onHome: () -> Unit = {}                   // set by the shell: back to the Home page
    val hasProject get() = timeline != null
    var selectedTextId by mutableStateOf<Int?>(null)   // the selected text clip (Text Studio); never set together with selectedId
    var styleLib by mutableStateOf(StyleLibrary())     // styles the person saved (filesDir/styles.json)
    private val styleFile get() = File(act.filesDir, "styles.json")
    init { styleLib = try { if (styleFile.exists()) StyleLibrary.parse(styleFile.readText()) else StyleLibrary() } catch (e: Exception) { StyleLibrary() } }

    /** The whole timeline (scrubbing/editing): can be longer than the narration when a scene was extended. */
    fun totalMs(): Long { val t = timeline ?: return 0L; return max(t.audioMs, t.endMs) }
    fun hasNarration() = narration != null
    /** Where playing and the exported video stop: the end of the narration (picture edits never change it); without narration, the last picture. */
    fun playEndMs(): Long { val t = timeline ?: return 0L; return if (narration != null) t.audioMs else max(t.audioMs, t.endMs) }
    fun selectedClip(): ImageClip? { val id = selectedId ?: return null; return timeline?.clips?.firstOrNull { it.id == id } }

    // ---------- timeline edits ----------
    private fun sync() {
        val e = editor ?: return
        timeline = e.current; canUndo = e.canUndo; canRedo = e.canRedo
        val id = selectedId
        if (id != null && e.current.clips.none { it.id == id }) selectedId = null
        val tid = selectedTextId
        if (tid != null && e.current.texts.none { it.id == tid }) selectedTextId = null
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
    /** Cuts the selected scene at the playhead. Core ignores a cut that would leave a half shorter than MIN_CLIP_MS, so check first and say why. */
    fun split() {
        val id = selectedId ?: return; val c = timeline?.clips?.firstOrNull { it.id == id } ?: return; val at = playMs
        if (at < c.startMs + Timeline.MIN_CLIP_MS || at > c.endMs - Timeline.MIN_CLIP_MS) { toast("Move the playhead inside this scene (drag the timeline), then tap Split"); return }
        edit { it.split(id, at) }
    }
    fun delete() { val id = selectedId ?: return; edit { it.delete(id, true) }; selectedId = null }
    /** Any length, no magnet: the scenes after this one move later/earlier, the narration stays as it is. */
    // ---------- projects (step 36): saved in filesDir/projects through core ProjectStore ----------
    private val store by lazy { ProjectStore(File(act.filesDir, "projects")) }
    var projectId by mutableStateOf<String?>(null)
    var projectName by mutableStateOf("")
    var projects by mutableStateOf<List<ProjectSummary>>(emptyList())
    private var manifest: ProjectManifest? = null
    private var projectOpen = false                       // true when the timeline was opened from disk (no analysis object exists then)
    private val copied = HashMap<String, String>()         // source path -> path inside the project (copy each file once)
    private var saveGen = 0
    private fun resetProject() { manifest = null; projectId = null; projectName = ""; copied.clear() }
    fun refreshProjects() { thread { val l = try { store.summaries() } catch (_: Exception) { emptyList() }; act.runOnUiThread { projects = l } } }
    private fun scheduleSave() {
        if (timeline == null) return
        val g = ++saveGen
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (g == saveGen) saveProject() }, 1500)
    }
    /** Writes manifest + timeline.json; copies the pictures, music and narration into the project once so it opens later without the temporary cache. */
    fun saveProject(done: () -> Unit = {}) {
        val tl = timeline ?: run { done(); return }
        val prefs = ExportPrefs(resP, fps, aspect, captionsOn); val nar = narration; val nm = projectName
        thread {
            try {
                val m = doSave(tl, prefs, nar, nm)
                act.runOnUiThread { projectId = m.id; projectName = m.name; done() }
            } catch (e: Exception) { act.runOnUiThread { status = "Could not save the project: ${e.message}"; done() } }
        }
    }
    @Synchronized private fun doSave(tl: Timeline, prefs: ExportPrefs, nar: File?, nm: String): ProjectManifest {
        var m = manifest ?: store.create(nm.ifBlank { "Project " + java.text.SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(java.util.Date()) })
        val dir = store.dir(m.id).path
        fun keep(p: String): String = copied.getOrPut(p) { val f = File(p); if (!f.exists() || f.path.startsWith(dir)) p else store.importFile(m.id, f).path }
        var t2 = tl
        for (c in tl.clips) c.image?.let { t2 = t2.replaceImage(c.id, keep(it)) }
        tl.mix.music?.let { mu -> t2 = t2.setMix(t2.mix.copy(music = mu.copy(path = keep(mu.path)))) }
        val inputs = if (nar != null) mapOf("audio" to listOf(File(keep(nar.path)).name)) else emptyMap()
        m = store.saveWithPrefs(m.copy(inputs = inputs), t2, prefs); manifest = m
        return m
    }
    fun openProject(id: String, done: () -> Unit) {
        if (busy || exportPct != null) { toast("Wait until the current work has finished."); return }
        thread {
            try {
                val lp = store.load(id)
                val tl = lp.timeline ?: throw IllegalStateException("this project has no saved timeline: start a new project and press Analyse")
                val nar = lp.manifest.inputs["audio"]?.firstOrNull()?.let { File(File(store.dir(id), "inputs"), it) }?.takeIf { it.exists() }
                val mp = nar?.let { try { MediaPlayer().apply { setDataSource(it.path); prepare() } } catch (_: Exception) { null } }
                val prefs = ExportPrefs.fromMap(lp.manifest.settings)
                act.runOnUiThread {
                    stopPlay(); release(); player = mp; narration = nar; analysis = null; projectOpen = true; editor = TimelineEditor(tl)
                    manifest = lp.manifest; copied.clear(); projectId = id; projectName = lp.manifest.name
                    resP = prefs.resP; fps = prefs.fps; aspect = prefs.aspect; captionsOn = prefs.captionsOn
                    mp?.setOnCompletionListener { playMs = playEndMs(); playing = false }
                    selectedId = null; selectedTextId = null; playMs = 0L; playing = false; warnings = lp.warnings; busy = false
                    sync(); jumpKey++; loadPeaks(nar); status = "Opened \"${lp.manifest.name}\"."; sheet = null; done()
                }
            } catch (e: Exception) { act.runOnUiThread { say("Could not open the project: ${e.message}") } }
        }
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
    /** Everything the app has: project assets (pictures on the timeline, the music bed) and imported files (picked pictures, music, replaced pictures). Call off the main thread. */
    fun scanMedia(): List<MediaAsset> {
        val out = LinkedHashMap<String, MediaAsset>()
        fun add(f: File, origin: MediaOrigin) {
            if (!f.isFile) return
            out[f.path] = MediaAsset(f.path, f.name, MediaLibrary.classify(f.name), f.length(), f.lastModified(), origin)
        }
        for (d in listOf(File(act.cacheDir, "Pictures"), File(act.cacheDir, "replaced"), File(act.cacheDir, "in"), File(act.filesDir, "music")))
            d.listFiles()?.forEach { add(it, MediaOrigin.IMPORTED) }
        val tl = timeline
        tl?.clips?.mapNotNull { it.image }?.distinct()?.forEach { add(File(it), MediaOrigin.PROJECT) }
        tl?.mix?.music?.let { add(File(it.path), MediaOrigin.PROJECT) }
        return out.values.toList()
    }
    /** Pictures replace the picture of the selected scene (one at a time); audio becomes the music bed. */
    fun addMediaToProject(list: List<MediaAsset>) {
        val pic = list.filter { it.kind == MediaKind.IMAGE }; val aud = list.filter { it.kind == MediaKind.AUDIO }
        if (pic.isNotEmpty()) {
            val id = selectedId
            if (id == null) say("Select a scene on the timeline first: the picture replaces its picture.")
            else { edit { it.replaceImage(id, pic.first().path) }; say(if (pic.size > 1) "First picture used (one scene is selected)." else "Picture replaced.") }
        }
        aud.firstOrNull()?.let { a ->
            val ms = try { ((AudioDecoder.durationSec(File(a.path)) ?: 0.0) * 1000).toLong() } catch (_: Throwable) { 0L }
            edit { it.setMix(it.mix.copy(music = MusicTrack(a.path, a.name, durationMs = ms))) }; say("Music added: ${a.name}.")
        }
    }
    // ---------- audio (step 34) ----------
    fun setNarrationAudio(a: AudioSettings) = edit { it.setMix(it.mix.copy(narration = a)) }
    fun setMusic(m: MusicTrack?) = edit { it.setMix(it.mix.copy(music = m)) }
    /** Real waveform of the narration: PEAKS_PER_SEC peaks per second, computed once per narration file in a background thread. */
    var peaks by mutableStateOf<FloatArray?>(null)
    private var peaksFor: String? = null
    private fun loadPeaks(f: File?) {
        if (f == null) { peaks = null; peaksFor = null; return }
        if (peaksFor == f.path && peaks != null) return
        peaks = null; peaksFor = f.path
        thread {
            try {
                val (samples, _) = AudioDecoder.decode(f)
                val n = max(1, (samples.size.toDouble() / 1.0).let { _ -> (timeline?.audioMs ?: 0L) / 1000.0 * PEAKS_PER_SEC }.toInt())
                val p = Waveform.peaks(samples, n)
                act.runOnUiThread { if (peaksFor == f.path) peaks = p }
            } catch (_: Throwable) { /* no wave: the timeline keeps its plain bar */ }
        }
    }
    fun setFx(id: Int, fx: com.auto.core.ClipFx) = edit { it.setFx(id, fx) }
    fun setTransition(id: Int, t: com.auto.core.TransitionSpec) = edit { it.setTransition(id, t) }
    /** One undo step for the whole loop. */
    fun setTransitionAll(t: com.auto.core.TransitionSpec) = edit { tl -> tl.clips.fold(tl) { acc, c -> acc.setTransition(c.id, t) } }
    fun setLength(ms: Long) { val id = selectedId ?: return; edit { it.setLength(id, ms) } }
    fun addLength(deltaMs: Long) { val c = selectedClip() ?: return; setLength(c.durMs + deltaMs) }
    fun selectClip(id: Int?) {
        selectedId = id; if (id != null) selectedTextId = null
        val c = id?.let { i -> timeline?.clips?.firstOrNull { it.id == i } } ?: return
        seekTo(c.startMs + 1); jumpKey++
    }

    // ---------- playback (narration through MediaPlayer; no audio = a plain clock) ----------
    fun seekTo(ms: Long) {
        playMs = ms.coerceIn(0L, max(0L, totalMs()))
        try { player?.seekTo(playMs.toInt()) } catch (_: Exception) {}
    }
    fun togglePlay() {
        if (timeline == null) return
        if (playing) { stopPlay(); return }
        if (playMs >= playEndMs() - 50) seekTo(0)
        try { player?.let { it.seekTo(playMs.toInt()); it.start() } } catch (_: Exception) {}
        playing = true
    }
    fun stopPlay() { try { player?.pause() } catch (_: Exception) {}; playing = false }
    /** Called every ~33 ms while playing. */
    fun tick(dtMs: Long) {
        val p = player
        playMs = if (p != null) try { p.currentPosition.toLong() } catch (_: Exception) { playMs + dtMs } else playMs + dtMs
        if (playMs >= playEndMs()) { playMs = playEndMs(); stopPlay() }
    }
    fun release() { try { player?.release() } catch (_: Exception) {}; player = null }

    // ---------- Text Studio (steps 28-30): every change is ONE TimelineEditor edit, so undo/redo works ----------
    fun selectedText(): TextClip? { val id = selectedTextId ?: return null; return timeline?.texts?.firstOrNull { it.id == id } }
    fun selectText(id: Int?) {
        selectedTextId = id; if (id == null) return
        selectedId = null
        selectedText()?.let { seekTo(it.startMs + 1); jumpKey++ }
    }
    /** New text at the playhead (3 s, Subtitle look), selected so Text Studio edits it right away. */
    fun addText(style: OverlayStyle = StylePresets.byName("Subtitle") ?: OverlayStyle()) {
        val t = timeline ?: return; val id = t.nextTextId
        edit { it.addTextAt(playMs, "Your text", style) }
        if (timeline?.texts?.any { it.id == id } == true) selectText(id)
    }
    fun editText(id: Int, text: String? = null, startMs: Long? = null, endMs: Long? = null, style: OverlayStyle? = null) = edit { it.editText(id, text, startMs, endMs, style) }
    fun moveText(id: Int, startMs: Long) = edit { it.moveText(id, startMs) }
    fun deleteText(id: Int) { edit { it.deleteText(id) }; selectedTextId = null }
    fun duplicateText(id: Int) { val t = timeline ?: return; val n = t.nextTextId; edit { it.duplicateText(id) }; if (timeline?.texts?.any { it.id == n } == true) selectText(n) }
    fun splitText(id: Int) {
        val c = timeline?.texts?.firstOrNull { it.id == id } ?: return
        if (playMs < c.startMs + Timeline.MIN_CLIP_MS || playMs > c.endMs - Timeline.MIN_CLIP_MS) { toast("Move the playhead inside the text first."); return }
        edit { it.splitText(id, playMs) }
    }
    private fun saveLib(l: StyleLibrary) { styleLib = l; try { styleFile.writeText(l.toJson()) } catch (e: Exception) { toast("Could not save the style: ${e.message}") } }
    fun saveStyle(name: String, s: OverlayStyle) { saveLib(styleLib.with(name, s)); toast("Style saved.") }
    fun deleteSavedStyle(name: String) = saveLib(styleLib.without(name))

    // ---------- Captions sheet (step 31) ----------
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
    /** Sends the cues as text (SRT or VTT) to any app the person picks (mail, notes, files...). */
    fun shareCaptions(vtt: Boolean) {
        val caps = timeline?.captions.orEmpty()
        if (caps.isEmpty()) { toast("There are no caption cues yet. Tap From scenes or import a file."); return }
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, if (vtt) "captions.vtt" else "captions.srt"); putExtra(Intent.EXTRA_TEXT, if (vtt) CaptionTools.toVtt(caps) else CaptionTools.toSrt(caps)) }
        try { act.startActivity(Intent.createChooser(i, "Share captions")) } catch (e: Exception) { toast("Could not share: ${e.message}") }
    }

    // ---------- picking files ----------
    fun onPicked(mode: Int, uris: List<Uri>) {
        if (uris.isEmpty()) return
        status = "Copying ${uris.size} file(s)..."
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
                if (mode == 5) {   // music file for the Audio sheet
                    val u = uris.first(); val name = displayName(u)
                    try {
                        val f = unique(File(act.filesDir, "music").apply { mkdirs() }, name)
                        act.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        val sec = AudioDecoder.durationSec(f) ?: throw IllegalStateException("not an audio file")
                        act.runOnUiThread { edit { it.setMix(it.mix.copy(music = MusicTrack(f.path, name, durationMs = (sec * 1000).toLong()))) }; status = "Music added: $name."; toast(status) }
                    } catch (e: Exception) { act.runOnUiThread { status = "Music not added: ${e.message}"; toast(status) } }
                    return@thread
                }
                if (mode == 4) {   // caption file for the Captions sheet
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

    /** Text, caption, JSON and audio files inside a ZIP are copied out (zip-slip safe); the pictures stay in the ZIP. */
    private fun extractNonPictures(zip: File): List<File> {
        val out = ArrayList<File>(); val dir = File(act.cacheDir, "in").apply { mkdirs() }; val root = dir.canonicalFile
        val ok = setOf("txt", "md", "text", "docx", "srt", "vtt", "json") + com.auto.core.Bundle.AUDIO_EXT.map { it.removePrefix(".") }
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { it.startsWith(".") || it == "__MACOSX" }) continue
                if (parts.last().substringAfterLast('.', "").lowercase() !in ok) continue
                val f = unique(root, parts.last())
                if (!f.canonicalFile.path.startsWith(root.path + File.separator)) continue
                z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                out.add(f)
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

    // ---------- analyse (same logic as the old screen: timestamps, audio, pictures, script - none mandatory) ----------
    fun analyse() {
        if (inFiles.isEmpty() && bundleZips.isEmpty() && zips.isEmpty() && pics.isEmpty()) { status = "Add at least one of: the captions (timestamps) file, the narration audio, the pictures or the script."; return }
        status = "Analysing..."; busy = true
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
                val msgs = (v.errors.map { "ERROR: $it" } + v.warnings + b.notes.filterNot { it.startsWith("MISSING: scene details") } + a.warnings).distinct()
                val mp = audioF?.let { try { MediaPlayer().apply { setDataSource(it.path); prepare() } } catch (_: Exception) { null } }
                val ed = TimelineEditor(Timeline.fromScenes(a.scenes, a.timing.duration))
                act.runOnUiThread {
                    release(); resetProject(); projectOpen = false; player = mp; analysis = a; narration = audioF; editor = ed
                    mp?.setOnCompletionListener { playMs = playEndMs(); playing = false }
                    selectedId = null; playMs = 0L; playing = false; warnings = msgs; busy = false
                    sync(); jumpKey++; loadPeaks(audioF)
                    status = "${a.scenes.size} scenes ready."; sheet = null
                }
            } catch (e: Exception) { act.runOnUiThread { busy = false; status = "Problem: ${e.message ?: e.toString()}" } }
        }
    }

    // ---------- new project / temporary files ----------
    private val tempDirs = listOf("in", "bundle", "zips", "Pictures", "work", "export", "replaced")

    /** Forgets the current files, analysis and timeline and opens the Inputs sheet. Ignored while analysing or exporting. */
    fun newProject() {
        if (busy || exportPct != null) { toast("Wait until the current work has finished."); return }
        stopPlay(); release()
        inFiles.clear(); zips.clear(); bundleZips.clear(); pics.clear()
        analysis = null; narration = null; editor = null; projectOpen = false; resetProject(); saveGen++
        timeline = null; canUndo = false; canRedo = false; selectedId = null; playMs = 0L; warnings = emptyList(); kinds = emptySet()
        status = "Choose your files in Inputs, then press Analyse."; sheet = "inputs"
        thread { for (d in tempDirs) File(act.cacheDir, d).deleteRecursively() }
    }

    /** Deletes the imported copies, extracted pictures and old exports in the app's temporary folder (the current project is closed too). Returns the freed size to `done`. */
    fun clearTemp(done: (Long) -> Unit) {
        if (busy || exportPct != null) { toast("Wait until the current work has finished."); return }
        stopPlay(); release()
        inFiles.clear(); zips.clear(); bundleZips.clear(); pics.clear()
        analysis = null; narration = null; editor = null; projectOpen = false; resetProject(); saveGen++
        timeline = null; canUndo = false; canRedo = false; selectedId = null; playMs = 0L; warnings = emptyList(); kinds = emptySet()
        status = "Choose your files in Inputs, then press Analyse."
        thread {
            val n = tempDirs.sumOf { d -> File(act.cacheDir, d).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            for (d in tempDirs) File(act.cacheDir, d).deleteRecursively()
            act.runOnUiThread { done(n) }
        }
    }

    // ---------- export (main thread) ----------
    fun export() {
        if (analysis == null && !projectOpen) return; val tl = timeline ?: return
        if (exportPct != null) return
        val settings = try { ExportOptions.settings(Resolution.of(resP), aspect, fps) } catch (e: Exception) { status = "Export failed: ${e.message}"; return }
        exportPct = 0; status = "Exporting ${resP.let { if (it == 2160) "4K" else "${it}p" }} ${aspect.label} $fps fps..."; stopPlay()
        try {
            val onP = { p: Int -> exportPct = p; status = "Exporting... $p%" }
            val onD = { where: String -> exportPct = null; status = "Done: $where" }
            val onE = { err: String -> exportPct = null; status = "Export failed: $err" }
            // always from the edited timeline: the video is exactly as long as the narration (scenes after its end are left out, never squeezed in)
            Exporter.exportTimeline(act, ExportPlan.fromTimeline(tl, clampToAudio = narration != null), narration, settings, captionsOn, onP, onD, onE)
        } catch (e: Exception) { exportPct = null; status = "Export failed: ${e.message}" }
    }
}
