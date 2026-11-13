import ai.zyraxon.assistant.callagent.*

var fails = 0; var passes = 0
fun check(name: String, c: Boolean) { if (c) passes++ else { fails++; println("FAIL: $name") } }

fun main() {
    var clock = 0L
    fun mk(known: String = "") = CallSession("সায়েদ", "01712345678", known, now = { clock })
    // ---- the usual flow
    var s = mk(); val g = s.greeting()
    check("greeting asks name", g.contains("সায়েদ") && g.contains("নাম") && s.stage == Stage.ASK_NAME)
    clock += 5000
    var r = s.onCaller("আমি রহিম বলছি")
    check("name taken", s.callerName == "রহিম" && r.say.contains("রহিম") && s.stage == Stage.ASK_PURPOSE)
    r = s.onCaller("কালকের মিটিংটা বিকাল ৫টায় নিয়ে যেতে বলেছেন স্যার")
    check("purpose + ask more", s.purpose.contains("মিটিং") && r.say.contains("আর কিছু") && !r.hangUp && !r.model)
    r = s.onCaller("না, ধন্যবাদ")
    check("goodbye hangs up", r.hangUp && s.stage == Stage.ENDED)
    clock += 40000
    val sum = s.summary()
    check("summary", sum.caller == "রহিম" && sum.purpose.contains("মিটিং") && sum.seconds == 45 && !sum.urgent && !sum.spam)
    check("summary text", sum.text("সায়েদ").let { it.contains("রহিম") && it.contains("মিটিং") && it.contains("কলার:") && it.contains("সহকারী:") })
    check("sms to owner", sum.smsToOwner().contains("রহিম") && sum.smsToOwner().length < 200)

    // ---- name and reason together
    s = mk(); s.greeting(); r = s.onCaller("আমি করিম, আপনার বাসার গ্যাস বিলের ব্যাপারে ফোন করেছি")
    check("name+purpose together", s.callerName == "করিম" && s.purpose.contains("গ্যাস") && r.say.contains("আর কিছু"))
    // ---- english
    s = mk(); s.greeting(); s.onCaller("My name is John"); check("english name", s.callerName == "John")
    // ---- bare name
    s = mk(); s.greeting(); s.onCaller("সালমা"); check("bare name", s.callerName == "সালমা")
    // ---- reason first, name after
    s = mk(); s.greeting(); r = s.onCaller("আপনাকে একটু জরুরি দরকার হাসপাতালে আসতে হবে")
    check("urgent detected", s.urgent && r.say.contains("নাম") && s.stage == Stage.ASK_NAME)
    r = s.onCaller("আমি রফিক"); check("then name", s.callerName == "রফিক" && s.stage == Stage.ASK_MORE)
    check("urgent in summary", s.summary().urgent && s.summary().smsToOwner().startsWith("জরুরি"))
    // ---- never loops asking the name
    s = mk(); s.greeting(); s.onCaller("এই ধরেন একটা ব্যাপার আছে ওই প্রজেক্টের"); r = s.onCaller("প্রজেক্টের ডেডলাইন নিয়ে ভাই ওনার সাথে কথা বলা দরকার"); check("no endless name loop", s.stage == Stage.ASK_MORE && r.say.contains("আর কিছু"))
    // ---- spam
    s = mk("অজানা"); s.greeting(); r = s.onCaller("আপনি লোন অফার পেয়েছেন, ৫ লক্ষ টাকা পর্যন্ত")
    check("spam ends", s.spam && r.hangUp && s.summary().text("সায়েদ").contains("স্প্যাম"))
    // ---- a question goes to the model
    s = mk(); s.greeting(); s.onCaller("আমি রহিম"); r = s.onCaller("সায়েদ ভাই কখন ফিরবেন?")
    check("question to model", r.model && !r.hangUp)
    check("model prompt rules", s.modelPrompt("কখন ফিরবেন?").let { it.contains("ব্যক্তিগত তথ্য") && it.contains("বাংলায়") && it.contains("কখন ফিরবেন") && it.contains("রহিম") })
    r = s.modelSaid("আমি জানি না, তবে আপনার বার্তা জানিয়ে দেব।"); check("model said spoken", r.say.contains("জানিয়ে") && !r.hangUp && s.stage == Stage.ASK_MORE)
    check("model empty", s.modelSaid("   ").say.isEmpty())
    // ---- silence
    s = mk(); s.greeting(); r = s.onSilence(); check("silence 1", r.say.contains("আছেন") && !r.hangUp)
    r = s.onSilence(); check("silence 2 hangs up", r.hangUp && s.stage == Stage.ENDED)
    s = mk(); s.greeting(); s.onSilence(); s.onCaller("আমি রহিম"); r = s.onSilence(); check("silence resets after speech", !r.hangUp)
    // ---- known caller (contact)
    s = mk("আম্মু"); val g2 = s.greeting(); check("known name greeting", g2.contains("আম্মু") && !g2.contains("নামটা") && s.stage == Stage.ASK_PURPOSE)
    // ---- extra messages kept, long calls end
    s = mk(); s.greeting(); s.onCaller("আমি রহিম"); s.onCaller("একটা কাজ আছে"); s.onCaller("আর একটা কথা, বাজারে দেখা হবে কাল"); check("extra message kept", s.summary().messages.isNotEmpty())
    s = mk(); s.greeting(); var ended = false; for (i in 1..20) { val x = s.onCaller("আরও একটা কথা $i"); if (x.hangUp) { ended = true; break } }; check("long call ends", ended)
    // ---- after the end nothing more is said
    check("after end", s.onCaller("হ্যালো").hangUp)
    // ---- transcript order
    s = mk(); s.greeting(); s.onCaller("আমি রহিম"); check("transcript", s.transcript().map { it.who } == listOf("agent", "caller", "agent"))

    println("T4 passed=$passes failed=$fails"); if (fails > 0) System.exit(1)
}
