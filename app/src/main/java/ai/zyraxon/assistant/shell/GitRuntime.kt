package ai.zyraxon.assistant.shell

import android.content.Context
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Real `git clone`, `git pull` and `git status`, in pure Java.
 *
 * busybox ships about 400 applets but not git — git is far too large to be an
 * applet. Without this the terminal would be the one shell that cannot clone a
 * repository, which is exactly what it is most often used for. JGit is a
 * complete git implementation with no native code, so it works on every ABI.
 */
object GitRuntime {

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val directory: File? = null
    )

    /**
     * Clones `url` into `target`. A token is used when the repository is private;
     * for a public repository it can be left empty.
     */
    fun clone(
        context: Context,
        url: String,
        target: File,
        token: String = "",
        onProgress: (String) -> Unit = {}
    ): Outcome {
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            return Outcome(false, "${target.name} already exists and is not empty")
        }
        return try {
            val monitor = object : ProgressMonitor {
                override fun start(totalTasks: Int) {}
                override fun beginTask(title: String, totalWork: Int) = onProgress(title)
                override fun update(completed: Int) {}
                override fun endTask() {}
                override fun isCancelled() = false
            }
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(target)
                .setProgressMonitor(monitor)
            if (token.isNotBlank()) {
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("token", token))
            }
            val git = cmd.call()
            val branch = git.repository.branch
            val files = target.listFiles()?.size ?: 0
            git.close()
            Outcome(true, "Cloned $url → ${target.name} (branch $branch, $files entries)", target)
        } catch (e: Exception) {
            Outcome(false, e.message ?: e.toString())
        }
    }

    /** Fast-forwards an existing checkout. */
    fun pull(context: Context, repo: File, token: String = ""): Outcome = try {
        val git = Git.open(repo)
        val cmd = git.pull()
        if (token.isNotBlank()) {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("token", token))
        }
        val r = cmd.call()
        val text = r.toString()
        git.close()
        Outcome(true, text, repo)
    } catch (e: Exception) {
        Outcome(false, e.message ?: e.toString())
    }

    /** Branch, HEAD and changed files for a checkout. */
    fun status(context: Context, repo: File): Outcome = try {
        val git = Git.open(repo)
        val branch = git.repository.branch
        val changed = git.status().call().let { st ->
            (st.added + st.changed + st.modified + st.untracked).sorted()
        }
        git.close()
        val body = buildString {
            append("On branch $branch\n")
            if (changed.isEmpty()) append("nothing to commit, working tree clean")
            else changed.forEach { append("  $it\n") }
        }.trimEnd()
        Outcome(true, body, repo)
    } catch (e: Exception) {
        Outcome(false, e.message ?: e.toString())
    }

    /**
     * Serves a `git …` line, or returns null when the line is not git at all.
     *
     * Both the terminal and the assistant's `run_shell` call this, so a `git
     * clone` behaves the same however it was asked for. Anything beyond clone,
     * pull and status is refused with a pointer to `run_shell`, rather than
     * pretending to have run it.
     */
    fun runCommand(context: Context, line: String, cwd: File): Outcome? {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.firstOrNull() != "git") return null
        val sub = parts.getOrNull(1)
            ?: return Outcome(false, "usage: git clone <url> [dir] | git pull | git status")
        return when (sub) {
            "clone" -> {
                val url = parts.getOrNull(2)
                    ?: return Outcome(false, "git clone: missing repository URL")
                val name = parts.getOrNull(3)
                    ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                clone(context, url, File(cwd, name))
            }
            "pull" -> pull(context, cwd)
            "status" -> status(context, cwd)
            else -> Outcome(
                false,
                "git $sub: only clone, pull and status run here. Use run_shell for other git work."
            )
        }
    }
}
