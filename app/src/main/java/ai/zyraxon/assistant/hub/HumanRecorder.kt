package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.AccessibilityBridge
import ai.zyraxon.assistant.skills.Skill
import ai.zyraxon.assistant.skills.SkillEngine
import ai.zyraxon.assistant.skills.UiNode

/**
 * "Teach by doing": the person does a job by hand in any app and ZYRAXON writes it down as a skill.
 * The accessibility service reports their taps, typing and scrolling; this class tidies them
 * (one "type" step per field, one "scroll" per swipe run) and turns them into a skill.
 */
object HumanRecorder {
    private class Ev(val kind: String, val node: UiNode, val value: String, val pkg: String)

    private val events = ArrayList<Ev>()
    @Volatile var recording = false; private set
    @Volatile var name = ""; private set
    @Volatile var count = 0; private set
    private var firstPkg = ""

    fun start(skillName: String): Boolean {
        if (AccessibilityBridge.userListener != null && recording) return false
        synchronized(events) { events.clear(); count = 0; firstPkg = "" }
        name = skillName.trim(); recording = true
        AccessibilityBridge.userListener = AccessibilityBridge.UserEvents { kind, text, id, cls, x, y, editable, pkg -> add(kind, text, id, cls, x, y, editable, pkg) }
        return true
    }

    fun add(kind: String, text: String, id: String, cls: String, x: Int, y: Int, editable: Boolean, pkg: String) {
        if (!recording) return
        synchronized(events) {
            if (firstPkg.isEmpty()) firstPkg = pkg
            val node = UiNode(text = if (kind == "type") "" else text, id = id, cls = cls, click = kind == "tap", editable = editable, x = x, y = y)
            val last = events.lastOrNull()
            when {
                kind == "type" && last != null && last.kind == "type" && last.node.id == id && last.node.cls == cls -> events[events.size - 1] = Ev("type", node, text, pkg)
                kind == "scroll" && last != null && last.kind == "scroll" -> {}
                else -> events += Ev(kind, node, text, pkg)
            }
            count = events.size
        }
    }

    /** Stops listening and returns the learned skill (null when nothing was done). */
    fun stop(description: String = ""): Skill? {
        recording = false; AccessibilityBridge.userListener = null
        val list = synchronized(events) { events.toList() }
        if (list.isEmpty() || name.isBlank()) return null
        val r = SkillEngine.Recorder(name, firstPkg)
        r.open(firstPkg)
        for (e in list) when (e.kind) {
            "tap" -> r.tap(listOf(e.node), 0)
            "type" -> r.type(listOf(e.node), 0, e.value)
            "scroll" -> r.scroll(true)
        }
        return r.finish(description)
    }

    fun cancel() { recording = false; AccessibilityBridge.userListener = null; synchronized(events) { events.clear(); count = 0 } }
}
