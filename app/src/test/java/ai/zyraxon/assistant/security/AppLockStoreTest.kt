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
 * The app lock is one secret — a pattern or a PIN, whichever was chosen last —
 * with an optional spoken word stacked on top. These tests hold the two rules
 * that matter: choosing a second secret replaces the first rather than adding a
 * second door, and a guess is checked only against the kind it claims.
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
    fun `setting a pin replaces the pattern`() {
        lock.setPattern("0-4-8")
        lock.setPin("1379")
        assertFalse(lock.hasPattern)
        assertTrue(lock.hasPin)
        assertTrue(lock.verifyPin("1379"))
        assertFalse(lock.verifyPattern("0-4-8"))
    }

    @Test
    fun `setting a pattern replaces the pin`() {
        lock.setPin("1379")
        lock.setPattern("0-4-8")
        assertFalse(lock.hasPin)
        assertTrue(lock.hasPattern)
        assertTrue(lock.verifyPattern("0-4-8"))
        assertFalse(lock.verifyPin("1379"))
    }

    @Test
    fun `a spoken word stacks on top of a secret`() {
        lock.setPin("1379")
        lock.unlockCommand = "খুলে দাও"
        assertTrue(lock.hasPin)
        assertTrue(lock.hasVoice)
        assertTrue(lock.verifyPin("1379"))
        assertTrue(lock.matchesUnlock("খুলে দাও"))
    }

    @Test
    fun `a spoken word alone is a lock too`() {
        lock.unlockCommand = "খুলে দাও"
        assertTrue(lock.hasVoice)
        assertFalse(lock.hasSecret)
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
    fun `the active kind is whichever secret was chosen last`() {
        lock.setPin("1234")
        assertEquals(AppLockStore.MODE_PIN, lock.activeKind)
        lock.setPattern("0-1-2-3")
        assertEquals(AppLockStore.MODE_PATTERN, lock.activeKind)
    }

    @Test
    fun `the active kind is null when only a spoken word is set`() {
        lock.unlockCommand = "খুলে দাও"
        assertNull(lock.activeKind)
    }

    @Test
    fun `verify with an explicit kind checks that kind`() {
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
