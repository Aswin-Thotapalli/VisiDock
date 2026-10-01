package com.thotapalli.visidock

import java.io.*
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Fixed-size independently authenticated records avoid provider-wide GCM buffering. */
internal object BackupRecords {
    const val CHUNK = 64 * 1024
    const val MAX_PACKED = 2L * 1024 * 1024 * 1024 + 64L * 1024 * 1024
    fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray,
        index: Long, length: Int): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val nonce = ByteBuffer.allocate(12).put(prefix).putLong(index).array()
        init(mode, key, GCMParameterSpec(128, nonce))
        updateAAD(header)
        updateAAD(ByteBuffer.allocate(12).putLong(index).putInt(length).array())
    }
}

internal class AuthenticatedBackupOutput(output: OutputStream, private val key: SecretKeySpec,
    private val header: ByteArray, private val prefix: ByteArray) : OutputStream() {
    private val output = DataOutputStream(output)
    private val buffer = ByteArray(BackupRecords.CHUNK)
    private var count = 0
    private var index = 0L
    private var total = 0L
    private var closed = false
    override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed)
        require(offset >= 0 && length >= 0 && offset <= bytes.size-length)
        var at = offset
        var remaining = length
        while (remaining > 0) {
            val amount = minOf(buffer.size-count, remaining)
            bytes.copyInto(buffer, count, at, at+amount)
            count += amount; at += amount; remaining -= amount
            if (count == buffer.size) emit(count)
        }
    }
    private fun emit(length: Int) {
        total += length
        require(total <= BackupRecords.MAX_PACKED) { "Backup exceeds the supported size." }
        val encrypted = BackupRecords.cipher(Cipher.ENCRYPT_MODE, key, header, prefix, index++, length)
            .doFinal(buffer, 0, length)
        output.writeInt(length)
        output.write(encrypted)
        buffer.fill(0, 0, length)
        count = 0
    }
    override fun flush() { if (!closed) { if (count > 0) emit(count); output.flush() } }
    override fun close() {
        if (closed) return
        try {
            if (count > 0) emit(count)
            emit(0) // An authenticated terminator is mandatory, even for an empty final chunk.
            output.flush()
        } finally { closed = true; buffer.fill(0); output.close() }
    }
}

internal class AuthenticatedBackupInput(input: InputStream, private val key: SecretKeySpec,
    private val header: ByteArray, private val prefix: ByteArray) : InputStream() {
    private val input = DataInputStream(input)
    private var clear = ByteArray(0)
    private var position = 0
    private var index = 0L
    private var total = 0L
    private var finished = false
    override fun read(): Int {
        if (!availableChunk()) return -1
        return clear[position++].toInt() and 255
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size-length)
        if (length == 0) return 0
        if (!availableChunk()) return -1
        val amount = minOf(length, clear.size-position)
        clear.copyInto(bytes, offset, position, position+amount); position += amount
        return amount
    }
    private fun availableChunk(): Boolean {
        if (position < clear.size) return true
        if (finished) return false
        clear.fill(0)
        val length = input.readInt() // EOF without authenticated terminator is truncation, never success.
        require(length in 0..BackupRecords.CHUNK) { "Invalid encrypted backup record." }
        total += length
        require(total <= BackupRecords.MAX_PACKED) { "Backup exceeds the supported size." }
        val encrypted = ByteArray(length+16).also(input::readFully)
        clear = BackupRecords.cipher(Cipher.DECRYPT_MODE, key, header, prefix, index++, length).doFinal(encrypted)
        position = 0
        if (length == 0) {
            require(input.read() == -1) { "Unexpected data after backup terminator." }
            finished = true
            return false
        }
        return true
    }
    override fun close() { clear.fill(0); input.close() }
}
