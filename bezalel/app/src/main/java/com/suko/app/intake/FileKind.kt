package com.suko.app.intake

import com.cuevideo.app.extOf
import com.cuevideo.app.isAudioName
import com.cuevideo.app.isImageName
import com.cuevideo.app.isJunkName
import com.cuevideo.app.isSrtName
import com.cuevideo.app.isVideoName

/** What a file is, decided from its name only. Every file gets exactly one kind; nothing is silently dropped. */
enum class FileKind { IMAGE, VIDEO, AUDIO, SRT, TEXT, JSON, FONT, ZIP, JUNK, UNKNOWN }

object FileKinds {
    private val FONT_EXT = setOf("ttf", "otf")

    fun of(pathOrName: String): FileKind {
        if (isJunkName(pathOrName)) return FileKind.JUNK
        val name = pathOrName.substringAfterLast('/').substringAfterLast('\\')
        return when {
            isImageName(name) -> FileKind.IMAGE
            isVideoName(name) -> FileKind.VIDEO
            isAudioName(name) -> FileKind.AUDIO
            isSrtName(name) -> FileKind.SRT
            extOf(name) == "txt" -> FileKind.TEXT
            extOf(name) == "json" -> FileKind.JSON
            extOf(name) in FONT_EXT -> FileKind.FONT
            extOf(name) == "zip" -> FileKind.ZIP
            else -> FileKind.UNKNOWN
        }
    }

    /** Plain-words reason shown to the user for files the tools will not use. Null = the file is usable. */
    fun skipReason(kind: FileKind): String? = when (kind) {
        FileKind.JUNK -> "hidden or system file"
        FileKind.UNKNOWN -> "file type not supported"
        else -> null
    }
}
