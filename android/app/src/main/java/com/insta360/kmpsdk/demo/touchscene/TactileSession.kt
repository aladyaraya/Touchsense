package com.insta360.kmpsdk.demo.touchscene

enum class TactileSessionState { LIVE, EXPLORING, STALE }

/** Keeps exploration snapshots stable even while live analysis continues. */
class TactileSession {
    private var latest: TactileMap? = null
    private var frozen: TactileMap? = null

    var state: TactileSessionState = TactileSessionState.LIVE
        private set

    @Synchronized
    fun currentState(): TactileSessionState = state

    @Synchronized
    fun updateLive(map: TactileMap) {
        latest = map
    }

    @Synchronized
    fun freeze(): TactileMap? {
        val source = latest ?: return null
        frozen = TactileMap(source.version, source.sourceTimestampMs, source.width, source.height, source.copyCells())
        state = TactileSessionState.EXPLORING
        return frozen
    }

    @Synchronized
    fun refresh(): TactileMap? {
        val current = frozen ?: return null
        val candidate = latest ?: return null
        if (candidate.version <= current.version) return null
        return freeze()
    }

    @Synchronized
    fun markStale(): Boolean {
        if (frozen == null || state == TactileSessionState.STALE) return false
        state = TactileSessionState.STALE
        return true
    }

    @Synchronized
    fun leaveExploration() {
        frozen = null
        state = TactileSessionState.LIVE
    }

    @Synchronized
    fun activeMap(): TactileMap? = if (state == TactileSessionState.LIVE) latest else frozen
}
