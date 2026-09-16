package dev.winniesi.zbox.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRepositoryTest {

    /** 内存存储，统计 load/save 次数用于验证缓存行为。 */
    private class FakeStore : DeviceStore {
        var data: List<DeviceRecord> = emptyList()
        var loadCalls = 0
        var saveCalls = 0

        override suspend fun load(): List<DeviceRecord> {
            loadCalls++
            return data
        }

        override suspend fun save(devices: List<DeviceRecord>) {
            saveCalls++
            data = devices
        }
    }

    /** 可逆假加密，模拟真实 SecretCipher 的存取对称性。 */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: String): String = "enc($plain)"
        override fun decrypt(stored: String): String = stored.removePrefix("enc(").removeSuffix(")")
    }

    private var nowMs = 1_000L
    private val store = FakeStore()
    private val cipher = FakeCipher()

    private fun repo(): DeviceRepository = DeviceRepository(
        store = store,
        cipher = cipher,
        now = { nowMs },
    )

    private fun link(
        mid: String = "mid-1",
        sid: String = "s1",
        hash: String = "h1",
        t: Long = 5_000L,
        name: String? = "omarchy",
        appVersion: String? = "3.11.2",
        host: String = "zcode.z.ai",
    ): RemoteLink = RemoteLink(
        scheme = "https",
        host = host,
        path = "/remote/v4",
        sid = sid,
        hash = hash,
        t = t,
        mid = mid,
        name = name,
        appVersion = appVersion,
    )

    @Test
    fun `adding new device persists encrypted record`() = runTest {
        val result = repo().addOrUpdateFromLink(link()) as AddResult.Added
        assertEquals("omarchy", result.record.name)
        assertEquals("enc(h1)", result.record.hashStored)
        assertEquals(1_000L, result.record.addedAt)
        assertEquals(1, store.data.size)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun `name falls back when link has no name`() = runTest {
        val result = repo().addOrUpdateFromLink(link(name = null)) as AddResult.Added
        assertEquals(DeviceRepository.DEFAULT_NAME, result.record.name)
    }

    @Test
    fun `rescanning same mid updates credentials without duplicating`() = runTest {
        val repo = repo()
        val added = repo.addOrUpdateFromLink(link()) as AddResult.Added
        nowMs = 2_000L
        val updated = repo.addOrUpdateFromLink(link(sid = "s2", hash = "h2", t = 9_999)) as AddResult.Updated

        assertEquals(added.record.mid, updated.record.mid)
        assertEquals(1, store.data.size)
        assertEquals("s2", updated.record.sid)
        assertEquals("enc(h2)", updated.record.hashStored)
        assertEquals(9_999L, updated.record.issuedAt)
        assertEquals(1_000L, updated.record.addedAt) // 添加时间保留
    }

    @Test
    fun `display name sets custom name and survives rescan`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link(), displayName = "我的机器")
        val updated = repo.addOrUpdateFromLink(link(name = "renamed-on-desktop")) as AddResult.Updated
        assertEquals("我的机器", updated.record.name)
        assertTrue(updated.record.customName)
    }

    @Test
    fun `non custom name follows desktop rename on rescan`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link(name = "old"))
        val updated = repo.addOrUpdateFromLink(link(name = "new-name")) as AddResult.Updated
        assertEquals("new-name", updated.record.name)
        assertFalse(updated.record.customName)
    }

    @Test
    fun `rename marks custom and survives rescan`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link())
        repo.rename("mid-1", "手改名")
        val updated = repo.addOrUpdateFromLink(link(name = "again")) as AddResult.Updated
        assertEquals("手改名", updated.record.name)
        assertTrue(updated.record.customName)
    }

    @Test
    fun `blank rename is ignored`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link(name = "keep"))
        repo.rename("mid-1", "   ")
        assertEquals("keep", store.data.single().name)
        assertFalse(store.data.single().customName)
    }

    @Test
    fun `remove deletes and persists`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link(mid = "a"))
        repo.addOrUpdateFromLink(link(mid = "b"))
        repo.remove("a")
        assertEquals(listOf("b"), store.data.map { it.mid })
    }

    @Test
    fun `markOpened stamps last opened`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link())
        nowMs = 5_555L
        repo.markOpened("mid-1")
        assertEquals(5_555L, store.data.single().lastOpenedAt)
    }

    @Test
    fun `exists checks by mid`() = runTest {
        val repo = repo()
        repo.addOrUpdateFromLink(link(mid = "x"))
        assertTrue(repo.exists("x"))
        assertFalse(repo.exists("y"))
    }

    @Test
    fun `plainUrl decrypts hash and parses back to same link`() = runTest {
        val repo = repo()
        val record = (repo.addOrUpdateFromLink(link()) as AddResult.Added).record
        val url = repo.plainUrl(record)
        val parsed = RemoteLinkParser().parse(url) as RemoteLinkParser.Result.Ok
        assertEquals("h1", parsed.link.hash)
        assertEquals("s1", parsed.link.sid)
        assertEquals("zcode.z.ai", parsed.link.host)
        assertEquals("/remote/v4", parsed.link.path)
        assertEquals(record.issuedAt, parsed.link.t)
    }

    @Test
    fun `state is reloaded once and cached across reads`() = runTest {
        val repo = repo()
        repo.refresh()
        repo.refresh()
        repo.addOrUpdateFromLink(link())
        assertEquals(1, store.loadCalls)
    }

    @Test
    fun `new repository instance reads persisted devices`() = runTest {
        repo().addOrUpdateFromLink(link(mid = "persisted"))
        val second = repo()
        val loaded = second.refresh()
        assertEquals(1, loaded.size)
        assertEquals("persisted", loaded.single().mid)
        assertEquals("h1", cipher.decrypt(loaded.single().hashStored))
    }
}

class SortedForDisplayTest {

    private fun record(mid: String, addedAt: Long, lastOpenedAt: Long?) = DeviceRecord(
        mid = mid,
        name = mid,
        host = "h",
        path = "/p",
        sid = "s",
        hashStored = "e",
        issuedAt = 0,
        appVersion = null,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
    )

    @Test
    fun `recently opened first, then by addedAt desc, never opened last`() {
        val sorted = sortedForDisplay(
            listOf(
                record("never-old", addedAt = 100, lastOpenedAt = null),
                record("opened-2", addedAt = 1, lastOpenedAt = 900),
                record("never-new", addedAt = 200, lastOpenedAt = null),
                record("opened-1", addedAt = 1, lastOpenedAt = 1_000),
            ),
        )
        assertEquals(listOf("opened-1", "opened-2", "never-new", "never-old"), sorted.map { it.mid })
    }
}
