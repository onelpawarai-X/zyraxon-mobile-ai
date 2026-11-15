import ai.zyraxon.assistant.world.*

var fails = 0; var passes = 0
fun check(name: String, c: Boolean) { if (c) passes++ else { fails++; println("FAIL: $name") } }

fun main() {
    var clock = 0L
    fun mk(known: String = "", lang: String = "bn") = CallSession("সায়েদ", "01712345678", known, now = { clock }, pack = LangPack.forLanguage(lang))
    // ---- the usual flow
    var s = mk(); val g = s.start().say
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
    check("summary text", sum.text("সায়েদ").let { it.contains("রহিম") && it.contains("মিটিং") && it.contains("Caller:") && it.contains("Assistant") })
    check("sms to owner", sum.smsToOwner().contains("রহিম") && sum.smsToOwner().length < 200)

    // ---- name and reason together
    s = mk(); s.start(); r = s.onCaller("আমি করিম, আপনার বাসার গ্যাস বিলের ব্যাপারে ফোন করেছি")
    check("name+purpose together", s.callerName == "করিম" && s.purpose.contains("গ্যাস") && r.say.contains("আর কিছু"))
    // ---- english
    s = mk(); s.start(); s.onCaller("My name is John"); check("english name", s.callerName == "John")
    // ---- bare name
    s = mk(); s.start(); s.onCaller("সালমা"); check("bare name", s.callerName == "সালমা")
    // ---- reason first, name after
    s = mk(); s.start(); r = s.onCaller("আপনাকে একটু জরুরি দরকার হাসপাতালে আসতে হবে")
    check("urgent detected", s.urgent && r.say.contains("নাম") && s.stage == Stage.ASK_NAME)
    r = s.onCaller("আমি রফিক"); check("then name", s.callerName == "রফিক" && s.stage == Stage.ASK_MORE)
    check("urgent in summary", s.summary().urgent && s.summary().smsToOwner().startsWith("‼"))
    // ---- never loops asking the name
    s = mk(); s.start(); s.onCaller("এই ধরেন একটা ব্যাপার আছে ওই প্রজেক্টের"); r = s.onCaller("প্রজেক্টের ডেডলাইন নিয়ে ভাই ওনার সাথে কথা বলা দরকার"); check("no endless name loop", s.stage == Stage.ASK_MORE && r.say.contains("আর কিছু"))
    // ---- spam
    s = mk("অজানা"); s.start(); r = s.onCaller("আপনি লোন অফার পেয়েছেন, ৫ লক্ষ টাকা পর্যন্ত")
    check("spam ends", s.spam && r.hangUp && s.summary().text("সায়েদ").contains("spam"))
    // ---- a question goes to the model
    s = mk(); s.start(); s.onCaller("আমি রহিম"); r = s.onCaller("সায়েদ ভাই কখন ফিরবেন?")
    check("question to model", r.model && !r.hangUp)
    check("model prompt rules", s.modelPrompt("কখন ফিরবেন?").let { it.contains("SAME language") && it.contains("কখন ফিরবেন") && it.contains("রহিম") })
    r = s.modelSaid("আমি জানি না, তবে আপনার বার্তা জানিয়ে দেব।"); check("model said spoken", r.say.contains("জানিয়ে") && !r.hangUp && s.stage == Stage.ASK_MORE)
    check("model empty", s.modelSaid("   ").say.isNotEmpty() && mk(lang = "th").modelSaid("  ").say.isEmpty())
    // ---- silence
    s = mk(); s.start(); r = s.onSilence(); check("silence 1", r.say.contains("আছেন") && !r.hangUp)
    r = s.onSilence(); check("silence 2 hangs up", r.hangUp && s.stage == Stage.ENDED)
    s = mk(); s.start(); s.onSilence(); s.onCaller("আমি রহিম"); r = s.onSilence(); check("silence resets after speech", !r.hangUp)
    // ---- known caller (contact)
    s = mk("আম্মু"); val g2 = s.start().say; check("known name greeting", g2.contains("আম্মু") && !g2.contains("নামটা") && s.stage == Stage.ASK_PURPOSE)
    // ---- extra messages kept, long calls end
    s = mk(); s.start(); s.onCaller("আমি রহিম"); s.onCaller("একটা কাজ আছে"); s.onCaller("আর একটা কথা, বাজারে দেখা হবে কাল"); check("extra message kept", s.summary().messages.isNotEmpty())
    s = mk(); s.start(); var ended = false; for (i in 1..20) { val x = s.onCaller("আরও একটা কথা $i"); if (x.hangUp) { ended = true; break } }; check("long call ends", ended)
    // ---- after the end nothing more is said
    check("after end", s.onCaller("হ্যালো").hangUp)
    // ---- transcript order
    s = mk(); s.start(); s.onCaller("আমি রহিম"); check("transcript", s.transcript().map { it.who } == listOf("agent", "caller", "agent"))

    // ================= any language
    // english pack
    s = mk(lang = "en"); var st = s.start().say; check("en greeting", st.contains("assistant") && st.contains("name"))
    r = s.onCaller("Hi, this is Maria"); check("en name", s.callerName == "Maria" && r.say.contains("Maria"))
    r = s.onCaller("Please tell him the invoice is ready"); check("en purpose", s.purpose.contains("invoice") && r.say.contains("anything else", true))
    r = s.onCaller("No, thank you"); check("en bye", r.hangUp && r.say.contains("goodbye", true))
    // spanish, french, german, hindi, arabic, japanese, russian, chinese: greet + name + goodbye
    val samples = mapOf("es" to Triple("Me llamo Carlos", "Quiero dejar un recado para él", "No, gracias"),
        "fr" to Triple("Je m'appelle Pierre", "Je voulais confirmer le rendez-vous de demain", "Non, merci"),
        "de" to Triple("Mein Name ist Hans", "Ich möchte den Termin morgen bestätigen", "Nein, danke"),
        "hi" to Triple("मेरा नाम राहुल", "मुझे कल की मीटिंग के बारे में बताना था", "नहीं, धन्यवाद"),
        "ar" to Triple("اسمي أحمد", "أريد ترك رسالة له", "لا، شكراً"),
        "ja" to Triple("私は田中", "明日の会議について連絡しました", "いいえ、ありがとう"),
        "ru" to Triple("Меня зовут Иван", "Хочу подтвердить встречу завтра", "Нет, спасибо"),
        "zh" to Triple("我叫王明", "我想确认明天的会议", "没有了，谢谢"),
        "pt" to Triple("Meu nome é João", "Queria confirmar a reunião de amanhã", "Não, obrigado"),
        "tr" to Triple("Benim adım Ali", "Yarınki toplantıyı teyit etmek istiyorum", "Hayır, teşekkürler"),
        "id" to Triple("Nama saya Budi", "Saya ingin konfirmasi rapat besok", "Tidak, terima kasih"),
        "ko" to Triple("저는 김민수", "내일 회의 확인하려고 전화했어요", "아니요, 감사합니다"),
        "it" to Triple("Mi chiamo Marco", "Volevo confermare la riunione di domani", "No, grazie"))
    for ((lang, t) in samples) {
        s = mk(lang = lang); st = s.start().say
        check("$lang greeting", st.contains("সায়েদ") && st.length > 10)
        s.onCaller(t.first); check("$lang name taken (${s.callerName})", s.callerName.isNotBlank() && s.stage == Stage.ASK_PURPOSE)
        s.onCaller(t.second); check("$lang purpose", s.purpose.isNotBlank() && s.stage == Stage.ASK_MORE)
        r = s.onCaller(t.third); check("$lang bye", r.hangUp && s.stage == Stage.ENDED)
    }
    // spam / urgent words work in every language
    s = mk(lang = "es"); s.start(); r = s.onCaller("Tiene un préstamo preaprobado, oferta especial"); check("es spam", s.spam && r.hangUp)
    s = mk(lang = "de"); s.start(); s.onCaller("Ich bin Hans"); s.onCaller("Es ist ein Notfall, bitte rufen Sie zurück"); check("de urgent", s.urgent)
    s = mk(lang = "ar"); s.start(); s.onCaller("اسمي سامي"); s.onCaller("الأمر عاجل جداً"); check("ar urgent", s.urgent)
    // a language without a pack: the model talks
    check("no pack for th", LangPack.forLanguage("th") == null && LangPack.forLanguage("pt-BR") != null && LangPack.forLanguage("zh_CN") != null)
    s = mk(lang = "th"); r = s.start(); check("model greets", r.model && s.modelDriven && s.modelPrompt("").contains("Greet the caller"))
    r = s.modelSaid("สวัสดีค่ะ ดิฉันเป็นผู้ช่วยของคุณสายิด ขอทราบชื่อได้ไหมคะ"); check("model greeting spoken", r.say.contains("สวัสดี") && !r.hangUp)
    r = s.onCaller("ผมสมชาย โทรมาเรื่องงานด่วน"); check("model gets caller", r.model && s.modelPrompt("x").contains("ผมสมชาย"))
    r = s.modelSaid("รับทราบค่ะ จะแจ้งให้ทราบนะคะ [NAME: สมชาย] [PURPOSE: เรื่องงาน] [URGENT]"); check("tags parsed", s.callerName == "สมชาย" && s.purpose == "เรื่องงาน" && s.urgent && !r.say.contains("[") && !r.hangUp)
    r = s.modelSaid("ขอบคุณค่ะ สวัสดีค่ะ [END]"); check("model ends call", r.hangUp && s.stage == Stage.ENDED && !r.say.contains("END"))
    s = mk(lang = "th"); s.start(); r = s.onSilence(); check("model silence", r.model && s.modelPrompt("").contains("nothing"))
    s = mk(lang = "th"); s.start(); for (i in 1..16) s.onCaller("more $i"); check("model long call wraps up", s.modelPrompt("x").contains("[END]") && s.modelSaid("bye").hangUp)
    check("model empty tags only", mk(lang = "th").modelSaid("[END]").hangUp)
    check("summary fallback purpose", mk(lang = "th").also { it.start(); it.onCaller("hello there about the car") }.summary().purpose.contains("car"))
    // summary in the user's words
    check("summary labels", sum.text("X") { k -> if (k == "call_title") "📞 সারাংশ" else k }.contains("সারাংশ"))

    println("T4 passed=$passes failed=$fails"); if (fails > 0) System.exit(1)
}
