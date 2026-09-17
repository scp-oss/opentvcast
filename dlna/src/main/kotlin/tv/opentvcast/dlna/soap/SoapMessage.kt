/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.soap

import tv.opentvcast.dlna.ssdp.xmlEscape
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One SOAP control request: the action a control point invoked, and its
 * arguments.
 *
 * Parsing is deliberately permissive about *shape* — control points disagree on
 * namespace prefixes, omit the XML declaration, and send empty arguments — and
 * strict about *meaning*: a body with no action element is rejected rather than
 * guessed at, because acting on a half-read request can stop playback the user
 * just started.
 */
class SoapRequest(
    val action: String,
    val serviceType: String,
    /** Every argument the caller sent; empty strings are real values (DIDL metadata often is). */
    val arguments: Map<String, String>,
) {
    /** Convenience lookup; `null` means the argument was absent. */
    fun argument(name: String): String? = arguments[name]

    companion object {
        private val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

        /** Parses an envelope, or returns `null` if there is no action to invoke. */
        fun parse(raw: String): SoapRequest? {
            if (raw.isBlank()) return null
            val body = try {
                val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(raw.toByteArray()))
                document.getElementsByTagNameNS(SOAP_NS, "Body").item(0)
            } catch (e: Exception) {
                return null
            } ?: return null

            val actionElement = body.childNodes.let { nodes ->
                (0 until nodes.length).map { nodes.item(it) }
                    .firstOrNull { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            } ?: return null

            val action = actionElement.localName ?: return null
            val serviceType = actionElement.namespaceURI ?: return null

            val args = linkedMapOf<String, String>()
            val children = actionElement.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                args[node.localName ?: continue] = node.textContent ?: ""
            }
            return SoapRequest(action, serviceType, args)
        }

        const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    }
}

/**
 * Builds SOAP replies: either an `<ActionNameResponse>` carrying the out
 * arguments, or a UPnP fault.
 *
 * A fault must still be a well-formed envelope — a control point that cannot
 * parse the error has nothing to show the user, which is how "the TV
 * disappeared" gets reported for what was really a rejected transition.
 */
object SoapResponse {

    /** No such action on this service. */
    const val ERROR_INVALID_ACTION = 401

    /** An argument was missing or unparseable. */
    const val ERROR_INVALID_ARGS = 402

    /** The action is valid but failed. */
    const val ERROR_ACTION_FAILED = 501

    /** The transport is not in a state that allows this transition. */
    const val ERROR_TRANSITION_NOT_AVAILABLE = 701

    fun success(serviceType: String, action: String, arguments: Map<String, String> = emptyMap()): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
            append("<s:Envelope xmlns:s=\"${SoapRequest.SOAP_NS}\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n")
            append("  <s:Body>\r\n")
            append("    <u:${action}Response xmlns:u=\"$serviceType\">\r\n")
            for ((name, value) in arguments) {
                append("      <$name>${xmlEscape(value)}</$name>\r\n")
            }
            append("    </u:${action}Response>\r\n")
            append("  </s:Body>\r\n")
            append("</s:Envelope>")
        }

    fun fault(code: Int, description: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        append("<s:Envelope xmlns:s=\"${SoapRequest.SOAP_NS}\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n")
        append("  <s:Body>\r\n")
        append("    <s:Fault>\r\n")
        append("      <faultcode>s:Client</faultcode>\r\n")
        append("      <faultstring>UPnPError</faultstring>\r\n")
        append("      <detail>\r\n")
        append("        <UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">\r\n")
        append("          <errorCode>$code</errorCode>\r\n")
        append("          <errorDescription>${xmlEscape(description)}</errorDescription>\r\n")
        append("        </UPnPError>\r\n")
        append("      </detail>\r\n")
        append("    </s:Fault>\r\n")
        append("  </s:Body>\r\n")
        append("</s:Envelope>")
    }
}
