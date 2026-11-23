import ai.zyraxon.assistant.core.MiniJson
import ai.zyraxon.assistant.skills.*

var fails = 0; var passes = 0
fun check(name: String, c: Boolean) { if (c) passes++ else { fails++; println("FAIL: $name") } }

class FakeDev(var screens: List<List<UiNode>>, val startScreen: Int = 0) : SkillDevice {
    var cur = startScreen; var typed = ArrayList<String>(); var taps = ArrayList<String>(); var opened = ""; var scrolled = 0
    var nextOnTap: Map<String, Int> = emptyMap()
    override fun tree() = screens[cur]
    override fun tap(n: UiNode): Boolean { taps += n.text; nextOnTap[n.text]?.let { cur = it }; return true }
    override fun type(text: String): Boolean { typed += text; return true }
    override fun key(name: String) = true
    override fun openApp(app: String): Boolean { opened = app; return true }
    override fun scroll(forward: Boolean): Boolean { scrolled++; if (screens.size > cur + 1 && screens[cur].none { it.text == "পাঠাও" }) { /* reveal */ } ; return true }
    override fun pause(ms: Long) {}
}

fun main() {
    // --- MiniJson
    val j = MiniJson.parse("""{"a":[1,2.5,"x\nব",true,null],"b":{"c":"d"}}""")
    check("json parse", MiniJson.stringify(j) == """{"a":[1,2.5,"x\n${'ব'}",true,null],"b":{"c":"d"}}""")
    check("json roundtrip", MiniJson.parse(MiniJson.stringify(j)) == j)

    // --- screens of a payment app, version 1
    val v1 = listOf(
        UiNode("সেন্ড মানি", "id/send", "Button", click = true), UiNode("রিসিভারের নম্বর", "id/num_label"),
        UiNode("", "id/num", "EditText", editable = true).copy(text = "01XXXXXXXXX"),
        UiNode("পরিমাণ", "id/amt_label"), UiNode("0", "id/amt", "EditText", editable = true),
        UiNode("পরবর্তী", "id/next", "Button", click = true))
    // teach it
    val rec = SkillEngine.Recorder("বিকাশে-টাকা-পাঠাও", "bKash")
    rec.open("bKash"); rec.tap(v1, 0); rec.type(v1, 2, "01712345678"); rec.type(v1, 4, "500"); rec.tap(v1, 5)
    var sk = rec.finish("টাকা পাঠানো").parameterize("01712345678", "number").parameterize("500", "amount")
    check("params found", sk.params == listOf("number", "amount"))
    check("param in step", sk.steps[2].value == "{{number}}" && sk.steps[3].value == "{{amount}}")
    // json round trip
    val back = Skill.fromJson(sk.toJson()); check("skill json", back == sk)

    // --- run on the same screen
    val d1 = FakeDev(listOf(v1))
    val r1 = SkillEngine.run(sk, mapOf("number" to "01898765432", "amount" to "1200"), d1)
    check("run ok", r1.ok); check("typed values", d1.typed == listOf("01898765432", "1200")); check("opened", d1.opened == "bKash")
    check("taps", d1.taps == listOf("সেন্ড মানি", "01XXXXXXXXX", "0", "পরবর্তী"))
    check("run counter", r1.skill.runs == 1)
    // missing parameter
    val rm = SkillEngine.run(sk, mapOf("number" to "017"), FakeDev(listOf(v1)))
    check("missing param stops", !rm.ok && rm.reason.contains("amount"))

    // --- the app is redesigned: reordered, one label renamed, ids kept for fields
    val v2 = listOf(
        UiNode("পরিমাণ", "id/amt_label"), UiNode("0", "id/amt", "EditText", editable = true),
        UiNode("নম্বর দিন", "id/num_label"), UiNode("01XXXXXXXXX", "id/num", "EditText", editable = true),
        UiNode("Send Money", "id/send", "Button", click = true), UiNode("পরবর্তী ধাপ", "id/next", "Button", click = true))
    val d2 = FakeDev(listOf(v2))
    val r2 = SkillEngine.run(sk, mapOf("number" to "01898765432", "amount" to "1200"), d2)
    check("redesign still runs", r2.ok)
    check("redesign right fields", d2.typed == listOf("01898765432", "1200"))
    check("redesign healed", r2.log.count { it.healed } >= 2)
    // healed skill now matches v2 with exact text
    val d2b = FakeDev(listOf(v2)); val r2b = SkillEngine.run(r2.skill, mapOf("number" to "1", "amount" to "2"), d2b)
    check("healed skill run clean", r2b.ok && r2b.log.none { it.healed })

    // --- element below the fold is scrolled to
    class ScrollDev(val before: List<UiNode>, val after: List<UiNode>) : SkillDevice {
        var shown = before; var taps = ArrayList<String>()
        override fun tree() = shown; override fun tap(n: UiNode): Boolean { taps += n.text; return true }
        override fun type(text: String) = true; override fun key(name: String) = true; override fun openApp(app: String) = true
        override fun scroll(forward: Boolean): Boolean { shown = after; return true }; override fun pause(ms: Long) {}
    }
    val sd = ScrollDev(listOf(UiNode("হোম")), listOf(UiNode("হোম"), UiNode("পরবর্তী", "id/next", "Button", click = true)))
    val onlyNext = Skill("s", steps = listOf(Step("tap", Selector("পরবর্তী", "id/next", "Button"))))
    check("scroll to find", SkillEngine.run(onlyNext, emptyMap(), sd).ok && sd.taps == listOf("পরবর্তী"))

    // --- failure reports where and what was visible
    val gone = FakeDev(listOf(listOf(UiNode("একদম অন্য স্ক্রিন"), UiNode("লগইন"))))
    val rf = SkillEngine.run(onlyNext, emptyMap(), gone)
    check("fail stops", !rf.ok && rf.failedAt == 0 && rf.screen.contains("লগইন") && rf.reason.contains("পরবর্তী"))

    // --- an unrelated element must not be tapped (no false positives)
    val wrong = FakeDev(listOf(listOf(UiNode("ক্যামেরা", "id/cam", "Button", click = true), UiNode("সেটিংস", "id/set", "Button", click = true))))
    check("no false match", !SkillEngine.run(Skill("s", steps = listOf(Step("tap", Selector("পরবর্তী", "id/next", "Button")))), emptyMap(), wrong).ok && wrong.taps.isEmpty())

    // --- parameter inside selector text (tap a contact by name)
    val contacts = listOf(UiNode("রহিম"), UiNode("করিম", click = true), UiNode("সালমা"))
    val cd = FakeDev(listOf(contacts))
    val byName = Skill("c", params = listOf("who"), steps = listOf(Step("tap", Selector("{{who}}"))))
    check("param selector", SkillEngine.run(byName, mapOf("who" to "করিম"), cd).ok && cd.taps == listOf("করিম"))

    println("T1 passed=$passes failed=$fails"); if (fails > 0) System.exit(1)
}
