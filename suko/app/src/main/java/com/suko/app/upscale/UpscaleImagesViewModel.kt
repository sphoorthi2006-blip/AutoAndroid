package com.suko.app.upscale

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suko.app.intake.IntakeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpscaleDone(
    val summary: String,
    val where: String,
    val outputs: List<String>,
    val notSaved: List<String>,
    val note: String?,
)

data class UpscaleImagesUi(
    val busy: Boolean = false,
    val label: String = "",
    val progress: Double = 0.0,
    val result: UpscaleDone? = null,
    val error: String? = null,
)

/**
 * Upscale Images: the pictures found by intake (files, ZIPs, folders) are upscaled one at a time into the shared cache,
 * then written as PNG files or as one ZIP per input into a "Bezalel upscaled" folder inside the folder the user chose.
 * The cache is the same one the Auto Editor uses, so a picture upscaled here is not upscaled again there (and a
 * cancelled run continues where it stopped).
 */
class UpscaleImagesViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication<Application>()
    private val _ui = MutableStateFlow(UpscaleImagesUi())
    val ui: StateFlow<UpscaleImagesUi> = _ui
    @Volatile private var cancel = false
    private var job: Job? = null

    fun cancel() { cancel = true }

    fun start(entries: List<IntakeEntry>, tree: Uri, mode: OutputMode, removeMark: Boolean) {
        if (_ui.value.busy || entries.isEmpty()) return
        cancel = false
        _ui.value = UpscaleImagesUi(busy = true, label = "Checking pictures...")
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = UpscaleSettings(removeMark = removeMark)
                val cache = UpscaleCache(File(ctx.cacheDir, "upscale"))
                val plan = UpscalePlan.build(entries, cache, settings)
                val ep = ExportProgress(plan.todo.size, maxOf(1.0, plan.total * 0.3))
                var batch: BatchResult? = null
                if (plan.todo.isNotEmpty()) {
                    val runner = UpscaleRunner(cache, AiEngine(ctx, removeMark), ClassicEngine(ctx, removeMark)) { System.gc() }
                    val b = runner.run(
                        plan, { cancel },
                        onProgress = { r ->
                            val p = ep.overall(ExportProgress.Phase.UPSCALE, ep.upscaleFraction(r.picturesDone, r.tileDone, r.tileTotal))
                            _ui.update { it.copy(label = ep.label(ExportProgress.Phase.UPSCALE, r.picturesDone), progress = p) }
                        },
                        onPicture = { r, sec -> if (r.outcome == Outcome.OK) ep.pictureDone(sec) },
                    )
                    if (b.cancelled) {
                        _ui.value = UpscaleImagesUi(error = "Cancelled. Finished pictures are kept, so starting again continues where it stopped.")
                        return@launch
                    }
                    batch = b
                }
                val done = plan.jobs.filter { cache.has(it.key) }
                val notSaved = plan.jobs.filter { !cache.has(it.key) }.map { it.entry.path }
                _ui.update { it.copy(label = "Saving pictures...", progress = ep.overall(ExportProgress.Phase.RENDER, 0.0)) }
                val items = UpscaleBatch.layout(done)
                val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("could not open the chosen folder")
                val out = root.createDirectory(UpscaleBatch.OUT_FOLDER) ?: throw IOException("could not create the output folder there")
                var written = 0
                val outputs = ArrayList<String>()
                val tick = { _ui.update { u -> u.copy(progress = ep.overall(ExportProgress.Phase.RENDER, written.toDouble() / maxOf(1, items.size))) } }
                if (mode == OutputMode.PNG_FOLDER) {
                    val dirs = HashMap<String, DocumentFile>()
                    fun dirFor(rel: String): DocumentFile {
                        if (rel.isEmpty()) return out
                        return dirs.getOrPut(rel) {
                            val parent = dirFor(rel.substringBeforeLast('/', ""))
                            parent.createDirectory(rel.substringAfterLast('/')) ?: throw IOException("could not create folder $rel")
                        }
                    }
                    for (item in items) {
                        if (cancel) break
                        val dir = dirFor(item.group + (if (item.relPath.contains('/')) "/" + item.relPath.substringBeforeLast('/') else ""))
                        val f = dir.createFile("image/png", item.relPath.substringAfterLast('/')) ?: throw IOException("could not create ${item.relPath}")
                        ctx.contentResolver.openOutputStream(f.uri, "w")?.use { o ->
                            java.io.BufferedOutputStream(o, 1 shl 16).use { b -> UpscaleBatch.cacheFile(cache, item.job).inputStream().use { s -> s.copyTo(b) } }
                        } ?: throw IOException("could not write ${item.relPath}")
                        written++; tick()
                    }
                    outputs += items.map { it.group }.distinct().map { "$it/" }
                } else {
                    for ((group, list) in items.groupBy { it.group }) {
                        if (cancel) break
                        val zf = out.createFile("application/zip", UpscaleBatch.zipName(group)) ?: throw IOException("could not create the ZIP for $group")
                        ctx.contentResolver.openOutputStream(zf.uri, "w")?.use { o ->
                            ZipOutputStream(BufferedOutputStream(o, 1 shl 16)).use { z ->
                                z.setLevel(Deflater.NO_COMPRESSION)   // PNG is already compressed
                                for (item in list) {
                                    z.putNextEntry(ZipEntry(item.relPath))
                                    UpscaleBatch.cacheFile(cache, item.job).inputStream().use { s -> s.copyTo(z) }
                                    z.closeEntry()
                                    written++; tick()
                                }
                            }
                        } ?: throw IOException("could not write the ZIP for $group")
                        outputs += zf.name ?: UpscaleBatch.zipName(group)
                    }
                }
                if (cancel) {
                    _ui.value = UpscaleImagesUi(error = "Cancelled while saving. $written picture(s) were already written to \"${UpscaleBatch.OUT_FOLDER}\".")
                    return@launch
                }
                cache.trimTo(2L shl 30, plan.jobs.map { it.key }.toSet())
                _ui.value = UpscaleImagesUi(
                    result = UpscaleDone(
                        UpscaleBatch.summary(written, plan.total, mode, outputs.size),
                        "${root.name ?: "chosen folder"}/${out.name ?: UpscaleBatch.OUT_FOLDER}",
                        outputs, notSaved, batch?.let { ExportUpscale.note(it) },
                    ),
                )
            } catch (e: OutOfMemoryError) {
                _ui.value = UpscaleImagesUi(error = "Not enough memory.")
            } catch (e: Exception) {
                _ui.value = UpscaleImagesUi(error = "Upscaling failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun reset() { if (!_ui.value.busy) _ui.value = UpscaleImagesUi() }
}
