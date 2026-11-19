// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wire is read through these two helpers everywhere, because Android's
 * `org.json` answers the four letters "null" for a JSON null and ignores the
 * caller's default. That text used to travel onward as real content — into a tool
 * result the model read back, into a message the voice spoke — and the assistant
 * said "null, null" before its answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JsonSafeTest {

    @Test
    fun `a JSON null is never the word null`() {
        val o = JSONObject("""{"summary":null}""")
        assertEquals("null", o.optString("summary", "fallback"))
        assertEquals("", o.safe("summary"))
        assertEquals("fallback", o.safe("summary", "fallback"))
    }

    @Test
    fun `a missing key falls back`() {
        val o = JSONObject("{}")
        assertEquals("d", o.safe("nope", "d"))
        assertEquals("", o.safe("nope"))
    }

    @Test
    fun `a real value is returned unchanged`() {
        val o = JSONObject("""{"t":"আসসালামু আলাইকুম"}""")
        assertEquals("আসসালামু আলাইকুম", o.safe("t"))
    }

    @Test
    fun `a null inside an array is never the word null`() {
        val a = JSONArray("""["a",null,"b"]""")
        assertEquals("a", a.safe(0))
        assertEquals("", a.safe(1))
        assertEquals("b", a.safe(2))
        assertEquals("", a.safe(9))
    }
}
