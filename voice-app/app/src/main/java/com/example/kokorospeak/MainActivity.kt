package com.example.kokorospeak

import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var engine: TtsEngine
    private val voices = Voices.all

    private lateinit var inputText: EditText
    private lateinit var voiceSpinner: Spinner
    private lateinit var mixSpinner: Spinner
    private lateinit var mixSeek: SeekBar
    private lateinit var mixLabel: TextView
    private lateinit var randomBtn: Button
    private lateinit var speedSeek: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var speakBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    @Volatile
    private var lastAudio: List<FloatArray> = emptyList()
    private var lastRate = 24000
    private var speaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        mixSpinner = findViewById(R.id.mixSpinner)
        mixSeek = findViewById(R.id.mixSeek)
        mixLabel = findViewById(R.id.mixLabel)
        randomBtn = findViewById(R.id.randomBtn)
        speedSeek = findViewById(R.id.speedSeek)
        speedLabel = findViewById(R.id.speedLabel)
        speakBtn = findViewById(R.id.speakBtn)
        stopBtn = findViewById(R.id.stopBtn)
        saveBtn = findViewById(R.id.saveBtn)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        engine = TtsEngine(applicationContext)

        val voiceLabels = voices.map { it.label }
        voiceSpinner.adapter = adapterOf(voiceLabels)
        mixSpinner.adapter = adapterOf(listOf("None (single voice)") + voiceLabels)

        mixSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                mixSeek.isEnabled = pos > 0
                updateMixLabel()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        mixSeek.setOnSeekBarChangeListener(simpleSeekListener { updateMixLabel() })
        speedSeek.setOnSeekBarChangeListener(simpleSeekListener {
            speedLabel.text = "Speed: ${"%.1f".format(currentSpeed())}x"
        })

        randomBtn.setOnClickListener { pickRandomFemaleMaleMix() }
        speakBtn.setOnClickListener { onSpeak() }
        stopBtn.setOnClickListener { engine.requestStop() }
        saveBtn.setOnClickListener { onSave() }

        loadModel()
    }

    private fun adapterOf(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun simpleSeekListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    /** 0..15 on the slider maps to 0.5x..2.0x speech speed. */
    private fun currentSpeed(): Float = 0.5f + speedSeek.progress * 0.1f

    /** 0..20 on the slider maps to 0..100% in steps of 5. */
    private fun currentMixPercent(): Int = mixSeek.progress * 5

    private fun updateMixLabel() {
        val pos = mixSpinner.selectedItemPosition
        mixLabel.text = if (pos <= 0) {
            "Mix: off"
        } else {
            val name = voices[pos - 1].label.substringBefore(" (")
            "Mix: ${currentMixPercent()}% $name"
        }
    }

    /** Picks a random female voice as the main voice and a random male voice to blend in at 50%. */
    private fun pickRandomFemaleMaleMix() {
        val females = voices.indices.filter { voices[it].female }
        val males = voices.indices.filter { !voices[it].female }
        voiceSpinner.setSelection(females[Random.nextInt(females.size)])
        mixSpinner.setSelection(males[Random.nextInt(males.size)] + 1)
        mixSeek.progress = 10
        updateMixLabel()
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun loadModel() {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                engine.load { setStatus(it) }
                setStatus("Ready. Type some text and tap Speak.")
                runOnUiThread {
                    progress.visibility = View.GONE
                    speakBtn.isEnabled = true
                }
            } catch (t: Throwable) {
                setStatus("Could not load the model:\n${t.message ?: t.javaClass.simpleName}")
                runOnUiThread { progress.visibility = View.GONE }
            }
        }
    }

    private fun onSpeak() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type some text first", Toast.LENGTH_SHORT).show()
            return
        }
        if (speaking || !engine.isReady) return

        speaking = true
        speakBtn.isEnabled = false
        saveBtn.isEnabled = false
        progress.visibility = View.VISIBLE

        val sidA = voices[voiceSpinner.selectedItemPosition].sid
        val mixPos = mixSpinner.selectedItemPosition
        val sidB: Int? = if (mixPos > 0) voices[mixPos - 1].sid else null
        val mixPercent = currentMixPercent()
        val speed = currentSpeed()

        worker.execute {
            val collected = ArrayList<FloatArray>()
            try {
                val speakerId = engine.prepareVoice(sidA, sidB, mixPercent) { setStatus(it) }
                engine.speak(text, speakerId, speed, collected) { setStatus(it) }
                setStatus("Done.")
            } catch (t: Throwable) {
                setStatus("Error: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                lastAudio = collected
                lastRate = engine.sampleRate
                speaking = false
                runOnUiThread {
                    progress.visibility = View.GONE
                    speakBtn.isEnabled = engine.isReady
                    saveBtn.isEnabled = collected.isNotEmpty()
                }
            }
        }
    }

    private fun onSave() {
        val audio = lastAudio
        if (audio.isEmpty()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        val rate = lastRate
        worker.execute {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "speech_${System.currentTimeMillis()}.wav")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/KokoroSpeak"
                    )
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create the file")
                contentResolver.openOutputStream(uri)?.use { WavWriter.write(it, audio, rate) }
                    ?: throw IllegalStateException("Could not open the file for writing")
                setStatus("Saved to Downloads/KokoroSpeak")
            } catch (t: Throwable) {
                setStatus("Save failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.requestStop()
        worker.execute { engine.release() }
        worker.shutdown()
    }
}
