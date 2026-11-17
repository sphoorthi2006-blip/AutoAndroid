package com.cuevideo.app

import java.util.Locale

val IMAGE_EXT = listOf("jpg", "jpeg", "png", "webp", "bmp")
val VIDEO_EXT = listOf("mp4", "mov", "mkv", "webm", "m4v", "avi")
val AUDIO_EXT = listOf("mp3", "wav", "m4a", "aac", "flac", "ogg")

fun extOf(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun isImageName(name: String) = extOf(name) in IMAGE_EXT
fun isVideoName(name: String) = extOf(name) in VIDEO_EXT
fun isAudioName(name: String) = extOf(name) in AUDIO_EXT
fun isSrtName(name: String) = extOf(name) == "srt"

/** Files we must never use: macOS junk and hidden files. */
fun isJunkName(pathOrName: String): Boolean {
    if (pathOrName.contains("__MACOSX")) return true
    val base = pathOrName.substringAfterLast('/').substringAfterLast('\\')
    return base.startsWith(".")
}

object CueNames {
    private val NUM = Regex("\\d+")
    private val CLIP = Regex("(\\d+)(?:\\s*[-\\u2013]\\s*(\\d+))?")

    /** Image cue number = the first number in the file name (like desktop). */
    fun imageCue(fileName: String): Int? {
        val stem = fileName.substringBeforeLast('.')
        val m = NUM.find(stem) ?: return null
        return m.value.toIntOrNull()
    }

    /**
     * Scene clip name -> (first, last) cues.
     * 53.mp4, n53.mp4, anything_53.mp4 -> 53..53; 12-14.mp4 -> 12..14.
     * With several numbers, the LAST number or range is used. Leading zeros ignored.
     */
    fun clipRange(fileName: String): Pair<Int, Int>? {
        val stem = fileName.substringBeforeLast('.')
        val last = CLIP.findAll(stem).lastOrNull() ?: return null
        val a = last.groupValues[1].toIntOrNull() ?: return null
        val bText = last.groupValues[2]
        val b = if (bText.isEmpty()) a else (bText.toIntOrNull() ?: return null)
        return Pair(a, b)
    }
}
