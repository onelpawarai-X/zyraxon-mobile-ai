package ai.zyraxon.assistant.autopilot

import ai.zyraxon.assistant.core.MiniJson
import java.util.Calendar
import java.util.TimeZone

enum class Kind { SMS, NOTIFICATION, CALL, DAILY, ONCE, INTERVAL, BATTERY_LOW, APP_OPENED }

/** When a rule fires. Unset fields match anything. */
data class Trigger(
    val kind: Kind,
    val from: String = "",                    // sender / caller / app name
    val any: List<String> = emptyList(),      // at least one of these words in the text
    val all: List<String> = emptyList(),      // every one of these words in the text
    val none: List<String> = emptyList(),     // none of these words
    val hour: Int = -1, val minute: Int = 0,  // DAILY
    val atMs: Long = 0,                       // ONCE
    val everyMin: Int = 0,                    // INTERVAL
    val below: Int = 0,                       // BATTERY_LOW (percent)
    val pkg: String = ""                      // APP_OPENED / NOTIFICATION
)

data class Rule(
    val id: String, val title: String, val trigger: Trigger, val task: String,
    val enabled: Boolean = true, val createdAt: Long = 0, val lastFired: Long = 0,
    val cooldownSec: Int = 20, val fired: Int = 0
)

/** Something that happened on the phone. */
data class Event(val kind: Kind, val from: String = "", val text: String = "", val pkg: String = "", val timeMs: Long = 0, val battery: Int = -1)

