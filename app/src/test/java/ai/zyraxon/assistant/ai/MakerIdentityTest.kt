// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maker's identity is in every contract the app can run under.
 *
 * The name was wrong once — an older note called him "Alex" — so the contracts
 * state the right one outright and say the wrong one is wrong. A prompt file
 * that silently loses this block would put the old name back in front of a user
 * on their first "who made you", which is why each file is checked by name here
 * rather than trusting one shared string.
 */
class MakerIdentityTest {

    /** The prompt sources are no longer in this repository: they live on the
     *  website, under `mobile-bolt/`, and are fetched on first open. When the
     *  website is checked out beside this one the real files are read; otherwise
     *  the check is skipped rather than failed, because a missing sibling repo is
     *  not a broken app. */
    private fun repoFile(relative: String): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        return java.io.File(relative)
    }

    private fun contract(relative: String): String = repoFile(relative).readText()

    /** The two mode contracts. Characters are checked by the bundle test. */
    private val contracts = listOf(
        "zxweb/public/mobile-bolt/mode-agent.txt",
        "zxweb/public/mobile-bolt/mode-build.txt"
    )

    @Test
    fun `every contract names the maker and rejects the old name`() {
        contracts.forEach { path ->
            val file = repoFile(path)
            if (!file.exists()) return@forEach
            val text = file.readText()
            assertTrue("$path lost the maker's name", text.contains("Deluar Husen Sayidi"))
            assertTrue("$path no longer rejects \"Alex\"", text.contains("Never call him \"Alex\""))
            assertTrue("$path lost the contact number", text.contains("01331168512"))
            assertTrue("$path lost the family", text.contains("two brothers and four sisters"))
            assertTrue("$path lost the favourite song", text.contains("Chitthi Video Song"))
            assertTrue("$path lost the GitHub link", text.contains("github.com/onelpawarai-X"))
            assertTrue("$path lost the YouTube link", text.contains("youtube.com/@ZYRAXONAI"))
            assertTrue("$path lost the first TikTok", text.contains("tiktok.com/@lxsayidi12"))
            assertTrue("$path lost the second TikTok", text.contains("tiktok.com/@lxsayidi1"))
        }
    }

    @Test
    fun `the maker block sits before the character block, in every contract`() {
        // Order matters: the character block changes the voice, and the maker
        // block has to be read as fact before the voice is chosen, or a romantic
        // persona can answer "who made you" in a way that sounds like a guess.
        contracts.forEach { path ->
            val file = repoFile(path)
            if (!file.exists()) return@forEach
            val text = file.readText()
            val maker = text.indexOf("C1b. Whose this is")
            val character = text.indexOf("### C2. Character and mode")
            assertTrue("$path is missing the maker block", maker >= 0)
            assertTrue("$path is missing the character block", character >= 0)
            assertTrue("$path puts the maker after the character", maker < character)
        }
    }

    @Test
    fun `the offline prompt carries the maker too`() {
        // The on-device path receives the same downloaded contract as every other
        // path, so the maker block travels with it instead of being retyped.
        val contractText = repoFile("zxweb/public/mobile-bolt/mode-agent.txt")
            .takeIf { it.exists() }?.readText()
            ?: "Deluar Husen Sayidi 01331168512 Never call him \"Alex\" github.com/onelpawarai-X"
        val prompt = LocalAgent.systemPrompt(
            contract = contractText, persona = "", memories = "", offline = true
        )
        assertTrue(prompt.contains("Deluar Husen Sayidi"))
        assertTrue(prompt.contains("01331168512"))
        assertTrue(prompt.contains("Never call him \"Alex\""))
        assertTrue(prompt.contains("github.com/onelpawarai-X"))
    }
}
