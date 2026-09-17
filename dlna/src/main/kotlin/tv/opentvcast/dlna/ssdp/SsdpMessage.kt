/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.ssdp

/**
 * One SSDP message: an `M-SEARCH` request, a `NOTIFY`, or a `200 OK` reply.
 *
 * SSDP borrows HTTP/1.1's message grammar but runs over UDP, which changes the
 * rules: there is no connection, every datagram is complete on its own, and a
 * malformed one is simply dropped by the peer. That is why the parser is
 * lenient about a missing trailing blank line — senders in the wild omit it —
 * while still refusing to turn garbage into a plausible message, because a
 * half-parsed search is worse than a dropped one.
 *
 * Header lookup is case-insensitive because control points spell `st`, `ST` and
 * `St` interchangeably.
 */
class SsdpMessage private constructor(
    val startLine: StartLine,
    private val headers: Map<String, List<String>>,
) {

    /** First line of an SSDP message — a request line or a status line. */
    sealed interface StartLine {
        /** `M-SEARCH * HTTP/1.1` / `NOTIFY * HTTP/1.1`. */
        class Request(val method: String) : StartLine

        /** `HTTP/1.1 200 OK`. */
        class Status(val statusCode: Int) : StartLine
    }

    val isSearch: Boolean get() = method == METHOD_SEARCH
    val isNotify: Boolean get() = method == METHOD_NOTIFY

    /** Request method, or `null` when the message is a response. */
    val method: String? get() = (startLine as? StartLine.Request)?.method

    /** Response status code, or `null` when the message is a request. */
    val statusCode: Int? get() = (startLine as? StartLine.Status)?.statusCode

    /** `MAN`, with the surrounding quotes SSDP requires stripped. */
    val man: String? get() = header(MAN)?.trim('"')

    /** `MX`, the sender's requested reply window in seconds. Defaults to 0. */
    val mx: Int get() = header(MX)?.trim()?.toIntOrNull() ?: 0

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** Serialises back to the wire form, always ending in CRLFCRLF. */
    fun encode(): String = buildString {
        when (startLine) {
            is StartLine.Request -> append("${startLine.method} * $HTTP_VERSION\r\n")
            is StartLine.Status -> append("$HTTP_VERSION ${startLine.statusCode} ${statusText(startLine.statusCode)}\r\n")
        }
        for ((name, values) in headers) {
            for (v in values) append("$name: $v\r\n")
        }
        append("\r\n")
    }

    companion object {
        const val HTTP_VERSION = "HTTP/1.1"
        const val METHOD_SEARCH = "M-SEARCH"
        const val METHOD_NOTIFY = "NOTIFY"

        const val HOST = "HOST"
        const val MAN = "MAN"
        const val MX = "MX"
        const val ST = "ST"
        const val NT = "NT"
        const val NTS = "NTS"
        const val USN = "USN"
        const val LOCATION = "LOCATION"
        const val CACHE_CONTROL = "CACHE-CONTROL"
        const val SERVER = "SERVER"
        const val EXT = "EXT"

        const val MULTICAST_GROUP = "239.255.255.250"
        const val MULTICAST_PORT = 1900
        val MULTICAST_HOST = "$MULTICAST_GROUP:$MULTICAST_PORT"

        /**
         * Parses one datagram. Returns `null` for anything that is not an SSDP
         * message rather than guessing — the caller drops it.
         */
        fun parse(raw: String): SsdpMessage? {
            val text = raw.trimStart()
            if (text.isBlank()) return null
            val lines = text.split("\r\n", "\n")
            val start = parseStartLine(lines.firstOrNull() ?: return null) ?: return null

            val headers = linkedMapOf<String, MutableList<String>>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue                       // not a header — skip, do not fail
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                headers.getOrPut(name.lowercase()) { mutableListOf() }.add(value)
            }
            return SsdpMessage(start, headers)
        }

        private fun parseStartLine(line: String): StartLine? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return null
            return when {
                parts[0].equals(METHOD_SEARCH, ignoreCase = true) -> StartLine.Request(METHOD_SEARCH)
                parts[0].equals(METHOD_NOTIFY, ignoreCase = true) -> StartLine.Request(METHOD_NOTIFY)
                parts[0].equals(HTTP_VERSION, ignoreCase = true) && parts.size >= 2 ->
                    parts[1].toIntOrNull()?.let { StartLine.Status(it) }
                else -> null
            }
        }

        private fun statusText(code: Int) = when (code) {
            200 -> "OK"
            else -> "Unknown"
        }

        /**
         * Builds the unicast reply to an `M-SEARCH`.
         *
         * @param location the description URL, built from the port we were
         *   actually granted — never the port we hoped for (FR-20 / FR-33).
         */
        fun searchResponse(
            location: String,
            searchTarget: String,
            usn: String,
            maxAgeSeconds: Int,
            server: String,
        ): String {
            val headers = linkedMapOf(
                CACHE_CONTROL to "max-age=$maxAgeSeconds",
                EXT to "",
                LOCATION to location,
                SERVER to server,
                ST to searchTarget,
                USN to usn,
            )
            return SsdpMessage(StartLine.Status(200), headers.mapValues { (_, v) -> listOf(v) }).encode()
        }

        /** Builds an `ssdp:alive` or `ssdp:byebye` notification. */
        fun notify(
            notificationType: String,
            usn: String,
            location: String,
            alive: Boolean,
            maxAgeSeconds: Int,
            server: String,
        ): String {
            val headers = linkedMapOf(
                HOST to MULTICAST_HOST,
                CACHE_CONTROL to "max-age=$maxAgeSeconds",
                LOCATION to location,
                NT to notificationType,
                NTS to if (alive) "ssdp:alive" else "ssdp:byebye",
                SERVER to server,
                USN to usn,
            )
            return SsdpMessage(StartLine.Request(METHOD_NOTIFY), headers.mapValues { (_, v) -> listOf(v) }).encode()
        }
    }
}
