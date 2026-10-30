package ai.zyraxon.assistant.viewmodel

import android.app.Application
import ai.zyraxon.assistant.shell.TestBusybox
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The terminal's behaviour as the user drives it: a line is typed, it runs, the
 * output lands in the scrollback. `cd` is checked because it is the one command
 * whose effect must outlive the process it started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun installBusybox() = TestBusybox.install(context)

    private fun newViewModel(): ZyraxonViewModel = ZyraxonViewModel(context)

    /** Types a line and waits for its output to land. */
    private fun run(vm: ZyraxonViewModel, line: String): List<ai.zyraxon.assistant.model.TerminalLine> {
        val before = vm.terminalLines.value.size
        vm.runTerminal(line)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val lines = vm.terminalLines.value
            if (lines.size > before && !vm.terminalBusy.value) return lines.drop(before)
            Thread.sleep(25)
        }
        return vm.terminalLines.value.drop(before)
    }

    @Test
    fun `a line runs and its output lands in the scrollback`() {
        val vm = newViewModel()
        val produced = run(vm, "echo hello-from-terminal")
        assertEquals(1, produced.size)
        assertTrue(produced.first().output, produced.first().output.contains("hello-from-terminal"))
        assertEquals(0, produced.first().exitCode)
    }

    @Test
    fun `a failing command is marked as an error`() {
        val vm = newViewModel()
        val produced = run(vm, "ls /no/such/place")
        assertEquals(1, produced.size)
        assertTrue("expected a non-zero exit", produced.first().exitCode != 0)
        assertTrue("expected the line to be flagged", produced.first().isError)
    }

    @Test
    fun `cd changes where the next command runs`() {
        val vm = newViewModel()
        run(vm, "mkdir -p sub/dir")
        run(vm, "cd sub/dir")
        assertEquals("~/sub/dir", vm.terminalCwd.value)

        val produced = run(vm, "pwd")
        assertTrue(produced.first().output, produced.first().output.trim().endsWith("sub/dir"))
    }

    @Test
    fun `cd to a missing directory fails without moving`() {
        val vm = newViewModel()
        val start = vm.terminalCwd.value
        val produced = run(vm, "cd nowhere-at-all")
        assertTrue(produced.first().isError)
        assertEquals(start, vm.terminalCwd.value)
    }

    @Test
    fun `an applet from the toolbox resolves by name`() {
        val vm = newViewModel()
        val produced = run(vm, "printf 'b\\na\\n' | sort | tr '\\n' ','")
        assertTrue(produced.first().output, produced.first().output.contains("a,b,"))
        assertFalse(produced.first().isError)
    }

    @Test
    fun `a new shell keeps its own scrollback and working directory`() {
        val vm = newViewModel()
        run(vm, "echo first-shell")
        run(vm, "mkdir -p work")
        run(vm, "cd work")
        assertEquals("~/work", vm.terminalCwd.value)

        // Open a second shell: it starts clean and at home, not in the first
        // shell's directory.
        vm.newTerminalSession()
        assertEquals(2, vm.terminalSessions.value.size)
        assertEquals("~", vm.terminalCwd.value)
        assertTrue("নতুন শেল খালি নয়", vm.terminalLines.value.isEmpty())

        // Switch back: the first shell still shows its output and its directory.
        val firstId = vm.terminalSessions.value.first().id
        vm.selectTerminalSession(firstId)
        assertEquals("~/work", vm.terminalCwd.value)
        assertTrue(
            "পুরনো শেলের আউটপুট হারিয়ে গেছে",
            vm.terminalLines.value.any { it.output.contains("first-shell") }
        )
    }

    @Test
    fun `closing the last shell opens a fresh one instead of leaving none`() {
        val vm = newViewModel()
        val only = vm.terminalSessions.value.first().id
        vm.closeTerminalSession(only)
        assertEquals("শেষ শেল বন্ধ হয়ে খালি হয়ে গেছে", 1, vm.terminalSessions.value.size)
        assertTrue(vm.activeTerminalId.value.isNotBlank())
    }
}
