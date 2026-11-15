package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.i18n.t
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The Automation hub: one place, six tabs, a button for everything the agent can do on its own. */
@Composable
fun HubPane(viewModel: ai.zyraxon.assistant.viewmodel.ZyraxonViewModel) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(ai.zyraxon.assistant.i18n.Loc.current) { if (HubTranslator.ensure(c) { viewModel.askLong(it) }) version++ }
    key(version) { HubPaneBody() }
}

@Composable
private fun HubPaneBody() {
    var tab by remember { mutableStateOf(0) }
    val names = listOf(t("স্কিল"), t("অটোপাইলট"), t("হিসাব"), t("টাইমলাইন"), t("ব্রেন"), t("কল"))
    HubCard {
        HubTitle(t("অটোমেশন হাব"))
        HubBody(t("ZYRAXON যা নিজে নিজে করতে পারে তার সব বাটন এখানে। প্রতিটি ট্যাবের ওপরে লেখা আছে সেটা কীভাবে কাজ করে।"))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        names.forEachIndexed { i, n -> HubChip(n, tab == i) { tab = i } }
    }
    when (tab) {
        0 -> HubSkillsTab()
        1 -> HubAutopilotTab()
        2 -> HubMoneyTab()
        3 -> HubTimelineTab()
        4 -> HubBrainTab()
        else -> HubCallTab()
    }
}
