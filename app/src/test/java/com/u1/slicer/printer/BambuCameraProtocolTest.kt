package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuCameraProtocolTest {

    @Test
    fun `buildAuthPacket encodes chamber image auth payload`() {
        val packet = BambuCameraProtocol.buildAuthPacket("bblp", "12345678")

        assertEquals(80, packet.size)
        assertEquals(0x40, BambuCameraProtocol.readLittleEndianInt(packet, 0))
        assertEquals(0x3000, BambuCameraProtocol.readLittleEndianInt(packet, 4))
        assertArrayEquals("bblp".encodeToByteArray(), packet.copyOfRange(16, 20))
        assertArrayEquals("12345678".encodeToByteArray(), packet.copyOfRange(48, 56))
    }

    @Test
    fun `readFramePayloadSize uses little endian header`() {
        val header = byteArrayOf(
            0x2A, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        assertEquals(42, BambuCameraProtocol.readFramePayloadSize(header))
    }

    @Test
    fun `h2d uses authenticated tls backed rtsp while a1 and p2s stay unsupported`() {
        val client = DefaultBambuCameraClient()
        val h2d = BambuConfig(
            ip = "192.168.1.50",
            accessCode = "12345678",
            serial = "H2D123",
            model = BambuModel.H2D,
        )
        val a1 = h2d.copy(model = BambuModel.A1)
        val p2s = h2d.copy(model = BambuModel.P2S)

        assertTrue(client.supports(BambuModel.H2D))
        assertEquals(
            "rtsps://bblp:12345678@192.168.1.50:322/streaming/live/1",
            client.rtspUri(h2d),
        )
        assertNull(client.rtspUri(a1))
        assertTrue(!client.supports(BambuModel.P2S))
        assertNull(client.rtspUri(p2s))
    }
}
