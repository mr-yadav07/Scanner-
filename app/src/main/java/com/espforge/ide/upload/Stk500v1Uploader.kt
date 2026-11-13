package com.espforge.ide.upload

import dev.espflasher.app.domain.SerialTransport
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Stk500v1Uploader(private val transport: SerialTransport) {
    companion object {
        private const val GET_SYNC = 0x30
        private const val ENTER_PROGMODE = 0x50
        private const val LEAVE_PROGMODE = 0x51
        private const val LOAD_ADDRESS = 0x55
        private const val PROG_PAGE = 0x64
        private const val READ_PAGE = 0x74
        private const val READ_SIGN = 0x75
        private const val INSYNC = 0x14
        private const val OK = 0x10
        private const val CRC_EOP = 0x20
        private const val PAGE_SIZE = 128
    }

    suspend fun upload(hexText: String, nanoOldBootloader: Boolean = false, onProgress: (Float) -> Unit): String {
        val firmware = IntelHexParser.parse(hexText)
        val baudRates = if (nanoOldBootloader) listOf(57600, 115200) else listOf(115200, 57600)
        var lastFailure: Throwable? = null
        for (baud in baudRates) {
            try {
                transport.open(baud)
                resetBoard()
                synchronize()
                enterProgrammingMode()
                val signature = readSignature()
                require(signature.contentEquals(byteArrayOf(0x1E, 0x95.toByte(), 0x0F))) {
                    "Unexpected AVR signature ${signature.joinToString("") { "%02X".format(it) }}; expected ATmega328P (1E950F)."
                }
                writeAndVerify(firmware, onProgress)
                command(byteArrayOf(LEAVE_PROGMODE.toByte(), CRC_EOP.toByte()), 2, 1500)
                return "AVR firmware uploaded and verified (ATmega328P, ${baud} baud)."
            } catch (t: Throwable) {
                lastFailure = t
            } finally {
                runCatching { transport.close() }
            }
        }
        throw IllegalStateException("AVR upload failed at 115200 and 57600 baud: ${lastFailure?.message}", lastFailure)
    }

    private suspend fun resetBoard() {
        transport.setDtr(false)
        transport.setRts(false)
        delay(100)
        transport.setDtr(true)
        delay(80)
        transport.setDtr(false)
        delay(350)
    }

    private suspend fun synchronize() {
        var last: Throwable? = null
        repeat(8) {
            try {
                val response = command(byteArrayOf(GET_SYNC.toByte(), CRC_EOP.toByte()), 2, 500)
                expectOk(response, "GET_SYNC")
                return
            } catch (t: Throwable) {
                last = t
                delay(100)
            }
        }
        throw IllegalStateException("No STK500 bootloader response. Hold RESET/check cable and try again.", last)
    }

    private suspend fun enterProgrammingMode() {
        val response = command(byteArrayOf(ENTER_PROGMODE.toByte(), CRC_EOP.toByte()), 2, 1000)
        expectOk(response, "ENTER_PROGMODE")
    }

    private suspend fun readSignature(): ByteArray {
        val response = command(byteArrayOf(READ_SIGN.toByte(), CRC_EOP.toByte()), 5, 1000)
        if (response[0].toInt() != INSYNC) error("AVR signature response did not start with INSYNC.")
        if ((response.last().toInt() and 0xFF) != OK) error("AVR signature read returned an error.")
        return response.copyOfRange(1, 4)
    }

    private suspend fun writeAndVerify(data: ByteArray, onProgress: (Float) -> Unit) {
        val paddedSize = ((data.size + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE
        val image = data.copyOf(paddedSize).also { if (paddedSize > data.size) it.fill(0xFF.toByte(), data.size) }
        var address = 0
        while (address < image.size) {
            val page = image.copyOfRange(address, address + PAGE_SIZE)
            loadAddress(address / 2)
            val write = ByteArray(4 + page.size)
            write[0] = PROG_PAGE.toByte()
            write[1] = 0
            write[2] = PAGE_SIZE.toByte()
            write[3] = 'F'.code.toByte()
            page.copyInto(write, 4)
            val writeCommand = write + CRC_EOP.toByte()
            expectOk(command(writeCommand, 2, 2500), "PROG_PAGE")
            loadAddress(address / 2)
            val readCommand = byteArrayOf(READ_PAGE.toByte(), 0, PAGE_SIZE.toByte(), 'F'.code.toByte(), CRC_EOP.toByte())
            val readBack = command(readCommand, PAGE_SIZE + 2, 2500)
            if (readBack[0].toInt() != INSYNC || (readBack.last().toInt() and 0xFF) != OK) {
                error("AVR read-back response was invalid at 0x${address.toString(16)}.")
            }
            val actual = readBack.copyOfRange(1, 1 + PAGE_SIZE)
            if (!actual.contentEquals(page)) error("AVR verification failed at flash address 0x${address.toString(16)}.")
            address += PAGE_SIZE
            onProgress(address.coerceAtMost(image.size) * 100f / image.size)
        }
    }

    private suspend fun loadAddress(wordAddress: Int) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(LOAD_ADDRESS.toByte())
            .put(wordAddress.toByte())
            .put((wordAddress ushr 8).toByte())
            .put(CRC_EOP.toByte())
            .array()
        expectOk(command(bytes, 2, 1000), "LOAD_ADDRESS")
    }

    private suspend fun command(request: ByteArray, responseLength: Int, timeoutMs: Int): ByteArray {
        transport.write(request)
        val response = ByteArray(responseLength)
        var offset = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (offset < responseLength && System.currentTimeMillis() < deadline) {
            val part = transport.read(responseLength - offset, 100)
            if (part.isNotEmpty()) {
                part.copyInto(response, offset)
                offset += part.size
            }
        }
        if (offset != responseLength) error("AVR bootloader response timed out.")
        return response
    }

    private fun expectOk(response: ByteArray, command: String) {
        if (response.size < 2 || (response[0].toInt() and 0xFF) != INSYNC || (response.last().toInt() and 0xFF) != OK) {
            error("AVR bootloader rejected $command.")
        }
    }
}
