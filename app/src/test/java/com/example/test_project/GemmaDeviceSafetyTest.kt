package com.example.test_project

import android.content.SharedPreferences
import com.example.test_project.ondevice.GemmaHealth
import com.example.test_project.ondevice.GemmaProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaDeviceSafetyTest {
    @Test
    fun fourGigabytePhoneUsesCpuAndLoadsAfterCapture() {
        // The Galaxy A17 that crashed reports about 3.6 GB.
        val profile = GemmaProfile.forMemory(totalRamBytes = 3_617_599_488L, lowRamDevice = false)
        assertFalse(profile.useGpu)
        assertFalse(profile.loadAtStartup)
        assertTrue(profile.outputTokens < profile.contextTokens)
    }

    @Test
    fun eightGigabytePhoneTriesGpuAndPreloads() {
        val profile = GemmaProfile.forMemory(totalRamBytes = 7_300_000_000L, lowRamDevice = false)
        assertTrue(profile.useGpu)
        assertTrue(profile.loadAtStartup)
    }

    @Test
    fun lowRamFlagAlwaysWins() {
        assertEquals(GemmaProfile.SMALL, GemmaProfile.forMemory(12_000_000_000L, lowRamDevice = true))
    }

    @Test
    fun lowMemoryKillWhileBusyTurnsGemmaOff() = runTest {
        val preferences = MemoryPreferences()
        val beforeKill = GemmaHealth(preferences)
        var busyDuringWork = false
        // Simulate the process dying inside the work: the busy mark is written first and never cleared.
        preferences.freezeWrites = false
        try {
            beforeKill.whileBusy {
                busyDuringWork = preferences.getBoolean("busy", false)
                preferences.freezeWrites = true
                throw IllegalStateException("process killed")
            }
        } catch (_: IllegalStateException) {
        }
        assertTrue(busyDuringWork)

        preferences.freezeWrites = false
        val nextLaunch = GemmaHealth(preferences)
        nextLaunch.checkLastExit(lastExitWasLowMemory = true)
        assertTrue(nextLaunch.isDisabled)

        nextLaunch.enable()
        assertFalse(nextLaunch.isDisabled)
    }

    @Test
    fun lowMemoryKillWhileIdleDoesNotBlameGemma() = runTest {
        val preferences = MemoryPreferences()
        val health = GemmaHealth(preferences)
        health.whileBusy { }
        health.checkLastExit(lastExitWasLowMemory = true)
        assertFalse(health.isDisabled)
    }

    @Test
    fun otherExitsWhileBusyDoNotTurnGemmaOff() {
        val preferences = MemoryPreferences().apply { edit().putBoolean("busy", true).commit() }
        val health = GemmaHealth(preferences)
        health.checkLastExit(lastExitWasLowMemory = false)
        assertFalse(health.isDisabled)
    }

    /** Minimal SharedPreferences; [freezeWrites] stands in for a process that has been killed. */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var freezeWrites = false

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = this
            override fun commit(): Boolean {
                if (!freezeWrites) values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
