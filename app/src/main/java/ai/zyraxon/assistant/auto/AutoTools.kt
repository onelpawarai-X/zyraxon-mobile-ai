package ai.zyraxon.assistant.auto

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.autopilot.Autopilot
import ai.zyraxon.assistant.autopilot.Event
import ai.zyraxon.assistant.autopilot.Kind
import ai.zyraxon.assistant.autopilot.Rule
import ai.zyraxon.assistant.brain.Brain
import ai.zyraxon.assistant.core.MiniJson
import ai.zyraxon.assistant.core.safe
import ai.zyraxon.assistant.finance.Dir
import ai.zyraxon.assistant.finance.Fmt
import ai.zyraxon.assistant.finance.Ledger
import ai.zyraxon.assistant.finance.SmsParser
import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.office.OfficeFiles
import ai.zyraxon.assistant.skills.Skill
import ai.zyraxon.assistant.skills.SkillDevice
import ai.zyraxon.assistant.skills.SkillEngine
import ai.zyraxon.assistant.skills.UiNode
import ai.zyraxon.assistant.timeline.Timeline
import ai.zyraxon.assistant.viewer.DocViewer
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar

/**
 * The agent-facing side of the six engines: app skills, autopilot rules, the SMS money ledger,
 * the action timeline with undo, the offline brain, and the call agent switch.
 */
object AutoTools {
    val NAMES = setOf(
        "skill_record", "skill_run", "skill_list", "skill_delete", "skill_import",
        "autopilot_add", "autopilot_list", "autopilot_remove", "autopilot_toggle",
        "ledger_scan", "ledger_report", "ledger_budget",
        "timeline_list", "timeline_undo",
        "brain_index", "brain_search",
        "call_agent", "call_log"
    )

    /** Set by the ViewModel: runs an automation task as an agent turn. Until then tasks wait in a queue on disk. */
    @Volatile var dispatcher: ((title: String, prompt: String) -> Unit)? = null

    fun dir(c: Context): File = File(c.filesDir, "zyraxon").apply { mkdirs() }
    fun prefs(c: Context) = c.getSharedPreferences("zyraxon_auto", Context.MODE_PRIVATE)

    private fun ok(data: Any?, summary: String) = JSONObject().put("ok", true).put("summary", Loc.tool(summary)).put("data", data)
    private fun err(error: String, summary: String) = JSONObject().put("ok", false).put("error", error).put("summary", Loc.tool(summary))

    fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    private fun mediaFile(path: File, title: String) = JSONArray().put(JSONObject().put("kind", "file").put("path", path.absolutePath).put("title", title))

    // ------------------------------------------------------------------ timeline (shared)
    @Volatile private var tl: Timeline? = null
    fun timeline(c: Context): Timeline = tl ?: synchronized(this) { tl ?: Timeline(File(dir(c), "timeline")).also { tl = it } }

