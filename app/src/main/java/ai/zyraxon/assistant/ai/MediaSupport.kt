package ai.zyraxon.assistant.ai

/**
 * What a model can actually be *sent*, as opposed to what it can be told about.
 *
 * A picture, a clip or a voice note can reach the agent two ways: the real bytes
 * as a content part, or an HTTPS link the agent downloads itself. The link always
 * works. The part is better — the model looks at the thing instead of shelling
 * out to fetch it — but only some models accept it, and a part sent to a model
 * that does not is a hard 400 that kills the whole turn.
 *
 * So a part is sent only when the model is *known* to take it:
 *
 *  - OpenRouter publishes `architecture.input_modalities` for every model, so
 *    there the answer is exact, not guessed.
 *  - Google's OpenAI-compatible endpoint takes `input_audio`, so its audio
 *    families are recognised by name.
 *  - Everything else falls back to the naming rule in [VisionSupport], and audio
 *    and video stay off unless a model's own name says otherwise.
 *
 * Anything not positively recognised is treated as text-and-links, which is the
 * one combination that never fails a turn.
 */
object MediaSupport {

    /** The three media kinds a turn can carry. */
    data class Caps(
        val images: Boolean,
        val audio: Boolean,
        val video: Boolean
    ) {
        /** A short form for the model cache, e.g. "i,a". */
        fun encode(): String = buildString {
            if (images) append("i")
            if (audio) append("a")
            if (video) append("v")
        }

        val any: Boolean get() = images || audio || video
    }

    /** Text and links only — the safe default for a model nothing is known about. */
    val NONE = Caps(images = false, audio = false, video = false)

    /** Text plus pictures — what the app assumed before media was routed by kind. */
    val IMAGES = Caps(images = true, audio = false, video = false)

    fun decode(s: String): Caps = Caps(
        images = s.contains('i'),
        audio = s.contains('a'),
        video = s.contains('v')
    )

    /**
     * The kinds this model takes, from the provider's own declaration when there
     * is one and from a naming rule otherwise.
     *
     * [declared] is OpenRouter's `architecture.input_modalities`. When it is
     * present it is the whole answer: the provider is stating what the model
     * accepts, and a name cannot override a declaration.
     */
    fun capsFor(provider: String, modelId: String, declared: List<String>?): Caps {
        if (declared != null) {
            val mods = declared.map { it.lowercase() }
            return Caps(
                images = mods.any { it == "image" || it == "images" },
                audio = mods.any { it == "audio" || it == "input_audio" },
                video = mods.any { it == "video" || it == "input_video" }
            )
        }
        val id = modelId.lowercase()
        return Caps(
            images = VisionSupport.seesImages(modelId),
            audio = hearsAudio(provider, id),
            video = seesVideo(provider, id)
        )
    }

    /**
     * Whether the model takes spoken audio. Google's compatible endpoint accepts
     * `input_audio` for its audio families, and the audio-preview naming is the
     * one OpenAI itself uses. Nothing else is assumed.
     */
    private fun hearsAudio(provider: String, id: String): Boolean {
        if (id.contains("embedding") || id.contains("tts") || id.contains("transcribe")) return false
        if (id.contains("-audio") || id.contains("audio-") || id.contains("native-audio")) return true
        return provider == "google" && id.contains("omni")
    }

    /**
     * Whether the model takes a clip. Only names that say so are trusted; video
     * parts are the least widely supported, so a wrong guess here is the most
     * expensive one.
     */
    private fun seesVideo(provider: String, id: String): Boolean {
        if (id.contains("embedding") || id.contains("tts")) return false
        if (id.contains("omni")) return true
        return id.contains("video") && provider != "google"
    }
}
