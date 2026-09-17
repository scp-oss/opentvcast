package tv.opentvcast.dlna.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests for the UPnP documents a control point fetches from us: the device
 * description and each service's SCPD.
 *
 * WHY: a control point that cannot parse one of these documents drops the
 * renderer with no diagnostics. Two properties matter more than the rest:
 *
 * - **The URLs must be built from the port we were actually granted** (FR-20 /
 *   FR-33). A description that advertises 8200 while serving on 49153 is
 *   invisible.
 * - **The SCPD must declare every action the control point is about to call**,
 *   including `LastChange` for GENA eventing (FR-35).
 */
class DeviceDescriptionTest {

    private val udn = "2f402f80-da50-11e1-9b23-0017882a4a01"
    // Deliberately not the "default" DLNA port: the granted port is whatever
    // the allocator returned, and the documents must follow it.
    private val baseUrl = "http://192.168.1.7:49153"

    private fun description(name: String = "Living Room TV") =
        DeviceDescription.build(
            udn = udn,
            friendlyName = name,
            manufacturer = "opentvcast",
            modelName = "opentvcast TV",
            baseUrl = baseUrl,
        )

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(xml.byteInputStream())

    // ─── device description ──────────────────────────────────────────────────

    @Test
    fun `the description is well-formed XML`() {
        parse(description())
    }

    @Test
    fun `the description identifies the device`() {
        val xml = description()

        assertTrue(xml.contains("<UDN>uuid:$udn</UDN>"))
        assertTrue(xml.contains("<friendlyName>Living Room TV</friendlyName>"))
        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
    }

    @Test
    fun `a friendly name is XML-escaped`() {
        // A TV called "Bed & Bath <2>" must not produce broken XML — one
        // unescaped ampersand and the whole document is rejected.
        val xml = description("Bed & Bath <2> \"TV\"")

        assertTrue(xml.contains("Bed &amp; Bath &lt;2&gt;"))
        parse(xml)
    }

    @Test
    fun `every service URL follows the granted port, not a default`() {
        val xml = description()

        for (service in DlnaServices.all) {
            assertTrue("$baseUrl${service.scpdPath} missing", xml.contains("$baseUrl${service.scpdPath}"))
            assertTrue("$baseUrl${service.controlPath} missing", xml.contains("$baseUrl${service.controlPath}"))
            assertTrue("$baseUrl${service.eventPath} missing", xml.contains("$baseUrl${service.eventPath}"))
        }
    }

    @Test
    fun `all three required services are declared`() {
        val xml = description()

        assertTrue(xml.contains("urn:schemas-upnp-org:service:AVTransport:1"))
        assertTrue(xml.contains("urn:schemas-upnp-org:service:RenderingControl:1"))
        assertTrue(xml.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
    }

    @Test
    fun `the description URL is a stable path a control point can fetch`() {
        assertEquals("$baseUrl${DeviceDescription.DESCRIPTION_PATH}", DeviceDescription.locationFor(baseUrl))
    }

    // ─── SCPD ────────────────────────────────────────────────────────────────

    @Test
    fun `each SCPD is well-formed and declares its actions`() {
        for (service in DlnaServices.all) {
            val xml = ServiceControlDescription.build(service)

            parse(xml)
            for (action in service.actions) {
                assertTrue("${service.serviceType} missing action ${action.name}", xml.contains("<name>${action.name}</name>"))
            }
        }
    }

    @Test
    fun `AVTransport declares the playback actions a control point calls`() {
        val xml = ServiceControlDescription.build(DlnaServices.avTransport)

        for (action in listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek",
                "GetPositionInfo", "GetTransportInfo", "GetMediaInfo", "GetCurrentTransportActions")) {
            assertTrue("$action missing", xml.contains("<name>$action</name>"))
        }
    }

    @Test
    fun `RenderingControl declares volume and mute`() {
        val xml = ServiceControlDescription.build(DlnaServices.renderingControl)

        for (action in listOf("SetVolume", "GetVolume", "SetMute", "GetMute")) {
            assertTrue("$action missing", xml.contains("<name>$action</name>"))
        }
    }

    @Test
    fun `ConnectionManager answers protocol info queries`() {
        val xml = ServiceControlDescription.build(DlnaServices.connectionManager)

        for (action in listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo")) {
            assertTrue("$action missing", xml.contains("<name>$action</name>"))
        }
    }

    @Test
    fun `evented services declare the LastChange variable GENA will send`() {
        // FR-35: without LastChange there is nothing to put in a NOTIFY body,
        // so transport and volume changes never reach the control point.
        for (service in listOf(DlnaServices.avTransport, DlnaServices.renderingControl)) {
            assertTrue(
                "${service.serviceType} must declare LastChange",
                ServiceControlDescription.build(service).contains("<name>LastChange</name>"),
            )
        }
    }

    @Test
    fun `every argument names a state variable that the SCPD declares`() {
        // A control point validates this; an undeclared variable is a hard
        // error, not a warning.
        for (service in DlnaServices.all) {
            val xml = ServiceControlDescription.build(service)
            for (action in service.actions) {
                for (arg in action.arguments) {
                    assertTrue(
                        "${action.name}.${arg.name}: ${arg.relatedStateVariable} undeclared",
                        xml.contains("<name>${arg.relatedStateVariable}</name>"),
                    )
                }
            }
        }
    }
}
