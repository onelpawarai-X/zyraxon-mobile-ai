package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.auto.AutoTools
import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** The hub's buttons talk to the same tools the agent uses, so a button and a spoken command always do the same thing. */
object HubApi {
    suspend fun call(c: Context, tool: String, vararg kv: Pair<String, Any?>): JSONObject =
        AutoTools.run(c, tool, JSONObject().also { o -> kv.forEach { o.put(it.first, it.second) } })

    fun list(r: JSONObject): List<JSONObject> { val a = r.optJSONArray("data") ?: JSONArray(); return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    fun obj(r: JSONObject): JSONObject = r.optJSONObject("data") ?: JSONObject()
    fun text(r: JSONObject): String = r.optString("summary")

    fun accessibilityOn(c: Context): Boolean = runCatching {
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(c.packageName)
    }.getOrDefault(false)

    fun openAccessibilitySettings(c: Context) { runCatching { c.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    fun openListenerSettings(c: Context) { runCatching { c.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    fun goHome(c: Context) { runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    fun shareFile(c: Context, f: java.io.File, mime: String = "application/octet-stream") {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
            val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            c.startActivity(android.content.Intent.createChooser(i, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openFile(c: Context, f: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
            val mime = java.net.URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
            c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
