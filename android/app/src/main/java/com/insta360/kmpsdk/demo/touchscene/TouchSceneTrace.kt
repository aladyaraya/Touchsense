package com.insta360.kmpsdk.demo.touchscene

import java.util.ArrayDeque
import java.util.UUID

/** Bounded, metadata-only event history for a single preview session. */
class TouchSceneTrace(
    val sessionId: String = UUID.randomUUID().toString(),
    private val capacity: Int = 256,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(capacity > 0) }

    data class Event(
        val sessionId: String,
        val wallTimeMs: Long,
        val elapsedMs: Long,
        val stage: String,
        val event: String,
        val code: String?,
        val fallback: String?,
        val mapVersion: Long?,
    ) {
        fun toJsonLine(): String = buildString {
            append('{')
            append("\"sessionId\":").append(sessionId.jsonString())
            append(",\"wallTimeMs\":").append(wallTimeMs)
            append(",\"elapsedMs\":").append(elapsedMs)
            append(",\"stage\":").append(stage.jsonString())
            append(",\"event\":").append(event.jsonString())
            append(",\"code\":").append(code?.jsonString() ?: "null")
            append(",\"fallback\":").append(fallback?.jsonString() ?: "null")
            append(",\"mapVersion\":").append(mapVersion ?: "null")
            append('}')
        }
    }

    private val events = ArrayDeque<Event>()

    @Synchronized
    fun record(
        stage: String,
        event: String,
        code: String? = null,
        fallback: String? = null,
        mapVersion: Long? = null,
    ): Event {
        val entry = Event(sessionId, wallClockMs(), elapsedMs(), stage, event, code, fallback, mapVersion)
        if (events.size == capacity) events.removeFirst()
        events.addLast(entry)
        return entry
    }

    @Synchronized
    fun snapshot(): List<Event> = events.toList()

    fun jsonLines(): String = snapshot().joinToString(separator = "\n", postfix = "\n") { it.toJsonLine() }
}

private fun String.jsonString(): String = buildString {
    append('"')
    for (character in this@jsonString) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
    }
    append('"')
}
