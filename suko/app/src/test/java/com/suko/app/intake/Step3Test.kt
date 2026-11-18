package com.suko.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Step3Test {
    @Test
    fun routesEachTypeByExtensionAnyCase() {
        assertEquals(FileKind.IMAGE, FileKinds.of("7.PNG"))
        assertEquals(FileKind.VIDEO, FileKinds.of("clips/12-14.mp4"))
        assertEquals(FileKind.AUDIO, FileKinds.of("voice.M4A"))
        assertEquals(FileKind.SRT, FileKinds.of("master.srt"))
        assertEquals(FileKind.TEXT, FileKinds.of("script.txt"))
        assertEquals(FileKind.JSON, FileKinds.of("a/b/plan.json"))
        assertEquals(FileKind.FONT, FileKinds.of("My Font.otf"))
        assertEquals(FileKind.ZIP, FileKinds.of("more.zip"))
    }

    @Test
    fun junkAndUnknownAreNamedNotDropped() {
        assertEquals(FileKind.JUNK, FileKinds.of("__MACOSX/1.png"))
        assertEquals(FileKind.JUNK, FileKinds.of("folder/.DS_Store"))
        assertEquals(FileKind.UNKNOWN, FileKinds.of("notes.docx"))
        assertEquals(FileKind.UNKNOWN, FileKinds.of("noextension"))
        assertNotNull(FileKinds.skipReason(FileKind.UNKNOWN))
        assertNotNull(FileKinds.skipReason(FileKind.JUNK))
        assertNull(FileKinds.skipReason(FileKind.IMAGE))
    }
}
