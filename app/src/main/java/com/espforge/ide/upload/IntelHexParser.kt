package com.espforge.ide.upload

import java.io.ByteArrayOutputStream

object IntelHexParser {
    fun parse(text: String): ByteArray {
        val memory = sortedMapOf<Long, Byte>()
        var upperAddress = 0L
        var eof = false
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            require(!eof) { "Data appears after Intel HEX EOF at line ${index + 1}." }
            require(line.startsWith(":")) { "Invalid Intel HEX record at line ${index + 1}." }
            val record = runCatching {
                line.drop(1).chunked(2).map { it.toInt(16) }
            }.getOrElse { throw IllegalArgumentException("Invalid hex digits at line ${index + 1}.") }
            require(record.size >= 5) { "Truncated Intel HEX record at line ${index + 1}." }
            val byteCount = record[0]
            require(record.size == byteCount + 5) { "Record length mismatch at line ${index + 1}." }
            require(record.sum() and 0xFF == 0) { "Checksum mismatch at line ${index + 1}." }
            val address = (record[1] shl 8) or record[2]
            val type = record[3]
            val payload = record.subList(4, 4 + byteCount)
            when (type) {
                0x00 -> payload.forEachIndexed { offset, value ->
                    val absolute = upperAddress + address + offset
                    require(absolute < 0x8000) { "HEX data address 0x${absolute.toString(16)} is outside ATmega328P flash." }
                    memory[absolute] = value.toByte()
                }
                0x01 -> {
                    require(byteCount == 0) { "Invalid EOF record at line ${index + 1}." }
                    eof = true
                }
                0x02 -> {
                    require(byteCount == 2) { "Invalid extended segment address at line ${index + 1}." }
                    upperAddress = (((payload[0] shl 8) or payload[1]).toLong()) shl 4
                }
                0x04 -> {
                    require(byteCount == 2) { "Invalid extended linear address at line ${index + 1}." }
                    upperAddress = (((payload[0] shl 8) or payload[1]).toLong()) shl 16
                }
                0x03, 0x05 -> Unit
                else -> throw IllegalArgumentException("Unsupported Intel HEX record type 0x${type.toString(16)}.")
            }
        }
        require(eof) { "Intel HEX EOF record is missing." }
        require(memory.isNotEmpty()) { "Intel HEX contains no flash data." }
        val last = memory.lastKey().toInt()
        val result = ByteArray(last + 1) { 0xFF.toByte() }
        memory.forEach { (address, value) -> result[address.toInt()] = value }
        return result
    }
}
