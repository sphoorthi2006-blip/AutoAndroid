package com.suko.app.intake

import java.io.ByteArrayInputStream
import java.io.InputStream

/** In-memory file or folder for tests. */
class MemNode(
    override val name: String,
    private val kids: List<MemNode>? = null,
    private val data: ByteArray = ByteArray(0),
) : FolderNode {
    override val isDirectory get() = kids != null
    override val size get() = data.size.toLong()
    override fun children(): List<FolderNode> = kids ?: emptyList()
    override fun open(): InputStream = ByteArrayInputStream(data)
}

fun memDir(n: String, vararg k: MemNode) = MemNode(n, k.toList())
fun memFile(n: String, data: ByteArray = ByteArray(1)) = MemNode(n, null, data)
