// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app lock is two independent secrets: a pattern and a PIN. These tests hold
 * that line — setting one must never disturb the other, and a guess is checked
 * only against the kind it claims.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppLockStoreTest {

    private lateinit var context: Context
    private lateinit var lock: AppLockStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("zyraxon_app_lock", Context.MODE_PRIVATE).edit().clear().commit()
        lock = AppLockStore(context)
    }

    @Test
    fun `a pattern verifies and a wrong one does not`() {
        lock.setPattern("0-1-2-3")
        assertTrue(lock.verifyPattern("0-1-2-3"))
        assertFalse(lock.verifyPattern("3-2-1-0"))
    }

    @Test
    fun `a pin verifies and a wrong one does not`() {
        lock.setPin("4821")
        assertTrue(lock.verifyPin("4821"))
        assertFalse(lock.verifyPin("1111"))
    }

    @Test
    fun `setting a pin leaves the pattern alone`() {
        lock.setPattern("0-4-8")
        lock.setPin("1379")
        assertTrue(lock.hasPattern)
        assertTrue(lock.hasPin)
        assertTrue(lock.verifyPattern("0-4-8"))
        assertTrue(lock.verifyPin("1379"))
    }

    @Test
    fun `setting a pattern leaves the pin alone`() {
        lock.setPin("1379")
        lock.setPattern("0-4-8")
        assertTrue(lock.verifyPin("1379"))
        assertTrue(lock.verifyPattern("0-4-8"))
    }

    @Test
    fun `removing the pattern keeps the pin`() {
        lock.setPattern("0-1-2")
        lock.setPin("2468")
        lock.removePattern()
        assertFalse(lock.hasPattern)
        assertTrue(lock.hasPin)
        assertTrue(lock.verifyPin("2468"))
    }

    @Test
    fun `removing the pin keeps the pattern`() {
        lock.setPattern("0-1-2")
        lock.setPin("2468")
        lock.removePin()
        assertTrue(lock.hasPattern)
        assertFalse(lock.hasPin)
    }

    @Test
    fun `a missing secret never verifies`() {
        assertFalse(lock.verifyPattern("0-1-2-3"))
        assertFalse(lock.verifyPin("1234"))
    }

    @Test
    fun `setting a secret arms the lock`() {
        assertFalse(lock.isArmed)
        lock.setPattern("0-1-2-3")
        assertTrue(lock.isEnabled)
        assertTrue(lock.isArmed)
    }

    @Test
    fun `the active kind follows the preference when both are set`() {
        lock.setPattern("0-1-2-3")
        lock.setPin("1234")
        lock.preferredKind = AppLockStore.MODE_PIN
        assertEquals(AppLockStore.MODE_PIN, lock.activeKind)
        lock.preferredKind = AppLockStore.MODE_PATTERN
        assertEquals(AppLockStore.MODE_PATTERN, lock.activeKind)
    }

    @Test
    fun `the active kind falls back to whichever exists`() {
        lock.setPin("1234")
        lock.preferredKind = AppLockStore.MODE_PATTERN
        assertEquals(AppLockStore.MODE_PIN, lock.activeKind)
    }

    @Test
    fun `verify with an explicit kind checks that kind`() {
        lock.setPattern("0-1-2-3")
        lock.setPin("1234")
        assertTrue(lock.verify("1234", AppLockStore.MODE_PIN))
        assertFalse(lock.verify("1234", AppLockStore.MODE_PATTERN))
    }

    @Test
    fun `clear removes both secrets`() {
        lock.setPattern("0-1-2-3")
        lock.setPin("1234")
        lock.clear()
        assertFalse(lock.hasPattern)
        assertFalse(lock.hasPin)
        assertFalse(lock.isEnabled)
    }

    @Test
    fun `the spoken words match case and space insensitively`() {
        lock.lockCommand = "লক করো"
        lock.unlockCommand = "খুলে দাও"
        assertTrue(lock.matchesLock("  লক করো "))
        assertTrue(lock.matchesUnlock("খুলে দাও"))
        assertFalse(lock.matchesLock("খুলে দাও"))
    }

    @Test
    fun `an unset spoken word never matches`() {
        assertFalse(lock.matchesLock("anything"))
        assertFalse(lock.matchesUnlock("anything"))
    }

    @Test
    fun `clearing a spoken word removes it`() {
        lock.unlockCommand = "খুলে দাও"
        lock.unlockCommand = ""
        assertNull(lock.unlockCommand)
        assertFalse(lock.matchesUnlock("খুলে দাও"))
    }
}
