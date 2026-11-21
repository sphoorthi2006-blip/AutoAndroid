package com.suko.app.web

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** The native methods a page may call. Each takes the page's text payload and returns a JSON object as text. */
typealias NativeMethod = (payload: String) -> String

object NativeMethods {
    /** Only the host itself: "health" and the "size" test helper. The app adds "keepalive" (see PhoneEngine). */
    fun defaults(extra: Map<String, NativeMethod> = emptyMap()): Map<String, NativeMethod> = base() + extra

    private fun base(): Map<String, NativeMethod> = mapOf(
        "health" to { _ -> """{"engine":"${WebConfig.ENGINE}","ready":false}""" },
        "size" to { p -> """{"chars":${p.length}}""" },     // test helper: proves big payloads arrive whole
    )
}

/** Runs native methods and keeps each answer as a file until the page has read it once. */
class BridgeCore(private val methods: Map<String, NativeMethod>, private val dir: File) {
    class Result(val ok: Boolean, val message: String)

    init { dir.mkdirs() }

    fun run(id: String, method: String, payload: String): Result {
        if (!WebRules.safeId(id)) return Result(false, "bad job id")
        val m = methods[method] ?: return Result(false, "unknown method: $method")
        return try {
            File(dir, "$id.json").writeText(m(payload), Charsets.UTF_8)
            Result(true, "")
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** The answer of a finished job, once. A second ask (or an unknown id) gives null. */
    fun take(id: String): ByteArray? {
        if (!WebRules.safeId(id)) return null
        val f = File(dir, "$id.json")
        if (!f.isFile) return null
        val b = f.readBytes()
        f.delete()
        return b
    }

    /** Removes answers nobody read (left from an earlier run). */
    fun cleanLeftovers(): Int = (dir.listFiles() ?: emptyArray()).count { it.delete() }
}

/** Where a saved file goes. [out] is complete only after [commit]; [abort] removes everything. */
interface SaveTarget {
    val out: OutputStream
    val where: String
    fun commit()
    fun abort()
}

/** Saves that arrive as base64 chunks: begin, many chunks, end. */
class SaveRegistry(private val open: (name: String, mime: String) -> SaveTarget) {
    private class Session(val target: SaveTarget, var bytes: Long = 0)
    private val sessions = HashMap<String, Session>()

    @Synchronized fun begin(id: String, name: String, mime: String) {
        require(WebRules.safeId(id)) { "bad save id" }
        require(!sessions.containsKey(id)) { "save already started" }
        sessions[id] = Session(open(WebRules.safeFileName(name), mime.ifBlank { "application/octet-stream" }))
    }

    @Synchronized fun chunk(id: String, base64: String) {
        val s = sessions[id] ?: throw IllegalStateException("no save is open for this id")
        try {
            val b = Base64.getDecoder().decode(base64)
            s.target.out.write(b)
            s.bytes += b.size
        } catch (e: Exception) {
            sessions.remove(id)
            s.target.abort()
            throw e
        }
    }

    /** Finishes the file and returns where it is. */
    @Synchronized fun end(id: String): String {
        val s = sessions.remove(id) ?: throw IllegalStateException("no save is open for this id")
        try { s.target.commit() } catch (e: Exception) { s.target.abort(); throw e }
        return s.target.where
    }

    @Synchronized fun abort(id: String) { sessions.remove(id)?.target?.abort() }
    @Synchronized fun abortAll() { sessions.keys.toList().forEach { abort(it) } }
}

/** One file the app hands to the page (from its own intake). */
class InputFile(val name: String, val mime: String, val size: Long, val open: () -> InputStream)

class InputRegistry(private var files: List<InputFile> = emptyList()) {
    @Synchronized fun set(list: List<InputFile>) { files = list }
    @Synchronized fun get(i: Int): InputFile? = files.getOrNull(i)

    @Synchronized fun listJson(): String = files.joinToString(",", "[", "]") {
        """{"name":${WebRules.jsString(it.name)},"mime":${WebRules.jsString(it.mime)},"size":${it.size}}"""
    }
}

/** Files to give the next web page. Set it, then start the web activity (same process). */
object WebInputs {
    @Volatile var files: List<InputFile> = emptyList()
}
