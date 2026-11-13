package com.espforge.ide.upload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntelHexParserTest {
    @Test fun parsesSimpleHex() {
        val hex = ":0400000001020304F2\n:00000001FF"
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), IntelHexParser.parse(hex))
    }

    @Test fun fillsAddressGapsWithErasedFlashValue() {
        val hex = ":0100000000FF\n:0100020002FB\n:00000001FF"
        assertArrayEquals(byteArrayOf(0, -1, 2), IntelHexParser.parse(hex))
    }

    @Test fun rejectsBadChecksum() {
        assertThrows(IllegalArgumentException::class.java) {
            IntelHexParser.parse(":010000000000\n:00000001FF")
        }
    }
}
