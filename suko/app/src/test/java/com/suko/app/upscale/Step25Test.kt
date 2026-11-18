package com.suko.app.upscale

import com.suko.app.intake.FileKind
import com.suko.app.intake.IntakeEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Upscale Images: how input paths become output groups and PNG names. */
class Step25Test {
    private fun job(path: String) = UpscaleJob(IntakeEntry(path, FileKind.IMAGE, 1, null, File("x")), "k$path", File("o"), false)
    private fun rows(vararg p: String) = UpscaleBatch.layout(p.map(::job)).map { it.group + "|" + it.relPath }

    @Test fun looseFolderAndZipPicturesGetTheirGroup() {
        assertEquals(
            listOf("pictures|1.png", "Folder|sub/2.png", "pack|inner/3.png", "pack|b/4.png"),
            rows("1.jpg", "Folder/sub/2.JPG", "pack.zip/inner/3.jpeg", "pack.zip/b.zip/4.png"),
        )
    }

    @Test fun sameNameInOneGroupIsNeverOverwritten() {
        assertEquals(listOf("pictures|1.png", "pictures|1_2.png", "pictures|1_3.png"), rows("1.jpg", "1.png", "1.webp"))
    }

    @Test fun pathTricksCannotLeaveTheOutputFolder() {
        assertEquals(listOf("evil|6.png"), rows("../evil/6.png"))
    }

    @Test fun zipNamesAndSummary() {
        assertEquals("pack_x2.zip", UpscaleBatch.zipName("pack"))
        assertEquals("Saved 3 of 4 pictures into 2 ZIPs.", UpscaleBatch.summary(3, 4, OutputMode.ZIPS, 2))
        assertEquals("Saved 1 picture as PNG files.", UpscaleBatch.summary(1, 1, OutputMode.PNG_FOLDER, 0))
    }
}
