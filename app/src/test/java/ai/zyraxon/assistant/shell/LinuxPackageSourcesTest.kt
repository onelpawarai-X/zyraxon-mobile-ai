// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.shell

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repositories the payload installs from, checked against the payload itself.
 *
 * The payload is a renamed copy of another distribution's, and the rename once
 * reached the repository URLs: every mirror file named a host under a domain that
 * does not resolve, and the repository directory was renamed with it. `pkg` found
 * no reachable mirror and refused to install anything — while the bundled packages
 * kept the base system alive, so the failure only appeared the first time a user
 * asked for something new, with no clue as to why.
 *
 * This reads the real archive and proves the URLs are real, so a payload swap that
 * reintroduces a branded host fails here rather than on a phone.
 */
class LinuxPackageSourcesTest {

    private val payloads = listOf(
        "payload64/assets/linux/aarch64/bootstrap.zip",
        "payload32/assets/linux/arm/bootstrap.zip",
        "payloadx64/assets/linux/x86_64/bootstrap.zip"
    )

    private val url = Regex("https?://[^/\\s\"']+")

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

    @Test
    fun `no repository host carries the brand`() {
        val checked = mutableListOf<String>()
        for (rel in payloads) {
            val file = open(rel) ?: continue
            checked += rel
            val offenders = sortedMapOf<String, String>()
            ZipFile(file).use { zip ->
                for (name in repositoryFiles(zip)) {
                    val text = zip.getInputStream(zip.getEntry(name)).readBytes()
                        .toString(Charsets.UTF_8)
                    for (host in url.findAll(text).map { it.value.removePrefix("https://").removePrefix("http://") }) {
                        if (host.lowercase().contains("zyrxon")) offenders[host] = name
                    }
                }
            }
            assertTrue(
                "$rel still names branded repositories:\n  " +
                    offenders.entries.joinToString("\n  ") { "${it.key}  (${it.value})" },
                offenders.isEmpty()
            )
        }
        assertTrue("no payload archive found to check", checked.isNotEmpty())
    }

    @Test
    fun `no repository path is the renamed one`() {
        for (rel in payloads) {
            val file = open(rel) ?: continue
            ZipFile(file).use { zip ->
                for (name in repositoryFiles(zip)) {
                    val text = zip.getInputStream(zip.getEntry(name)).readBytes()
                        .toString(Charsets.UTF_8)
                    assertFalse(
                        "$rel still points at the renamed repository path in $name",
                        Regex("/(zyrxon)-(main|root|x11)").containsMatchIn(text)
                    )
                }
            }
        }
    }

    @Test
    fun `the default repository is the upstream one`() {
        for (rel in payloads) {
            val file = open(rel) ?: continue
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("etc/apt/sources.list")
                val text = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                assertTrue(
                    "$rel installs from a host that is not the upstream one:\n$text",
                    text.contains("packages-cf.termux.dev") || text.contains("packages.termux.dev")
                )
            }
        }
    }

    @Test
    fun `the repair rewrites a host and a path, and leaves the rest`() {
        val broken = """
            # This file is sourced by pkg
            WEIGHT=10
            MAIN="https://packages-cf.zyrxon.dev/apt/zyrxon-main"
            ROOT="https://packages-cf.zyrxon.dev/apt/zyrxon-root"
            X11="https://packages-cf.zyrxon.dev/apt/zyrxon-x11"
        """.trimIndent()
        val fixed = LinuxRuntime.repairPackageHosts(broken)
        assertTrue(fixed.contains("https://packages-cf.termux.dev/apt/termux-main"))
        assertTrue(fixed.contains("https://packages-cf.termux.dev/apt/termux-root"))
        assertTrue(fixed.contains("https://packages-cf.termux.dev/apt/termux-x11"))
        assertFalse(fixed.contains("zyrxon-main"))
        // The comment and the key name are not a network location and must survive.
        assertTrue(fixed.contains("# This file is sourced by pkg"))
        assertTrue(fixed.contains("WEIGHT=10"))
    }

    @Test
    fun `the repair leaves a host that was never renamed alone`() {
        val untouched = """MAIN="https://mirror.albony.in/termux/termux-main""""
        assertEquals(untouched, LinuxRuntime.repairPackageHosts(untouched))
    }

    @Test
    fun `the repair covers every host the rename touched`() {
        // The repair table is the rename run backwards, and it has to be complete: a
        // host it misses keeps its branded name on a guest installed by an earlier
        // build — the defect the repair exists for — and nothing else would notice.
        // Each of these was found in the payload's mirror files before the repair.
        val renamed = listOf(
            "packages-cf.zyrxon.dev", "packages.zyrxon.dev", "mirror.zyrxon.dev",
            "mirrors.uzyrxon.dev", "zyrxon.niranjan.co", "zyrxon.3san.dev",
            "zyrxon.cdn.lumito.net", "zyrxon.danyael.xyz", "zyrxon.librehat.com",
            "zyrxon.mentality.rip", "wiki.zyrxon.com"
        )
        for (host in renamed) {
            val fixed = LinuxRuntime.repairPackageHosts("MAIN=\"https://$host/apt/zyrxon-main\"")
            assertFalse("$host is branded and the repair does not cover it", fixed.contains("zyrxon"))
            assertTrue("$host did not become the upstream host", fixed.contains("termux"))
        }
    }

    @Test
    fun `no repository file in the payload still carries a branded name`() {
        // The payload is the thing that ships. If a branded name is in it, the repair
        // table is irrelevant: the guest is installed with the bad name already there.
        val checked = mutableListOf<String>()
        for (rel in payloads) {
            val file = open(rel) ?: continue
            checked += rel
            ZipFile(file).use { zip ->
                for (name in repositoryFiles(zip)) {
                    val text = zip.getInputStream(zip.getEntry(name)).readBytes()
                        .toString(Charsets.UTF_8)
                    val branded = Regex("https?://([^/\\s\"']*zyrxon[^/\\s\"']*)")
                        .findAll(text).map { it.groupValues[1] }.toList()
                    assertTrue(
                        "$rel: $name still names $branded",
                        branded.isEmpty()
                    )
                }
            }
        }
        assertTrue("no payload archive found to check", checked.isNotEmpty())
    }
}
