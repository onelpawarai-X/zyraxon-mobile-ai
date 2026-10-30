package ai.zyraxon.assistant.voice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's complaint: while the assistant talks, its own voice came back into
 * the chat as a user message. The guard is that the ear ignores anything heard
 * while the speaker is active and for a short tail after the last word, so a
 * phrase arriving right after a reply is treated as echo, not as the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EchoGuardTest {

    private fun manager(): NativeVoiceManager =
        NativeVoiceManager(
            ApplicationProvider.getApplicationContext<Application>(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        )

    @Test
    fun `nothing is treated as echo before anything is spoken`() {
        assertFalse("শুরুতেই echo ধরা উচিত নয়", manager().isSpeakerActive())
    }

    @Test
    fun `the tail after a reply counts as echo`() {
        val m = manager()
        m.beginReply()
        m.endReply()
        assertTrue("উত্তর শেষ হওয়ার পরপরই echo গার্ড চালু থাকতে হবে", m.isSpeakerActive())
    }

    @Test
    fun `the tail expires`() {
        val m = manager()
        m.endReply()
        // The tail is 1.2s; a phrase a moment later is the user, not echo.
        Thread.sleep(1_400)
        assertFalse("টেল শেষে আর echo নয়", m.isSpeakerActive())
    }
}
