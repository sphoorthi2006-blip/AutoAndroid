package com.suko.app.svg

import org.junit.Assert.*
import org.junit.Test

class SvgClicksTest {
    @Test fun oneClickPerPanelAndOnePerPanelessSlide() {
        val j = """{"slides":[{"start":0,"end":9,"panels":[{"start":1.2},{"start":4.1}]},{"start":9,"end":12}]}"""
        val (p, e) = SvgClicks.parse(j)
        assertNull(e)
        assertEquals(listOf(1.2, 4.1, 9.0), p!!.times)
        assertEquals(12.0, p.endSeconds, 1e-9)
    }
    @Test fun badJsonGivesMessage() {
        assertNotNull(SvgClicks.parse("{").second)
        assertNotNull(SvgClicks.parse("""{"a":1}""").second)
    }
}