    // ------------------------------------------------------------------ the phone as a skill device
    fun readNodes(): List<UiNode> {
        val arr = runCatching { JSONArray(DeviceBridge.readScreen()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<UiNode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += UiNode(o.optString("text"), o.optString("id"), o.optString("cls"), o.optBoolean("click"), o.optBoolean("editable"), o.optInt("x"), o.optInt("y"))
        }
        return out
    }

    class PhoneDevice(private val c: Context) : SkillDevice {
        override fun tree() = readNodes()
        override fun tap(n: UiNode) = DeviceBridge.tap(n.x.toFloat(), n.y.toFloat())
        override fun type(text: String) = DeviceBridge.typeText(text)
        override fun key(name: String) = DeviceBridge.pressKey(name)
        override fun openApp(app: String): Boolean { DeviceBridge.openApp(c, app); return true }
        override fun scroll(forward: Boolean): Boolean {
            val m = c.resources.displayMetrics; val x = m.widthPixels / 2f
            val a = m.heightPixels * 0.72f; val b = m.heightPixels * 0.3f
            return if (forward) DeviceBridge.swipe(x, a, x, b) else DeviceBridge.swipe(x, b, x, a)
        }
        override fun pause(ms: Long) { Thread.sleep(ms) }
    }

    // ------------------------------------------------------------------ skills
    @Volatile private var rec: SkillEngine.Recorder? = null
    private var recApp = ""
    private fun skillsDir(c: Context) = File(dir(c), "skills").apply { mkdirs() }
    private fun skillName(s: String) = s.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').take(40)
    private fun loadSkill(c: Context, name: String): Skill? =
        File(skillsDir(c), skillName(name) + ".zxskill").takeIf { it.isFile }?.let { runCatching { Skill.fromJson(it.readText()) }.getOrNull() }

    /** While a skill is being taught, every UI tool the agent (or the user's own request) uses becomes a step. */
    fun onUiTool(name: String, args: JSONObject) {
        val r = rec ?: return
        try {
            when (name) {
                "open_app" -> { val a = args.safe("package").ifBlank { args.safe("name") }.ifBlank { args.safe("app") }; if (recApp.isEmpty()) recApp = a; r.open(a) }
                "tap_text" -> { val n = readNodes(); val t = SkillEngine.norm(args.safe("text"))
                    val i = n.indexOfFirst { SkillEngine.norm(it.text) == t }.takeIf { it >= 0 } ?: n.indexOfFirst { SkillEngine.norm(it.text).contains(t) }
                    if (i >= 0) r.tap(n, i) else r.tap(listOf(UiNode(args.safe("text"))), 0) }
                "tap" -> { val n = readNodes(); val x = args.optDouble("x"); val y = args.optDouble("y")
                    val i = n.indices.minByOrNull { Math.hypot(n[it].x - x, n[it].y - y) }
                    if (i != null && Math.hypot(n[i].x - x, n[i].y - y) < 120) r.tap(n, i) }
                "type_text" -> { val n = readNodes(); val i = n.indexOfFirst { it.editable }; if (i >= 0) r.type(n, i, args.safe("text")) }
                "press_key" -> r.key(args.safe("key"))
                "swipe" -> r.scroll(args.optDouble("y2") < args.optDouble("y1"))
            }
        } catch (_: Exception) {}
    }

    private fun skillTool(c: Context, name: String, args: JSONObject): JSONObject = when (name) {
        "skill_record" -> when (args.safe("action", "status").lowercase()) {
            "start" -> {
                val n = skillName(args.safe("name")); if (n.isEmpty()) err("no-name", "স্কিলের নাম দাও")
                else { rec = SkillEngine.Recorder(n); recApp = ""; ok(n, "স্কিল রেকর্ড শুরু — এখন কাজটা tap_text/type_text দিয়ে করো, শেষে skill_record stop") }
            }
            "stop" -> {
                val r = rec
                if (r == null) err("not-recording", "রেকর্ডিং চালু নেই")
                else {
                    var sk = r.finish(args.safe("description")).copy(app = recApp)
                    (args.optJSONObject("params") ?: JSONObject()).let { p -> p.keys().forEach { lit -> sk = sk.parameterize(lit, p.optString(lit)) } }
                    rec = null
                    if (sk.steps.isEmpty()) err("empty", "কোনো ধাপ রেকর্ড হয়নি")
                    else { File(skillsDir(c), sk.name + ".zxskill").writeText(sk.toJson()); ok(JSONObject().put("name", sk.name).put("steps", sk.steps.size).put("params", JSONArray(sk.params)), "স্কিল সেভ হয়েছে: ${sk.name} (${sk.steps.size} ধাপ)") }
                }
            }
            else -> ok(JSONObject().put("recording", rec != null).put("steps", rec?.size ?: 0), if (rec != null) "রেকর্ড চলছে" else "রেকর্ডিং চালু নেই")
        }
        "skill_list" -> {
            val files = skillsDir(c).listFiles { f -> f.extension == "zxskill" }?.sortedBy { it.name } ?: emptyList()
            val want = args.safe("name")
            if (want.isNotBlank() && args.optBoolean("export")) {
                val f = File(skillsDir(c), skillName(want) + ".zxskill")
                if (!f.isFile) err("no-skill", "এই স্কিল নেই")
                else ok(JSONObject().put("path", f.absolutePath), "স্কিল ফাইল তৈরি: ${f.name}").put("media", mediaFile(f, f.name))
            } else ok(JSONArray(files.mapNotNull { f -> runCatching { Skill.fromJson(f.readText()) }.getOrNull() }.map {
                JSONObject().put("name", it.name).put("description", it.description).put("params", JSONArray(it.params)).put("steps", it.steps.size).put("runs", it.runs).put("app", it.app)
            }), "${files.size}টি স্কিল আছে")
        }
        "skill_delete" -> if (File(skillsDir(c), skillName(args.safe("name")) + ".zxskill").delete()) ok(true, "স্কিল মুছে ফেলা হয়েছে") else err("no-skill", "এই স্কিল নেই")
        "skill_import" -> {
            val f = File(args.safe("path"))
            val sk = runCatching { Skill.fromJson(f.readText()) }.getOrNull()
            if (sk == null || sk.steps.isEmpty()) err("bad-skill", "স্কিল ফাইল পড়া যায়নি")
            else { File(skillsDir(c), skillName(sk.name) + ".zxskill").writeText(sk.toJson()); ok(JSONObject().put("name", sk.name).put("params", JSONArray(sk.params)), "স্কিল আনা হয়েছে: ${sk.name}") }
        }
        else -> err("unknown", name)
    }

    private fun skillRun(c: Context, args: JSONObject): JSONObject {
        val sk = loadSkill(c, args.safe("name")) ?: return err("no-skill", "এই নামের স্কিল নেই — skill_list দিয়ে দেখো")
        val a = HashMap<String, String>(); (args.optJSONObject("args") ?: JSONObject()).let { o -> o.keys().forEach { a[it] = o.optString(it) } }
        val run = SkillEngine.run(sk, a, PhoneDevice(c))
        File(skillsDir(c), sk.name + ".zxskill").writeText(run.skill.toJson())   // healed selectors and the run count are kept
        return if (run.ok) ok(JSONObject().put("steps", run.log.size).put("healed", run.log.count { it.healed }), "স্কিল «${sk.name}» সম্পন্ন (${run.log.size} ধাপ)")
        else err("skill-failed", "ধাপ ${run.failedAt + 1}-এ থেমেছে: ${run.reason}").put("data", JSONObject()
            .put("failed_at", run.failedAt + 1).put("screen", JSONArray(run.screen)).put("done_steps", run.log.count { it.ok })
            .put("next", "স্ক্রিন দেখে এই ধাপ থেকে নিজে tap_text/type_text দিয়ে বাকিটা শেষ করো"))
    }

    // ------------------------------------------------------------------ autopilot
    private val lock = Any()
    private fun rulesFile(c: Context) = File(dir(c), "autopilot.json")
    fun loadRules(c: Context): MutableList<Rule> = synchronized(lock) { runCatching { Autopilot.fromJson(rulesFile(c).takeIf { it.isFile }?.readText().orEmpty()).toMutableList() }.getOrDefault(mutableListOf()) }
    fun saveRules(c: Context, l: List<Rule>) = synchronized(lock) { rulesFile(c).writeText(Autopilot.toJson(l)) }

    private fun hasPerm(c: Context, p: String) = androidx.core.content.ContextCompat.checkSelfPermission(c, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun listenerEnabled(c: Context) = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    private fun autopilotAdd(c: Context, args: JSONObject): JSONObject {
        val task = args.safe("task").trim(); if (task.isEmpty()) return err("no-task", "task দাও — ঘটনা ঘটলে কী করবে")
        val m = (plain(args) as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
        if (m["in_minutes"] != null && m["at_ms"] == null) m["at_ms"] = System.currentTimeMillis() + MiniJson.long(m["in_minutes"]) * 60_000L
        val trig = try { Autopilot.triggerFrom(m) } catch (e: IllegalArgumentException) { return err("bad-trigger", e.message ?: "ট্রিগার ঠিক নেই") }
        val rule = Rule("ap" + System.currentTimeMillis().toString(36), args.safe("title").ifBlank { task.take(30) }, trig, task,
            createdAt = System.currentTimeMillis(), cooldownSec = args.optInt("cooldown_seconds", if (trig.kind == Kind.BATTERY_LOW) 3600 else 20))
        val l = loadRules(c); l += rule; saveRules(c, l)
        val needs = ArrayList<String>()
        if (trig.kind == Kind.SMS && !hasPerm(c, android.Manifest.permission.RECEIVE_SMS)) needs += "SMS অনুমতি: request_permission(name=\"sms\") দিয়ে চালু করো"
        if (trig.kind == Kind.NOTIFICATION && !listenerEnabled(c)) needs += "নোটিফিকেশন অ্যাক্সেস: request_permission(name=\"notification_listener\") খুলে ZYRAXON চালু করো"
        if (trig.kind == Kind.CALL && !hasPerm(c, android.Manifest.permission.READ_PHONE_STATE)) needs += "ফোন অনুমতি: request_permission(name=\"phone\")"
        return ok(JSONObject().put("id", rule.id).put("rule", Autopilot.describe(rule)).put("needs", JSONArray(needs)), "অটোপাইলট নিয়ম যোগ হয়েছে: ${rule.title}")
    }

    private fun autopilotTool(c: Context, name: String, args: JSONObject): JSONObject = when (name) {
        "autopilot_add" -> autopilotAdd(c, args)
        "autopilot_list" -> { val l = loadRules(c); ok(JSONArray(l.map { JSONObject().put("id", it.id).put("rule", Autopilot.describe(it)).put("enabled", it.enabled).put("fired", it.fired)
            .put("next", Autopilot.nextFire(it, System.currentTimeMillis()).let { n -> if (n > 0) n else JSONObject.NULL }) }), "${l.size}টি অটোপাইলট নিয়ম") }
        "autopilot_remove" -> { val l = loadRules(c); val id = args.safe("id"); if (l.removeAll { it.id == id || it.title == id }) { saveRules(c, l); ok(true, "নিয়ম মুছে ফেলা হয়েছে") } else err("no-rule", "এই নিয়ম নেই") }
        "autopilot_toggle" -> { val l = loadRules(c); val i = l.indexOfFirst { it.id == args.safe("id") || it.title == args.safe("id") }
            if (i < 0) err("no-rule", "এই নিয়ম নেই") else { l[i] = l[i].copy(enabled = args.optBoolean("enabled", !l[i].enabled)); saveRules(c, l); ok(l[i].enabled, if (l[i].enabled) "নিয়ম চালু হয়েছে" else "নিয়ম বন্ধ হয়েছে") } }
        else -> err("unknown", name)
    }

    // ------------------------------------------------------------------ ledger
    private fun ledgerFile(c: Context) = File(dir(c), "ledger.json")
    fun loadLedger(c: Context): Ledger = synchronized(lock) { runCatching { Ledger.fromJson(ledgerFile(c).takeIf { it.isFile }?.readText().orEmpty()) }.getOrDefault(Ledger()) }
    fun saveLedger(c: Context, l: Ledger) = synchronized(lock) { ledgerFile(c).writeText(l.toJson()) }

    /** Adds a money SMS to the ledger. Returns the new transaction text, or null when it is not a money SMS or already known. */
    fun ledgerAddSms(c: Context, sender: String, body: String, timeMs: Long): String? = synchronized(lock) {
        val t = SmsParser.parse(sender, body, timeMs) ?: return null
        val l = loadLedger(c); if (!l.add(t)) return null
        saveLedger(c, l)
        (if (t.dir == Dir.IN) "+" else "−") + Fmt.money(t.amount) + " " + t.type + (if (t.party.isNotBlank()) " (${t.party})" else "") + " · ${t.category}"
    }

    private fun readInbox(c: Context, sinceMs: Long, max: Int): List<Triple<String, String, Long>> {
        val out = ArrayList<Triple<String, String, Long>>()
        c.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), if (sinceMs > 0) "date >= ?" else null,
            if (sinceMs > 0) arrayOf(sinceMs.toString()) else null, "date DESC LIMIT $max")?.use { q ->
            while (q.moveToNext()) out += Triple(q.getString(0).orEmpty(), q.getString(1).orEmpty(), q.getLong(2))
        }
        return out
    }

    private fun period(p: String): Triple<Long, Long, String> {
        val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val today = c.timeInMillis; val day = 86_400_000L
        return when (p.lowercase()) {
            "today", "আজ" -> Triple(today, today + day, "আজ")
            "yesterday", "গতকাল" -> Triple(today - day, today, "গতকাল")
            "month", "মাস" -> { c.set(Calendar.DAY_OF_MONTH, 1); Triple(c.timeInMillis, today + day, "এই মাসে") }
            "all", "সব" -> Triple(0L, today + day, "মোট")
            else -> Triple(today - 6 * day, today + day, "গত ৭ দিনে")
        }
    }

    private fun ledgerTool(c: Context, name: String, args: JSONObject): JSONObject = when (name) {
        "ledger_scan" -> {
            if (!hasPerm(c, android.Manifest.permission.READ_SMS)) err("no-permission", "SMS পড়ার অনুমতি নেই — request_permission(name=\"sms\") দাও")
            else {
                if (args.has("auto")) prefs(c).edit().putBoolean("ledger_auto", args.optBoolean("auto")).apply()
                val days = args.optInt("days", 30).coerceIn(1, 400)
                val since = System.currentTimeMillis() - days * 86_400_000L
                val l = loadLedger(c); var added = 0; var seen = 0
                for ((addr, body, t) in readInbox(c, since, 3000)) { seen++; SmsParser.parse(addr, body, t)?.let { if (l.add(it)) added++ } }
                saveLedger(c, l)
                ok(JSONObject().put("scanned", seen).put("new", added).put("total", l.txns.size).put("auto", prefs(c).getBoolean("ledger_auto", false)), "$seen টি SMS দেখা হয়েছে, $added টি নতুন লেনদেন যোগ হয়েছে")
            }
        }
        "ledger_budget" -> {
            val l = loadLedger(c); val cat = args.safe("category")
            if (cat.isNotBlank()) { if (args.optDouble("limit", 0.0) > 0) l.budgets[cat] = args.optDouble("limit") else l.budgets.remove(cat); saveLedger(c, l) }
            val (f, t, _) = period("month")
            ok(JSONObject().put("budgets", JSONObject(l.budgets as Map<*, *>)).put("alerts", JSONArray(l.alerts(f, t))), "বাজেট সেট করা আছে: ${l.budgets.size}টি")
        }
        "ledger_report" -> {
            val l = loadLedger(c); val (from, to, label) = period(args.safe("period", "week"))
            val s = l.summarize(from, to); val alerts = l.alerts(period("month").first, period("month").second)
            val data = JSONObject().put("period", label).put("income", s.income).put("spent", s.spent).put("fees", s.fees).put("count", s.count).put("balance", s.lastBalance)
                .put("by_category", JSONArray(s.byCategory.map { JSONObject().put("category", it.first).put("amount", it.second) }))
                .put("top_parties", JSONArray(s.topParties.map { JSONObject().put("party", it.first).put("amount", it.second) }))
                .put("alerts", JSONArray(alerts)).put("spoken", Fmt.spoken(s, alerts, label))
            if (args.safe("format", "text").lowercase() == "xlsx") {
                val (tx, sum) = Fmt.rows(l, from, to)
                val out = File(File(ai.zyraxon.assistant.ai.ToolRegistry.workspace(c), "documents").apply { mkdirs() }, "hisab-${args.safe("period", "week")}.xlsx")
                val bos = ByteArrayOutputStream(); OfficeFiles.xlsx(listOf(OfficeFiles.Sheet("লেনদেন", tx), OfficeFiles.Sheet("সারাংশ", sum)), bos); out.writeBytes(bos.toByteArray())
                data.put("path", out.absolutePath)
                ok(data, "হিসাবের Excel রিপোর্ট তৈরি হয়েছে").put("media", mediaFile(out, out.name))
            } else ok(data, "$label খরচ ${Fmt.money(s.spent)}, আয় ${Fmt.money(s.income)}")
        }
        else -> err("unknown", name)
    }

    // ------------------------------------------------------------------ timeline tools
    private fun timelineTool(c: Context, name: String, args: JSONObject): JSONObject {
        val tl = timeline(c)
        return if (name == "timeline_list") {
            val sdf = java.text.SimpleDateFormat("dd/MM HH:mm:ss")
            ok(JSONArray(tl.list(args.optInt("limit", 25).coerceIn(1, 100)).map {
                JSONObject().put("id", it.id).put("time", sdf.format(java.util.Date(it.time))).put("tool", it.tool).put("what", it.summary).put("ok", it.ok).put("undoable", it.undoable).put("undone", it.undone)
            }), "এজেন্টের সাম্প্রতিক কাজ")
        } else if (args.has("since_id")) {
            val r = tl.undoSince(args.optLong("since_id")); ok(JSONArray(r.map { it.message }), "${r.count { it.ok }}টি কাজ ফেরানো হয়েছে")
        } else { val r = tl.undo(args.optLong("id", -1)); if (r.ok) ok(r.message, r.message) else err("undo-failed", r.message) }
    }

    // ------------------------------------------------------------------ brain
    @Volatile private var brainCache: Brain? = null
    private fun brainFile(c: Context) = File(dir(c), "brain.json")
    private fun mtimeFile(c: Context) = File(dir(c), "brain_mtimes.json")
    private fun brain(c: Context): Brain = brainCache ?: synchronized(this) { brainCache ?: Brain.load(brainFile(c)).also { brainCache = it } }

    private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "html", "htm", "kt", "java", "js", "ts", "py", "sh", "css", "sql", "ini", "toml", "tex", "rtf")
    private val DOC_EXT = setOf("docx", "xlsx", "pptx")
    private val SKIP_DIR = setOf("node_modules", ".git", "build", "cache", "snap", "skills", "timeline", ".gradle", "__pycache__")

