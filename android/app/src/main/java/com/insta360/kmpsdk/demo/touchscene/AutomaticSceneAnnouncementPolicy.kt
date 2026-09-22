package com.insta360.kmpsdk.demo.touchscene

/** Announces an initial useful scene and subsequent meaningful changes, never every frame. */
class AutomaticSceneAnnouncementPolicy(private val cooldownMs: Long = 5_000L) {
    init { require(cooldownMs >= 0) }

    private var lastSignature: String? = null
    private var lastAnnouncedAt: Long = Long.MIN_VALUE

    fun shouldAnnounce(description: SceneDescription, nowMs: Long): Boolean {
        val signature = signature(description) ?: return false
        if (signature == lastSignature) return false
        if (lastAnnouncedAt != Long.MIN_VALUE && nowMs - lastAnnouncedAt < cooldownMs) return false
        lastSignature = signature
        lastAnnouncedAt = nowMs
        return true
    }

    fun markAnnounced(description: SceneDescription, nowMs: Long) {
        signature(description)?.let { lastSignature = it; lastAnnouncedAt = nowMs }
    }

    fun reset() {
        lastSignature = null
        lastAnnouncedAt = Long.MIN_VALUE
    }

    private fun signature(description: SceneDescription): String? {
        if (description.text == "暂时没有可用的画面描述") return null
        return listOf(
            description.subject,
            description.position,
            description.approximateSize,
            description.clipped?.toString(),
            description.lighting,
        ).joinToString("|") { it.orEmpty().trim() }.takeIf { it.isNotBlank() && it != "||||" }
            ?: description.text.trim().takeIf { it.isNotEmpty() }
    }
}
