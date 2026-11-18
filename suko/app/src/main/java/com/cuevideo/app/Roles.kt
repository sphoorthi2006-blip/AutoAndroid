package com.cuevideo.app

/** A file found in the chosen folder. uri is kept as text so this file has no Android code. */
data class FileInfo(val name: String, val uri: String, val size: Long)

data class FolderGuess(
    val master: FileInfo?,
    val original: FileInfo?,
    val audio: FileInfo?,
    val click: FileInfo?,
    val clips: List<FileInfo>,
)

object Roles {
    /**
     * Same rules as desktop discover(), for the files directly in one folder.
     * readText is used only to look for "turboscribe" and to count cues.
     */
    fun guess(files: List<FileInfo>, readText: (FileInfo) -> String): FolderGuess {
        val audioAll = files.filter { isAudioName(it.name) }
        val click = audioAll.firstOrNull { it.name.lowercase().contains("click") }
        val audio = audioAll.filter { !it.name.lowercase().contains("click") }
            .maxByOrNull { it.size }
        val srts = files.filter { isSrtName(it.name) }

        var master: FileInfo? = null
        var original: FileInfo? = null
        val named = srts.filter { it.name.lowercase().contains("master") }
        if (named.isNotEmpty()) {
            master = named[0]
            original = srts.firstOrNull { it != master }
        } else if (srts.size == 1) {
            master = srts[0]
        } else if (srts.size > 1) {
            val counts = srts.associateWith { Srt.parseText(readText(it)).size }
            val turbo = srts.firstOrNull { readText(it).lowercase().contains("turboscribe") }
            original = turbo ?: srts.maxByOrNull { counts[it] ?: 0 }
            master = srts.first { it != original }
        }
        val clips = files.filter { isVideoName(it.name) && !isJunkName(it.name) }
        return FolderGuess(master, original, audio, click, clips)
    }
}