    private fun fileText(f: File): String? = try {
        val ext = f.extension.lowercase()
        when {
            ext in DOC_EXT -> DocViewer.toHtml(f).substringAfter("</style>").replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            ext in TEXT_EXT -> f.readText().take(400_000)
            else -> null
        }
    } catch (_: Exception) { null }

    private fun brainIndex(c: Context, args: JSONObject): JSONObject {
        val b = brain(c)
        val mt = runCatching { MiniJson.obj(MiniJson.parse(mtimeFile(c).takeIf { it.isFile }?.readText().orEmpty())) }.getOrDefault(emptyMap()).toMutableMap()
        val roots = (args.optJSONArray("paths")?.let { a -> (0 until a.length()).map { File(a.getString(it)) } } ?: listOf(ai.zyraxon.assistant.ai.ToolRegistry.workspace(c)))
        var files = 0; var skipped = 0; var sms = 0
        for (root in roots) {
            if (!root.exists()) continue
            val walker = root.walkTopDown().onEnter { d -> d.name !in SKIP_DIR }.iterator()
            while (walker.hasNext() && files + skipped < 5000) {
                val f = walker.next(); if (!f.isFile || f.length() > 4_000_000) continue
                if (mt[f.absolutePath] == f.lastModified()) { skipped++; continue }
                val text = fileText(f) ?: continue
                b.upsert(f.absolutePath, "file", f.name, text, f.lastModified()); mt[f.absolutePath] = f.lastModified(); files++
            }
        }
        if (args.optBoolean("sms") && hasPerm(c, android.Manifest.permission.READ_SMS)) {
            for ((addr, body, t) in readInbox(c, 0, args.optInt("sms_count", 1000).coerceIn(10, 5000))) { b.upsert("sms:$addr:$t", "sms", addr, body, t); sms++ }
        }
        b.save(brainFile(c)); mtimeFile(c).writeText(MiniJson.stringify(mt))
        return ok(JSONObject().put("files", files).put("unchanged", skipped).put("sms", sms).put("sources", b.sources).put("chunks", b.size), "$files টি ফাইল, $sms টি SMS ইনডেক্স হয়েছে (মোট ${b.sources} উৎস)")
    }

