package com.cuevideo.app

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class UiState(
    val master: FileInfo? = null,
    val original: FileInfo? = null,
    val script: FileInfo? = null,
    val notice: String? = null,
    val originalCueList: List<Cue> = emptyList(),
    val audio: FileInfo? = null,
    val click: FileInfo? = null,
    val masterCues: List<Cue> = emptyList(),
    val images: List<ImageEntry> = emptyList(),
    val clips: List<ClipEntry> = emptyList(),
    val corrections: Map<String, Pair<Int, Int>> = emptyMap(),
    val busy: String? = null,
    val message: String? = null,
    val check: CheckResult? = null,
    val fps: Int = 30,
    val audioSeconds: Double? = null,
    val core: TimingResult? = null,
    val timing: TimingReport? = null,
)

/** What the Export section shows (Stage 5). */
data class ExportSummary(
    val videoName: String,
    val reportName: String,
    val frames: Int,
    val seconds: Double,
    val took: Double,
    val usedImages: Int,
    val givenImages: Int,
    val unusedImages: List<String>,
    val lufs: Double,
    val clicks: Int,
)

data class ExportUi(
    val busy: Boolean = false,
    val label: String = "",
    val progress: Double = 0.0,
    val result: ExportSummary? = null,
    val error: String? = null,
)

/** State of the Stage 4 audio test. */
data class AudioUi(
    val busy: Boolean = false,
    val label: String = "",
    val progress: Double = 0.0,
    val result: AudioResult? = null,
    val error: String? = null,
)

/** Everything the Timing section shows (section 7 and 8 of the spec). */
data class TimingReport(
    val starts: DoubleArray,
    val frames: IntArray,            // n+1 entries, see Timing.frameBounds
    val ratio: Double?,
    val estimated: List<Int>,
    val shifts: List<Pair<Int, Double>>,
    val shortSlides: List<Int>,
    val totalSeconds: Double,
    val noMatch: Boolean,
)

