package ai.zyraxon.assistant.ui

import ai.zyraxon.assistant.model.AppTab
import ai.zyraxon.assistant.ui.components.SessionNavPane
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user asked for the top of the screen to hold nothing but a Settings button,
 * with every section switch moved inside Settings. These render the real Settings
 * navigation pane against a real ViewModel, so a section that is missing from the
 * pane — or one that fails to actually switch the screen — fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun newViewModel(): ZyraxonViewModel =
        ZyraxonViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `every section is reachable from inside settings`() {
        compose.setContent { SessionNavPane(viewModel = newViewModel(), onDismiss = {}) }

        AppTab.values().forEach { tab ->
            val label = if (tab == AppTab.TERMINAL) "⌘ ZX — সিস্টেম শেল" else tab.title
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `tapping a section switches the screen and closes settings`() {
        val viewModel = newViewModel()
        var dismissed = 0
        compose.setContent { SessionNavPane(viewModel = viewModel, onDismiss = { dismissed++ }) }

        compose.onNodeWithText(AppTab.TASKS.title).performClick()
        compose.waitForIdle()

        assertEquals("সেকশন বদলানো হয়নি", AppTab.TASKS, viewModel.activeTab.value)
        assertEquals("সেকশন বাছাই করার পর সেটিংস বন্ধ হয়নি", 1, dismissed)
    }

    @Test
    fun `the microphone switch lives in settings and holds its state`() {
        val viewModel = newViewModel()
        viewModel.setMicEnabled(true)
        compose.setContent { SessionNavPane(viewModel = viewModel, onDismiss = {}) }

        compose.onNodeWithText("🎙️ মাইক চালু — বন্ধ করতে চাপুন").performClick()
        compose.waitForIdle()

        assertEquals("মাইক বন্ধ করা যায়নি", false, viewModel.alwaysListening.value)
    }
}
