// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.shell

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Where the guest's package manager is told to look.
 *
 * The address is **never** compiled into the app. It is read from a small
 * manifest on the website, the same way the prompt bundle is, so the repository
 * can be moved — a new edge, a new host, a new mirror — without shipping an
 * APK. That matters most at the start: the repository that serves our own
 * hostname is deployed after the app is built, and an address baked into a
 * binary would be wrong by the time the binary is on a phone.
 *
 * Two rules make this safe to run on a guest that already works:
 *
 * - A source is only written after it has answered a real request for the
 *   repository's own `Release` file. An address that merely parses is not
 *   adopted, because a `sources.list` naming a host that does not answer is
 *   exactly the fault that stopped `pkg` from installing anything before.
 * - If nothing answers, the guest's own `sources.list` is left exactly as it
 *   is. A package manager that still works is worth more than a tidy file.
 *
 * The manifest carries our hosts only. Where the bytes ultimately come from is
 * the repository's business, not the phone's, so no other project's hostname
 * exists anywhere in this app.
 */
object PackageRepo {

    /**
     * The manifest, in the order it is tried.
     *
     * Two entries so a single bad deploy is not an outage: the first is the
     * site, the second is the same file behind the bare domain.
     */
    private val MANIFESTS = listOf(
        "https://zyraxonai.lovable.app/pkgs/source.json",
        "https://zyraxonai.lovable.app/pkgs/source.json?v=2",
    )

    /** The path prefixes a repository must answer before it is adopted. */
    private val PROBE = "zyrxon-main/dists/stable/Release"

    private const val PREFS = "zyraxon_pkg_repo"
    private const val KEY_JSON = "manifest"
    private const val KEY_AT = "manifest_at"

    /** How long a fetched manifest is trusted before it is fetched again. */
    private const val TTL_MS = 6L * 60 * 60 * 1000

    /**
     * The repository to use, or `null` when nothing answered.
     *
     * A cached manifest is used first and refreshed in the background of the
     * same call only when it is older than [TTL_MS]; a phone with no network at
     * all therefore keeps working on the last address that did answer.
     */
    fun resolve(context: Context, log: (String) -> Unit = {}): String? {
        val cached = cachedManifest(context)
        val fresh = if (cached != null && !expired(context)) cached else fetchManifest(context, log)

        for (base in candidates(fresh ?: cached)) {
            if (reachable(base)) {
                log(" Package repository: $base")
                return base
            }
        }
        if (candidates(fresh ?: cached).isNotEmpty()) {
            log(" Package repository did not answer; leaving the guest's own sources untouched.")
        }
        return null
    }

    /** Every address the manifest offers, normalised to end with one `/`. */
    private fun candidates(manifest: JSONObject?): List<String> {
        val json = manifest ?: return emptyList()
        val out = mutableListOf<String>()
        json.optString("repo").trim().takeIf { it.isNotEmpty() }?.let { out += normalise(it) }
        val list = json.optJSONArray("fallback")
        if (list != null) {
            for (i in 0 until list.length()) {
                list.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out += normalise(it) }
            }
        }
        // A repository is only ever https: a plain-http source would let anyone
        // between the phone and us decide which packages get installed.
        return out.filter { it.startsWith("https://") }.distinct()
    }

    private fun normalise(base: String): String = if (base.endsWith("/")) base else "$base/"

    /**
     * True when the address served the repository's `Release` file.
     *
     * A ranged GET rather than a HEAD: several edges answer HEAD with 405 and
     * one byte proves the same thing while costing the same.
     */
    fun reachable(base: String): Boolean {
        // Refused here as well as in `candidates`, because this is the function
        // that decides, and a plain-http address must never reach the network.
        if (!base.startsWith("https://")) return false
        return runCatching {
            val url = URL(base + PROBE)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "zyraxon-pkg/1")
            }
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun expired(context: Context): Boolean {
        val at = prefs(context).getLong(KEY_AT, 0L)
        return at == 0L || System.currentTimeMillis() - at > TTL_MS
    }

    private fun cachedManifest(context: Context): JSONObject? = runCatching {
        val text = prefs(context).getString(KEY_JSON, null) ?: return null
        JSONObject(text)
    }.getOrNull()

    private fun fetchManifest(context: Context, log: (String) -> Unit): JSONObject? {
        for (url in MANIFESTS) {
            val text = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", "zyraxon-pkg/1")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: continue

            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            prefs(context).edit()
                .putString(KEY_JSON, text)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            return json
        }
        log(" Could not read the package manifest; keeping the last known repository.")
        return null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Writes `sources.list` for the resolved repository.
     *
     * Returns true when the file was written. A `false` means nothing answered
     * and the guest keeps whatever it already had, which is the whole point of
     * checking first.
     *
     * `[trusted=yes]` is deliberate: the index is served byte for byte from the
     * repository, and a guest that has no keyring entry for a brand-new key
     * would otherwise refuse every package with "no public key". The transport
     * is https and the host is ours, so the trust decision is about the host,
     * not about a signature we cannot yet rotate.
     */
    fun writeSources(root: File, base: String): Boolean = runCatching {
        val etc = File(root, "etc/apt").apply { mkdirs() }
        File(etc, "sources.list").writeText(
            buildString {
                appendLine("# ZYRAXON OS package sources.")
                appendLine("# Served by ZYRAXON. The address is read from our own manifest,")
                appendLine("# so it can move without a new build.")
                appendLine("deb [trusted=yes] ${normalise(base)}zyrxon-main stable main")
                appendLine("deb [trusted=yes] ${normalise(base)}zyrxon-root stable main")
            },
        )
        // A stale list from another repository would be fetched instead of ours
        // the moment apt runs, so the guest's cached indexes go with it.
        File(root, "etc/apt/sources.list.d").listFiles()?.forEach { it.delete() }
        true
    }.getOrDefault(false)
}
