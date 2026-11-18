package ai.zyraxon.assistant.help

/** One chapter of the built-in documentation. The text lives in `assets-src/help/<lang>/<file>`. */
class HelpChapter(val id: String, val icon: String, val file: String)

/** The chapters in reading order. Every language the app offers ships all of them in the APK. */
object HelpIndex {
    val chapters: List<HelpChapter> = listOf(
        HelpChapter("start", "🚀", "01-start.txt"),
        HelpChapter("chat", "💬", "02-chat.txt"),
        HelpChapter("modes", "🎛", "03-modes.txt"),
        HelpChapter("persona", "🎭", "04-persona.txt"),
        HelpChapter("voice", "🎙", "05-voice.txt"),
        HelpChapter("phone", "📱", "06-tools-phone.txt"),
        HelpChapter("files", "🗂", "07-tools-files.txt"),
        HelpChapter("build", "🛠", "08-tools-build.txt"),
        HelpChapter("media", "🎨", "09-tools-media.txt"),
        HelpChapter("cloud", "☁", "10-tools-cloud.txt"),
        HelpChapter("smart", "🧩", "11-tools-smart.txt"),
        HelpChapter("permissions", "🔑", "12-permissions.txt"),
        HelpChapter("approval", "🛡", "13-approval.txt"),
        HelpChapter("settings", "⚙", "14-settings.txt"),
        HelpChapter("models", "🧬", "15-models.txt"),
        HelpChapter("security", "🔒", "16-security.txt"),
        HelpChapter("language", "🌍", "17-language.txt"),
        HelpChapter("updates", "⬇", "18-updates.txt"),
        HelpChapter("tips", "💡", "19-tips.txt"),
        HelpChapter("trouble", "🩺", "20-trouble.txt"),
        HelpChapter("hub", "🤖", "21-hub.txt")
    )
}
