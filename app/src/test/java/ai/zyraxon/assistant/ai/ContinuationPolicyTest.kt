package ai.zyraxon.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops the assistant ending a turn one step short.
 *
 * Every case here is a real reply the user complained about: a failure report,
 * a bare promise, or a silent stop right after the screen changed.
 */
class ContinuationPolicyTest {

    @Test
    fun `a failure report is never an ending`() {
        assertTrue(ContinuationPolicy.shouldContinue("ওয়েবসাইটের ফাইল লেখা যায়নি", false, 0))
        assertTrue(ContinuationPolicy.shouldContinue("দুঃখিত, আমি পারলাম না", false, 0))
        assertTrue(ContinuationPolicy.shouldContinue("could not open the app", false, 0))
    }

    @Test
    fun `a bare promise is not an ending`() {
        assertTrue(ContinuationPolicy.shouldContinue("ঠিক আছে, এখনই খুলছি", false, 0))
        assertTrue(ContinuationPolicy.shouldContinue("লিখছি একটু পরেই", false, 0))
        assertTrue(ContinuationPolicy.shouldContinue("let me search for it", false, 0))
    }

    @Test
    fun `a silent stop right after the screen changed continues`() {
        // YouTube's results came back and the model stopped one tap short.
        assertTrue(ContinuationPolicy.shouldContinue("ইউটিউবে সার্চ করে দিলাম", true, 0))
    }

    @Test
    fun `a confirmed completion ends the turn`() {
        assertFalse(ContinuationPolicy.shouldContinue("ভিডিওটা এখন বাজছে", true, 0))
        assertFalse(ContinuationPolicy.shouldContinue("সাইটটা লাইভ হয়ে গেছে", true, 0))
        assertFalse(ContinuationPolicy.shouldContinue("the app is now open and playing", true, 0))
    }

    @Test
    fun `a plain answer with no screen change ends the turn`() {
        assertFalse(ContinuationPolicy.shouldContinue("আজকের তারিখ ৩০ অক্টোবর", false, 0))
        assertFalse(ContinuationPolicy.shouldContinue("তোমার নাম রাফি", false, 0))
    }

    @Test
    fun `the nudge budget stops an endless loop`() {
        assertTrue(ContinuationPolicy.shouldContinue("পারলাম না", false, 0))
        assertTrue(ContinuationPolicy.shouldContinue("পারলাম না", false, 3))
        assertFalse(ContinuationPolicy.shouldContinue("পারলাম না", false, 4))
    }

    @Test
    fun `an empty reply is not continued`() {
        assertFalse(ContinuationPolicy.shouldContinue("", false, 0))
    }
}
