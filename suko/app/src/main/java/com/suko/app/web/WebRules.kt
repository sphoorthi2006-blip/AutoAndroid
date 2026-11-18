package com.suko.app.web

/** Steps 16-17, pure part (no Android classes, tested on the JVM): which requests the web view may serve, and how. */
object WebConfig {
    /** Reserved name for app-served content. Nothing is ever fetched from it: the app answers every request itself. */
    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"
    /** Everything the web view may load lives under this folder of the app's assets. */
    const val ASSET_ROOT = "web/"
    const val ENGINE = "suko-native"
}

sealed class Route {
    /** A file inside the app's assets, e.g. "web/studio/index.html". */
    class Asset(val path: String) : Route()
    /** The one-time JSON answer of a native job. */
    class Bridge(val id: String) : Route()
    /** File number [index] handed over by the app's intake. */
    class Input(val index: Int) : Route()
    /** Anything else: not ours, answered with 403 and never sent to the network. */
    object Denied : Route()
}

object WebRules {
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun safeId(id: String): Boolean = ID.matches(id)

    /** Decides what a requested URL means. Only https://HOST/ is ours; paths with .., backslashes, % or empty parts are refused. */
    fun route(url: String): Route {
        val prefix = WebConfig.ORIGIN + "/"
        if (!url.startsWith(prefix)) return Route.Denied
        val path = url.substring(prefix.length).substringBefore('#').substringBefore('?')
        if (path.isEmpty() || path.contains('\\') || path.contains('%') || path.contains('\u0000')) return Route.Denied
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return Route.Denied
        return when (parts[0]) {
            "web" -> Route.Asset(path)
            "bridge" -> if (parts.size == 2 && safeId(parts[1])) Route.Bridge(parts[1]) else Route.Denied
            "input" -> {
                val n = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && n != null && n >= 0) Route.Input(n) else Route.Denied
            }
            else -> Route.Denied
        }
    }

    fun isOurs(url: String): Boolean = route(url) != Route.Denied

    private val MIME = mapOf(
        "html" to "text/html", "htm" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript",
        "css" to "text/css", "json" to "application/json", "txt" to "text/plain", "csv" to "text/csv",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "mp4" to "video/mp4", "wav" to "audio/wav", "mp3" to "audio/mpeg", "wasm" to "application/wasm",
    )

    fun mimeFor(path: String): String =
        MIME[path.substringAfterLast('/').substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** A file name that is safe to create in the Downloads folder. */
    fun safeFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trim('.')
        val cut = if (cleaned.length > 100) cleaned.take(80) + "." + cleaned.substringAfterLast('.', "").take(10) else cleaned
        return cut.ifEmpty { "file" }
    }

    /** Text as a JavaScript string literal (for evaluateJavascript). */
    fun jsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\u2028' -> sb.append("\\u2028")
            c == '\u2029' -> sb.append("\\u2029")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