object Autopilot {
    private val bn = "০১২৩৪৫৬৭৮৯"
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) { val i = bn.indexOf(c); sb.append(if (i >= 0) ('0' + i) else c) }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun digits(s: String) = s.filter { it.isDigit() }

    /** A sender matches by name or by the last digits of a phone number (+88017… equals 017…). */
    fun fromMatches(want: String, have: String): Boolean {
        if (want.isBlank()) return true
        val w = norm(want); val h = norm(have)
        if (h.contains(w)) return true
        val wd = digits(w); val hd = digits(h)
        return wd.length >= 6 && hd.length >= 6 && (hd.endsWith(wd.takeLast(10)) || wd.endsWith(hd.takeLast(10)))
    }

    fun textMatches(t: Trigger, text: String): Boolean {
        val x = norm(text)
        if (t.any.isNotEmpty() && t.any.none { x.contains(norm(it)) }) return false
        if (t.all.any { !x.contains(norm(it)) }) return false
        if (t.none.any { x.contains(norm(it)) }) return false
        return true
    }

    /** Event-driven rules: does this event fire this rule? */
    fun matches(rule: Rule, e: Event): Boolean {
        if (!rule.enabled) return false
        val t = rule.trigger
        if (t.kind != e.kind) return false
        if (rule.lastFired > 0 && e.timeMs - rule.lastFired < rule.cooldownSec * 1000L) return false
        return when (t.kind) {
            Kind.SMS, Kind.NOTIFICATION -> fromMatches(t.from, e.from) && (t.pkg.isBlank() || norm(e.pkg).contains(norm(t.pkg))) && textMatches(t, e.text)
            Kind.CALL -> fromMatches(t.from, e.from)
            Kind.APP_OPENED -> (t.pkg.isNotBlank() && norm(e.pkg).contains(norm(t.pkg))) || (t.from.isNotBlank() && norm(e.from).contains(norm(t.from)))
            Kind.BATTERY_LOW -> e.battery in 0 until t.below
            else -> false
        }
    }

    private fun cal(ms: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = ms }

    /** Time-driven rules: is it due now (and not already fired for this slot)? */
    fun due(rule: Rule, now: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (!rule.enabled) return false
        val t = rule.trigger
        return when (t.kind) {
            Kind.DAILY -> {
                val slot = cal(now, tz).apply { set(Calendar.HOUR_OF_DAY, t.hour); set(Calendar.MINUTE, t.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                now >= slot && rule.lastFired < slot && now - slot < 6 * 3600_000L
            }
            Kind.ONCE -> now >= t.atMs && rule.lastFired == 0L && now - t.atMs < 24 * 3600_000L
            Kind.INTERVAL -> t.everyMin > 0 && now - (if (rule.lastFired > 0) rule.lastFired else rule.createdAt) >= t.everyMin * 60_000L
            else -> false
        }
    }

    /** The next time a time-driven rule will fire (for showing the user), or -1. */
    fun nextFire(rule: Rule, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val t = rule.trigger
        return when (t.kind) {
            Kind.DAILY -> { val c = cal(now, tz).apply { set(Calendar.HOUR_OF_DAY, t.hour); set(Calendar.MINUTE, t.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1); c.timeInMillis }
            Kind.ONCE -> if (rule.lastFired == 0L && t.atMs > now) t.atMs else -1
            Kind.INTERVAL -> (if (rule.lastFired > 0) rule.lastFired else rule.createdAt) + t.everyMin * 60_000L
            else -> -1
        }
    }

    /** The message the agent receives when a rule fires. */
    fun taskPrompt(rule: Rule, e: Event?): String = buildString {
        append("[অটোপাইলট] নিয়ম: ").append(rule.title).append('\n')
        if (e != null) {
            append("ঘটনা: ").append(when (e.kind) { Kind.SMS -> "SMS এসেছে"; Kind.NOTIFICATION -> "নোটিফিকেশন এসেছে"; Kind.CALL -> "কল এসেছে"; Kind.APP_OPENED -> "অ্যাপ খোলা হয়েছে"; Kind.BATTERY_LOW -> "ব্যাটারি কমেছে (${e.battery}%)"; else -> "নির্ধারিত সময় হয়েছে" })
            if (e.from.isNotBlank()) append(" — ").append(e.from)
            if (e.text.isNotBlank()) append("\nবিষয়বস্তু: ").append(e.text.take(600))
            append('\n')
        }
        append("কাজ: ").append(rule.task).append("\nইউজারকে না জিজ্ঞেস করে কাজটা শেষ করো; শেষে এক লাইনে জানাও কী করেছ।")
    }

    fun toJson(rules: List<Rule>): String = MiniJson.stringify(rules.map { r ->
        val t = r.trigger
        mapOf("id" to r.id, "title" to r.title, "task" to r.task, "enabled" to r.enabled, "createdAt" to r.createdAt, "lastFired" to r.lastFired,
            "cooldownSec" to r.cooldownSec, "fired" to r.fired,
            "trigger" to mapOf("kind" to t.kind.name, "from" to t.from, "any" to t.any, "all" to t.all, "none" to t.none, "hour" to t.hour, "minute" to t.minute,
                "atMs" to t.atMs, "everyMin" to t.everyMin, "below" to t.below, "pkg" to t.pkg))
    })

    fun fromJson(src: String): List<Rule> = if (src.isBlank()) emptyList() else MiniJson.list(MiniJson.parse(src)).map { e ->
        val m = MiniJson.obj(e); val t = MiniJson.obj(m["trigger"])
        Rule(MiniJson.str(m["id"]), MiniJson.str(m["title"]),
            Trigger(Kind.valueOf(MiniJson.str(t["kind"], "SMS")), MiniJson.str(t["from"]), MiniJson.list(t["any"]).map { MiniJson.str(it) },
                MiniJson.list(t["all"]).map { MiniJson.str(it) }, MiniJson.list(t["none"]).map { MiniJson.str(it) }, MiniJson.int(t["hour"], -1),
                MiniJson.int(t["minute"]), MiniJson.long(t["atMs"]), MiniJson.int(t["everyMin"]), MiniJson.int(t["below"]), MiniJson.str(t["pkg"])),
            MiniJson.str(m["task"]), MiniJson.bool(m["enabled"], true), MiniJson.long(m["createdAt"]), MiniJson.long(m["lastFired"]),
            MiniJson.int(m["cooldownSec"], 20), MiniJson.int(m["fired"]))
    }

    /** Builds the trigger from the plain fields the agent passes to `autopilot_add`. */
    fun triggerFrom(a: Map<String, Any?>): Trigger {
        fun words(v: Any?): List<String> = when (v) { is List<*> -> v.map { it.toString() }; is String -> v.split(',', '،', '|').map { it.trim() }.filter { it.isNotEmpty() }; else -> emptyList() }
        val kind = when (MiniJson.str(a["kind"]).lowercase()) {
            "sms", "message" -> Kind.SMS; "notification", "notif" -> Kind.NOTIFICATION; "call" -> Kind.CALL
            "daily", "everyday" -> Kind.DAILY; "once", "at" -> Kind.ONCE; "interval", "every" -> Kind.INTERVAL
            "battery", "battery_low" -> Kind.BATTERY_LOW; "app", "app_opened" -> Kind.APP_OPENED
            else -> throw IllegalArgumentException("kind দিতে হবে: sms, notification, call, daily, once, interval, battery, app")
        }
        val time = MiniJson.str(a["time"])
        var h = MiniJson.int(a["hour"], -1); var mi = MiniJson.int(a["minute"])
        if (time.contains(':')) { h = time.substringBefore(':').trim().toIntOrNull() ?: -1; mi = time.substringAfter(':').trim().take(2).toIntOrNull() ?: 0 }
        require(kind != Kind.DAILY || h in 0..23) { "daily-র জন্য time (HH:MM) দিতে হবে" }
        return Trigger(kind, MiniJson.str(a["from"]), words(a["any"]), words(a["all"]), words(a["none"]), h, mi,
            MiniJson.long(a["at_ms"]), MiniJson.int(a["every_minutes"]), MiniJson.int(a["below"]), MiniJson.str(a["pkg"]))
    }

    fun describe(r: Rule, now: Long = System.currentTimeMillis()): String {
        val t = r.trigger
        val w = when (t.kind) {
            Kind.SMS -> "SMS" + (if (t.from.isNotBlank()) " (${t.from})" else "") + (if (t.any.isNotEmpty()) " — ${t.any.joinToString("/")}" else "")
            Kind.NOTIFICATION -> "নোটিফিকেশন" + (if (t.pkg.isNotBlank()) " (${t.pkg})" else "")
            Kind.CALL -> "কল" + (if (t.from.isNotBlank()) " (${t.from})" else "")
            Kind.DAILY -> "প্রতিদিন %02d:%02d".format(t.hour, t.minute)
            Kind.ONCE -> "একবার"; Kind.INTERVAL -> "প্রতি ${t.everyMin} মিনিট"
            Kind.BATTERY_LOW -> "ব্যাটারি ${t.below}%-এর নিচে"; Kind.APP_OPENED -> "অ্যাপ খুললে (${t.pkg.ifBlank { t.from }})"
        }
        return "${r.title}: $w → ${r.task.take(60)}"
    }
}
