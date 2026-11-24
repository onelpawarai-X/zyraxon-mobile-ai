package ai.zyraxon.assistant.ai

import java.util.Calendar
import java.util.TimeZone

/**
 * The friend check-in: the Dhaka clock and how long since the user last spoke.
 *
 * Every prompt path appends this block, so a greeting uses the real gap and
 * never invents one. `java.util.Calendar` (not `java.time`) keeps it safe on the
 * project's minimum SDK.
 */
object CompanionClock {
    private val DHAKA: TimeZone = TimeZone.getTimeZone("Asia/Dhaka")
    private val DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    private val MONTHS = arrayOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
    )
    private val WEEKDAYS = arrayOf("রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার")

    private fun bn(n: Int): String = buildString {
        for (ch in n.toString()) append(DIGITS[ch - '0'])
    }

    private fun clock(at: Long): String {
        val c = Calendar.getInstance(DHAKA).apply { timeInMillis = at }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val part = when {
            h < 12 -> "সকাল"
            h < 16 -> "দুপুর"
            h < 19 -> "বিকেল"
            else -> "রাত"
        }
        return "${bn(c.get(Calendar.DAY_OF_MONTH))} ${MONTHS[c.get(Calendar.MONTH)]} " +
            "${bn(c.get(Calendar.YEAR))}, $part ${bn(h12)}:${bn(c.get(Calendar.MINUTE))} " +
            "(${WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]})"
    }

    private fun gap(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        if (min < 2) return "এইমাত্র"
        if (min < 60) return "${bn(min.toInt())} মিনিট আগে"
        val hours = min / 60
        if (hours < 24) return "${bn(hours.toInt())} ঘণ্টা আগে"
        val days = hours / 24
        if (days < 30) return "${bn(days.toInt())} দিন আগে"
        val months = days / 30
        if (months < 12) return "${bn(months.toInt())} মাস আগে"
        return "${bn((months / 12).toInt())} বছর আগে"
    }

    /** A block to append to the system prompt. `lastUserAt` null = first talk. */
    fun block(lastUserAt: Long?): String {
        val now = System.currentTimeMillis()
        val lines = mutableListOf("এখন: ${clock(now)} — ঢাকা সময়।")
        if (lastUserAt != null && lastUserAt > 0) {
            lines += "শেষ কথা: ${gap(now - lastUserAt)} — ${clock(lastUserAt)}।"
        } else {
            lines += "শেষ কথা: এটাই প্রথম কথা — এই মানুষের সাথে এটা তোমার প্রথম বার্তা।"
        }
        return "\n## এই মুহূর্তের প্রসঙ্গ (শুধু তোমার জন্য — কখনো হুবহু বলবে না)\n" +
            lines.joinToString("\n") +
            "\nএই সময় দেখে মানুষের মতো খোঁজ নাও; একবারে একটাই প্রশ্ন করো।\n"
    }
}
