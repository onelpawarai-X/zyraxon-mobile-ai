package ai.zyraxon.assistant.timeline

import ai.zyraxon.assistant.core.MiniJson
import java.io.File

data class Entry(
    val id: Long, val time: Long, val tool: String, val summary: String, val ok: Boolean,
    val target: String = "", val snap: String = "", val existed: Boolean = false, val undone: Boolean = false
) { val undoable get() = target.isNotEmpty() && !undone }

data class UndoResult(val ok: Boolean, val message: String)

/**
 * Every action the agent takes is written here. File changes keep a copy of what was there before,
 * so any of them — or everything after a chosen point — can be undone with one call.
 */
class Timeline(private val dir: File, private val maxEntries: Int = 400, private val maxSnapBytes: Long = 40L * 1024 * 1024) {
    private val snapDir = File(dir, "snap")
    private val log = File(dir, "timeline.json")
    private val entries = ArrayList<Entry>()
    private var seq = 1L

    init {
        dir.mkdirs(); snapDir.mkdirs()
        runCatching {
            if (log.isFile) MiniJson.list(MiniJson.parse(log.readText())).forEach { e ->
                val m = MiniJson.obj(e)
                entries += Entry(MiniJson.long(m["id"]), MiniJson.long(m["t"]), MiniJson.str(m["tool"]), MiniJson.str(m["s"]), MiniJson.bool(m["ok"], true),
                    MiniJson.str(m["target"]), MiniJson.str(m["snap"]), MiniJson.bool(m["existed"]), MiniJson.bool(m["undone"]))
            }
        }
        seq = (entries.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private fun save() = log.writeText(MiniJson.stringify(entries.map {
        mapOf("id" to it.id, "t" to it.time, "tool" to it.tool, "s" to it.summary, "ok" to it.ok, "target" to it.target, "snap" to it.snap, "existed" to it.existed, "undone" to it.undone)
    }))

    private fun size(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Copies what is at [path] before a tool changes it. Returns the snapshot name ("" when nothing was there or it is too large). */
    @Synchronized fun snapshot(path: File): String {
        if (!path.exists()) return ""
        if (size(path) > maxSnapBytes) return ""
        val name = "${seq}_${path.name}"
        val dest = File(snapDir, name)
        if (path.isDirectory) path.copyRecursively(dest, overwrite = true) else path.copyTo(dest, overwrite = true)
        return name
    }

    @Synchronized fun record(tool: String, summary: String, ok: Boolean, target: String = "", snap: String = "", existed: Boolean = false, now: Long = System.currentTimeMillis()): Entry {
        val e = Entry(seq++, now, tool, summary.take(160), ok, if (ok) target else "", snap, existed)
        entries += e
        while (entries.size > maxEntries) { val old = entries.removeAt(0); if (old.snap.isNotEmpty()) File(snapDir, old.snap).deleteRecursively() }
        save(); return e
    }

    @Synchronized fun list(limit: Int = 30): List<Entry> = entries.takeLast(limit).reversed()
    @Synchronized fun get(id: Long) = entries.firstOrNull { it.id == id }

    @Synchronized fun undo(id: Long): UndoResult {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return UndoResult(false, "ঐ নম্বরের কাজ টাইমলাইনে নেই")
        val e = entries[i]
        if (e.undone) return UndoResult(false, "এটা আগেই ফেরানো হয়েছে")
        if (e.target.isEmpty()) return UndoResult(false, "এই কাজ ফেরানো যায় না (${e.tool})")
        val t = File(e.target)
        return try {
            if (e.existed && e.snap.isNotEmpty()) {
                val s = File(snapDir, e.snap)
                if (!s.exists()) return UndoResult(false, "আগের কপি আর নেই")
                if (t.exists()) t.deleteRecursively()
                t.parentFile?.mkdirs()
                if (s.isDirectory) s.copyRecursively(t, overwrite = true) else s.copyTo(t, overwrite = true)
            } else if (!e.existed) {
                if (t.exists()) t.deleteRecursively()
            } else return UndoResult(false, "আগের কপি রাখা যায়নি (ফাইল খুব বড় ছিল)")
            entries[i] = e.copy(undone = true); save()
            UndoResult(true, "ফেরানো হয়েছে: ${e.summary}")
        } catch (ex: Exception) { UndoResult(false, "ফেরানো যায়নি: ${ex.message}") }
    }

    /** Undoes every change made after (and including) [id], newest first. */
    @Synchronized fun undoSince(id: Long): List<UndoResult> =
        entries.filter { it.id >= id && it.undoable }.sortedByDescending { it.id }.map { undo(it.id) }
}
