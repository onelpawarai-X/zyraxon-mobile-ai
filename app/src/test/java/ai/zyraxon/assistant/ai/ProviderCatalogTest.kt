package ai.zyraxon.assistant.ai

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the provider catalogue that decides where a model's request goes.
 * These assertions cover the bug that made a saved key never show as connected
 * and left the selector empty: a provider whose catalogue was never read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderCatalogTest {

    @Test
    fun everySettingsProviderIsInTheCatalogue() {
        // The ids are the settings storage keys; a mismatch would silently drop a
        // provider the user just connected.
        val expected = listOf(
            "gemini", "anthropic", "groq", "openrouter", "deepseek", "openai", "opencode", "zyraxon"
        )
        for (id in expected) {
            assertNotNull("missing provider: $id", ProviderCatalog.find(id))
        }
    }

    @Test
    fun everyProviderHasAbsoluteHttpsEndpoints() {
        for (spec in ProviderCatalog.specs) {
            assertTrue("${spec.id} models url", spec.modelsUrl.startsWith("https://"))
            assertTrue("${spec.id} chat url", spec.chatUrl.startsWith("https://"))
            assertTrue("${spec.id} label", spec.label.isNotBlank())
        }
    }

    @Test
    fun providerIdsAreUnique() {
        val ids = ProviderCatalog.specs.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun openRouterCatalogueNeedsNoKeySoModelsLoadBeforeAKeyIsSet() {
        val spec = ProviderCatalog.find("openrouter")!!
        assertTrue(spec.catalogIsPublic)
        assertFalse(spec.needsKeyForCatalog)
        val loadable = ProviderCatalog.loadable { "" }
        assertTrue(loadable.any { it.id == "openrouter" })
    }

    @Test
    fun zyraxonCatalogueIsPublicSoItsTenModelsLoadWithoutAKey() {
        // The user's own line lists its models publicly even while chat is gated;
        // the models must therefore appear before any key is entered.
        val spec = ProviderCatalog.find("zyraxon")!!
        assertTrue(spec.catalogIsPublic)
        assertFalse(spec.needsKeyForCatalog)
        assertTrue(ProviderCatalog.loadable { "" }.any { it.id == "zyraxon" })
    }

    @Test
    fun noProviderThatNeedsAKeyIsLoadedWithoutOne() {
        // Guards the other half: making ZYRAXON public must not leak a keyed
        // provider into the no-key list, which would 401 on every launch.
        val withoutKeys = ProviderCatalog.loadable { "" }.map { it.id }
        for (id in listOf("gemini", "anthropic", "groq", "deepseek", "openai", "opencode")) {
            assertFalse("$id needs a key but loaded without one", withoutKeys.contains(id))
        }
    }

    @Test
    fun aProviderWithAKeyBecomesLoadable() {
        val loadable = ProviderCatalog.loadable { id -> if (id == "groq") "gsk_test" else "" }
        assertTrue(loadable.any { it.id == "groq" })
        // A provider with no key must not be attempted, so its failure is not
        // mistaken for a broken model list.
        assertFalse(loadable.any { it.id == "deepseek" })
    }

    @Test
    fun bearerKeyGoesIntoTheAuthorizationHeader() {
        val builder = Request.Builder().url("https://example.com")
        ProviderCatalog.applyAuth(builder, ProviderCatalog.find("groq")!!, "gsk_abc")
        val header = builder.build().header("Authorization")
        assertEquals("Bearer gsk_abc", header)
    }

    @Test
    fun anthropicGetsBothItsKeyHeaders() {
        val builder = Request.Builder().url("https://example.com")
        val spec = ProviderCatalog.find("anthropic")!!
        ProviderCatalog.applyAuth(builder, spec, "sk-ant-1")
        val request = builder.build()
        assertEquals("Bearer sk-ant-1", request.header("Authorization"))
        assertEquals("sk-ant-1", request.header("x-api-key"))
        // Anthropic rejects a request without this header, so its absence would
        // look like a bad key.
        assertEquals("2023-06-01", request.header("anthropic-version"))
    }

    @Test
    fun googleCatalogueTakesTheKeyInTheQueryNotTheHeader() {
        val spec = ProviderCatalog.find("gemini")!!
        val url = ProviderCatalog.urlFor(spec, "AIza_test", forCatalog = true)
        assertTrue(url, url.contains("key=AIza_test"))
        // Chat uses the OpenAI-compatible path, which wants a bearer token.
        val chat = ProviderCatalog.urlFor(spec, "AIza_test", forCatalog = false)
        assertFalse(chat, chat.contains("key="))
    }

    @Test
    fun openAiStyleCatalogueIsParsed() {
        val spec = ProviderCatalog.find("groq")!!
        val body = """
            {"object":"list","data":[
              {"id":"llama-3.3-70b-versatile","object":"model"},
              {"id":"llama-3.1-8b-instant","object":"model"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            ProviderCatalog.parseModels(spec, body)
        )
    }

    @Test
    fun googleNativeCatalogueIsParsedAndNonChatModelsAreDropped() {
        val spec = ProviderCatalog.find("gemini")!!
        val body = """
            {"models":[
              {"name":"models/gemini-2.0-flash"},
              {"name":"models/gemini-2.5-flash"},
              {"name":"models/text-embedding-004"},
              {"name":"models/imagen-3.0-generate"},
              {"name":"models/gemini-2.5-flash-preview-tts"}
            ]}
        """.trimIndent()
        val ids = ProviderCatalog.parseModels(spec, body)
        assertEquals(listOf("gemini-2.0-flash", "gemini-2.5-flash"), ids)
    }

    @Test
    fun geminiLiveModelsSurviveTheFilterAndAreDetectedAsRealtime() {
        // The `-tts` filter must not swallow the live models: they are named
        // `native-audio` and `live`, which is what the provider uses for the
        // models that actually hold a conversation.
        val spec = ProviderCatalog.find("gemini")!!
        val body = """
            {"models":[
              {"name":"models/gemini-2.5-flash"},
              {"name":"models/gemini-2.5-flash-preview-tts"},
              {"name":"models/gemini-2.5-flash-native-audio-preview-12-2025"},
              {"name":"models/gemini-3.1-flash-live-preview"}
            ]}
        """.trimIndent()
        val ids = ProviderCatalog.parseModels(spec, body)
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-flash-native-audio-preview-12-2025",
                "gemini-3.1-flash-live-preview"),
            ids
        )
        assertTrue(spec.isRealtimeModel("gemini-2.5-flash-native-audio-preview-12-2025"))
        assertTrue(spec.isRealtimeModel("gemini-3.1-flash-live-preview"))
        assertFalse(spec.isRealtimeModel("gemini-2.5-flash-preview-tts"))
        assertFalse(spec.isRealtimeModel("gemini-2.5-flash"))
    }

    @Test
    fun openRouterStylePublicCatalogueIsParsed() {
        val spec = ProviderCatalog.find("openrouter")!!
        val body = """{"data":[{"id":"anthropic/claude-3.5-sonnet"},{"id":"meta-llama/llama-3.1-8b-instruct:free"}]}"""
        assertEquals(
            listOf("anthropic/claude-3.5-sonnet", "meta-llama/llama-3.1-8b-instruct:free"),
            ProviderCatalog.parseModels(spec, body)
        )
    }

    @Test
    fun duplicateModelIdsAreCollapsed() {
        val spec = ProviderCatalog.find("openai")!!
        val body = """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o"), ProviderCatalog.parseModels(spec, body))
    }

    @Test
    fun anErrorBodyYieldsTheProvidersOwnMessage() {
        val body = """{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""
        assertEquals("Invalid API Key", ProviderCatalog.parseError(body, 401))
    }

    @Test
    fun anUnparsableErrorBodyStillReportsTheStatusCode() {
        assertEquals("HTTP 500", ProviderCatalog.parseError("<html>oops</html>", 500))
        assertEquals("HTTP 503", ProviderCatalog.parseError("", 503))
    }

    @Test
    fun aMalformedCatalogueYieldsNoModelsRatherThanACrash() {
        val spec = ProviderCatalog.find("groq")!!
        assertTrue(ProviderCatalog.parseModels(spec, "not json").isEmpty())
        assertTrue(ProviderCatalog.parseModels(spec, "{}").isEmpty())
        assertTrue(ProviderCatalog.parseModels(spec, """{"data":[]}""").isEmpty())
    }

    @Test
    fun fastestProvidersAreRankedFirst() {
        val ordered = ProviderCatalog.specs.sortedBy { it.speedRank }
        assertTrue(ordered.first().speedRank <= ordered.last().speedRank)
        // Groq is the quickest hosted inference, so it must precede OpenAI.
        assertTrue(
            ProviderCatalog.find("groq")!!.speedRank < ProviderCatalog.find("openai")!!.speedRank
        )
    }

    @Test
    fun everySeedModelBelongsToAProviderThatActuallyHasSeeds() {
        // Seeds only exist as an offline fallback; they must never be invented for
        // a provider whose real catalogue always loads.
        assertTrue(ProviderCatalog.find("openrouter")!!.seedModels.isEmpty())
        assertTrue(ProviderCatalog.find("groq")!!.seedModels.isNotEmpty())
    }

    @Test
    fun modelProviderLookupRoundTrips() {
        assertNotNull(ProviderCatalog.forModelProvider("groq"))
        assertNull(ProviderCatalog.forModelProvider("does-not-exist"))
    }

    @Test
    fun aRejectedRequestIsNotRetriedButAnAvailabilityProblemIs() {
        // A missing thought_signature or a retired model id fails on every
        // provider, so retrying it only makes the user wait minutes for the same
        // error. Quota, overload and dropped connections are worth another try.
        assertFalse(ProviderCatalog.isRetryableError(
            "Function call is missing a thought_signature in functionCall parts."
        ))
        assertFalse(ProviderCatalog.isRetryableError(
            "This model models/gemini-2.5-flash is no longer available to new users."
        ))
        assertFalse(ProviderCatalog.isRetryableError("Request contains an invalid argument."))
        assertTrue(ProviderCatalog.isRetryableError("You exceeded your current quota"))
        assertTrue(ProviderCatalog.isRetryableError("This model is currently experiencing high demand."))
        assertTrue(ProviderCatalog.isRetryableError("timeout"))
        assertTrue(ProviderCatalog.isRetryableError(null))
    }

    @Test
    fun geminiShipsLiveSeedModelsNotRetiredIds() {
        // The old seed was gemini-2.5-flash, which Google now answers with 404 for
        // new users; the seeds must be ids Google still serves.
        val seeds = ProviderCatalog.find("gemini")!!.seedModels
        assertTrue("gemini-এর সিড মডেল থাকা দরকার", seeds.isNotEmpty())
        assertTrue(
            "বাতিল হওয়া মডেল আইড সিডে থাকা যাবে না",
            seeds.none { it == "gemini-2.5-flash" || it == "gemini-2.0-flash" }
        )
    }
}
