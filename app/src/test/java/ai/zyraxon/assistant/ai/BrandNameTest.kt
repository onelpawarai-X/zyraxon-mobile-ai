package ai.zyraxon.assistant.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cloud agent is the app's own, and no screen may name the vendor behind it.
 *
 * The cloud catalogue returns models as "openhands/<id>". The model picker shows
 * `AiModel.name`, so that prefix used to put the vendor's name in front of the
 * user on the model list and in every chat bubble's model label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrandNameTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `no loaded model shows the vendor prefix`() = runBlocking {
        val models = ZyraxonAiEngine(context).fetchLiveModels()
        models.forEach { model ->
            assertFalse("${model.id} leaked the vendor into the picker", model.name.contains("openhands", ignoreCase = true))
            assertFalse("${model.id} leaked the vendor into the picker", model.name.contains("OpenHands"))
        }
    }

    @Test
    fun `the cloud model id still routes, only its label is cleaned`() = runBlocking {
        // The id must stay exactly what the cloud expects, or turns stop routing;
        // only the display name is rewritten.
        val models = ZyraxonAiEngine(context).fetchLiveModels()
        val cloud = models.filter { it.provider == "zyraxon_apex" }
        cloud.forEach { assertTrue("apex id was rewritten: ${it.id}", it.id.isNotBlank()) }
        assertTrue(cloud.all { !it.name.contains("/") })
    }

    @Test
    fun `the cloud catalogue name is cleaned deterministically`() {
        // Offline, so this does not depend on the network answering: the exact
        // string the cloud's catalogue returns, run through the real formatter.
        val engine = ZyraxonAiEngine(context)
        val name = engine.formatModelName("openhands/deepseek-v4.1-flash")
        assertFalse("vendor leaked: $name", name.contains("openhands", ignoreCase = true))
        assertFalse("owner prefix leaked: $name", name.contains("/"))
        assertTrue("the model itself vanished: $name", name.contains("Deepseek", ignoreCase = true))
        // The same prefix rule already applied to opencode must keep working.
        assertEquals("Claude Sonnet 4 5", engine.formatModelName("opencode/claude-sonnet-4-5"))
    }
}
