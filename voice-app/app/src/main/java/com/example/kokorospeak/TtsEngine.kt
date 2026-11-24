package com.example.kokorospeak

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps the sherpa-onnx Kokoro model: unpacks it from assets on first run, loads it,
 * mixes two voices on request, and speaks text sentence by sentence so sound starts quickly.
 *
 * How mixing works: every voice in voices.bin is a block of style numbers. A blend is the
 * weighted average of two blocks. sherpa-onnx cannot do this itself, so we write the blended
 * numbers over voice A's slot in a working copy of voices.bin and reload the model with it.
 */
class TtsEngine(private val context: Context) {

    companion object {
        /** Folder name inside app/src/main/assets/ that holds the Kokoro model files. */
        const val MODEL_DIR = "kokoro-multi-lang-v1_0"
    }

    private var tts: OfflineTts? = null

    private lateinit var modelDir: File
    private lateinit var originalVoices: File
    private lateinit var mixVoices: File
    private var currentVoicesFile: File? = null
    private var currentMixKey: String? = null
    private var baseSpeakerCount = 0

    @Volatile
    private var stopRequested = false

    val isReady: Boolean get() = tts != null
    val sampleRate: Int get() = tts?.sampleRate() ?: 24000

    // ---------------------------------------------------------------- loading

    /** Call on a background thread. Takes a while the first time (copies ~340 MB). */
    fun load(onStatus: (String) -> Unit) {
        modelDir = File(context.filesDir, MODEL_DIR)
        originalVoices = File(modelDir, "voices.bin")
        mixVoices = File(context.filesDir, "voices_mix.bin")
        val marker = File(modelDir, ".copied")

        if (!marker.exists()) {
            val top = context.assets.list(MODEL_DIR) ?: emptyArray()
            if (top.isEmpty()) {
                throw IllegalStateException(
                    "Model files not found in assets/$MODEL_DIR. " +
                        "See SETUP.md, step 5 (add the Kokoro model folder), then rebuild."
                )
            }
            onStatus("First run: unpacking voice model (one time only)...")
            copyAssetDir(MODEL_DIR, modelDir)
            marker.writeText("ok")
        }

        onStatus("Loading voice model...")
        createTts(originalVoices)
        baseSpeakerCount = tts?.numSpeakers() ?: 0
        currentMixKey = null
    }

