// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSpeechPolicyTest {

    @Test
    fun detectsBanglaFullReadRequests() {
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("আমাকে এটা পূর্ণ ভাবে পড়ে শোনাও"))
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("পুরোটা পড়ে শুনাও"))
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("সবটা পড়ে শোনাও"))
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("সম্পূর্ণ বিবরণ দাও"))
    }

    @Test
    fun detectsEnglishFullReadRequests() {
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("read full article please"))
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("read everything aloud"))
        assertTrue(NaturalSpeechPolicy.isFullReadRequested("read all"))
    }

    @Test
    fun regularPromptsAreNotFullRead() {
        assertFalse(NaturalSpeechPolicy.isFullReadRequested("আজকের আবহাওয়া কেমন?"))
        assertFalse(NaturalSpeechPolicy.isFullReadRequested("একটা ওয়েবসাইট বানিয়ে দাও"))
        assertFalse(NaturalSpeechPolicy.isFullReadRequested("Hello, how are you?"))
    }
}