/** What the preview section shows for one cue (and one frame, for headings). */
data class PreviewState(
    val cue: Int = 1,
    val frame: Int = 0,
    val frames: Int = 0,              // > 1 only for headings: length of the cue in frames
    val image: ImageBitmap? = null,
    val info: String = "",
    val busy: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication<Application>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        File(ctx.cacheDir, "imgs").deleteRecursively()
        File(ctx.cacheDir, "bundle").deleteRecursively()
    }

    // ---------- slide preview (Stage 3) ----------

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview
    private var previewJob: Job? = null
    private val renderer by lazy { SlideRenderer(ctx) }
    private var layerText: String? = null
    private var layerBmp: Bitmap? = null

    fun showPreview(cue: Int, frame: Int) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value
            val chk = s.check
            if (chk == null) {
                _preview.value = PreviewState(cue, 0, 0, null, "Choose the master SRT first.")
                return@launch
            }
            if (cue < 1 || cue > chk.cueCount) {
                _preview.value = PreviewState(cue, 0, 0, null, "Cue numbers go from 1 to ${chk.cueCount}.")
                return@launch
            }
            _preview.value = _preview.value.copy(cue = cue, busy = true)
            try {
                val text = s.masterCues[cue - 1].text
                val rep = s.timing
                if (cue in chk.covered) {
                    _preview.value = PreviewState(cue, 0, 0, null, "Cue $cue is covered by a scene clip. Scene clips only appear in the exported video.")
                    return@launch
                }
                when (CueClassifier.classify(text)) {
                    CueKind.SUBSCRIBE -> {
                        val bmp = renderer.blackFrame()
                        _preview.value = PreviewState(cue, 0, 0, bmp.asImageBitmap(),
                            "Cue $cue: subscribe slide, pure black picture, no text.")
                    }
                    CueKind.HEADING -> {
                        val n = if (rep != null) rep.frames[cue] - rep.frames[cue - 1] else 90
                        val f = frame.coerceIn(0, maxOf(0, n - 1))
                        if (layerText != text || layerBmp == null) {
                            layerBmp = renderer.headingLayer(text)
                            layerText = text
                        }
                        val bmp = renderer.headingFrame(layerBmp!!, f, n, s.fps)
                        val z = SlideMath.zoomFactor(f, n)
                        val q = SlideMath.fadeBrightness(f, SlideMath.fadeFrames(s.fps))
                        _preview.value = PreviewState(cue, f, n, bmp.asImageBitmap(),
                            "Cue $cue: heading \"$text\"\nframe ${f + 1} of $n at ${s.fps} fps, zoom ${"%.3f".format(z)}x, brightness ${(q * 100).toInt()}%")
                    }
                    CueKind.NORMAL -> {
                        val img = chk.imageForCue[cue]
                        if (img == null) {
                            _preview.value = PreviewState(cue, 0, 0, null, "Cue $cue has no image yet.")
                        } else {
                            val bmp = renderer.loadPicture(img.uri)
                            _preview.value = if (bmp == null)
                                PreviewState(cue, 0, 0, null, "Could not read the picture ${img.name}.")
                            else PreviewState(cue, 0, 0, bmp.asImageBitmap(),
                                "Cue $cue: picture ${img.name}, cropped to 1920x1080.")
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                _preview.value = PreviewState(cue, 0, 0, null, "Not enough memory to draw this picture.")
            } catch (e: Exception) {
                _preview.value = PreviewState(cue, 0, 0, null, "Could not draw: ${e.message}")
            }
        }
    }

    // ---------- audio mix (Stage 4) ----------

    private val _audio = MutableStateFlow(AudioUi())
    val audio: StateFlow<AudioUi> = _audio
    @Volatile private var audioCancel = false
    private var audioJob: Job? = null

    fun cancelAudio() { audioCancel = true }

    /** Mixes voice-over + clicks into an .m4a and copies it to [dest] (chosen by the user). */
    fun startAudio(dest: Uri) {
        val s = _state.value
        val voice = s.audio
        val t = s.timing
        val times = clickTimesFor(s)
        if (voice == null || t == null || times == null || _audio.value.busy) return
        audioCancel = false
        _audio.value = AudioUi(busy = true, label = "Starting...")
        audioJob = viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(File(ctx.cacheDir, "audio").apply { mkdirs() }, "mix.m4a")
            try {
                var lastPct = -1
                val res = AudioJob.run(
                    ctx, voice, s.click, times, t.totalSeconds, s.audioSeconds, tmp,
                    { label, p ->
                        val pct = (p * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _audio.update { it.copy(label = label, progress = p) }
                        }
                    },
                    { audioCancel },
                )
                if (res == null) {
                    _audio.value = AudioUi(error = "Cancelled.")
                } else {
                    ctx.contentResolver.openOutputStream(dest, "w")?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                        ?: throw java.io.IOException("could not write to the chosen place")
                    _audio.value = AudioUi(result = res)
                }
            } catch (e: OutOfMemoryError) {
                _audio.value = AudioUi(error = "Not enough memory for the audio mix.")
            } catch (e: Exception) {
                _audio.value = AudioUi(error = "Audio mix failed: ${e.message}")
            } finally {
                tmp.delete()
            }
        }
    }

    // ---------- video export (Stage 5) ----------

    private val _export = MutableStateFlow(ExportUi())
    val export: StateFlow<ExportUi> = _export
    @Volatile private var exportCancel = false
    private var exportJob: Job? = null

    fun cancelExport() { exportCancel = true }

    private fun exportBaseName(s: UiState): String {
        val raw = (s.master?.name ?: "").substringBeforeLast('.')
        val b = raw.replace(Regex("(?i)[_\\- ]*master[_\\- ]*"), "_").trim('_', '-', ' ')
            .replace(Regex("[^A-Za-z0-9_.\\- ]"), "_")
        return if (b.isEmpty()) "cue_video" else b
    }

    /** Renders the video, then saves <name>.mp4 and <name>_report.csv into the folder [tree] chosen by the user. */
    fun startExport(tree: Uri) {
        val s = _state.value
        val chk = s.check
        val t = s.timing
        val times = clickTimesFor(s)
        if (chk == null || t == null || times == null || s.audio == null || !chk.canExport || _export.value.busy) return
        exportCancel = false
        _export.value = ExportUi(busy = true, label = "Starting...")
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            val work = File(ctx.cacheDir, "export")
            work.deleteRecursively()
            work.mkdirs()
            try {
                val plan = ExportPlanner.build(s.masterCues, t.frames, chk)
                val tmp = File(work, "video.mp4")
                var lastPct = -1
                val out = VideoExport.run(
                    ctx, s, plan, times, work, tmp,
                    { label, p ->
                        val pct = (p * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _export.update { it.copy(label = label, progress = p.coerceIn(0.0, 1.0)) }
                        }
                    },
                    { exportCancel },
                )
                if (out == null) {
                    _export.value = ExportUi(error = "Cancelled.")
                    return@launch
                }
                _export.update { it.copy(label = "Saving the files...", progress = 0.98) }
                val dir = DocumentFile.fromTreeUri(ctx, tree) ?: throw java.io.IOException("could not open the chosen folder")
                val base = exportBaseName(s)
                val vf = dir.createFile("video/mp4", "$base.mp4") ?: throw java.io.IOException("could not create the video file there")
                val vname = vf.name ?: "$base.mp4"
                ctx.contentResolver.openOutputStream(vf.uri, "w")?.use { o ->
                    java.io.BufferedOutputStream(o, 1 shl 20).use { b -> Mp4FastStart.rewrite(tmp, b) }
                } ?: throw java.io.IOException("could not write the video file")
                val csv = ReportCsv.build(s.masterCues, t.frames, plan, chk, s.fps)
                val rf = dir.createFile("text/csv", vname.substringBeforeLast('.') + "_report.csv")
                    ?: throw java.io.IOException("could not create the report file there")
                ctx.contentResolver.openOutputStream(rf.uri, "w")?.use { o ->
                    o.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    o.write(csv.toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException("could not write the report file")
                val (used, unused) = ReportCsv.usage(plan, s.images)
                _export.value = ExportUi(
                    result = ExportSummary(
                        vname, rf.name ?: "report.csv", out.frames, out.frames.toDouble() / s.fps, out.seconds,
                        used, s.images.size, unused, out.audio.lufs, out.audio.clicks,
                    ),
                )
            } catch (e: OutOfMemoryError) {
                _export.value = ExportUi(error = "Not enough memory to export.")
            } catch (e: Exception) {
                _export.value = ExportUi(error = "Export failed: ${e.message}")
            } finally {
                work.deleteRecursively()
            }
        }
    }

    // ---------- state helpers ----------

    private fun recompute(s: UiState): UiState {
        var chk = if (s.masterCues.isNotEmpty())
            Checker.check(s.masterCues, s.images, s.clips, s.corrections) else null
        val core = s.core
        var rep: TimingReport? = null
        if (chk != null && core != null) {
            val total = maxOf(s.masterCues.last().end, s.audioSeconds ?: 0.0)
            val frames = Timing.frameBounds(core.starts, total, s.fps)
            val noMatch = core.ratio != null && core.ratio < 0.40
            rep = TimingReport(
                starts = core.starts, frames = frames, ratio = core.ratio,
                estimated = if (core.ratio == null) emptyList() else core.estimated,
                shifts = if (core.ratio == null) emptyList() else Timing.biggestShifts(s.masterCues, core.starts),
                shortSlides = Timing.shortSlides(core.starts, total, chk.covered),
                totalSeconds = total, noMatch = noMatch,
            )
            if (noMatch) chk = chk.copy(
                errors = chk.errors + "the two SRT files don't look like the same script."
            )
        }
        return s.copy(check = chk, timing = rep)
    }

    /** Slide start times only change when a caption file changes, so they are cached in the state. */
    private fun coreFor(master: List<Cue>, original: List<Cue>): TimingResult? =
        if (master.isEmpty()) null
        else Timing.slideStarts(master, if (original.isEmpty()) null else original)

    private fun audioLength(uri: String): Double? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.parse(uri))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    fun setFps(fps: Int) = change { it.copy(fps = fps) }

    private fun change(f: (UiState) -> UiState) {
        _state.update { recompute(f(it)) }
    }

    private fun work(message: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            change { it.copy(busy = message, message = null, notice = null) }
            try {
                block()
            } catch (e: Exception) {
                change { it.copy(message = "Something went wrong: ${e.message}") }
            }
            change { it.copy(busy = null) }
        }
    }

    // ---------- file helpers ----------

    private fun info(uri: Uri): FileInfo {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // keep the fallback name
        }
        return FileInfo(name, uri.toString(), size)
    }

    private fun readBytes(uri: String): ByteArray = try {
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }

    private fun readText(f: FileInfo): String = String(readBytes(f.uri), Charsets.UTF_8)

    private fun mergeImages(old: List<ImageEntry>, new: List<ImageEntry>): List<ImageEntry> =
        old.filter { o -> new.none { it.name.equals(o.name, ignoreCase = true) } } + new

    private fun extractZip(zipUri: String): List<ImageEntry> {
        val dir = File(ctx.cacheDir, "imgs/z${System.currentTimeMillis()}")
        dir.mkdirs()
        val out = ArrayList<ImageEntry>()
        val used = HashSet<String>()
        ctx.contentResolver.openInputStream(Uri.parse(zipUri))?.use { ins ->
            ZipInputStream(ins).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val entryName = e.name
                    if (!e.isDirectory && !isJunkName(entryName) && isImageName(entryName)) {
                        var base = entryName.substringAfterLast('/').substringAfterLast('\\')
                        if (!used.add(base.lowercase())) base = "dup${used.size}_$base"
                        val f = File(dir, base)
                        FileOutputStream(f).use { zin.copyTo(it) }
                        out.add(ImageEntry(base, Uri.fromFile(f).toString()))
                    }
                    e = zin.nextEntry
                }
            }
        }
        return out
    }

    private fun docImages(files: List<DocumentFile>): List<ImageEntry> =
        files.filter { it.isFile && isImageName(it.name ?: "") && !isJunkName(it.name ?: "") }
            .map { ImageEntry(it.name ?: "", it.uri.toString()) }

    private fun countImages(dir: DocumentFile): Int {
        var n = 0
        for (k in dir.listFiles()) {
            if (k.isDirectory) n += countImages(k)
            else if (isImageName(k.name ?: "") && !isJunkName(k.name ?: "")) n++
        }
        return n
    }


    // ---------- one input for everything ----------

    private fun isKnownName(n: String) =
        isImageName(n) || isVideoName(n) || isAudioName(n) || isSrtName(n) || extOf(n) == "txt"

    /** Unpacks every useful file of a ZIP into the cache (pictures, SRT, text, audio, video). */
    private fun extractBundleZip(zipUri: String): List<FileInfo> {
        val dir = File(ctx.cacheDir, "bundle/z${System.nanoTime()}")
        dir.mkdirs()
        val out = ArrayList<FileInfo>()
        val used = HashSet<String>()
        ctx.contentResolver.openInputStream(Uri.parse(zipUri))?.use { ins ->
            ZipInputStream(ins).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val entryName = e.name
                    if (!e.isDirectory && !isJunkName(entryName) && isKnownName(entryName)) {
                        var base = entryName.substringAfterLast('/').substringAfterLast('\\')
                        if (!used.add(base.lowercase())) base = "dup${used.size}_$base"
                        val f = File(dir, base)
                        FileOutputStream(f).use { zin.copyTo(it) }
                        out.add(FileInfo(base, Uri.fromFile(f).toString(), f.length()))
                    }
                    e = zin.nextEntry
                }
            }
        }
        return out
    }

    private fun walkFolder(dir: DocumentFile, depth: Int, out: MutableList<FileInfo>) {
        for (k in dir.listFiles()) {
            val nm = k.name ?: continue
            if (isJunkName(nm)) continue
            if (k.isDirectory) {
                if (depth < 4) walkFolder(k, depth + 1, out)
            } else if (extOf(nm) == "zip") {
                out.addAll(extractBundleZip(k.uri.toString()))
            } else if (isKnownName(nm)) {
                out.add(FileInfo(nm, k.uri.toString(), k.length()))
            }
        }
    }

    /** Any mix of files and ZIP bundles. */
    fun addAny(uris: List<Uri>) = work("Reading your files...") {
        val found = ArrayList<FileInfo>()
        var skipped = 0
        for (u in uris) {
            val fi = info(u)
            if (isJunkName(fi.name)) continue
            if (extOf(fi.name) == "zip") found.addAll(extractBundleZip(fi.uri))
            else if (isKnownName(fi.name)) found.add(fi)
            else skipped++
        }
        ingest(found, skipped)
    }

    /** A folder with anything in it, including sub-folders and ZIP files. */
    fun addFolderAny(tree: Uri) = work("Looking through the folder...") {
        val root = DocumentFile.fromTreeUri(ctx, tree)
        if (root == null) {
            change { it.copy(message = "Could not open that folder.") }
            return@work
        }
        val found = ArrayList<FileInfo>()
        walkFolder(root, 0, found)
        ingest(found, 0)
    }

    /** Sorts found files into their fields and keeps what was already there when nothing new replaces it. */
    private fun ingest(found: List<FileInfo>, skipped: Int) {
        val st = _state.value
        val imgs = found.filter { isImageName(it.name) }.map { ImageEntry(it.name, it.uri) }
        val vids = found.filter { isVideoName(it.name) }.map { ClipEntry(it.name, it.uri) }
        val auds = found.filter { isAudioName(it.name) }
        val srtsNew = found.filter { isSrtName(it.name) }
        val txts = found.filter { extOf(it.name) == "txt" }

        var click = st.click
        var audio = st.audio
        var audioLen = st.audioSeconds
        val c = auds.firstOrNull { it.name.lowercase().contains("click") }
        if (c != null) click = c
        val v = auds.filter { !it.name.lowercase().contains("click") }.maxByOrNull { it.size }
        if (v != null) { audio = v; audioLen = audioLength(v.uri) }

        var master = st.master
        var original = st.original
        var mCues = st.masterCues
        var oCues = st.originalCueList
        if (srtsNew.isNotEmpty()) {
            val pool = (srtsNew + listOfNotNull(st.master, st.original)).distinctBy { it.uri }
            val g = Roles.guess(pool) { readText(it) }
            master = g.master
            mCues = if (g.master != null) Srt.parseBytes(readBytes(g.master.uri)) else emptyList()
            original = g.original
            oCues = if (g.original != null) Srt.parseBytes(readBytes(g.original.uri)) else emptyList()
        }
        val sc = txts.firstOrNull { it.name.lowercase().contains("script") } ?: txts.maxByOrNull { it.size }
        val script = sc ?: st.script
        val core = coreFor(mCues, oCues)

        val parts = ArrayList<String>()
        if (srtsNew.isNotEmpty()) {
            if (master != null) parts.add("master SRT")
            if (original != null) parts.add("original SRT")
        }
        if (sc != null) parts.add("script")
        if (v != null) parts.add("voice-over")
        if (c != null) parts.add("click sound")
        if (imgs.isNotEmpty()) parts.add("${imgs.size} picture(s)")
        if (vids.isNotEmpty()) parts.add("${vids.size} scene clip(s)")
        val noticeText: String? =
            if (parts.isEmpty()) null
            else "Found: " + parts.joinToString(", ") + "." + (if (skipped > 0) " Ignored $skipped file(s) of other types." else "")
        val warn: String? =
            if (parts.isEmpty()) "Nothing usable found" + (if (skipped > 0) " ($skipped file(s) of other types ignored)." else ".")
            else null
        change {
            it.copy(
                master = master, masterCues = mCues, original = original, originalCueList = oCues, core = core,
                audio = audio, audioSeconds = audioLen, click = click, script = script,
                images = mergeImages(it.images, imgs),
                clips = it.clips.filter { o -> vids.none { n -> n.name.equals(o.name, ignoreCase = true) } } + vids,
                notice = noticeText, message = warn,
            )
        }
    }

    fun setScript(uri: Uri) = work("Reading script...") {
        val fi = info(uri)
        change { it.copy(script = fi) }
    }

    fun clearScript() = change { it.copy(script = null) }

    /** Forget everything and delete the unpacked copies. */
    fun resetAll() {
        File(ctx.cacheDir, "imgs").deleteRecursively()
        File(ctx.cacheDir, "bundle").deleteRecursively()
        _state.value = UiState(fps = _state.value.fps)
        _preview.value = PreviewState()
    }

    // ---------- single files ----------

    fun setMaster(uri: Uri) = work("Reading master SRT...") {
        val fi = info(uri)
        val cues = Srt.parseBytes(readBytes(fi.uri))
        val core = coreFor(cues, _state.value.originalCueList)
        change {
            it.copy(
                master = fi, masterCues = cues, core = core,
                message = if (cues.isEmpty()) "No captions found in ${fi.name}." else null,
            )
        }
    }

    fun setOriginal(uri: Uri) = work("Reading original SRT...") {
        val fi = info(uri)
        val list = Srt.parseBytes(readBytes(fi.uri))
        val core = coreFor(_state.value.masterCues, list)
        change {
            it.copy(
                original = fi, originalCueList = list, core = core,
                message = if (list.isEmpty()) "No captions found in ${fi.name}. Master times will be used." else null,
            )
        }
    }

    fun clearOriginal() {
        val core = coreFor(_state.value.masterCues, emptyList())
        change { it.copy(original = null, originalCueList = emptyList(), core = core) }
    }

    fun setAudio(uri: Uri) = work("Reading voice-over...") {
        val fi = info(uri)
        val len = audioLength(fi.uri)
        change {
            it.copy(
                audio = fi, audioSeconds = len,
                message = if (len == null) "Could not read the length of ${fi.name}." else null,
            )
        }
    }

    fun setClick(uri: Uri) = work("Reading click sound...") {
        val fi = info(uri)
        change { it.copy(click = fi) }
    }

    fun clearClick() = change { it.copy(click = null) }

    // ---------- images ----------

    fun addImages(uris: List<Uri>) = work("Adding images...") {
        val new = uris.map { info(it) }
            .filter { isImageName(it.name) && !isJunkName(it.name) }
            .map { ImageEntry(it.name, it.uri) }
        change { it.copy(images = mergeImages(it.images, new)) }
    }

    fun addZip(uri: Uri) = work("Unpacking ZIP...") {
        val new = extractZip(uri.toString())
        change {
            it.copy(
                images = mergeImages(it.images, new),
                message = if (new.isEmpty()) "No pictures found inside the ZIP." else null,
            )
        }
    }

    fun addImageFolder(uri: Uri) = work("Reading folder...") {
        val root = DocumentFile.fromTreeUri(ctx, uri)
        val new = if (root == null) emptyList() else docImages(root.listFiles().toList())
        change {
            it.copy(
                images = mergeImages(it.images, new),
                message = if (new.isEmpty()) "No pictures found directly in that folder." else null,
            )
        }
    }

    fun clearImages() {
        change { it.copy(images = emptyList()) }
    }

    // ---------- scene clips ----------

    fun addClips(uris: List<Uri>) = work("Adding scene clips...") {
        val new = uris.map { info(it) }
            .filter { isVideoName(it.name) && !isJunkName(it.name) }
            .map { ClipEntry(it.name, it.uri) }
        change { s -> s.copy(clips = s.clips.filter { o -> new.none { it.uri == o.uri } } + new) }
    }

    fun removeClip(uri: String) =
        change { it.copy(clips = it.clips.filter { c -> c.uri != uri }, corrections = it.corrections - uri) }

    fun setCorrection(uri: String, first: Int, last: Int) =
        change { it.copy(corrections = it.corrections + (uri to Pair(first, last))) }

    // ---------- whole project folder ----------

    fun pickProject(treeUri: Uri) = work("Looking through the folder...") {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
        if (root == null) {
            change { it.copy(message = "Could not open that folder.") }
            return@work
        }
        val kids = root.listFiles().toList()
        val files = kids.filter { it.isFile }
            .map { FileInfo(it.name ?: "", it.uri.toString(), it.length()) }
            .filter { it.name.isNotEmpty() && !isJunkName(it.name) }
        val g = Roles.guess(files) { readText(it) }

        // images: files in the folder, else the sub-folder with most pictures, else the biggest ZIP
        var imgs = docImages(kids)
        if (imgs.isEmpty()) {
            val best = kids.filter { it.isDirectory }
                .map { Pair(it, countImages(it)) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
            if (best != null) {
                imgs = docImages(best.first.listFiles().toList())
            } else {
                val zip = files.filter { extOf(it.name) == "zip" }.maxByOrNull { it.size }
                if (zip != null) imgs = extractZip(zip.uri)
            }
        }
        val masterCues = if (g.master != null) Srt.parseBytes(readBytes(g.master.uri)) else emptyList()
        val origList = if (g.original != null) Srt.parseBytes(readBytes(g.original.uri)) else emptyList()
        val core = coreFor(masterCues, origList)
        val audioLen = if (g.audio != null) audioLength(g.audio.uri) else null
        change {
            it.copy(
                master = g.master, masterCues = masterCues,
                original = g.original, originalCueList = origList, core = core,
                audio = g.audio, audioSeconds = audioLen, click = g.click,
                images = imgs,
                clips = g.clips.map { f -> ClipEntry(f.name, f.uri) },
                corrections = emptyMap(),
                message = if (g.master == null || g.audio == null)
                    "Folder read, but I could not find " +
                        listOfNotNull(
                            if (g.master == null) "a master SRT" else null,
                            if (g.audio == null) "a voice-over" else null,
                        ).joinToString(" and ") + ". Choose it by hand below."
                else null,
            )
        }
    }
}
