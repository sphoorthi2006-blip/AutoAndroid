package com.suko.app.svg

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuevideo.app.AudioJob
import com.cuevideo.app.AudioResult
import com.cuevideo.app.FileInfo
import com.suko.app.intake.IntakeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SvgAudioUi(
    val voice: FileInfo? = null,
    val voiceSeconds: Double? = null,
    val click: FileInfo? = null,
    val clicksEnabled: Boolean = true,
    val plan: SvgClickPlan? = null,
    val planError: String? = null,
    val busy: Boolean = false,
    val label: String = "",
    val progress: Double = 0.0,
    val result: AudioResult? = null,
    val error: String? = null,
)

/** SVG Editor audio: optional voice-over, built-in (or custom) click per element, mix test. Same engine as the Auto Editor. */
class SvgAudioViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication<Application>()
    private val _ui = MutableStateFlow(SvgAudioUi())
    val ui: StateFlow<SvgAudioUi> = _ui
    @Volatile private var cancel = false

    private fun info(uri: Uri): FileInfo {
        var name = uri.lastPathSegment ?: "audio"
        var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        return FileInfo(name, uri.toString(), size)
    }

    private fun length(uri: String): Double? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.parse(uri))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        } catch (e: Exception) { null } finally { try { r.release() } catch (e: Exception) { } }
    }

    fun setVoice(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val fi = info(uri)
            val len = length(fi.uri)
            _ui.update { it.copy(voice = fi, voiceSeconds = len, result = null, error = null) }
        }
    }
    fun clearVoice() = _ui.update { it.copy(voice = null, voiceSeconds = null, result = null) }
    fun setClick(uri: Uri) { val fi = info(uri); _ui.update { it.copy(click = fi, result = null) } }
    fun clearClick() = _ui.update { it.copy(click = null, result = null) }
    fun setClicksEnabled(on: Boolean) = _ui.update { it.copy(clicksEnabled = on, result = null) }
    fun cancelMix() { cancel = true }

    /** Re-reads the click times whenever the JSON chosen on the intake screen changes. */
    fun loadJson(entry: IntakeEntry?) {
        if (entry == null) { _ui.update { it.copy(plan = null, planError = null) }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                entry.file?.readText() ?: entry.node!!.open().use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) { _ui.update { it.copy(plan = null, planError = "Could not open the JSON: ${e.message}") }; return@launch }
            val (plan, err) = SvgClicks.parse(text)
            _ui.update { it.copy(plan = plan, planError = err, result = null) }
        }
    }

    /** Mixes voice-over + one click per element into a limited AAC .m4a and copies it to [dest]. */
    fun mix(dest: Uri) {
        val s = _ui.value
        val plan = s.plan ?: return
        if (s.busy || (s.voice == null && !s.clicksEnabled)) return
        cancel = false
        _ui.update { it.copy(busy = true, label = "Starting...", progress = 0.0, result = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(File(ctx.cacheDir, "svg-audio").apply { mkdirs() }, "mix.m4a")
            try {
                val total = maxOf(plan.endSeconds, s.voiceSeconds ?: 0.0)
                val res = AudioJob.run(
                    ctx, s.voice, s.click, s.clicksEnabled, plan.times, total, s.voiceSeconds, tmp,
                    { label, p -> _ui.update { it.copy(label = label, progress = p) } },
                    { cancel },
                )
                if (res == null || cancel) { _ui.update { it.copy(busy = false, error = "Cancelled.") }; return@launch }
                ctx.contentResolver.openOutputStream(dest)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                _ui.update { it.copy(busy = false, result = res) }
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(busy = false, error = "Not enough memory for the audio mix.") }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = "Audio mix failed: ${e.message}") }
            } finally { tmp.delete() }
        }
    }
}
