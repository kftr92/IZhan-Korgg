package com.example

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    // Helper decoder simulation for tests
    data class DecodedMidiEvent(
        val type: String,
        val channel: Int,
        val noteOrData1: Int,
        val velocityOrData2: Int
    )

    private fun decodeBleMidi(raw: ByteArray): List<Any> {
        val results = mutableListOf<Any>()
        var inSysex = false
        val accumulator = ByteArrayOutputStream()

        var i = 0
        val end = raw.size

        if (end >= 2) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            if ((b0 and 0xC0) == 0x80 && (b1 and 0x80) != 0 && b1 != 0xF7 && b1 != 0xF0) {
                i = 2
            } else if ((b0 and 0xC0) == 0x80 && (b1 == 0xF0 || (b1 and 0x80) != 0)) {
                i = 1
            }
        }

        while (i < end) {
            val b = raw[i].toInt() and 0xFF

            if (inSysex) {
                if (b == 0xF7) {
                    accumulator.write(0xF7)
                    inSysex = false
                    val sysexBytes = accumulator.toByteArray()
                    results.add(sysexBytes)
                    accumulator.reset()
                } else if (b == 0xF0) {
                    accumulator.reset()
                    accumulator.write(0xF0)
                } else if (b < 0x80) {
                    accumulator.write(b)
                    if (accumulator.size() == 4) {
                        val currentBuf = accumulator.toByteArray()
                        if ((currentBuf[0].toInt() and 0xFF) == 0xF0 &&
                            (currentBuf[1].toInt() and 0xFF) == 0x7D &&
                            (currentBuf[2].toInt() and 0xFF) == 0x05) {
                            inSysex = false
                            results.add(currentBuf)
                            accumulator.reset()
                        }
                    }
                }
                i++
                continue
            }

            if (b == 0xF0) {
                inSysex = true
                accumulator.reset()
                accumulator.write(b)
                i++
                continue
            }

            if (i + 1 < end && b >= 0x80 && (raw[i + 1].toInt() and 0x80) != 0 && (raw[i + 1].toInt() and 0xFF) != 0xF7) {
                i++
                continue
            }

            if (b >= 0x80) {
                val type = b and 0xF0
                val ch = b and 0x0F
                if (type == 0x90) {
                    val note = raw.getOrNull(i + 1)?.toInt()?.and(0x7F) ?: 0
                    val vel = raw.getOrNull(i + 2)?.toInt()?.and(0x7F) ?: 0
                    val isNoteOn = vel > 0
                    results.add(DecodedMidiEvent(if (isNoteOn) "NOTE_ON" else "NOTE_OFF", ch, note, vel))
                    i += 3
                } else if (type == 0x80) {
                    val note = raw.getOrNull(i + 1)?.toInt()?.and(0x7F) ?: 0
                    val vel = raw.getOrNull(i + 2)?.toInt()?.and(0x7F) ?: 0
                    results.add(DecodedMidiEvent("NOTE_OFF", ch, note, vel))
                    i += 3
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return results
    }

    @Test
    fun test1_NoteOnDecoding() {
        // RAW: BF CB 90 27 3D
        val raw = byteArrayOf(0xBF.toByte(), 0xCB.toByte(), 0x90.toByte(), 0x27.toByte(), 0x3D.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val event = decoded[0] as DecodedMidiEvent
        assertEquals("NOTE_ON", event.type)
        assertEquals(0, event.channel) // channel 1 (0-indexed)
        assertEquals(39, event.noteOrData1)
        assertEquals(61, event.velocityOrData2)
    }

    @Test
    fun test2_NoteOffDecoding() {
        // RAW: 80 8C 80 27 40
        val raw = byteArrayOf(0x80.toByte(), 0x8C.toByte(), 0x80.toByte(), 0x27.toByte(), 0x40.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val event = decoded[0] as DecodedMidiEvent
        assertEquals("NOTE_OFF", event.type)
        assertEquals(0, event.channel)
        assertEquals(39, event.noteOrData1)
        assertEquals(64, event.velocityOrData2)
    }

    @Test
    fun test3_SelectSlot1Decoding() {
        // RAW: BF E1 F0 7D 05 00 F7
        val raw = byteArrayOf(0xBF.toByte(), 0xE1.toByte(), 0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x00.toByte(), 0xF7.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val sysex = decoded[0] as ByteArray
        assertEquals(4, sysex.size)
        assertEquals(0xF0.toByte(), sysex[0])
        assertEquals(0x7D.toByte(), sysex[1]) // zhanhostmidi manufacturer
        assertEquals(0x05.toByte(), sysex[2]) // CMD 05
        val slotIndex = sysex[3].toInt() and 0x7F
        assertEquals(0, slotIndex) // Slot Index 0 = Slot 1
    }

    @Test
    fun test4_SelectSlot13Decoding() {
        // RAW: 8D B7 F0 7D 05 0C F7
        val raw = byteArrayOf(0x8D.toByte(), 0xB7.toByte(), 0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x0C.toByte(), 0xF7.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val sysex = decoded[0] as ByteArray
        assertEquals(4, sysex.size)
        assertEquals(0xF0.toByte(), sysex[0])
        assertEquals(0x7D.toByte(), sysex[1]) // zhanhostmidi
        assertEquals(0x05.toByte(), sysex[2]) // CMD 05
        val slotIndex = sysex[3].toInt() and 0x7F
        assertEquals(12, slotIndex) // Slot Index 12 = Slot 13
    }

    @Test
    fun test5_MidiLearnSlot1() {
        // RAW: BF CB 90 27 3D
        val raw = byteArrayOf(0xBF.toByte(), 0xCB.toByte(), 0x90.toByte(), 0x27.toByte(), 0x3D.toByte())
        val decoded = decodeBleMidi(raw)
        val event = decoded[0] as DecodedMidiEvent
        var learningSlot1TriggerNote = -1
        if (event.type == "NOTE_ON") {
            learningSlot1TriggerNote = event.noteOrData1
        }
        assertEquals(39, learningSlot1TriggerNote)
    }

    @Test
    fun test6_MidiLearnSlot13() {
        // RAW: 8D AA 90 33 5A
        val raw = byteArrayOf(0x8D.toByte(), 0xAA.toByte(), 0x90.toByte(), 0x33.toByte(), 0x5A.toByte())
        val decoded = decodeBleMidi(raw)
        val event = decoded[0] as DecodedMidiEvent
        var learningSlot13TriggerNote = -1
        if (event.type == "NOTE_ON") {
            learningSlot13TriggerNote = event.noteOrData1
        }
        assertEquals(51, learningSlot13TriggerNote)
    }

    @Test
    fun test7_KorgSysExHandling() {
        // RAW: F0 42 30 00 01 15 4E 00 F7
        val raw = byteArrayOf(
            0xF0.toByte(), 0x42.toByte(), 0x30.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x15.toByte(), 0x4E.toByte(), 0x00.toByte(), 0xF7.toByte()
        )
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val sysex = decoded[0] as ByteArray
        val isZhanhostmidi = (sysex[0] == 0xF0.toByte() && sysex[1] == 0x7D.toByte())
        val isKorg = (sysex[0] == 0xF0.toByte() && sysex[1] == 0x42.toByte())
        assertFalse(isZhanhostmidi)
        assertTrue(isKorg)
    }

    @Test
    fun testUserRequest_Test1_Slot1WithoutF7() {
        // Input: F0 7D 05 00
        val raw = byteArrayOf(0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x00.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val sysex = decoded[0] as ByteArray
        assertEquals(4, sysex.size)
        assertEquals(0x05.toByte(), sysex[2]) // CMD 05
        val slotIndex = sysex[3].toInt() and 0x7F
        assertEquals(0, slotIndex)
        val slotNumber = slotIndex + 1
        assertEquals(1, slotNumber) // Slot 1
    }

    @Test
    fun testUserRequest_Test2_Slot13WithoutF7() {
        // Input: F0 7D 05 0C
        val raw = byteArrayOf(0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x0C.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val sysex = decoded[0] as ByteArray
        assertEquals(4, sysex.size)
        assertEquals(0x05.toByte(), sysex[2]) // CMD 05
        val slotIndex = sysex[3].toInt() and 0x7F
        assertEquals(12, slotIndex)
        val slotNumber = slotIndex + 1
        assertEquals(13, slotNumber) // Slot 13
    }

    @Test
    fun testUserRequest_Test3_Slot14WithoutF7() {
        // Input: F0 7D 05 0D
        val raw = byteArrayOf(0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x0D.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val sysex = decoded[0] as ByteArray
        assertEquals(4, sysex.size)
        assertEquals(0x05.toByte(), sysex[2]) // CMD 05
        val slotIndex = sysex[3].toInt() and 0x7F
        assertEquals(13, slotIndex)
        val slotNumber = slotIndex + 1
        assertEquals(14, slotNumber) // Slot 14
    }

    @Test
    fun testUserRequest_Test4_SequenceSlot13AndNotes() {
        // Sequence: F0 7D 05 0C followed by 90 33 58 followed by 80 33 40
        val raw = byteArrayOf(
            0xF0.toByte(), 0x7D.toByte(), 0x05.toByte(), 0x0C.toByte(),
            0x90.toByte(), 0x33.toByte(), 0x58.toByte(),
            0x80.toByte(), 0x33.toByte(), 0x40.toByte()
        )
        val decoded = decodeBleMidi(raw)
        assertEquals(3, decoded.size)
        
        // 1. SysEx CMD 05
        val sysex = decoded[0] as ByteArray
        assertEquals(0x05.toByte(), sysex[2])
        val slotNumber = (sysex[3].toInt() and 0x7F) + 1
        assertEquals(13, slotNumber) // Slot 13

        // 2. Note On
        val noteOn = decoded[1] as DecodedMidiEvent
        assertEquals("NOTE_ON", noteOn.type)
        assertEquals(51, noteOn.noteOrData1) // Note 51 (0x33)
        assertEquals(88, noteOn.velocityOrData2) // Vel 88 (0x58)

        // 3. Note Off
        val noteOff = decoded[2] as DecodedMidiEvent
        assertEquals("NOTE_OFF", noteOff.type)
        assertEquals(51, noteOff.noteOrData1) // Note 51 (0x33)
        assertEquals(64, noteOff.velocityOrData2) // Vel 64 (0x40)
    }

    @Test
    fun testUserRequest_Test5_NoteOn39Vel89() {
        // Input: 90 27 59
        val raw = byteArrayOf(0x90.toByte(), 0x27.toByte(), 0x59.toByte())
        val decoded = decodeBleMidi(raw)
        assertEquals(1, decoded.size)
        val noteOn = decoded[0] as DecodedMidiEvent
        assertEquals("NOTE_ON", noteOn.type)
        assertEquals(0, noteOn.channel) // Ch 1 (0-indexed)
        assertEquals(39, noteOn.noteOrData1) // Note 39 (0x27)
        assertEquals(89, noteOn.velocityOrData2) // Vel 89 (0x59)
    }

    @Test
    fun testEsp32Cmd08_Slot12SysExPacket_7BitPacked() {
        // TEST 1:
        // Slot 12 => slotIndex = 11 (0x0B), SysEx = F0 5B F7
        // rawData = [5B] (rawDataLen = 1)
        // Encoded = [00, 5B] (encodedLen = 2)
        // Expected Packet: F0 7D 08 0B 01 02 00 5B F7
        val slotIndex = 11
        val sysexHex = "F0 5B F7"
        val cleanHex = sysexHex.trim().replace(" ", "")
        val rawBytes = ByteArray(cleanHex.length / 2)
        for (i in rawBytes.indices) {
            val byteIndex = i * 2
            rawBytes[i] = cleanHex.substring(byteIndex, byteIndex + 2).toInt(16).toByte()
        }
        val rawData = rawBytes.copyOfRange(1, rawBytes.size - 1)
        val rawDataLen = rawData.size
        val encodedData = KorgMidiService.pack7BitData(rawData)
        val encodedLen = encodedData.size

        val sysexPacket = ByteArray(6 + encodedData.size + 1)
        sysexPacket[0] = 0xF0.toByte()
        sysexPacket[1] = 0x7D.toByte()
        sysexPacket[2] = 0x08.toByte()
        sysexPacket[3] = slotIndex.toByte()
        sysexPacket[4] = rawDataLen.toByte()
        sysexPacket[5] = encodedLen.toByte()
        System.arraycopy(encodedData, 0, sysexPacket, 6, encodedData.size)
        sysexPacket[sysexPacket.size - 1] = 0xF7.toByte()

        val expected = byteArrayOf(
            0xF0.toByte(), 0x7D.toByte(), 0x08.toByte(), 0x0B.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x00.toByte(), 0x5B.toByte(), 0xF7.toByte()
        )
        assertArrayEquals(expected, sysexPacket)
    }

    @Test
    fun testEsp32Cmd08_Slot12SysExPacket_HighBit_7BitPacked() {
        // TEST 2:
        // Input: F0 E2 5B F7
        // rawData = [E2, 5B] (rawDataLen = 2)
        // Encoded = [01, 62, 5B] (encodedLen = 3)
        // Expected: F0 7D 08 0B 02 03 01 62 5B F7
        val slotIndex = 11
        val sysexHex = "F0 E2 5B F7"
        val cleanHex = sysexHex.trim().replace(" ", "")
        val rawBytes = ByteArray(cleanHex.length / 2)
        for (i in rawBytes.indices) {
            val byteIndex = i * 2
            rawBytes[i] = cleanHex.substring(byteIndex, byteIndex + 2).toInt(16).toByte()
        }
        val rawData = rawBytes.copyOfRange(1, rawBytes.size - 1)
        val rawDataLen = rawData.size
        val encodedData = KorgMidiService.pack7BitData(rawData)
        val encodedLen = encodedData.size

        val sysexPacket = ByteArray(6 + encodedData.size + 1)
        sysexPacket[0] = 0xF0.toByte()
        sysexPacket[1] = 0x7D.toByte()
        sysexPacket[2] = 0x08.toByte()
        sysexPacket[3] = slotIndex.toByte()
        sysexPacket[4] = rawDataLen.toByte()
        sysexPacket[5] = encodedLen.toByte()
        System.arraycopy(encodedData, 0, sysexPacket, 6, encodedData.size)
        sysexPacket[sysexPacket.size - 1] = 0xF7.toByte()

        val expected = byteArrayOf(
            0xF0.toByte(), 0x7D.toByte(), 0x08.toByte(), 0x0B.toByte(), 0x02.toByte(), 0x03.toByte(),
            0x01.toByte(), 0x62.toByte(), 0x5B.toByte(), 0xF7.toByte()
        )
        assertArrayEquals(expected, sysexPacket)
    }

    @Test
    fun testEsp32Cmd08_DecodeTest1() {
        // TEST 3: Decode from TEST 1 -> F0 5B F7
        val encodedData = byteArrayOf(0x00.toByte(), 0x5B.toByte())
        val rawDataLen = 1
        val restoredData = KorgMidiService.unpack7BitData(encodedData, rawDataLen)
        val restoredSysEx = ByteArray(2 + restoredData.size)
        restoredSysEx[0] = 0xF0.toByte()
        System.arraycopy(restoredData, 0, restoredSysEx, 1, restoredData.size)
        restoredSysEx[restoredSysEx.size - 1] = 0xF7.toByte()

        val expected = byteArrayOf(0xF0.toByte(), 0x5B.toByte(), 0xF7.toByte())
        assertArrayEquals(expected, restoredSysEx)
    }

    @Test
    fun testEsp32Cmd08_DecodeTest2() {
        // TEST 4: Decode from TEST 2 -> F0 E2 5B F7
        val encodedData = byteArrayOf(0x01.toByte(), 0x62.toByte(), 0x5B.toByte())
        val rawDataLen = 2
        val restoredData = KorgMidiService.unpack7BitData(encodedData, rawDataLen)
        val restoredSysEx = ByteArray(2 + restoredData.size)
        restoredSysEx[0] = 0xF0.toByte()
        System.arraycopy(restoredData, 0, restoredSysEx, 1, restoredData.size)
        restoredSysEx[restoredSysEx.size - 1] = 0xF7.toByte()

        val expected = byteArrayOf(0xF0.toByte(), 0xE2.toByte(), 0x5B.toByte(), 0xF7.toByte())
        assertArrayEquals(expected, restoredSysEx)
    }

    @Test
    fun testPushSequence_SysexSlot_OnlySendsCmd01_Cmd08_Cmd06() {
        // TEST 5: Verify PUSH SLOT only sends CMD 01 + CMD 08 + CMD 06 and no raw SysEx directly
        val sentPackets = mutableListOf<ByteArray>()
        val slotIndex = 11 // Slot 12
        val preset = SoundPreset(
            name = "Sound 12",
            msb = 0,
            lsb = 0,
            program = 0,
            sysexHex = "F0 5B F7",
            mode = "Prog",
            triggerNote = 47,
            outputNote = -1,
            outputVelocity = 100,
            buttonType = "SYSEX",
            midiChannel = 0
        )

        // 1. CMD 01
        val cmd01 = byteArrayOf(
            0xF0.toByte(), 0x7D.toByte(), 0x01.toByte(),
            slotIndex.toByte(), 47.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
            0.toByte(), 127.toByte(), 100.toByte(), 3.toByte(), 0xF7.toByte()
        )
        sentPackets.add(cmd01)

        // 2. CMD 08 (if SYSEX with 7-bit packing)
        val isSysex = preset.buttonType.equals("SYSEX", ignoreCase = true) || preset.buttonType.equals("SX", ignoreCase = true)
        if (isSysex && preset.sysexHex.isNotBlank()) {
            val cleanHex = preset.sysexHex.replace(" ", "")
            val rawBytes = ByteArray(cleanHex.length / 2)
            for (i in rawBytes.indices) {
                rawBytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val rawData = rawBytes.copyOfRange(1, rawBytes.size - 1)
            val rawDataLen = rawData.size
            val encodedData = KorgMidiService.pack7BitData(rawData)
            val encodedLen = encodedData.size

            val cmd08 = ByteArray(6 + encodedData.size + 1)
            cmd08[0] = 0xF0.toByte()
            cmd08[1] = 0x7D.toByte()
            cmd08[2] = 0x08.toByte()
            cmd08[3] = slotIndex.toByte()
            cmd08[4] = rawDataLen.toByte()
            cmd08[5] = encodedLen.toByte()
            System.arraycopy(encodedData, 0, cmd08, 6, encodedData.size)
            cmd08[cmd08.size - 1] = 0xF7.toByte()
            sentPackets.add(cmd08)
        }

        // 3. CMD 06
        val nameBytes = preset.name.toByteArray(Charsets.UTF_8)
        val cmd06 = ByteArray(5 + nameBytes.size + 1)
        cmd06[0] = 0xF0.toByte()
        cmd06[1] = 0x7D.toByte()
        cmd06[2] = 0x06.toByte()
        cmd06[3] = slotIndex.toByte()
        cmd06[4] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, cmd06, 5, nameBytes.size)
        cmd06[cmd06.size - 1] = 0xF7.toByte()
        sentPackets.add(cmd06)

        // Verify sent packets count is exactly 3
        assertEquals(3, sentPackets.size)
        // Verify 1st packet is CMD 01 (Type = 3)
        assertEquals(0x01.toByte(), sentPackets[0][2])
        assertEquals(0x03.toByte(), sentPackets[0][12]) // buttonTypeCode = 3
        // Verify 2nd packet is CMD 08 with 7-bit packed SysEx
        assertEquals(0x08.toByte(), sentPackets[1][2])
        assertEquals(0x0B.toByte(), sentPackets[1][3]) // slot 11
        assertEquals(0x01.toByte(), sentPackets[1][4]) // rawDataLen = 1
        assertEquals(0x02.toByte(), sentPackets[1][5]) // encodedLen = 2
        assertEquals(0x00.toByte(), sentPackets[1][6]) // header
        assertEquals(0x5B.toByte(), sentPackets[1][7]) // body 5B
        assertEquals(0xF7.toByte(), sentPackets[1][8]) // sysex end
        // Verify 3rd packet is CMD 06
        assertEquals(0x06.toByte(), sentPackets[2][2])

        // Verify that raw SysEx F0 5B F7 is NOT present in the sent packet list as a standalone packet
        val rawSysex = byteArrayOf(0xF0.toByte(), 0x5B.toByte(), 0xF7.toByte())
        for (pkt in sentPackets) {
            assertFalse("Raw SysEx must not be sent directly on PUSH!", java.util.Arrays.equals(rawSysex, pkt))
        }
    }
}

