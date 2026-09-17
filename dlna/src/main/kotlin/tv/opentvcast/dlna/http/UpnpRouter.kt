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

import tv.opentvcast.dlna.soap.DlnaControlDispatcher
import tv.opentvcast.dlna.soap.SoapRequest
import tv.opentvcast.dlna.soap.SoapResponse
import tv.opentvcast.dlna.ssdp.DeviceDescription
import tv.opentvcast.dlna.ssdp.DlnaServices
import tv.opentvcast.dlna.ssdp.ServiceControlDescription

/**
 * Routes the HTTP requests a UPnP control point makes: the description
 * document, the SCPDs, SOAP control calls, and GENA subscribe/unsubscribe.
 *
 * A pure function of (request, renderer state) — no sockets — for the same
 * reason the transport state machine is: the status codes *are* the protocol
 * here, and they have to be testable. UPnP puts SOAP faults on HTTP 500, so
 * answering 200 to a rejected action makes control points believe it worked.
 *
 * @param baseUrl scheme + host + the port actually granted; every URL we emit
 *   is derived from it (FR-20 / FR-33).
 */
class UpnpRouter(
    private val udn: String,
    private val friendlyName: String,
    private val baseUrl: String,
    private val dispatcher: DlnaControlDispatcher,
    private val subscriptions: GenaSubscriptionManager,
) {

    fun handle(request: UpnpHttpRequest): UpnpHttpResponse = when {
        request.method == "GET" -> handleGet(request)
        request.method == "POST" -> handleControl(request)
        request.method == "SUBSCRIBE" -> handleSubscribe(request)
        request.method == "UNSUBSCRIBE" -> handleUnsubscribe(request)
        else -> notFound()
    }

    // ─── documents ───────────────────────────────────────────────────────────

    private fun handleGet(request: UpnpHttpRequest): UpnpHttpResponse {
        if (request.path == DeviceDescription.DESCRIPTION_PATH) {
            return xml(
                DeviceDescription.build(
                    udn = udn,
                    friendlyName = friendlyName,
                    manufacturer = MANUFACTURER,
                    modelName = MODEL_NAME,
                    baseUrl = baseUrl,
                ),
            )
        }
        val service = DlnaServices.byScpdPath(request.path) ?: return notFound()
        return xml(ServiceControlDescription.build(service))
    }

    // ─── SOAP control ────────────────────────────────────────────────────────

    private fun handleControl(request: UpnpHttpRequest): UpnpHttpResponse {
        val service = DlnaServices.byControlPath(request.path) ?: return notFound()
        val soapAction = request.header("SOAPACTION")
            ?: return UpnpHttpResponse(400, "Bad Request", "text/plain", body = "missing SOAPACTION".toByteArray())

        val (serviceType, action) = parseSoapAction(soapAction)
            ?: return UpnpHttpResponse(400, "Bad Request", "text/plain", body = "malformed SOAPACTION".toByteArray())

        // The header's service type and the control path must agree. Silently
        // dispatching on the path alone would let a stale or wrong SOAPACTION
        // invoke an action the caller never intended.
        if (serviceType != service.serviceType) {
            return UpnpHttpResponse(400, "Bad Request", "text/plain", body = "SOAPACTION service mismatch".toByteArray())
        }

        val soapRequest = SoapRequest.parse(request.body)
            ?: return UpnpHttpResponse(400, "Bad Request", "text/plain", body = "unparseable SOAP body".toByteArray())

        // SOAPACTION is authoritative: UPnP requires it and it is what the
        // control point believes it invoked. The body's element name is only a
        // fallback for senders that leave the header's action empty.
        val actionName = action.ifBlank { soapRequest.action }
        val reply = dispatcher.dispatch(service, actionName, soapRequest.arguments)

        return if (reply.contains("<s:Fault>")) {
            UpnpHttpResponse(500, "Internal Server Error", "text/xml", body = reply.toByteArray())
        } else {
            xml(reply)
        }
    }

    /** `"urn:...:AVTransport:1#Play"` → (service type, action). */
    private fun parseSoapAction(value: String): Pair<String, String>? {
        val cleaned = value.trim().trim('"')
        val hash = cleaned.indexOf('#')
        if (hash <= 0 || hash == cleaned.length - 1) return null
        return cleaned.substring(0, hash) to cleaned.substring(hash + 1)
    }

    // ─── GENA ────────────────────────────────────────────────────────────────

    private fun handleSubscribe(request: UpnpHttpRequest): UpnpHttpResponse {
        val service = DlnaServices.all.firstOrNull { it.eventPath == request.path } ?: return notFound()
        val callback = GenaSubscriptionManager.parseCallback(request.header("CALLBACK"))
            ?: return UpnpHttpResponse(400, "Bad Request", "text/plain", body = "missing CALLBACK".toByteArray())

        val timeout = GenaSubscriptionManager.parseTimeout(request.header("TIMEOUT"))
        val sid = subscriptions.subscribe(service.serviceId, callback, timeout)

        return UpnpHttpResponse(
            statusCode = 200,
            statusText = "OK",
            contentType = "text/xml",
            headers = mapOf(
                "SID" to sid,
                "TIMEOUT" to if (timeout == GenaSubscriptionManager.INFINITE) "Second-infinite" else "Second-$timeout",
            ),
        )
    }

    private fun handleUnsubscribe(request: UpnpHttpRequest): UpnpHttpResponse {
        val sid = request.header("SID")
            ?: return UpnpHttpResponse(412, "Precondition Failed", "text/plain", body = "missing SID".toByteArray())
        return if (subscriptions.unsubscribe(sid)) {
            UpnpHttpResponse(200, "OK", "text/xml")
        } else {
            // Unknown SID: UPnP says 412, not 404 — the subscription is gone,
            // which is what the caller wanted anyway.
            UpnpHttpResponse(412, "Precondition Failed", "text/plain", body = "unknown SID".toByteArray())
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun xml(body: String) = UpnpHttpResponse(200, "OK", "text/xml; charset=\"utf-8\"", body = body.toByteArray())

    private fun notFound() = UpnpHttpResponse(404, "Not Found", "text/plain", body = "not found".toByteArray())

    companion object {
        const val MANUFACTURER = "opentvcast"
        const val MODEL_NAME = "opentvcast TV"
    }
}
