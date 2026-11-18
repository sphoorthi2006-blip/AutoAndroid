package com.suko.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class Step6Test {
    private class Node(
        override val name: String,
        private val kids: List<Node>? = null,
        private val data: ByteArray = ByteArray(0),
        private val fail: Boolean = false,
    ) : FolderNode {
        override val isDirectory get() = kids != null || fail
        override val size get() = data.size.toLong()
        override fun children(): List<FolderNode> {
            if (fail) throw SecurityException("no access")
            return kids ?: emptyList()
        }
        override fun open(): InputStream = ByteArrayInputStream(data)
    }

    private fun dir(n: String, vararg k: Node) = Node(n, k.toList())
    private fun file(n: String, size: Int = 1) = Node(n, null, ByteArray(size))

    @Test
    fun walksSubfoldersWithRelativePathsAndKinds() {
        val root = dir("proj",
            file("master.srt"), file("voice.mp3", 9),
            dir("images", file("2.png"), file("10.png"), dir("deep", file("1.jpg"))),
            file("more.zip", 4),
        )
        val s = FolderWalker.walk(root)
        assertEquals(
            listOf("images/10.png", "images/2.png", "images/deep/1.jpg", "master.srt", "more.zip", "voice.mp3"),
            s.files.map { it.path },
        )
        assertEquals(FileKind.ZIP, s.files.first { it.path == "more.zip" }.kind)
        assertEquals(9L, s.files.first { it.path == "voice.mp3" }.size)
        assertTrue(s.problems.isEmpty())
    }

    @Test
    fun hiddenFoldersAreSkippedWithAReasonAndUnreadableFoldersDontCrash() {
        val root = dir("p", dir("__MACOSX", file("a.png")), dir(".git", file("b")), Node("locked", fail = true), file("ok.png"))
        val s = FolderWalker.walk(root)
        assertEquals(listOf("ok.png"), s.files.map { it.path })
        assertEquals(3, s.problems.size)
        assertTrue(s.problems.any { it.path == "locked" && it.reason.contains("cannot read") })
    }

    @Test
    fun depthAndFileLimitsStopWithAReason() {
        val root = dir("p", dir("a", dir("b", dir("c", file("x.png")))))
        val s = FolderWalker.walk(root, maxFolderDepth = 2)
        assertTrue(s.files.isEmpty())
        assertTrue(s.problems.any { it.reason.contains("too deep") })
        val many = dir("p", *Array(5) { file("$it.png") })
        val s2 = FolderWalker.walk(many, Limits(maxEntries = 2))
        assertEquals(2, s2.files.size)
        assertTrue(s2.problems.any { it.reason.contains("too many") })
    }
}