    private fun brainSearch(c: Context, args: JSONObject): JSONObject {
        val b = brain(c); if (b.size == 0) return err("empty", "ইনডেক্স খালি — আগে brain_index চালাও (sms:true দিলে SMS-ও আসবে)")
        val since = if (args.has("days")) System.currentTimeMillis() - args.optLong("days") * 86_400_000L else 0L
        val hits = b.search(args.safe("query"), args.optInt("limit", 6).coerceIn(1, 20), args.safe("kind"), since)
        return ok(JSONArray(hits.map { JSONObject().put("title", it.title).put("where", it.key).put("kind", it.kind).put("snippet", it.snippet).put("score", Math.round(it.score * 10) / 10.0) }),
            if (hits.isEmpty()) "কিছু পাওয়া যায়নি" else "${hits.size}টি ফল পাওয়া গেছে — ফাইল হলে open_file দিয়ে খোলো")
    }

    // ------------------------------------------------------------------ call agent switch
    private fun callAgentTool(c: Context, name: String, args: JSONObject): JSONObject {
        val p = prefs(c)
        if (name == "call_log") {
            val d = File(ai.zyraxon.assistant.ai.ToolRegistry.workspace(c), "calls")
            val files = d.listFiles { f -> f.extension == "md" }?.sortedByDescending { it.name }?.take(args.optInt("limit", 5).coerceIn(1, 20)) ?: emptyList()
            return ok(JSONArray(files.map { JSONObject().put("file", it.name).put("text", it.readText().take(1500)) }), "${files.size}টি কলের সারাংশ")
        }
        when (args.safe("action", "status").lowercase()) {
            "on" -> { p.edit().putBoolean("call_agent", true).apply { if (args.safe("owner").isNotBlank()) putString("owner", args.safe("owner").trim()) }.apply(); DeviceBridge.setAutoAnswer(true, args.optLong("answer_after_seconds", 6).coerceIn(1, 30) * 1000L); DeviceBridge.watchCalls(c) }
            "off" -> { p.edit().putBoolean("call_agent", false).apply(); DeviceBridge.setAutoAnswer(false, 0) }
        }
        if (args.safe("owner").isNotBlank()) p.edit().putString("owner", args.safe("owner").trim()).apply()
        val needs = ArrayList<String>()
        if (!hasPerm(c, android.Manifest.permission.READ_PHONE_STATE)) needs += "request_permission(name=\"phone\")"
        if (!hasPerm(c, android.Manifest.permission.RECORD_AUDIO)) needs += "request_permission(name=\"microphone\")"
        val on = p.getBoolean("call_agent", false)
        return ok(JSONObject().put("enabled", on).put("owner", p.getString("owner", "") ?: "").put("needs", JSONArray(needs)),
            if (on) "কল-এজেন্ট চালু — কল এলে ${p.getString("owner", "")?.ifBlank { "মালিক" }}-এর হয়ে ধরবে" else "কল-এজেন্ট বন্ধ")
    }

