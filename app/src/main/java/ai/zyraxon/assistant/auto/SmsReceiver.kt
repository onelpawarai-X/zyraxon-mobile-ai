package ai.zyraxon.assistant.auto

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.autopilot.Kind
import ai.zyraxon.assistant.finance.Fmt
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** A new SMS: money messages go into the ledger, and any autopilot rule that matches wakes the agent. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = LinkedHashMap<String, StringBuilder>()
        var time = System.currentTimeMillis()
        for (p in parts) { bySender.getOrPut(p.originatingAddress.orEmpty()) { StringBuilder() }.append(p.messageBody.orEmpty()); if (p.timestampMillis > 0) time = p.timestampMillis }
        val pending = goAsync()
        Thread {
            try {
                for ((sender, body) in bySender) {
                    val text = body.toString()
                    if (AutoTools.prefs(context).getBoolean("ledger_auto", false)) {
                        AutoTools.ledgerAddSms(context, sender, text, time)?.let { line ->
                            DeviceBridge.notify(context, "হিসাবে যোগ হলো", line)
                            val l = AutoTools.loadLedger(context)
                            val c = java.util.Calendar.getInstance(); c.set(java.util.Calendar.DAY_OF_MONTH, 1); c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
                            l.alerts(c.timeInMillis, System.currentTimeMillis() + 86_400_000L).firstOrNull()?.let { DeviceBridge.notify(context, "বাজেট সতর্কতা", it) }
                        }
                    }
                    AutoRuntime.event(context, Kind.SMS, from = sender, text = text)
                }
            } catch (_: Exception) {
            } finally { pending.finish() }
        }.start()
    }
}
