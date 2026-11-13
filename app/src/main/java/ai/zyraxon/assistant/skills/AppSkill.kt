package ai.zyraxon.assistant.skills

import ai.zyraxon.assistant.core.MiniJson

/** One readable element of the screen, in reading order (what the accessibility tree gives us). */
data class UiNode(
    val text: String, val id: String = "", val cls: String = "",
    val click: Boolean = false, val editable: Boolean = false, val x: Int = 0, val y: Int = 0
)

/**
 * How a step finds its element again. It is not a screen position: it is the element's id, its
 * words, its kind and the words next to it — so it still works after the app is redesigned.
 */
data class Selector(
    val text: String = "", val id: String = "", val cls: String = "",
    val before: String = "", val after: String = "", val editable: Boolean = false
) {
    companion object {
        fun capture(nodes: List<UiNode>, index: Int): Selector {
            val n = nodes[index]
            return Selector(n.text, n.id, n.cls, nodes.getOrNull(index - 1)?.text.orEmpty(), nodes.getOrNull(index + 1)?.text.orEmpty(), n.editable)
        }
    }
}

data class Step(val op: String, val sel: Selector? = null, val value: String = "", val note: String = "")

data class Skill(
    val name: String, val description: String = "", val params: List<String> = emptyList(),
    val app: String = "", val steps: List<Step> = emptyList(), val runs: Int = 0
) {
    /** Turns a literal the user typed while teaching (a phone number, an amount) into a named parameter. */
    fun parameterize(literal: String, param: String): Skill {
        if (literal.isBlank()) return this
        fun r(s: String) = s.replace(literal, "{{$param}}")
        return copy(params = (params + param).distinct(), steps = steps.map { st ->
            st.copy(value = r(st.value), sel = st.sel?.copy(text = r(st.sel.text), before = r(st.sel.before), after = r(st.sel.after)))
        })
    }

    fun toJson(): String = MiniJson.stringify(mapOf(
        "name" to name, "description" to description, "params" to params, "app" to app, "runs" to runs,
        "steps" to steps.map { s ->
            mapOf("op" to s.op, "value" to s.value, "note" to s.note,
                "sel" to s.sel?.let { mapOf("text" to it.text, "id" to it.id, "cls" to it.cls, "before" to it.before, "after" to it.after, "editable" to it.editable) })
        }
    ))

    companion object {
        fun fromJson(src: String): Skill {
            val o = MiniJson.obj(MiniJson.parse(src))
            return Skill(
                MiniJson.str(o["name"]), MiniJson.str(o["description"]), MiniJson.list(o["params"]).map { MiniJson.str(it) },
                MiniJson.str(o["app"]), MiniJson.list(o["steps"]).map { e ->
                    val m = MiniJson.obj(e); val s = m["sel"]?.let { MiniJson.obj(it) }
                    Step(MiniJson.str(m["op"]), s?.let { Selector(MiniJson.str(it["text"]), MiniJson.str(it["id"]), MiniJson.str(it["cls"]), MiniJson.str(it["before"]), MiniJson.str(it["after"]), MiniJson.bool(it["editable"])) },
                        MiniJson.str(m["value"]), MiniJson.str(m["note"]))
                }, MiniJson.int(o["runs"])
            )
        }
    }
}

/** What a skill needs from the phone. The app implements it with the accessibility service; tests use a fake. */
interface SkillDevice {
    fun tree(): List<UiNode>
    fun tap(n: UiNode): Boolean
    fun type(text: String): Boolean
    fun key(name: String): Boolean
    fun openApp(app: String): Boolean
    fun scroll(forward: Boolean): Boolean
    fun pause(ms: Long)
}

data class Match(val index: Int, val score: Int)

object SkillEngine {
    const val FOUND = 40

    fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun words(s: String) = norm(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    private fun similar(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val x = words(a); val y = words(b); if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    /** Scores every element against the selector and returns the best, or null when nothing is close enough. */
    fun find(sel: Selector, nodes: List<UiNode>): Match? {
        var best: Match? = null
        nodes.forEachIndexed { i, n ->
            var s = 0.0
            if (sel.id.isNotEmpty() && n.id == sel.id) s += 50
            val t = norm(sel.text); val nt = norm(n.text)
            if (t.isNotEmpty()) {
                s += when {
                    nt == t -> 45.0
                    nt.contains(t) || t.contains(nt) && nt.length >= 2 -> 30.0
                    else -> 30 * similar(sel.text, n.text)
                }
            }
            if (sel.cls.isNotEmpty() && n.cls == sel.cls) s += 4
            if (sel.editable == n.editable) s += 3 else s -= 20
            if (sel.before.isNotEmpty() && norm(nodes.getOrNull(i - 1)?.text.orEmpty()) == norm(sel.before)) s += 8
            if (sel.after.isNotEmpty() && norm(nodes.getOrNull(i + 1)?.text.orEmpty()) == norm(sel.after)) s += 8
            if (best == null || s > best!!.score) best = Match(i, s.toInt())
        }
        return best?.takeIf { it.score >= FOUND }
    }

    fun fill(s: String, args: Map<String, String>): String = Regex("\\{\\{(\\w+)}}").replace(s) { args[it.groupValues[1]] ?: it.value }

    data class StepLog(val index: Int, val op: String, val ok: Boolean, val healed: Boolean = false, val detail: String = "")
    data class Run(val ok: Boolean, val log: List<StepLog>, val failedAt: Int = -1, val reason: String = "", val screen: List<String> = emptyList(), val skill: Skill)

    /**
     * Runs a skill. A step whose element moved or was renamed is found again by [find] (and the
     * selector is updated, so the skill heals itself); an element below the fold is scrolled to.
     * When a step cannot be done the run stops and reports where and what the screen showed, so the
     * agent can finish the job from that step by itself.
     */
    fun run(skill: Skill, args: Map<String, String>, dev: SkillDevice, scrollTries: Int = 4): Run {
        val missing = skill.params.filter { args[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) return Run(false, emptyList(), -1, "দরকারি তথ্য নেই: ${missing.joinToString()}", skill = skill)
        val log = ArrayList<StepLog>(); val steps = skill.steps.toMutableList(); var anyHealed = false
        for ((i, st) in steps.withIndex()) {
            when (st.op) {
                "open_app" -> { if (!dev.openApp(fill(st.value.ifBlank { skill.app }, args))) return fail(skill, steps, log, i, "অ্যাপ খোলা যায়নি", dev); dev.pause(1500); log += StepLog(i, st.op, true) }
                "wait" -> { dev.pause(st.value.toLongOrNull() ?: 1000); log += StepLog(i, st.op, true) }
                "key" -> { if (!dev.key(st.value)) return fail(skill, steps, log, i, "বোতাম চাপা যায়নি: ${st.value}", dev); dev.pause(500); log += StepLog(i, st.op, true) }
                "scroll" -> { dev.scroll(st.value != "back"); dev.pause(400); log += StepLog(i, st.op, true) }
                "tap", "type" -> {
                    val sel = st.sel?.let { it.copy(text = fill(it.text, args), before = fill(it.before, args), after = fill(it.after, args)) }
                        ?: return fail(skill, steps, log, i, "সিলেক্টর নেই", dev)
                    var m: Match? = null; var nodes = dev.tree(); var tries = 0
                    while (true) {
                        m = find(sel, nodes)
                        if (m != null || tries >= scrollTries) break
                        dev.scroll(true); dev.pause(400); tries++; nodes = dev.tree()
                    }
                    if (m == null) return fail(skill, steps, log, i, "'${sel.text}' স্ক্রিনে পাওয়া যায়নি", dev)
                    val node = nodes[m.index]
                    val changed = norm(node.text) != norm(sel.text) || (sel.id.isNotEmpty() && node.id != sel.id)
                    if (!dev.tap(node)) return fail(skill, steps, log, i, "ট্যাপ হয়নি", dev)
                    if (st.op == "type") { dev.pause(300); if (!dev.type(fill(st.value, args))) return fail(skill, steps, log, i, "লেখা যায়নি", dev) }
                    if (changed) { // heal: remember the element as it looks now
                        anyHealed = true
                        val old = steps[i].sel!!
                        steps[i] = steps[i].copy(sel = old.copy(
                            text = if (old.text.contains("{{")) old.text else node.text, id = node.id.ifEmpty { old.id }, cls = node.cls.ifEmpty { old.cls },
                            before = nodes.getOrNull(m.index - 1)?.text.orEmpty().let { if (old.before.contains("{{")) old.before else it },
                            after = nodes.getOrNull(m.index + 1)?.text.orEmpty().let { if (old.after.contains("{{")) old.after else it }))
                    }
                    dev.pause(500); log += StepLog(i, st.op, true, changed, node.text)
                }
                else -> return fail(skill, steps, log, i, "অজানা ধাপ: ${st.op}", dev)
            }
        }
        return Run(true, log, skill = skill.copy(steps = steps, runs = skill.runs + 1))
    }

    private fun fail(skill: Skill, steps: List<Step>, log: List<StepLog>, i: Int, why: String, dev: SkillDevice): Run {
        val seen = runCatching { dev.tree().take(40).map { it.text } }.getOrDefault(emptyList())
        return Run(false, log + StepLog(i, steps[i].op, false, false, why), i, why, seen, skill.copy(steps = steps))
    }

    /** Records a teaching session: every action the user (or agent) performs becomes a step. */
    class Recorder(val name: String, val app: String = "") {
        private val steps = ArrayList<Step>()
        fun open(app: String) { steps += Step("open_app", value = app) }
        fun tap(nodes: List<UiNode>, index: Int) { if (index in nodes.indices) steps += Step("tap", Selector.capture(nodes, index)) }
        fun type(nodes: List<UiNode>, index: Int, value: String) { if (index in nodes.indices) steps += Step("type", Selector.capture(nodes, index), value) }
        fun key(k: String) { steps += Step("key", value = k) }
        fun scroll(forward: Boolean = true) { steps += Step("scroll", value = if (forward) "fwd" else "back") }
        fun finish(description: String = ""): Skill = Skill(name, description, emptyList(), app, steps.toList())
        val size get() = steps.size
    }
}
