package com.auto.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where the AI backend lives. The token is entered by the user in the app (never compiled in). http:// is accepted only for private/local addresses. */
data class BackendConfig(val baseUrl: String, val token: String) {
    val url: String get() = baseUrl.trim().trimEnd('/')
    fun validate() {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw PipelineError("Backend address must start with https:// (or http:// for a server on your own network).")
        if (token.isBlank()) throw PipelineError("Backend token is empty. Enter the token you set as AUTO_API_TOKEN on the server.")
        val host = URL(url).host
        val priv = host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        if (url.startsWith("http://") && !priv) throw PipelineError("Plain http:// is only allowed for servers on your own network. Use https:// for '$host' so your token and audio are encrypted.")
    }
}

/** What the backend found in the narration. words are the VERIFIED spoken words with real start/end seconds. */
class BackendResult(val words: List<TimedWord>, val speech: List<Pair<Double, Double>>, val silences: List<Pair<Double, Double>>, val warnings: List<String>,
                    val alignment: String, val captionMatch: Double?, val transcript: String, val duration: Double, val uploaded: Boolean)

/** Step 10. Plain HttpURLConnection (works on Android and JVM). Re-sends nothing the server already has; retries network hiccups; every failure becomes a readable PipelineError. */
class BackendClient(private val cfg: BackendConfig, private val connectMs: Int = 15000, private val readMs: Int = 60000, private val retries: Int = 3) {
    private fun open(path: String, method: String): HttpURLConnection {
        val c = URL(cfg.url + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = connectMs; c.readTimeout = readMs
        c.setRequestProperty("Authorization", "Bearer ${cfg.token}")
        return c
    }
    private fun explain(code: Int, body: String): PipelineError = PipelineError(when (code) {
        401 -> "The server rejected the token (401). Check the token in settings matches AUTO_API_TOKEN on the server."
        413 -> "The server refused the file: too large."
        else -> "Server error $code: ${body.take(200)}"
    })
    private fun <T> withRetry(what: String, f: () -> T): T {
        var last: IOException? = null
        for (k in 0 until retries) {
            try { return f() } catch (e: IOException) { last = e; if (Thread.currentThread().isInterrupted) break; try { Thread.sleep(500L * (k + 1) * (k + 1)) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break } }
        }
        throw PipelineError("Cannot reach the analysis server while $what (${cfg.url}): ${last?.message ?: "no connection"}. Check the address and your internet connection.")
    }
    private fun text(c: HttpURLConnection): String = (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""

    fun health(): Boolean = withRetry("checking the server") { val c = open("/v1/health", "GET"); try { c.responseCode == 200 } finally { c.disconnect() } }

    /** true if the file had to be uploaded, false if the server already had these exact bytes. */
    fun ensureUploaded(file: File, sha: String, onProgress: (Double) -> Unit = {}): Boolean {
        val has = withRetry("checking for the audio") { val c = open("/v1/audio/$sha", "HEAD"); try { c.responseCode.also { if (it == 401) throw explain(it, "") } == 200 } finally { c.disconnect() } }
        if (has) return false
        withRetry("uploading the audio") {
            val c = open("/v1/audio/$sha", "PUT"); c.doOutput = true; c.setFixedLengthStreamingMode(file.length()); c.readTimeout = 600000
            try {
                var sent = 0L
                file.inputStream().use { i -> c.outputStream.use { o -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); sent += n; onProgress(sent.toDouble() / file.length()) } } }
                if (c.responseCode != 200) throw explain(c.responseCode, text(c))
            } finally { c.disconnect() }
        }
        return true
    }

    fun analyze(audio: File, script: String = "", captions: String = "", language: String? = null, pollMs: Long = 2000, timeoutMs: Long = 3 * 3600_000L,
                onStatus: (String, Double) -> Unit = { _, _ -> }): BackendResult {
        cfg.validate()
        onStatus("checking server", 0.0)
        if (!health()) throw PipelineError("The analysis server answered but is not healthy.")
        onStatus("uploading audio", 0.02)
        val sha = Validation.sha256(audio.path)
        val uploaded = ensureUploaded(audio, sha) { onStatus("uploading audio", 0.02 + 0.1 * it) }
        val body = "{\"audio_sha256\":${jsonQuote(sha)},\"script_text\":${jsonQuote(script)},\"caption_text\":${jsonQuote(captions)}" + (language?.let { ",\"language\":${jsonQuote(it)}" } ?: "") + "}"
        val jid = withRetry("starting the analysis") {
            val c = open("/v1/jobs", "POST"); c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            try { c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val t = text(c); if (c.responseCode != 200) throw explain(c.responseCode, t)
                (MiniJson(t).parse() as Map<*, *>)["job_id"] as String
            } finally { c.disconnect() }
        }
        val t0 = System.currentTimeMillis()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw PipelineError("Analysis cancelled.")
            if (System.currentTimeMillis() - t0 > timeoutMs) throw PipelineError("The analysis took longer than ${timeoutMs / 60000} minutes and was stopped. Your job id is $jid; press Analyse again to continue waiting for it.")
            val d = withRetry("waiting for the analysis") {
                val c = open("/v1/jobs/$jid", "GET")
                try { val t = text(c); if (c.responseCode == 404) throw PipelineError("The server lost this job (it may have been restarted). Press Analyse again."); if (c.responseCode != 200) throw explain(c.responseCode, t); MiniJson(t).parse() as Map<*, *> } finally { c.disconnect() }
            }
            when (d["status"]) {
                "done" -> return parseResult(d["result"] as Map<*, *>, uploaded)
                "error" -> throw PipelineError("Audio analysis failed on the server: ${d["error"]}")
                else -> onStatus((d["stage"] as? String) ?: "working", 0.12 + 0.85 * ((d["progress"] as? Double) ?: 0.0))
            }
            try { Thread.sleep(pollMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw PipelineError("Analysis cancelled.") }
        }
    }

    companion object {
        private fun pairs(x: Any?) = (x as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.let { Pair((it[0] as Number).toDouble(), (it[1] as Number).toDouble()) } } ?: emptyList()
        fun parseResult(r: Map<*, *>, uploaded: Boolean = false): BackendResult {
            val words = ArrayList<TimedWord>()
            for (sg in (r["segments"] as? List<*> ?: emptyList<Any?>())) for (w in ((sg as Map<*, *>)["words"] as? List<*> ?: emptyList<Any?>())) {
                w as Map<*, *>; val t = (w["word"] ?: "").toString().trim()
                if (t.isNotEmpty()) words.add(TimedWord(t, (w["start"] as Number).toDouble(), (w["end"] as Number).toDouble()))
            }
            if (words.isEmpty()) throw PipelineError("The server returned no spoken words.")
            return BackendResult(words, pairs(r["speech"]), pairs(r["silences"]), (r["warnings"] as? List<*>)?.map { it.toString() } ?: emptyList(), r["alignment"]?.toString() ?: "",
                ((r["caption_diff"] as? Map<*, *>)?.get("ratio") as? Number)?.toDouble(), r["transcript"]?.toString() ?: "", (r["duration"] as? Number)?.toDouble() ?: 0.0, uploaded)
        }
    }
}
