package com.pradertamas.uicapturetest

import java.util.UUID

/** Local diagnostic correlation only: never a request ID, code, token or signaling authority. */
internal class ReceiverTrace(private val write: (String) -> Unit) {
    companion object {
        private val process = UUID.randomUUID().toString().take(8)
        fun exception(error: Throwable): String = "exception=${error.javaClass.simpleName} message=${safe(error.message.orEmpty())}"
        fun safe(value: String): String = value
            .replace(Regex("(?i)(?:https?|wss?)://\\S+"), "[url]")
            .replace(Regex("(?i)(session|code|secret|claim|token|capability|requestId)\\s*[=:]\\s*[^\\s,&]+"), "$1=[redacted]")
            .replace(Regex("[A-Za-z0-9_-]{16,}"), "[redacted]")
            .replace('\n', ' ').replace('\r', ' ').take(300)
    }
    @Volatile private var session = UUID.randomUUID().toString().take(8)
    fun begin() { session = UUID.randomUUID().toString().take(8); record("session-start") }
    fun record(event: String) = write("role=RECEIVE process=$process session=$session $event")
    fun snapshot(): (String) -> Unit {
        val exact = session
        return { event -> write("role=RECEIVE process=$process session=$exact $event") }
    }
}
