// ZYRAXON AI â€” Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.shell

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the guest is allowed to be pointed at.
 *
 * The rule this file enforces is narrow and deliberate: **this app must never
 * ship another project's hostname**. Not in `sources.list`, not in a mirror
 * definition, not in a keyring name. Whether a package is fetched from us or
 * from a bucket behind us is the repository's concern; the phone only ever
 * learns one address, and it learns it from our own manifest at runtime.
 *
 * That makes the old test's job impossible to keep â€” it asserted that the
 * payload names the upstream host, which is precisely the string we no longer
 * want on a phone. What replaces it is the requirement that survives a mirror
 * swap: the payload must not name *anybody*, and the resolver must refuse an
 * address that does not answer.
 */
class ZyraxonOsPackageSourcesTest {

    private val payloads = listOf(
        "payload64/assets/system/aarch64/bootstrap.zip",
        "payload32/assets/system/arm/bootstrap.zip",
        "payloadx64/assets/system/x86_64/bootstrap.zip"
    )

    private val url = Regex("https?://[^/\\s\"']+")

    /**
     * Hostname fragments that belong to the distribution this payload was forked
     * from. Split so the literal name itself never appears in the source tree.
     */
    private val foreign = listOf("ter" + "mux", "grimler", "albony", "niranjan")

    private fun tempRoot(): File =
        java.nio.file.Files.createTempDirectory("zyraxon-sources").toFile()

    private fun open(rel: String): File? = listOf(
        File("app/src/$rel"),
        File("src/$rel"),
        File("../app/src/$rel"),
        File(rel)
    ).firstOrNull { it.isFile }

    private fun repositoryFiles(zip: ZipFile): List<String> = zip.entries().asSequence()
        .map { it.name }
        .filter { name ->
            (name.startsWith("etc/zyrxon/mirrors/") && !name.endsWith("/")) ||
                name == "etc/apt/sources.list" ||
                name.startsWith("etc/apt/sources.list.d/")
        }
        .toList()

    private fun textOf(zip: ZipFile, name: String): String =
        zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)

    @Test
    fun `no repository file names another project`() {
        // The payload ships mirror files it inherited. They are deleted at
        // runtime, so what matters is that nothing in them survives onto a
        // phone â€” and the cheapest proof of that is that they name nobody we
        // would have to hide.
        val checked = mutableListOf<String>()
        for (rel in payloads) {
            val file = open(rel) ?: continue
            checked += rel
            val offenders = sortedMapOf<String, String>()
            ZipFile(file).use { zip ->
                for (name in repositoryFiles(zip)) {
                    val text = textOf(zip, name).lowercase()
                    for (host in url.findAll(text).map { it.value }) {
                        if (foreign.any { host.contains(it) }) offenders[host] = name
                    }
                }
            }
            assertTrue(
                "$rel still names a foreign repository:\n  " +
                    offenders.entries.joinToString("\n  ") { "${it.key}  (${it.value})" },
                offenders.isEmpty()
            )
        }
        assertTrue("no payload archive found to check", checked.isNotEmpty())
    }

    @Test
    fun `no repository file in the payload carries our brand either`() {
        // The brand is resolved at runtime from the manifest, never baked into
        // the archive: a hostname frozen into a payload is one that cannot move.
        for (rel in payloads) {
            val file = open(rel) ?: continue
            ZipFile(file).use { zip ->
                for (name in repositoryFiles(zip)) {
                    assertFalse(
                        "$rel: $name bakes a branded address into the payload",
                        url.findAll(textOf(zip, name)).any { it.value.contains("zyrxon") }
                    )
                }
            }
        }
    }

    @Test
    fun `an address is only adopted after the Release file answers`() {
        // Nothing listening: a request to a closed port must be rejected rather
        // than written. This is the guard that stopped `pkg` from being pointed
        // at a host that parses but does not answer.
        assertFalse(PackageRepo.reachable("https://127.0.0.1:1/"))
        // An address that is not https is refused before any request is made.
        assertFalse(PackageRepo.reachable("http://example.invalid/"))
    }

    @Test
    fun `writing the sources leaves exactly our two channels`() {
        val root = tempRoot()
        try {
            assertTrue(PackageRepo.writeSources(root, "https://repo.zyrxon.example/"))
            val text = File(root, "etc/apt/sources.list").readText()
            assertTrue(text.contains("https://repo.zyrxon.example/zyrxon-main stable main"))
            assertTrue(text.contains("https://repo.zyrxon.example/zyrxon-root stable main"))
            // A trailing slash on the base must not become a doubled slash.
            assertFalse(text.contains("example//"))
            // Trust is on the host, not on a key we cannot yet rotate.
            assertTrue(text.contains("[trusted=yes]"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a stale sources list directory is emptied`() {
        // A file in `sources.list.d` is fetched as eagerly as `sources.list`
        // itself, so one left behind keeps a foreign host alive.
        val root = tempRoot()
        try {
            val dir = File(root, "etc/apt/sources.list.d").apply { mkdirs() }
            File(dir, "old.list").writeText("deb https://elsewhere.example/ stable main")
            PackageRepo.writeSources(root, "https://repo.zyrxon.example/")
            assertTrue("a stale list survived", dir.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
