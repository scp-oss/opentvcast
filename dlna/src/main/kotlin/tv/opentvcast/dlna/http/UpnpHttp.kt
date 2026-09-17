/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.http

import tv.opentvcast.dlna.ssdp.xmlEscape

/** One HTTP request, already parsed off the socket. */
data class UpnpHttpRequest(
    val method: String,
    val path: String,
    /** Header names are upper-cased for lookup, because senders disagree. */
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.uppercase()]
}

/** One HTTP response, ready to be written back to the socket. */
data class UpnpHttpResponse(
    val statusCode: Int,
    val statusText: String,
    val contentType: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = EMPTY_BODY,
) {
    /** The full response, headers and all. */
    fun encode(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            for ((name, value) in headers) append("$name: $value\r\n")
            append("\r\n")
        }
        return head.toByteArray() + body
    }

    companion object {
        val EMPTY_BODY = ByteArray(0)
    }
}

/** UPnP GENA (eventing) subscriptions for one renderer (FR-35). */
class GenaSubscriptionManager(
    /** Supplies subscription IDs; injected so tests get deterministic SIDs. */
    private val idSource: () -> String = { "uuid:${java.util.UUID.randomUUID()}" },
    /** Supplies "now"; injected so expiry is testable without sleeping. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** One live subscription to a service's event URL. */
    data class Subscription(
        val sid: String,
        val serviceId: String,
        val callbackUrl: String,
        val timeoutSeconds: Int,
        /** Expiry in the same millis domain as [clock]. */
        val expiresAtMs: Long,
    )

    private val subscriptions = LinkedHashMap<String, Subscription>()

    /**
     * `Second-N`, or the sentinel [INFINITE] for a subscription that never
     * expires.
     */
    fun subscribe(serviceId: String, callbackUrl: String, timeoutSeconds: Int): String {
        val sid = idSource()
        subscriptions[sid] = Subscription(
            sid = sid,
            serviceId = serviceId,
            callbackUrl = callbackUrl,
            timeoutSeconds = timeoutSeconds,
            expiresAtMs = if (timeoutSeconds == INFINITE) Long.MAX_VALUE else clock() + timeoutSeconds * 1000L,
        )
        return sid
    }

    /** Renews a subscription in place; `false` when the SID is unknown. */
    fun renew(sid: String, timeoutSeconds: Int): Boolean {
        val existing = subscriptions[sid] ?: return false
        subscriptions[sid] = existing.copy(
            timeoutSeconds = timeoutSeconds,
            expiresAtMs = if (timeoutSeconds == INFINITE) Long.MAX_VALUE else clock() + timeoutSeconds * 1000L,
        )
        return true
    }

    fun unsubscribe(sid: String): Boolean = subscriptions.remove(sid) != null

    fun subscribers(serviceId: String): List<Subscription> =
        subscriptions.values.filter { it.serviceId == serviceId }

    /**
     * Drops subscriptions past their expiry and returns the SIDs that went, so
     * the caller can log or stop notifying them.
     */
    fun expire(nowMs: Long): List<String> {
        val expired = subscriptions.values.filter { it.expiresAtMs <= nowMs }.map { it.sid }
        expired.forEach { subscriptions.remove(it) }
        return expired
    }

    companion object {
        /** `INFINITE` — UPnP's "never expire" timeout. */
        const val INFINITE = -1

        /** Parses `Second-1800` / `Second-infinite` into seconds (or [INFINITE]). */
        fun parseTimeout(value: String?): Int {
            if (value.isNullOrBlank()) return DEFAULT_TIMEOUT_SECONDS
            val body = value.trim().removePrefix("Second-").trim()
            if (body.equals("infinite", ignoreCase = true)) return INFINITE
            return body.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_TIMEOUT_SECONDS
        }

        /** UPnP default when a control point omits TIMEOUT. */
        const val DEFAULT_TIMEOUT_SECONDS = 1800

        /** Parses a GENA `CALLBACK` header value, `<http://host:port/path>`. */
        fun parseCallback(value: String?): String? =
            value?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Builds the `NOTIFY` body for a state change: a property set carrying
         * `LastChange`, which is the only variable AVTransport and
         * RenderingControl actually event on.
         */
        fun notifyBody(serviceId: String, variables: Map<String, String>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
            append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\r\n")
            append("  <e:property>\r\n")
            append("    <LastChange>\r\n")
            append("      <Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/$serviceId\">\r\n")
            append("        <InstanceID val=\"0\">\r\n")
            for ((name, value) in variables) {
                append("          <$name val=\"${xmlEscape(value)}\"/>\r\n")
            }
            append("        </InstanceID>\r\n")
            append("      </Event>\r\n")
            append("    </LastChange>\r\n")
            append("  </e:property>\r\n")
            append("</e:propertyset>\r\n")
        }
    }
}

/**
 * Parses a request off the wire.
 *
 * Two rules that exist because breaking them produces silence rather than an
 * error:
 *
 * - headers are matched by name, never by position, and case-insensitively;
 * - the body is taken by `Content-Length` and a short read is reported as
 *   *incomplete* (`null`) instead of being dispatched — a SOAP envelope with
 *   DIDL metadata regularly arrives in more than one packet.
 */
object UpnpHttpParser {


    fun parse(raw: ByteArray): UpnpHttpRequest? {
        if (raw.isEmpty()) return null
        val headEnd = indexOfHeaderEnd(raw) ?: return null
        val head = String(raw, 0, headEnd, Charsets.UTF_8)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ", "	")?.filter { it.isNotBlank() } ?: return null
        if (requestLine.size < 2) return null

        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim().trim('"')
        }

        val bodyStart = headEnd + 4
        val declared = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
        if (declared > 0 && raw.size - bodyStart < declared) return null
        val body = if (declared > 0) String(raw, bodyStart, declared, Charsets.UTF_8) else ""

        return UpnpHttpRequest(method, path, headers, body)
    }

    /**
     * Reads `Content-Length` out of a header block **before** the body has
     * arrived.
     *
     * The reader needs this: it must know how many bytes to pull off the socket,
     * and asking [parse] to find out would fail — a header-only buffer is by
     * definition an incomplete request. (That mistake made the server drop every
     * POST without answering, which a pure unit test of [parse] could never
     * have caught: only a real socket test saw the silent close.)
     */
    fun contentLength(headerBytes: ByteArray): Int {
        val text = String(headerBytes, Charsets.UTF_8)
        for (line in text.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                return line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /** Index of the CRLFCRLF that ends the header block, or `null` if absent. */
    private fun indexOfHeaderEnd(raw: ByteArray): Int? {
        for (i in 0..raw.size - 4) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return null
    }
}
