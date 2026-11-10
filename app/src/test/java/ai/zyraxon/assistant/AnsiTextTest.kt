package ai.zyraxon.assistant

import ai.zyraxon.assistant.shell.AnsiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal shows whatever a real command prints. These cover the escape
 * handling that used to leak raw `[31m` bytes onto the screen.
 */
class AnsiTextTest {

    @Test
    fun plainTextIsOneSpanWithNoColour() {
        val spans = AnsiText.spans("hello world")
        assertEquals(1, spans.size)
        assertEquals("hello world", spans[0].text)
        assertNull(spans[0].color)
    }

    @Test
    fun sgrColourIsParsedAndStripped() {
        val spans = AnsiText.spans("\u001b[31mred\u001b[0m plain")
        assertEquals(2, spans.size)
        assertEquals("red", spans[0].text)
        assertEquals(0xFFBF616A.toInt(), spans[0].color)
        assertEquals(" plain", spans[1].text)
        assertNull(spans[1].color)
    }

    @Test
    fun boldAndColourCombine() {
        val spans = AnsiText.spans("\u001b[1;32mok\u001b[0m")
        assertEquals("ok", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals(0xFFA3BE8C.toInt(), spans[0].color)
    }

    @Test
    fun cursorMovesAreDropped() {
        // `\u001b[2K` (erase line) and `\u001b[1A` (cursor up) are not text.
        assertEquals("abc", AnsiText.plain("a\u001b[2Kbc\u001b[1A"))
    }

    @Test
    fun trueColourIsParsed() {
        val spans = AnsiText.spans("\u001b[38;2;10;20;30mx\u001b[0m")
        assertEquals(0xFF0A141E.toInt(), spans[0].color)
    }

    @Test
    fun incompleteEscapeDoesNotCrash() {
        assertEquals("a", AnsiText.plain("a\u001b["))
    }
}
