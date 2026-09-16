package dev.winniesi.zbox.core

/** 链接（钥匙）新鲜度启发式判定结果。 */
enum class Freshness { FRESH, LIKELY_STALE }

/**
 * 官方没有公布钥匙有效期（刷新二维码立即使旧钥匙失效），
 * 只能按"钥匙生成距今多久"做启发式提示，超过阈值即认为"可能已失效"。
 */
fun freshnessOf(
    issuedAtMs: Long,
    nowMs: Long,
    staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
): Freshness = if (issuedAtMs > nowMs || nowMs - issuedAtMs < staleAfterMs) {
    Freshness.FRESH
} else {
    Freshness.LIKELY_STALE
}

const val DEFAULT_STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000