    private fun createTts(voicesFile: File) {
        tts?.release()
        tts = null

        // The multi-language model lists its lexicon files separated by commas.
        val lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
            .map { File(modelDir, it) }
            .filter { it.exists() }
            .joinToString(",") { it.absolutePath }

        val kokoro = OfflineTtsKokoroModelConfig(
            model = File(modelDir, "model.onnx").absolutePath,
            voices = voicesFile.absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
            lexicon = lexicon,
            lengthScale = 1.0f,
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = kokoro,
                numThreads = minOf(4, Runtime.getRuntime().availableProcessors()),
                debug = false,
                provider = "cpu",
            ),
        )
        // No AssetManager: we pass real file paths because the files were copied out of the APK.
        tts = OfflineTts(config = config)
        currentVoicesFile = voicesFile
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            } catch (e: FileNotFoundException) {
                dest.mkdirs() // it was an empty folder
            }
            return
        }
        dest.mkdirs()
        for (name in children) {
            copyAssetDir("$assetPath/$name", File(dest, name))
        }
    }

    // ----------------------------------------------------------- voice mixing

    /**
     * Gets the engine ready for the chosen voice(s) and returns the speaker id to speak with.
     * Call on the background thread before [speak]. Switching to or between mixes reloads the
     * model, which takes a few seconds; repeating the same voice or mix is instant.
     *
     * @param mixPercent how much of voice B to blend in (0-100). Ignored if [sidB] is null.
     */
    fun prepareVoice(
        sidA: Int,
        sidB: Int?,
        mixPercent: Int,
        onStatus: (String) -> Unit,
    ): Int {
        val blend = sidB != null && sidB != sidA && mixPercent > 0

        if (!blend) {
            if (currentVoicesFile != originalVoices) {
                onStatus("Switching to a single voice (a few seconds)...")
                createTts(originalVoices)
            }
            currentMixKey = null
            return sidA
        }

        val key = "$sidA-$sidB-$mixPercent"
        if (key != currentMixKey || currentVoicesFile != mixVoices) {
            onStatus("Mixing voices (a few seconds)...")
            writeMixedVoices(sidA, sidB!!, mixPercent / 100f)
            createTts(mixVoices)
            currentMixKey = key
        }
        return sidA
    }

    /** Writes voices_mix.bin: a copy of voices.bin where voice A's block becomes the blend. */
    private fun writeMixedVoices(sidA: Int, sidB: Int, weightB: Float) {
        val speakers = baseSpeakerCount
        val total = originalVoices.length()
        if (speakers <= 0 || total % speakers != 0L) {
            throw IllegalStateException("Unexpected voices.bin layout, so voices cannot be mixed")
        }
        if (sidA >= speakers || sidB >= speakers) {
            throw IllegalStateException("Voice id is out of range for this model")
        }
        val blockBytes = (total / speakers).toInt()

        val a = ByteArray(blockBytes)
        val b = ByteArray(blockBytes)
        RandomAccessFile(originalVoices, "r").use { f ->
            f.seek(sidA.toLong() * blockBytes)
            f.readFully(a)
            f.seek(sidB.toLong() * blockBytes)
            f.readFully(b)
        }

        val fa = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val mixed = ByteBuffer.allocate(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        val fm = mixed.asFloatBuffer()
        val weightA = 1f - weightB
        for (i in 0 until blockBytes / 4) {
            fm.put(i, weightA * fa.get(i) + weightB * fb.get(i))
        }

        originalVoices.copyTo(mixVoices, overwrite = true)
        RandomAccessFile(mixVoices, "rw").use { f ->
            f.seek(sidA.toLong() * blockBytes)
            f.write(mixed.array())
        }
    }

    // --------------------------------------------------------------- speaking

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Generates and plays [text]. Blocks until playback ends or [requestStop] is called.
     * Every generated chunk is added to [collect] so it can be saved as a WAV afterwards.
     * Call on a background thread, after [prepareVoice].
     */
    fun speak(
        text: String,
        speakerId: Int,
        speed: Float,
        collect: MutableList<FloatArray>,
        onStatus: (String) -> Unit,
    ) {
        val engine = tts ?: throw IllegalStateException("Model is not loaded yet")
        stopRequested = false

        val rate = engine.sampleRate()
        val minBuffer = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(rate)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, rate * 4)) // about 1 second of float audio
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        var framesWritten = 0L
        try {
            track.play()
            val sentences = splitSentences(text)
            for ((index, sentence) in sentences.withIndex()) {
                if (stopRequested) break
                onStatus("Speaking part ${index + 1} of ${sentences.size}...")

                val audio = engine.generate(sentence, speakerId, speed)
                if (stopRequested) break

                val samples = audio.samples
                collect.add(samples)

                // Write in ~0.1 s chunks so Stop reacts quickly.
                val chunk = rate / 10
                var offset = 0
                while (offset < samples.size && !stopRequested) {
                    val count = minOf(chunk, samples.size - offset)
                    val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                    offset += written
                    framesWritten += written
                }
            }

            if (stopRequested) {
                track.pause()
                track.flush()
            } else {
                // Wait for the queued audio to finish playing (with a safety timeout).
                val deadline = System.currentTimeMillis() + 120_000
                while (!stopRequested &&
                    track.playbackHeadPosition < framesWritten &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(50)
                }
            }
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
    }

    /** Splits on sentence endings so the first sentence plays while the rest is still generating. */
    private fun splitSentences(text: String): List<String> {
        val parts = text.trim()
            .split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text.trim()) else parts
    }

    fun release() {
        tts?.release()
        tts = null
    }
}
