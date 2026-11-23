// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * Governs natural, human-like voice delivery.
 *
 * In regular conversation, a real person answers concisely and gets straight to
 * the point, rather than reading aloud lengthy manuals, code lines, or bulleted
 * catalogs item by item.
 *
 * When the user explicitly requests a full reading (e.g. "পূর্ণ ভাবে পড়ে শোনাও",
 * "পুরোটা পড়ে শুনাও", "read full"), this policy allows unbounded full-text speech.
 */
object NaturalSpeechPolicy {

    private val FULL_READ_TRIGGERS = listOf(
        "পূর্ণ বিবরণ", "পূর্ণভাবে", "পূর্ণ ভাবে", "পুরোটা পড়ে", "পুরোটা পরে", "পুরোটা শুনাও",
        "পুরোটা শোনাও", "সবটা পড়ে", "সবটা পরে", "সবটা শুনাও", "সবটা শোনাও", "সম্পূর্ণ পড়ে",
        "সম্পূর্ণ পরে", "সম্পূর্ণ বিবরণ", "পুরো পড়ে", "পুরো পরে",
        "read full", "read everything", "read aloud full", "read all", "read completely"
    )

    /** Maximum spoken character length for natural conversational streaming before capping. */
    const val MAX_CONVERSATIONAL_CHARS = 850

    /** Returns true if the user's prompt explicitly requested reading out the full content. */
    fun isFullReadRequested(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val lowered = prompt.lowercase()
        return FULL_READ_TRIGGERS.any { lowered.contains(it) }
    }
}
