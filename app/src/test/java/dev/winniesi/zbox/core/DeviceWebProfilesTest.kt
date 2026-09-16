package dev.winniesi.zbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWebProfilesTest {

    @Test
    fun `uuid mid is stripped to alphanumerics`() {
        val name = DeviceWebProfiles.profileNameFor("11111111-2222-4333-8444-555555555555")
        assertTrue(name.matches(Regex("[a-zA-Z0-9]+")))
        assertEquals("11111111222243338444555555555555", name)
    }

    @Test
    fun `overlong mid is truncated`() {
        val name = DeviceWebProfiles.profileNameFor("a".repeat(100))
        assertEquals(40, name.length)
    }

    @Test
    fun `empty or fully stripped mid falls back`() {
        assertEquals("device", DeviceWebProfiles.profileNameFor(""))
        assertEquals("device", DeviceWebProfiles.profileNameFor("---"))
    }

    @Test
    fun `isolation follows profile store support`() {
        assertTrue(DeviceWebProfiles.shouldIsolate(true))
        assertFalse(DeviceWebProfiles.shouldIsolate(false))
    }
}

class ShortcutsTest {

    private fun record(mid: String, addedAt: Long, lastOpenedAt: Long?) = DeviceRecord(
        mid = mid, name = mid, host = "h", path = "/p", sid = "s", hashStored = "e",
        issuedAt = 0, appVersion = null, addedAt = addedAt, lastOpenedAt = lastOpenedAt,
    )

    @Test
    fun `takes most recently used devices`() {
        val picked = shortcutsFor(
            listOf(
                record("a", addedAt = 1, lastOpenedAt = null),
                record("b", addedAt = 2, lastOpenedAt = 900),
                record("c", addedAt = 3, lastOpenedAt = 1_000),
                record("d", addedAt = 4, lastOpenedAt = 500),
                record("e", addedAt = 5, lastOpenedAt = 100),
            ),
        )
        assertEquals(listOf("c", "b", "d", "e"), picked.map { it.mid })
    }

    @Test
    fun `respects custom max and empty list`() {
        assertEquals(2, shortcutsFor(listOf(record("a", 1, 1), record("b", 2, 2)), max = 2).size)
        assertTrue(shortcutsFor(emptyList()).isEmpty())
    }
}
