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

/** Escapes the five XML characters that would otherwise break a document. */
internal fun xmlEscape(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

/**
 * Builds the UPnP device description a control point fetches from `LOCATION`.
 *
 * The one thing that must not go wrong: **every URL is derived from the port we
 * were actually granted** (FR-20 / FR-33). A renderer that advertises the port
 * it *wanted* while serving on the one it *got* is invisible, and nothing logs
 * the mismatch.
 */
object DeviceDescription {

    /** Stable path; [locationFor] is the only place that composes the URL. */
    const val DESCRIPTION_PATH = "/description.xml"

    fun locationFor(baseUrl: String): String = baseUrl.trimEnd('/') + DESCRIPTION_PATH

    fun build(
        udn: String,
        friendlyName: String,
        manufacturer: String,
        modelName: String,
        baseUrl: String,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\r\n")
        append("  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n")
        append("  <device>\r\n")
        append("    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\r\n")
        append("    <friendlyName>${xmlEscape(friendlyName)}</friendlyName>\r\n")
        append("    <manufacturer>${xmlEscape(manufacturer)}</manufacturer>\r\n")
        append("    <modelName>${xmlEscape(modelName)}</modelName>\r\n")
        append("    <UDN>uuid:${xmlEscape(udn)}</UDN>\r\n")
        append("    <serviceList>\r\n")
        for (service in DlnaServices.all) {
            append("      <service>\r\n")
            append("        <serviceType>${service.serviceType}</serviceType>\r\n")
            append("        <serviceId>${service.serviceId}</serviceId>\r\n")
            append("        <SCPDURL>${baseUrl.trimEnd('/')}${service.scpdPath}</SCPDURL>\r\n")
            append("        <controlURL>${baseUrl.trimEnd('/')}${service.controlPath}</controlURL>\r\n")
            append("        <eventSubURL>${baseUrl.trimEnd('/')}${service.eventPath}</eventSubURL>\r\n")
            append("      </service>\r\n")
        }
        append("    </serviceList>\r\n")
        append("  </device>\r\n")
        append("</root>\r\n")
    }
}

/**
 * Builds a service's SCPD (Service Control Protocol Description) document.
 *
 * A control point reads this before it calls anything: an action that is not
 * declared here is never invoked, and an argument naming an undeclared state
 * variable is a hard error. `LastChange` is declared on the evented services
 * because GENA has nothing to send without it (FR-35).
 */
object ServiceControlDescription {

    fun build(service: DlnaService): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\r\n")
        append("  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n")

        append("  <actionList>\r\n")
        for (action in service.actions) {
            append("    <action>\r\n")
            append("      <name>${action.name}</name>\r\n")
            if (action.arguments.isNotEmpty()) {
                append("      <argumentList>\r\n")
                for (arg in action.arguments) {
                    append("        <argument>\r\n")
                    append("          <name>${arg.name}</name>\r\n")
                    append("          <direction>${arg.direction.wire}</direction>\r\n")
                    append("          <relatedStateVariable>${arg.relatedStateVariable}</relatedStateVariable>\r\n")
                    append("        </argument>\r\n")
                }
                append("      </argumentList>\r\n")
            }
            append("    </action>\r\n")
        }
        append("  </actionList>\r\n")

        append("  <serviceStateTable>\r\n")
        for (variable in service.stateVariables) {
            val events = if (variable.sendsEvents) " sendEvents=\"yes\"" else ""
            append("    <stateVariable$events>\r\n")
            append("      <name>${variable.name}</name>\r\n")
            append("      <dataType>${variable.dataType}</dataType>\r\n")
            append("    </stateVariable>\r\n")
        }
        append("  </serviceStateTable>\r\n")

        append("</scpd>\r\n")
    }
}
