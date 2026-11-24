package com.suko.app.intake

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream

/** A file or folder inside a folder the user picked (Android document tree). Name and size are read once. */
class DocNode(private val ctx: Context, private val doc: DocumentFile) : FolderNode {
    override val name: String by lazy { doc.name ?: "file" }
    override val isDirectory: Boolean by lazy { doc.isDirectory }
    override val size: Long by lazy { if (doc.isDirectory) 0L else doc.length() }
    override val location: String get() = doc.uri.toString()
    override fun children(): List<FolderNode> = doc.listFiles().map { DocNode(ctx, it) }
    override fun open(): InputStream =
        ctx.contentResolver.openInputStream(doc.uri) ?: throw IOException("cannot open $name")
}

/** A single file the user picked with the file picker. */
class UriNode(private val ctx: Context, private val uri: Uri) : FolderNode {
    private val meta: Pair<String, Long> by lazy {
        var n = uri.lastPathSegment ?: "file"
        var s = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) n = c.getString(ni) ?: n
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) s = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // keep the fallback name
        }
        Pair(n, s)
    }
    override val name: String get() = meta.first
    override val isDirectory: Boolean get() = false
    override val size: Long get() = meta.second
    override val location: String get() = uri.toString()
    override fun children(): List<FolderNode> = emptyList()
    override fun open(): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $name")
}

object AndroidNodes {
    fun files(ctx: Context, uris: List<Uri>): List<FolderNode> = uris.map { UriNode(ctx, it) }

    /** The folder the user picked, or null when Android will not open it. */
    fun tree(ctx: Context, tree: Uri): FolderNode? =
        DocumentFile.fromTreeUri(ctx, tree)?.let { DocNode(ctx, it) }
}
