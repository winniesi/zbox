package dev.winniesi.zbox.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDtoRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialize and deserialize preserves records`() {
        val records = listOf(
            DeviceRecord(
                mid = "mid-1",
                name = "omarchy",
                host = "zcode.z.ai",
                path = "/remote/v4",
                sid = "s1",
                hashStored = "enc(h1)",
                issuedAt = 123L,
                appVersion = "3.11.2",
                addedAt = 456L,
                lastOpenedAt = 789L,
                customName = true,
            ),
            DeviceRecord(
                mid = "mid-2",
                name = "server",
                host = "zcode.z.ai",
                path = "/remote/v4",
                sid = "s2",
                hashStored = "enc(h2)",
                issuedAt = 1L,
                appVersion = null,
                addedAt = 2L,
            ),
        )
        val book = DeviceBookDto(devices = records.map { it.toDto() })
        val raw = json.encodeToString(DeviceBookDto.serializer(), book)
        val decoded = json.decodeFromString(DeviceBookDto.serializer(), raw)
        assertEquals(book, decoded)
        assertEquals(records, decoded.devices.map { it.toRecord() })
    }

    @Test
    fun `unknown fields in stored json are tolerated`() {
        val raw = """
            {"version":1,"devices":[{"mid":"m","name":"n","host":"h","path":"/p","sid":"s",
            "hashStored":"e","issuedAt":1,"addedAt":2,"futureField":"x"}]}
        """.trimIndent()
        val decoded = json.decodeFromString(DeviceBookDto.serializer(), raw)
        assertEquals(1, decoded.devices.size)
        assertEquals("m", decoded.devices[0].toRecord().mid)
    }

    @Test
    fun `device link rebuilds loadable url`() {
        val record = DeviceRecord(
            mid = "m",
            name = "n",
            host = "zcode.z.ai",
            path = "/remote/v4",
            sid = "s",
            hashStored = "e",
            issuedAt = 42L,
            appVersion = "3.11.2",
            addedAt = 0,
        )
        val url = record.link("secret").toUrlString()
        assertEquals(
            "https://zcode.z.ai/remote/v4?sid=s&hash=secret&t=42&mid=m&app_version=3.11.2",
            url,
        )
    }
}
