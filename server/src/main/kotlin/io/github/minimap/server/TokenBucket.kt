package io.github.minimap.server

/** Monotonic-time byte budget, owned by the server thread. */
class TokenBucket(private val rate: Double, private val capacity: Double, now: Long) {
    private var tokens = capacity
    private var last = now

    init {
        require(rate.isFinite() && capacity.isFinite() && rate > 0 && capacity > 0)
    }

    fun available(bytes: Int, now: Long): Boolean {
        tokens = minOf(capacity, tokens + maxOf(0, now - last) / 1e9 * rate)
        last = now
        return bytes >= 0 && bytes <= tokens
    }

    fun take(bytes: Int, now: Long): Boolean {
        if (!available(bytes, now)) return false
        tokens -= bytes
        return true
    }
}
