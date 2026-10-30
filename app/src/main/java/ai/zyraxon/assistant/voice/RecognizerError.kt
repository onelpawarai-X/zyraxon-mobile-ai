package ai.zyraxon.assistant.voice

/**
 * Classifies a `SpeechRecognizer` error code.
 *
 * The recognizer ends a session constantly — a pause in speech, a phrase it
 * could not match, a busy engine — and reports each ending through `onError`.
 * Treating all of those as "the microphone closed" is what made the listening
 * indicator and the status line flicker off and on many times a minute, which
 * the user saw as the microphone being switched off and on again and again.
 *
 * Only a genuine, user-fixable failure should ever show as "not listening";
 * everything else is the normal end of one phrase and the loop simply reopens.
 *
 * Kept as a pure function of the code so it is testable without a recognizer.
 */
object RecognizerError {

    // SpeechRecognizer constants, spelled out so this file has no Android
    // dependency and the unit test can drive it directly.
    private const val NETWORK_TIMEOUT = 1
    private const val NETWORK = 2
    private const val AUDIO = 3
    private const val SERVER = 4
    private const val CLIENT = 5
    private const val SPEECH_TIMEOUT = 6
    private const val NO_MATCH = 7
    private const val RECOGNIZER_BUSY = 8
    private const val INSUFFICIENT_PERMISSIONS = 9
    private const val TOO_MANY_REQUESTS = 10
    private const val SERVER_DISCONNECTED = 11
    private const val LANGUAGE_NOT_SUPPORTED = 12
    private const val LANGUAGE_UNAVAILABLE = 13
    private const val CANNOT_CHECK_SUPPORT = 14

    /**
     * True when the error is the ordinary end of one phrase and the ear should
     * stay open — the microphone keeps its "listening" state and the loop only
     * waits its debounce before reopening.
     */
    fun isTransient(code: Int): Boolean = when (code) {
        SPEECH_TIMEOUT,
        NO_MATCH,
        NETWORK_TIMEOUT,
        NETWORK,
        AUDIO,
        SERVER,
        CLIENT,
        RECOGNIZER_BUSY,
        TOO_MANY_REQUESTS,
        SERVER_DISCONNECTED -> true
        else -> false
    }

    /**
     * True when only the user can fix it — a missing permission or a language
     * the engine does not carry. These are the cases worth reporting, and the
     * loop backs off instead of hammering the recognizer.
     */
    fun needsUserAction(code: Int): Boolean = when (code) {
        INSUFFICIENT_PERMISSIONS,
        LANGUAGE_NOT_SUPPORTED,
        LANGUAGE_UNAVAILABLE,
        CANNOT_CHECK_SUPPORT -> true
        else -> false
    }
}
