package ai.zyraxon.assistant.lock

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Screen locking and unlocking, and the enrolment that makes locking possible.
 *
 * Locking is a real system lock, not a dark overlay: the keyguard comes up and
 * the phone's own PIN, pattern or password is what gets past it. Unlocking
 * therefore works only when the user has set no secure lock — with one in place
 * Android will not let any app lift the keyguard, and [unlockScreen] says so
 * instead of pretending.
 */
object DeviceLocker {

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True once the user has activated ZYRAXON in the device-admin screen. */
    fun isAdminActive(context: Context): Boolean = try {
        dpm(context).isAdminActive(ZyraxonDeviceAdmin.component(context))
    } catch (_: Throwable) {
        false
    }

    /** Whether the phone has a PIN, pattern or password the keyguard enforces. */
    fun hasSecureLock(context: Context): Boolean = try {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        // isDeviceSecure is API 23; before that isKeyguardSecure answered the
        // same question and is still present, just deprecated.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) km.isDeviceSecure
        else @Suppress("DEPRECATION") km.isKeyguardSecure
    } catch (_: Throwable) {
        false
    }

    fun isScreenOn(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Throwable) {
        true
    }

    /**
     * The system screen that grants admin rights. The user taps "Activate" and
     * the app never has to be opened to do it.
     */
    fun enrolIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ZyraxonDeviceAdmin.component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Zyraxon কে স্ক্রিন লক করার অনুমতি দিন। এটি শুধু লক করে — ডেটা মোছে না, " +
                    "পাসওয়ার্ড দেখে না, অন্য কোনো ক্ষমতা নেয় না।"
            )

    /** The system screen that revokes admin rights. */
    fun revokeIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ZyraxonDeviceAdmin.component(context))

    /**
     * Locks the phone now. Returns false when the app is not an active admin, or
     * the platform refused — the caller reports that rather than claiming success.
     */
    fun lockNow(context: Context): Boolean = try {
        val manager = dpm(context)
        if (!manager.isAdminActive(ZyraxonDeviceAdmin.component(context))) {
            false
        } else {
            manager.lockNow()
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Lifts the keyguard. Android allows this only while no secure lock is set,
     * so a phone with a PIN reports [UNLOCK_NEEDS_SECURE_LOCK] and the user's own
     * PIN stays in charge. [finishActivity] is the transient activity the
     * platform asks for; when it is null the activity is launched instead.
     */
    fun unlockScreen(context: Context, finishActivity: android.app.Activity? = null): UnlockResult {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (km.isKeyguardLocked.not()) return UnlockResult.ALREADY_UNLOCKED
        val secure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) km.isDeviceSecure
        else @Suppress("DEPRECATION") km.isKeyguardSecure
        if (secure) return UnlockResult.UNLOCK_NEEDS_SECURE_LOCK
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && finishActivity != null) {
                km.requestDismissKeyguard(finishActivity, null)
                UnlockResult.REQUESTED
            } else {
                @Suppress("DEPRECATION")
                km.newKeyguardLock("zyraxon").disableKeyguard()
                UnlockResult.REQUESTED
            }
        } catch (e: Throwable) {
            UnlockResult.FAILED
        }
    }

    /**
     * Turns the screen off by locking it, which is the only way an app may do
     * this; waking is the user's power button. Both are reported honestly.
     */
    fun screenOff(context: Context): Boolean = lockNow(context)

    /**
     * Whether the phone can be told to skip its lock screen after a lock, for
     * phones with no secure lock. Purely informational for the assistant.
     */
    fun canSkipLockScreen(context: Context): Boolean =
        !hasSecureLock(context) && isAdminActive(context)

    /** Opens the system's own lock-screen settings so the user can set a PIN. */
    fun secureLockSettingsIntent(): Intent =
        Intent(Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    enum class UnlockResult {
        ALREADY_UNLOCKED,
        REQUESTED,
        UNLOCK_NEEDS_SECURE_LOCK,
        FAILED
    }
}
