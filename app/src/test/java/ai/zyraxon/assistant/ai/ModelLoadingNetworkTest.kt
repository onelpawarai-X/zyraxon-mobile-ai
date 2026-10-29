package ai.zyraxon.assistant.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the model catalogue really loads over the network.
 *
 * This is the regression test for the bug the user reported: after connecting a
 * provider no models ever appeared. It calls the real engine against the real
 * public endpoints (no key needed for these two), so it fails if the parallel
 * loader, the auth wiring or the JSON parsing breaks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelLoadingNetworkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun publicCatalogueLoadsWithoutAnyKey() = runBlocking {
        val engine = ZyraxonAiEngine(context)
        // No keys are set, so only the public directories can answer.
        val models = engine.fetchLiveModels()

        assertFalse("no models loaded from any public catalogue", models.isEmpty())
        assertTrue(
            "openrouter's public directory should contribute models",
            models.any { it.provider == "openrouter" }
        )
        // Every model must name a real provider and a non-blank id.
        for (m in models) {
            assertTrue("blank id in ${m.provider}", m.id.isNotBlank())
            assertTrue("blank name for ${m.id}", m.name.isNotBlank())
            assertTrue("unknown provider for ${m.id}", ProviderCatalog.find(m.provider) != null)
        }
    }

    @Test
    fun progressIsReportedWhileLoadingSoTheSelectorFillsEarly() = runBlocking {
        val engine = ZyraxonAiEngine(context)
        val snapshots = mutableListOf<Int>()
        val final = engine.fetchLiveModels { partial -> snapshots.add(partial.size) }

        // At least one intermediate snapshot must have arrived, otherwise the UI
        // would sit empty until every provider finished.
        assertTrue("no progress was reported", snapshots.isNotEmpty())
        assertTrue("final list is smaller than a snapshot", final.size >= snapshots.last())
    }

    @Test
    fun aKeyThatIsObviouslyWrongIsReportedAsNotConnected() = runBlocking {
        val engine = ZyraxonAiEngine(context)
        engine.setApiKey("groq", "gsk_not_a_real_key")
        val (ok, message) = engine.verifyProviderKey("groq")

        assertFalse("a bogus key must never report connected", ok)
        assertTrue("the provider's own reason should be shown", message.isNotBlank())
        engine.setApiKey("groq", "")
    }

    @Test
    fun verifyingWithNoKeySaysSoInsteadOfClaimingConnected() = runBlocking {
        val engine = ZyraxonAiEngine(context)
        engine.setApiKey("deepseek", "")
        val (ok, message) = engine.verifyProviderKey("deepseek")

        assertFalse(ok)
        assertTrue("expected a missing-key message, got: $message", message.contains("কী"))
    }
}
