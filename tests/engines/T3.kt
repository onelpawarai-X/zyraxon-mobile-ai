import ai.zyraxon.assistant.brain.*
import ai.zyraxon.assistant.timeline.*
import java.io.File

var fails = 0; var passes = 0
fun check(name: String, c: Boolean) { if (c) passes++ else { fails++; println("FAIL: $name") } }

fun main() {
    // ================= Tokenizer
    check("stem bn", Tokenizer.stem("ভাড়ার") == "ভাড়া" && Tokenizer.stem("বইগুলো") == "বই" && Tokenizer.stem("চুক্তিপত্রের") == "চুক্তিপত্র")
    check("stem short safe", Tokenizer.stem("কে") == "কে" && Tokenizer.stem("মা") == "মা")
    check("digits unify", Tokenizer.tokens("১২৩৪ টাকা") .contains("1234"))
    check("english stem", Tokenizer.tokens("Invoices payments running").containsAll(listOf("invoice", "payment", "runn")))
    check("zwnj", Tokenizer.tokens("ক‌র্মী") == Tokenizer.tokens("কর্মী"))

    // ================= Brain
    val b = Brain()
    b.upsert("/docs/rent.txt", "file", "বাড়ি ভাড়ার চুক্তিপত্র", "এই চুক্তিপত্রটি গুলশান ১ নম্বরের ফ্ল্যাটের জন্য। মাসিক ভাড়া ২৫,০০০ টাকা। চুক্তির মেয়াদ ১ জানুয়ারি ২০২৬ থেকে ১২ মাস। জামানত ৫০,০০০ টাকা।")
    b.upsert("/docs/cv.docx", "file", "আমার সিভি", "আমি কম্পিউটার সায়েন্স নিয়ে পড়েছি। অভিজ্ঞতা: অ্যান্ড্রয়েড অ্যাপ ডেভেলপমেন্ট, কোটলিন।")
    b.upsert("sms:1", "sms", "bKash", "You have received Tk 500.00 from 01712345678. TrxID ABC123", 1000)
    b.upsert("note:1", "note", "বাজার", "চাল ডাল তেল কিনতে হবে। দুধ আনতে ভুলো না।")
    b.upsert("/docs/meeting.md", "file", "Project meeting notes", "The quarterly budget review was moved to Thursday. Alice will present the invoices summary.")
    check("size", b.size == 5 && b.sources == 5)
    var h = b.search("বাড়ি ভাড়া চুক্তি")
    check("bn query finds rent", h.isNotEmpty() && h[0].key == "/docs/rent.txt")
    h = b.search("ভাড়ার চুক্তিটা কোথায়")
    check("inflected query", h.isNotEmpty() && h[0].key == "/docs/rent.txt")
    h = b.search("জামানত কত")
    check("fact inside doc", h.isNotEmpty() && h[0].key == "/docs/rent.txt" && h[0].snippet.contains("জামানত"))
    h = b.search("android developer")
    check("english query on english tokens none", h.isEmpty() || h[0].key != "/docs/rent.txt")
    h = b.search("অ্যান্ড্রয়েড")
    check("cv found", h.isNotEmpty() && h[0].key == "/docs/cv.docx")
    h = b.search("invoice")
    check("english stem search", h.isNotEmpty() && h[0].key == "/docs/meeting.md")
    h = b.search("bkash 500", kind = "sms")
    check("kind filter", h.size == 1 && h[0].kind == "sms")
    check("kind filter excludes", b.search("bkash 500", kind = "file").isEmpty())
    h = b.search("দুধ")
    check("note", h.isNotEmpty() && h[0].key == "note:1")
    h = b.search("ভাড়")
    check("prefix", h.isNotEmpty() && h[0].key == "/docs/rent.txt")
    check("no hit", b.search("পারমাণবিক রিয়্যাক্টর").isEmpty())
    check("empty query", b.search("").isEmpty() && b.search("   ").isEmpty())
    // update replaces old text
    b.upsert("/docs/rent.txt", "file", "বাড়ি ভাড়ার চুক্তিপত্র", "নতুন চুক্তিতে ভাড়া ৩০,০০০ টাকা।")
    check("update removes old", b.search("জামানত").isEmpty() && b.search("ভাড়া ৩০০০০").isNotEmpty())
    b.remove("note:1"); check("remove", b.search("দুধ").isEmpty() && b.size == 4)
    // since filter
    check("since", b.search("bkash", sinceMs = 5000).isEmpty() && b.search("bkash", sinceMs = 500).isNotEmpty())
    // long documents are chunked and a deep fact is still found
    val long = StringBuilder(); for (i in 1..400) long.append("এটি সাধারণ বাক্য নম্বর $i। "); long.append("গোপন কোড হলো জাফরান। "); for (i in 1..200) long.append("আরও কিছু লেখা $i। ")
    b.upsert("/docs/long.txt", "file", "বড় ফাইল", long.toString())
    h = b.search("জাফরান")
    check("deep chunk found", h.isNotEmpty() && h[0].key == "/docs/long.txt" && h[0].snippet.contains("জাফরান"))
    // persistence
    val f = File.createTempFile("brain", ".json"); b.save(f)
    val b2 = Brain.load(f)
    check("persist", b2.sources == b.sources && b2.search("জাফরান").isNotEmpty() && b2.search("invoice").isNotEmpty())
    check("persist same ranking", b2.search("ভাড়া").map { it.key } == b.search("ভাড়া").map { it.key })
    f.delete()
    // speed on a big index
    val big = Brain(); val t0 = System.currentTimeMillis()
    for (i in 1..3000) big.upsert("f$i", "file", "ডকুমেন্ট $i", "এটি ফাইল নম্বর $i। বিষয়: হিসাব, রিপোর্ট, মিটিং এবং ক্লায়েন্ট নম্বর ${i % 50}। অতিরিক্ত লেখা ${i * 7}।")
    big.upsert("needle", "file", "গোপন", "হারানো পাসপোর্টের ফটোকপি এখানে আছে")
    val t1 = System.currentTimeMillis(); val r = big.search("পাসপোর্ট কপি"); val t2 = System.currentTimeMillis()
    check("big index finds", r.isNotEmpty() && r[0].key == "needle")
    check("big index fast (<400ms)", t2 - t1 < 400)
    println("index 3000 docs ${t1 - t0}ms, search ${t2 - t1}ms")

    // ================= Timeline
    val dir = File.createTempFile("tlx", "").also { it.delete(); it.mkdirs() }
    val work = File(dir, "work").also { it.mkdirs() }
    val tl = Timeline(File(dir, "tl"))
    val doc = File(work, "a.txt"); doc.writeText("version 1")
    // overwrite
    val s1 = tl.snapshot(doc); doc.writeText("version 2")
    val e1 = tl.record("write_file", "a.txt লেখা", true, doc.absolutePath, s1, existed = true)
    // create new file
    val nf = File(work, "new.txt"); val s2 = tl.snapshot(nf); nf.writeText("hi")
    val e2 = tl.record("write_file", "new.txt বানানো", true, nf.absolutePath, s2, existed = false)
    // delete a folder
    val fol = File(work, "folder").also { it.mkdirs() }; File(fol, "x.txt").writeText("X"); File(fol, "y.txt").writeText("Y")
    val s3 = tl.snapshot(fol); fol.deleteRecursively()
    val e3 = tl.record("delete_file", "folder মোছা", true, fol.absolutePath, s3, existed = true)
    // a command (not undoable)
    val e4 = tl.record("run_shell", "ls", true)
    check("list order", tl.list().map { it.id } == listOf(e4.id, e3.id, e2.id, e1.id))
    check("undoable flags", e1.undoable && e3.undoable && !e4.undoable)
    check("undo overwrite", tl.undo(e1.id).ok && doc.readText() == "version 1")
    check("undo twice", !tl.undo(e1.id).ok)
    check("undo create", tl.undo(e2.id).ok && !nf.exists())
    check("undo folder delete", tl.undo(e3.id).ok && File(fol, "x.txt").readText() == "X" && File(fol, "y.txt").readText() == "Y")
    check("undo command", !tl.undo(e4.id).ok)
    check("undo unknown", !tl.undo(9999).ok)
    // undoSince reverses newest first
    doc.writeText("v1"); val a1 = tl.snapshot(doc); doc.writeText("v2"); val r1 = tl.record("edit_file", "v2", true, doc.absolutePath, a1, true)
    val a2 = tl.snapshot(doc); doc.writeText("v3"); val r2 = tl.record("edit_file", "v3", true, doc.absolutePath, a2, true)
    val res = tl.undoSince(r1.id); check("undoSince", res.size == 2 && res.all { it.ok } && doc.readText() == "v1")
    // persistence + failed entries
    tl.record("write_file", "ব্যর্থ", false, "x", "", false)
    val tl2 = Timeline(File(dir, "tl"))
    check("persist", tl2.list(50).size == tl.list(50).size && tl2.get(e1.id)!!.undone)
    check("failed not undoable", !tl2.list(1)[0].undoable)
    check("new ids continue", tl2.record("a", "b", true).id > tl.list(1)[0].id)
    // size cap: huge file is not snapshotted
    val tl3 = Timeline(File(dir, "tl3"), maxSnapBytes = 10); val big2 = File(work, "big.bin"); big2.writeBytes(ByteArray(100))
    check("snapshot cap", tl3.snapshot(big2) == "")
    val e5 = tl3.record("write_file", "big", true, big2.absolutePath, "", true); check("cap undo says why", !tl3.undo(e5.id).ok)
    // ring buffer
    val tl4 = Timeline(File(dir, "tl4"), maxEntries = 5); repeat(12) { tl4.record("t", "n$it", true) }
    check("ring", tl4.list(50).size == 5 && tl4.list(1)[0].summary == "n11")
    dir.deleteRecursively()

    println("T3 passed=$passes failed=$fails"); if (fails > 0) System.exit(1)
}