    // ------------------------------------------------------------------ entry point
    suspend fun run(context: Context, name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        when {
            name.startsWith("skill_") -> if (name == "skill_run") skillRun(context, args) else skillTool(context, name, args)
            name.startsWith("autopilot_") -> autopilotTool(context, name, args)
            name.startsWith("ledger_") -> ledgerTool(context, name, args)
            name.startsWith("timeline_") -> timelineTool(context, name, args)
            name == "brain_index" -> brainIndex(context, args)
            name == "brain_search" -> brainSearch(context, args)
            name == "call_agent" || name == "call_log" -> callAgentTool(context, name, args)
            else -> err("unknown-tool", name)
        }
    }
}

/** Runs rules when events arrive and when the clock ticks; hands the tasks to the agent. */
object AutoRuntime {
    private val lock = Any()
    private fun pendingFile(c: Context) = File(AutoTools.dir(c), "autopilot_pending.json")

    fun dispatch(c: Context, title: String, prompt: String) {
        val d = AutoTools.dispatcher
        if (d != null) { d(title, prompt); return }
        synchronized(lock) {   // the app is not up yet: keep the task until it is
            val l = runCatching { MiniJson.list(MiniJson.parse(pendingFile(c).takeIf { it.isFile }?.readText().orEmpty())) }.getOrDefault(emptyList()).toMutableList<Any?>()
            l += mapOf("t" to title, "p" to prompt); pendingFile(c).writeText(MiniJson.stringify(l.takeLast(20)))
        }
    }

