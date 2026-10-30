package ai.zyraxon.assistant.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `git clone` is served by JGit rather than busybox, so it is tested against a
 * real repository: one is created, committed to, and then cloned. No network is
 * involved — the same code path runs, only the transport differs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun makeRepo(dir: File): Git {
        dir.mkdirs()
        val git = Git.init().setDirectory(dir).call()
        File(dir, "README.md").writeText("hello from zyraxon\n")
        git.add().addFilepattern("README.md").call()
        git.commit().setMessage("first commit").setAuthor("T", "t@example.com").call()
        return git
    }

    @Test
    fun `clone brings the files across from a real repository`() {
        val origin = File(context.filesDir, "git-origin").apply { deleteRecursively() }
        val git = makeRepo(origin)
        val target = File(context.filesDir, "git-clone-target").apply { deleteRecursively() }

        val outcome = GitRuntime.clone(context, origin.absolutePath, target)
        git.close()

        assertTrue(outcome.message, outcome.ok)
        val readme = File(target, "README.md")
        assertTrue("cloned repo has no README", readme.exists())
        assertEquals("hello from zyraxon", readme.readText().trim())
        assertTrue("cloned repo has no .git", File(target, ".git").exists())
    }

    @Test
    fun `cloning into a non-empty directory is refused`() {
        val origin = File(context.filesDir, "git-origin-2").apply { deleteRecursively() }
        val git = makeRepo(origin)
        val target = File(context.filesDir, "git-nonempty").apply {
            deleteRecursively(); mkdirs(); File(this, "blocker").writeText("x")
        }

        val outcome = GitRuntime.clone(context, origin.absolutePath, target)
        git.close()

        assertFalse(outcome.ok)
        assertTrue(outcome.message, outcome.message.contains("not empty"))
    }

    @Test
    fun `status reports the branch and a clean tree`() {
        val dir = File(context.filesDir, "git-status").apply { deleteRecursively() }
        val git = makeRepo(dir)
        val outcome = GitRuntime.status(context, dir)
        git.close()

        assertTrue(outcome.message, outcome.ok)
        assertTrue(outcome.message, outcome.message.contains("nothing to commit"))
    }

    @Test
    fun `status sees a file that was added after the commit`() {
        val dir = File(context.filesDir, "git-dirty").apply { deleteRecursively() }
        val git = makeRepo(dir)
        File(dir, "new.txt").writeText("untracked")
        val outcome = GitRuntime.status(context, dir)
        git.close()

        assertTrue(outcome.message, outcome.ok)
        assertTrue(outcome.message, outcome.message.contains("new.txt"))
    }

    @Test
    fun `a non-git line is not claimed by the git dispatcher`() {
        val dir = File(context.filesDir, "git-other").apply { deleteRecursively(); mkdirs() }
        assertEquals(null, GitRuntime.runCommand(context, "ls -la", dir))
    }

    @Test
    fun `the git dispatcher clones by line, the way the terminal types it`() {
        val origin = File(context.filesDir, "git-origin-3").apply { deleteRecursively() }
        val git = makeRepo(origin)
        val work = File(context.filesDir, "git-work").apply { deleteRecursively(); mkdirs() }

        val outcome = GitRuntime.runCommand(context, "git clone ${origin.absolutePath} fetched", work)
        git.close()

        assertTrue(outcome!!.message, outcome.ok)
        assertTrue(File(work, "fetched/README.md").exists())
    }

    @Test
    fun `the git dispatcher refuses a subcommand it does not serve`() {
        val dir = File(context.filesDir, "git-refuse").apply { deleteRecursively(); mkdirs() }
        val outcome = GitRuntime.runCommand(context, "git rebase --onto x", dir)
        assertFalse(outcome!!.ok)
        assertTrue(outcome.message, outcome.message.contains("only clone, pull and status"))
    }
}
