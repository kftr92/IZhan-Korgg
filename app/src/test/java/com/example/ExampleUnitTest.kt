package com.example

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testBleMidiHeaderStripping() {
        // Raw BLE MIDI: 8D AA 90 33 5A
        val raw = byteArrayOf(0x8D.toByte(), 0xAA.toByte(), 0x90.toByte(), 0x33.toByte(), 0x5A.toByte())
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF

        val isBleHeader = ((b0 and 0xC0) == 0x80 && (b1 and 0x80) != 0)
        assertTrue(isBleHeader)

        var i = if (isBleHeader) 2 else 0
        val status = raw[i].toInt() and 0xFF
        val note = raw[i + 1].toInt() and 0x7F
        val vel = raw[i + 2].toInt() and 0x7F

        assertEquals(0x90, status)
        assertEquals(51, note)
        assertEquals(90, vel)
    }

    @Test
    fun testEsp32SysExSlotSelectionDecoding() {
        // Raw BLE MIDI: 8D B7 F0 7D 05 0C F7
        val raw = byteArrayOf(
            0x8D.toByte(), 0xB7.toByte(),
            0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x0C.toByte(), 0xF7.toByte()
        )

        var inSysex = false
        val accumulator = ByteArrayOutputStream()
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        var i = if ((b0 and 0xC0) == 0x80 && (b1 and 0x80) != 0) 2 else 0

        var parsedSlot: Int? = null

        while (i < raw.size) {
            val b = raw[i].toInt() and 0xFF
            if (inSysex) {
                if (b == 0xF7) {
                    accumulator.write(0xF7)
                    inSysex = false
                    val sysexBytes = accumulator.toByteArray()
                    if (sysexBytes.size >= 5 && (sysexBytes[0].toInt() and 0xFF) == 0xF0 && (sysexBytes[1].toInt() and 0xFF) == 0x7D) {
                        val cmd = sysexBytes[2].toInt() and 0xFF
                        if (cmd == 0x05) {
                            parsedSlot = sysexBytes[3].toInt() and 0x7F
                        }
                    }
                } else if (b < 0x80) {
                    accumulator.write(b)
                }
            } else if (b == 0xF0) {
                inSysex = true
                accumulator.reset()
                accumulator.write(b)
            }
            i++
        }

        assertNotNull(parsedSlot)
        assertEquals(12, parsedSlot) // Index 12 corresponds to Slot 13
        assertEquals(13, parsedSlot!! + 1)
    }

    @Test
    fun testFragmentedSysExDecoding() {
        // Packet 1: 8D B7 F0 7D 05
        // Packet 2: 8D C2 0C F7
        val p1 = byteArrayOf(0x8D.toByte(), 0xB7.toByte(), 0xF0.toByte(), 0x7D.toByte(), 0x05.toByte())
        val p2 = byteArrayOf(0x8D.toByte(), 0xC2.toByte(), 0x0C.toByte(), 0xF7.toByte())

        var inSysex = false
        val accumulator = ByteArrayOutputStream()
        var parsedSlot: Int? = null

        fun parsePacket(raw: ByteArray) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            var i = if (!inSysex && (b0 and 0xC0) == 0x80 && (b1 and 0x80) != 0) 2 else 0

            while (i < raw.size) {
                val b = raw[i].toInt() and 0xFF
                if (inSysex) {
                    if (b == 0xF7) {
                        accumulator.write(0xF7)
                        inSysex = false
                        val sysexBytes = accumulator.toByteArray()
                        if (sysexBytes.size >= 5 && (sysexBytes[0].toInt() and 0xFF) == 0xF0 && (sysexBytes[1].toInt() and 0xFF) == 0x7D) {
                            val cmd = sysexBytes[2].toInt() and 0xFF
                            if (cmd == 0x05) {
                                parsedSlot = sysexBytes[3].toInt() and 0x7F
                            }
                        }
                    } else if (b < 0x80) {
                        accumulator.write(b)
                    }
                } else if (b == 0xF0) {
                    inSysex = true
                    accumulator.reset()
                    accumulator.write(b)
                }
                i++
            }
        }

        parsePacket(p1)
        assertTrue(inSysex)
        parsePacket(p2)
        assertFalse(inSysex)

        assertNotNull(parsedSlot)
        assertEquals(12, parsedSlot)
        assertEquals(13, parsedSlot!! + 1)
    }
}
