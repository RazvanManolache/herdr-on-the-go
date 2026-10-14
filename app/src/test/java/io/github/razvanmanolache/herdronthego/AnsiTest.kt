package io.github.razvanmanolache.herdronthego

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTest {
    @Test fun plainTextHasNoSpans() {
        val p = Ansi.parse("hello world", true)
        assertEquals("hello world", p.text)
        assertTrue(p.spans.isEmpty())
    }

    @Test fun basicForegroundColor() {
        val p = Ansi.parse("\u001B[32mgreen\u001B[0m plain", true)
        assertEquals("green plain", p.text)
        assertEquals(1, p.spans.size)
        val s = p.spans[0]
        assertEquals(0, s.start)
        assertEquals(5, s.end)
        assertEquals(Color(0xFF7EE787), s.style.color)
    }

    @Test fun brightColorAndBackground() {
        val p = Ansi.parse("\u001B[91;104mhi\u001B[m", true)
        assertEquals("hi", p.text)
        assertEquals(1, p.spans.size)
        assertEquals(Color(0xFFFF9E9E), p.spans[0].style.color)
        assertEquals(Color(0xFFA5D6FF), p.spans[0].style.background)
    }

    @Test fun color256AndTruecolor() {
        val cube = Ansi.parse("\u001B[38;5;15mW\u001B[0m", true)
        assertEquals(Color(0xFFFFFFFF), cube.spans[0].style.color)
        val rgb = Ansi.parse("\u001B[38;2;10;20;30mX\u001B[0m", true)
        assertEquals(Color(10, 20, 30), rgb.spans[0].style.color)
    }

    @Test fun boldResetAndSemicolonChain() {
        val p = Ansi.parse("\u001B[1;31mbold red\u001B[22;39mback", true)
        assertEquals("bold redback", p.text)
        assertEquals(1, p.spans.size)
        assertEquals(Color(0xFFF87171), p.spans[0].style.color)
        assertEquals(8, p.spans[0].end)
    }

    @Test fun nonSgrSequencesAreDropped() {
        val p = Ansi.parse("a\u001B[2Kb\u001B[1Gc\u001B]8;;http://x\u0007d\u001B(B", true)
        assertEquals("abcd", p.text)
        assertTrue(p.spans.isEmpty())
    }

    @Test fun incompleteTrailingEscapeIsDropped() {
        val p = Ansi.parse("ok\u001B[", true)
        assertEquals("ok", p.text)
    }

    @Test fun stripRemovesEverything() {
        assertEquals("red on blue", Ansi.strip("\u001B[31mred\u001B[44m on blue\u001B[0m"))
    }

    @Test fun lightThemeDarkensExplicitColors() {
        val dark = Ansi.parse("\u001B[37mW\u001B[0m", true).spans[0].style.color
        val light = Ansi.parse("\u001B[37mW\u001B[0m", false).spans[0].style.color
        assertEquals(Color(0xFFE6EDF3), dark)
        // Same slot on a light background must read as dark ink.
        assertTrue(light.red < 0.4f && light.green < 0.4f && light.blue < 0.4f)
    }

    @Test fun blackMapsToReadableGreyOnDark() {
        val p = Ansi.parse("\u001B[30mK\u001B[0m", true)
        assertEquals(Color(0xFF9AA0AA), p.spans[0].style.color)
        val l = Ansi.parse("\u001B[30mK\u001B[0m", false)
        assertEquals(Color(0xFF000000), l.spans[0].style.color)
    }

    @Test fun phoneColsMath() {
        // 1080px screen, 96px chrome, 21.6px glyph -> 45 columns.
        assertEquals(45, FitColumns.phoneCols(1080, 96, 21.6f))
        assertEquals(FitColumns.MIN, FitColumns.phoneCols(1080, 96, 500f))
        assertEquals(FitColumns.MAX, FitColumns.phoneCols(4000, 10, 5f))
        assertEquals(FitColumns.FALLBACK, FitColumns.phoneCols(0, 0, 0f))
    }
}
