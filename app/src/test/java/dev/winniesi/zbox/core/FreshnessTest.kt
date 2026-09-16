package dev.winniesi.zbox.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshnessTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `just issued is fresh`() {
        assertEquals(Freshness.FRESH, freshnessOf(now, now))
    }

    @Test
    fun `just under threshold is fresh`() {
        val issued = now - DEFAULT_STALE_AFTER_MS + 1
        assertEquals(Freshness.FRESH, freshnessOf(issued, now))
    }

    @Test
    fun `exactly at threshold is stale`() {
        val issued = now - DEFAULT_STALE_AFTER_MS
        assertEquals(Freshness.LIKELY_STALE, freshnessOf(issued, now))
    }

    @Test
    fun `far past threshold is stale`() {
        val issued = now - 7 * 24 * hour
        assertEquals(Freshness.LIKELY_STALE, freshnessOf(issued, now))
    }

    @Test
    fun `future issued time treated as fresh`() {
        assertEquals(Freshness.FRESH, freshnessOf(now + hour, now))
    }

    @Test
    fun `custom threshold respected`() {
        val issued = now - 2 * hour
        assertEquals(Freshness.LIKELY_STALE, freshnessOf(issued, now, staleAfterMs = hour))
        assertEquals(Freshness.FRESH, freshnessOf(issued, now, staleAfterMs = 3 * hour))
    }
}