    fun drain(c: Context) {
        val d = AutoTools.dispatcher ?: return
        val items = synchronized(lock) {
            val f = pendingFile(c); if (!f.isFile) return
            val l = runCatching { MiniJson.list(MiniJson.parse(f.readText())) }.getOrDefault(emptyList()); f.delete(); l
        }
        items.forEach { val m = MiniJson.obj(it); d(MiniJson.str(m["t"]), MiniJson.str(m["p"])) }
    }

    fun onEvent(c: Context, e: Event) {
        val rules = AutoTools.loadRules(c); var changed = false
        for ((i, r) in rules.withIndex()) {
            if (!Autopilot.matches(r, e)) continue
            rules[i] = r.copy(lastFired = e.timeMs.takeIf { it > 0 } ?: System.currentTimeMillis(), fired = r.fired + 1); changed = true
            dispatch(c, "অটোপাইলট: ${r.title}", Autopilot.taskPrompt(r, e))
        }
        if (changed) AutoTools.saveRules(c, rules)
    }

    fun event(c: Context, kind: Kind, from: String = "", text: String = "", pkg: String = "", battery: Int = -1) =
        onEvent(c, Event(kind, from, text, pkg, System.currentTimeMillis(), battery))

    /** Called about every 30 seconds while the app runs. */
    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        val rules = AutoTools.loadRules(c); var changed = false
        for ((i, r) in rules.withIndex()) if (Autopilot.due(r, now)) {
            rules[i] = r.copy(lastFired = now, fired = r.fired + 1, enabled = r.enabled && r.trigger.kind != Kind.ONCE); changed = true
            dispatch(c, "অটোপাইলট: ${r.title}", Autopilot.taskPrompt(r, null))
        }
        if (changed) AutoTools.saveRules(c, rules)
        val level = (c.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (level in 0..50) event(c, Kind.BATTERY_LOW, battery = level)   // each rule has its own limit and cooldown
    }
}
