package ai.zyraxon.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the "microphone keeps switching off and on" complaint.
 *
 * A recognizer ends its session constantly — a pause, an unmatched phrase, a
 * busy engine — and reports each ending through `onError`. The old code flipped
 * the listening state to false for every one of those, so the indicator blinked
 * off and on many times a minute. These tests pin the split: ordinary endings
 * are transient and keep the ear open, only a user-fixable failure closes it.
 */
class RecognizerErrorTest {

    // The codes as SpeechRecognizer defines them.
    private val noMatch = 7
    private val speechTimeout = 6
    private val recognizerBusy = 8
    private val network = 2
    private val serverDisconnected = 11

    private val insufficientPermissions = 9
    private val languageNotSupported = 12

    @Test
    fun `a phrase that did not match is not the microphone closing`() {
        assertTrue(RecognizerError.isTransient(noMatch))
        assertFalse("NO_MATCH-এ মাইক বন্ধ দেখানো যাবে না", RecognizerError.needsUserAction(noMatch))
    }

    @Test
    fun `a pause in speech is not the microphone closing`() {
        assertTrue(RecognizerError.isTransient(speechTimeout))
        assertFalse(RecognizerError.needsUserAction(speechTimeout))
    }

    @Test
    fun `a busy engine is not the microphone closing`() {
        assertTrue(RecognizerError.isTransient(recognizerBusy))
        assertFalse(RecognizerError.needsUserAction(recognizerBusy))
    }

    @Test
    fun `a dropped connection is retried, not shown as off`() {
        assertTrue(RecognizerError.isTransient(network))
        assertTrue(RecognizerError.isTransient(serverDisconnected))
        assertFalse(RecognizerError.needsUserAction(network))
    }

    @Test
    fun `a missing permission is the one case worth closing for`() {
        assertFalse(RecognizerError.isTransient(insufficientPermissions))
        assertTrue(RecognizerError.needsUserAction(insufficientPermissions))
    }

    @Test
    fun `a language the engine cannot speak is reported`() {
        assertTrue(RecognizerError.needsUserAction(languageNotSupported))
        assertFalse(RecognizerError.isTransient(languageNotSupported))
    }

    @Test
    fun `no code is both transient and user fixable`() {
        for (code in 0..20) {
            assertFalse(
                "কোড $code একসাথে দুটো হতে পারে না",
                RecognizerError.isTransient(code) && RecognizerError.needsUserAction(code)
            )
        }
    }
}
