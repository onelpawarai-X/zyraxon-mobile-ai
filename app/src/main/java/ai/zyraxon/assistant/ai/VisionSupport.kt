// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

/**
 * Whether a model can look at a picture.
 *
 * The phone captures a frame every two seconds and, for a model that can see,
 * the picture is the richest thing it can be given. But sending an image part to
 * a text-only model is a hard 400 from the provider, which fails the whole turn
 * — the user sees the assistant die for no reason they can act on. So the frame
 * is routed: a picture for a model with eyes, the positioned text digest for one
 * without.
 *
 * The rule is deliberately conservative and only names families that are known
 * to be text-only. Anything unrecognised is treated as able to see, because a
 * model that turns out not to have eyes still receives the digest beside the
 * picture, while a model wrongly stripped of its picture loses the feature
 * entirely. New providers are added here as they are met, never guessed from a
 * name that merely looks similar.
 */
object VisionSupport {

    /**
     * Substrings that mark a model as text-only. A name containing any of these
     * is sent the text digest instead of a picture.
     */
    private val TEXT_ONLY = listOf(
        // DeepSeek's chat and reasoning models are text-only; the -vl variants
        // are not, and the vision marker checked first keeps them.
        "deepseek",
        "gpt-3.5",
        "mixtral",
        // Cohere's Command R / R+ are text; Command A is not.
        "command-r",
        // Code-specialised models are text-only across every provider seen.
        "-coder",
        "coder-",
        // Gemma 2 predates Gemma 3's vision.
        "gemma-2",
        // Phi-3 mini/small are text; the "-vision" variants are not.
        "phi-3"
    )

    /** True when [modelId] should be sent a picture rather than only text. */
    fun seesImages(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        // An explicit vision marker always wins, whatever else the name says.
        if (id.contains("vl") || id.contains("vision") || id.contains("-v-")) return true
        if (TEXT_ONLY.any { id.contains(it) }) return false
        return true
    }

    /**
     * The pictures to keep for this model: all of them when it can see, none
     * when it cannot. An empty list makes the engine send plain text, which is
     * exactly the shape a text-only provider accepts.
     */
    fun picturesFor(modelId: String, images: List<String>): List<String> =
        if (seesImages(modelId)) images else emptyList()
}
