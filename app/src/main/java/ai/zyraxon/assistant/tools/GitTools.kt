package ai.zyraxon.assistant.tools

import ai.zyraxon.assistant.core.safe

import android.content.Context
import ai.zyraxon.assistant.ai.ToolRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git and GitHub for the phone, used by the assistant's `git` and `github_pr`
 * tools and by the Code panel's buttons — one implementation, so what the user
 * taps and what the agent runs behave the same.
 */
object GitTools {

    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun token(context: Context): String =
        context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE).getString("gh_token", "")?.trim().orEmpty()

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE).edit().putString("gh_token", token.trim()).apply()
    }

    private fun creds(context: Context) = token(context).takeIf { it.isNotBlank() }
        ?.let { UsernamePasswordCredentialsProvider("token", it) }

    private fun ok(summary: String, data: Any? = null) = JSONObject().put("ok", true).put("summary", summary).apply { if (data != null) put("data", data) }
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).put("summary", msg)

    fun projectsRoot(context: Context): File = File(ToolRegistry.workspace(context), "projects").apply { mkdirs() }

    private fun resolveDir(context: Context, dir: String): File {
        if (dir.isBlank()) {
            val last = context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE).getString("git_dir", "").orEmpty()
            if (last.isNotBlank() && File(last).exists()) return File(last)
            return ToolRegistry.workspace(context)
        }
        val f = File(dir)
        return if (f.isAbsolute) f else File(ToolRegistry.workspace(context), dir)
    }

    private fun remember(context: Context, dir: File) {
        context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE).edit().putString("git_dir", dir.absolutePath).apply()
    }

    /** Every folder under the workspace that holds a git repository. */
    fun findRepos(context: Context): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 3) return
            if (File(d, ".git").exists()) { out.add(d); return }
            d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { walk(it, depth + 1) }
        }
        walk(ToolRegistry.workspace(context), 0)
        return out
    }

    fun originRepo(repoDir: File): String? = try {
        Git.open(repoDir).use { g ->
            val u = g.repository.config.getString("remote", "origin", "url") ?: return null
            u.removePrefix("https://github.com/").removePrefix("git@github.com:").removeSuffix(".git").trim('/')
        }
    } catch (_: Exception) { null }

    fun run(context: Context, args: JSONObject, live: ((String) -> Unit)? = null): JSONObject {
        val action = args.safe("action").lowercase()
        val monitor = object : ProgressMonitor {
            override fun start(totalTasks: Int) {}
            override fun beginTask(title: String, totalWork: Int) { live?.invoke("$title\n") }
            override fun update(completed: Int) {}
            override fun endTask() {}
            override fun isCancelled() = false
        }
        return try {
            when (action) {
                "scan" -> {
                    val repos = findRepos(context)
                    val arr = JSONArray()
                    for (r in repos) Git.open(r).use { g ->
                        val st = g.status().call()
                        arr.put(JSONObject().put("dir", r.absolutePath).put("branch", g.repository.branch)
                            .put("changed", st.added.size + st.changed.size + st.modified.size + st.untracked.size + st.removed.size + st.missing.size)
                            .put("origin", originRepo(r) ?: ""))
                    }
                    ok("${repos.size}টি রিপো পাওয়া গেছে", arr)
                }
                "clone" -> {
                    val url = args.safe("url").trim()
                    if (url.isEmpty()) return err("url দরকার")
                    val name = args.safe("dir").ifBlank { url.trimEnd('/').substringAfterLast('/').removeSuffix(".git") }
                    val target = if (File(name).isAbsolute) File(name) else File(projectsRoot(context), name)
                    if (target.exists() && target.list()?.isNotEmpty() == true) return err("${target.name} আগেই আছে ও খালি নয়")
                    val c = Git.cloneRepository().setURI(url).setDirectory(target).setProgressMonitor(monitor)
                    args.safe("branch").takeIf { it.isNotBlank() }?.let { c.setBranch(it) }
                    creds(context)?.let { c.setCredentialsProvider(it) }
                    c.call().close()
                    remember(context, target)
                    ok("ক্লোন হয়েছে: ${target.absolutePath}", JSONObject().put("dir", target.absolutePath))
                }
                "init" -> {
                    val d = resolveDir(context, args.safe("dir")).apply { mkdirs() }
                    Git.init().setDirectory(d).call().close(); remember(context, d)
                    ok("git init: ${d.absolutePath}")
                }
                else -> {
                    val d = resolveDir(context, args.safe("dir"))
                    if (!File(d, ".git").exists()) return err("${d.name} একটা git রিপো নয় — আগে clone বা init করো")
                    remember(context, d)
                    Git.open(d).use { g -> inRepo(context, g, d, action, args, monitor) }
                }
            }
        } catch (e: Exception) {
            err(e.message ?: e.toString())
        }
    }

    private fun inRepo(context: Context, g: Git, d: File, action: String, args: JSONObject, monitor: ProgressMonitor): JSONObject {
        return when (action) {
            "status" -> {
                val st = g.status().call()
                val text = buildString {
                    append("On branch ").append(g.repository.branch).append('\n')
                    val rows = mutableListOf<String>()
                    st.added.forEach { rows.add("A $it") }; st.changed.forEach { rows.add("M $it") }
                    st.modified.forEach { rows.add(" M $it") }; st.removed.forEach { rows.add("D $it") }
                    st.missing.forEach { rows.add(" D $it") }; st.untracked.forEach { rows.add("?? $it") }
                    st.conflicting.forEach { rows.add("UU $it") }
                    if (rows.isEmpty()) append("working tree clean") else rows.sorted().forEach { append(it).append('\n') }
                }.trimEnd()
                ok("স্ট্যাটাস", text)
            }
            "add" -> {
                val files = args.safe("files").trim()
                if (files.isEmpty() || files == "." || files == "all") {
                    g.add().addFilepattern(".").call()
                    g.add().setUpdate(true).addFilepattern(".").call()
                } else files.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.forEach { g.add().addFilepattern(it).call() }
                ok("add সম্পন্ন")
            }
            "commit" -> {
                val msg = args.safe("message").ifBlank { "update" }
                g.add().addFilepattern(".").call()
                g.add().setUpdate(true).addFilepattern(".").call()
                val c = g.commit().setMessage(msg).setAuthor("ZYRAXON", "zyraxon@users.noreply.github.com")
                    .setCommitter("ZYRAXON", "zyraxon@users.noreply.github.com").call()
                ok("কমিট ${c.name.take(7)}: $msg", c.name)
            }
            "push" -> {
                val remote = args.safe("remote").ifBlank { "origin" }
                val cmd = g.push().setRemote(remote).setProgressMonitor(monitor)
                val branch = args.safe("branch").ifBlank { g.repository.branch }
                cmd.add("refs/heads/$branch:refs/heads/$branch")
                creds(context)?.let { cmd.setCredentialsProvider(it) }
                    ?: return err("GitHub টোকেন নেই — কোড প্যানেল/সেটিংসে টোকেন দাও")
                val res = cmd.call()
                val msgs = res.flatMap { r -> r.remoteUpdates.map { "${it.remoteName}: ${it.status}" } }
                val bad = res.flatMap { it.remoteUpdates }.firstOrNull { it.status.name !in setOf("OK", "UP_TO_DATE") }
                if (bad != null) err("push ব্যর্থ: ${bad.status} ${bad.message.orEmpty()}") else ok("push হয়েছে $branch", msgs.joinToString("\n"))
            }
            "pull" -> {
                val c = g.pull().setProgressMonitor(monitor)
                creds(context)?.let { c.setCredentialsProvider(it) }
                val r = c.call()
                if (r.isSuccessful) ok("pull সম্পন্ন", r.toString()) else err("pull ব্যর্থ: $r")
            }
            "branch" -> {
                val name = args.safe("name").ifBlank { args.safe("branch") }
                if (name.isBlank()) {
                    ok("ব্রাঞ্চ", g.branchList().call().joinToString("\n") { (if (it.name == "refs/heads/" + g.repository.branch) "* " else " ") + it.name.removePrefix("refs/heads/") })
                } else { g.checkout().setCreateBranch(true).setName(name).call(); ok("নতুন ব্রাঞ্চ: $name") }
            }
            "checkout" -> {
                val name = args.safe("branch").ifBlank { args.safe("name") }
                if (name.isBlank()) err("branch দরকার") else { g.checkout().setName(name).call(); ok("checkout: $name") }
            }
            "log" -> {
                val n = args.optInt("count", 10).coerceIn(1, 100)
                val rows = g.log().setMaxCount(n).call().joinToString("\n") { "${it.name.take(7)} ${it.shortMessage}" }
                ok("লগ", rows)
            }
            "diff" -> {
                val out = java.io.ByteArrayOutputStream()
                g.diff().setOutputStream(out).call()
                val t = out.toString("UTF-8")
                ok(if (t.isBlank()) "কোনো পরিবর্তন নেই" else "diff", t.take(40000))
            }
            "remote" -> {
                val url = args.safe("url").trim()
                if (url.isEmpty()) err("url দরকার") else {
                    val c = g.repository.config; c.setString("remote", "origin", "url", url); c.save(); ok("origin = $url")
                }
            }
            else -> err("অজানা git action: $action")
        }
    }

    // ------------------------------------------------------------- GitHub API --

    private fun api(context: Context, method: String, url: String, body: JSONObject? = null): Pair<Int, String> {
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer ${token(context)}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        b.method(method, rb ?: if (method == "GET" || method == "HEAD") null else "".toRequestBody())
        http.newCall(b.build()).execute().use { return it.code to (it.body?.string().orEmpty()) }
    }

    fun pr(context: Context, args: JSONObject): JSONObject {
        if (token(context).isBlank()) return err("GitHub টোকেন নেই — কোড প্যানেল/সেটিংসে টোকেন দাও")
        val repo = args.safe("repo").trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
            .ifBlank { resolveDir(context, args.safe("dir")).let { originRepo(it) } ?: "" }
        if (repo.isBlank()) return err("repo (owner/name) দরকার")
        val base = "https://api.github.com/repos/$repo"
        return try {
            when (args.safe("action").lowercase()) {
                "create" -> {
                    val dir = resolveDir(context, args.safe("dir"))
                    val head = args.safe("head").ifBlank { try { Git.open(dir).use { it.repository.branch } } catch (_: Exception) { "" } }
                    val body = JSONObject().put("title", args.safe("title").ifBlank { "Update from ZYRAXON" })
                        .put("head", head).put("base", args.safe("base").ifBlank { "main" })
                        .put("body", args.safe("body"))
                    val (c, t) = api(context, "POST", "$base/pulls", body)
                    val j = JSONObject(t.ifBlank { "{}" })
                    if (c in 200..299) ok("PR #${j.optInt("number")} তৈরি: ${j.safe("html_url")}", j.safe("html_url"))
                    else err("PR তৈরি হয়নি ($c): " + (j.safe("message") + " " + j.optJSONArray("errors")?.toString().orEmpty()).trim())
                }
                "list" -> {
                    val (c, t) = api(context, "GET", "$base/pulls?state=open&per_page=30")
                    if (c !in 200..299) return err("PR তালিকা ($c)")
                    val arr = JSONArray(t); val rows = StringBuilder()
                    for (i in 0 until arr.length()) arr.getJSONObject(i).let { rows.append("#${it.optInt("number")} ${it.safe("title")} [${it.optJSONObject("head")?.safe("ref")}]\n") }
                    ok("${arr.length()}টি খোলা PR", rows.toString().ifBlank { "কোনো খোলা PR নেই" })
                }
                "get" -> {
                    val (c, t) = api(context, "GET", "$base/pulls/${args.optInt("number")}")
                    if (c in 200..299) ok("PR", t.take(6000)) else err("PR পাওয়া যায়নি ($c)")
                }
                "merge" -> {
                    val (c, t) = api(context, "PUT", "$base/pulls/${args.optInt("number")}/merge",
                        JSONObject().put("merge_method", args.safe("method").ifBlank { "squash" }))
                    val j = JSONObject(t.ifBlank { "{}" })
                    if (c in 200..299 && j.optBoolean("merged", true)) ok("PR #${args.optInt("number")} মার্জ হয়েছে")
                    else err("মার্জ হয়নি ($c): ${j.safe("message")}")
                }
                "comment" -> {
                    val (c, _) = api(context, "POST", "$base/issues/${args.optInt("number")}/comments", JSONObject().put("body", args.safe("body")))
                    if (c in 200..299) ok("মন্তব্য যোগ হয়েছে") else err("মন্তব্য যায়নি ($c)")
                }
                "close" -> {
                    val (c, _) = api(context, "PATCH", "$base/pulls/${args.optInt("number")}", JSONObject().put("state", "closed"))
                    if (c in 200..299) ok("PR বন্ধ") else err("PR বন্ধ হয়নি ($c)")
                }
                else -> err("অজানা github_pr action")
            }
        } catch (e: Exception) { err(e.message ?: e.toString()) }
    }
}
