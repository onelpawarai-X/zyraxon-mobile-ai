package ai.zyraxon.assistant.lock

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * The admin component the user activates once, from a system screen that asks
 * only for "Allow". Being an active admin is what makes `lockNow()` legal — the
 * same call is refused with a SecurityException without it.
 *
 * No policy is declared beyond force-lock, so activation grants nothing else:
 * the app cannot wipe the device, reset the password or watch what is typed.
 */
class ZyraxonDeviceAdmin : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, ZyraxonDeviceAdmin::class.java)
    }
}
