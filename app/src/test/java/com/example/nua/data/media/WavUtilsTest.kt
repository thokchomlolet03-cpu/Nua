package com.example.nua.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for WavUtils — validates dynamic RIFF chunk parsing
 * and duration calculation across standard and extended WAV formats.
 */
class WavUtilsTest {

    /**
     * Creates a standard 44-byte header WAV file with the given PCM duration.
     */
    private fun createStandardWav(file: File, durationMs: Long, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16) {
        val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8)
        val pcmLength = ((durationMs * bytesPerSecond) / 1000).toInt()
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        buf.put('R'.code.toByte()); buf.put('I'.code.toByte())
        buf.put('F'.code.toByte()); buf.put('F'.code.toByte())
        buf.putInt(pcmLength + 36)
        buf.put('W'.code.toByte()); buf.put('A'.code.toByte())
        buf.put('V'.code.toByte()); buf.put('E'.code.toByte())

        // fmt sub-chunk
        buf.put('f'.code.toByte()); buf.put('m'.code.toByte())
        buf.put('t'.code.toByte()); buf.put(' '.code.toByte())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())

        // data sub-chunk
        buf.put('d'.code.toByte()); buf.put('a'.code.toByte())
        buf.put('t'.code.toByte()); buf.put('a'.code.toByte())
        buf.putInt(pcmLength)

        // Write header + empty PCM
        val fullData = ByteArray(44 + pcmLength)
        System.arraycopy(header, 0, fullData, 0, 44)
        file.writeBytes(fullData)
    }

    /**
     * Creates a WAV with an extra LIST chunk before the data chunk.
     * This results in a data chunk offset > 44 bytes.
     */
    private fun createExtendedWav(file: File, durationMs: Long) {
        val sampleRate = 44100
        val channels = 2
        val bitsPerSample = 16
        val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8)
        val pcmLength = ((durationMs * bytesPerSecond) / 1000).toInt()
        val byteRate = bytesPerSecond
        val blockAlign = channels * (bitsPerSample / 8)

        // LIST chunk with some metadata (26 bytes: 4 id + 4 size + 18 data)
        val listChunkData = "INFO".toByteArray() + "Test metadata!!".toByteArray() // 19 bytes inside LIST
        val listChunkSize = listChunkData.size
        val listChunkTotalSize = 8 + listChunkSize // 'LIST' + size int + data

        val totalHeaderSize = 12 + 24 + listChunkTotalSize + 8 // RIFF header + fmt + LIST + data header
        val totalFileSize = totalHeaderSize + pcmLength

        val data = ByteArray(totalFileSize)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buf.put('R'.code.toByte()); buf.put('I'.code.toByte())
        buf.put('F'.code.toByte()); buf.put('F'.code.toByte())
        buf.putInt(totalFileSize - 8)
        buf.put('W'.code.toByte()); buf.put('A'.code.toByte())
        buf.put('V'.code.toByte()); buf.put('E'.code.toByte())

        // fmt sub-chunk
        buf.put('f'.code.toByte()); buf.put('m'.code.toByte())
        buf.put('t'.code.toByte()); buf.put(' '.code.toByte())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())

        // LIST chunk (extra metadata)
        buf.put('L'.code.toByte()); buf.put('I'.code.toByte())
        buf.put('S'.code.toByte()); buf.put('T'.code.toByte())
        buf.putInt(listChunkSize)
        buf.put(listChunkData)

        // data sub-chunk
        buf.put('d'.code.toByte()); buf.put('a'.code.toByte())
        buf.put('t'.code.toByte()); buf.put('a'.code.toByte())
        buf.putInt(pcmLength)

        file.writeBytes(data)
    }

    @Test
    fun testFindDataChunkOffset_standardWav() {
        val tempFile = File.createTempFile("wav_test_standard_", ".wav")
        try {
            createStandardWav(tempFile, 2000)
            val offset = WavUtils.findDataChunkOffset(tempFile)
            assertEquals(44L, offset) // Standard WAV data starts at byte 44
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testFindDataChunkOffset_extendedWav() {
        val tempFile = File.createTempFile("wav_test_extended_", ".wav")
        try {
            createExtendedWav(tempFile, 1000)
            val offset = WavUtils.findDataChunkOffset(tempFile)
            assertTrue("Extended WAV data offset should be > 44, got $offset", offset > 44)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testGetWavDurationMs_standardWav() {
        val tempFile = File.createTempFile("wav_test_dur_", ".wav")
        try {
            createStandardWav(tempFile, 3000, sampleRate = 16000, channels = 1, bitsPerSample = 16)
            val duration = WavUtils.getWavDurationMs(tempFile, -1L)
            // Allow 1ms tolerance due to integer division
            assertTrue("Duration should be ~3000ms, got $duration", duration in 2999..3001)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testGetWavDurationMs_extendedWav() {
        val tempFile = File.createTempFile("wav_test_ext_dur_", ".wav")
        try {
            createExtendedWav(tempFile, 2000)
            val duration = WavUtils.getWavDurationMs(tempFile, -1L)
            assertTrue("Duration should be ~2000ms, got $duration", duration in 1999..2001)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testGetWavDurationMs_emptyFile() {
        val tempFile = File.createTempFile("wav_test_empty_", ".wav")
        try {
            tempFile.writeBytes(ByteArray(0))
            val duration = WavUtils.getWavDurationMs(tempFile, 999L)
            assertEquals(999L, duration)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testGetWavDurationMs_corruptedFile() {
        val tempFile = File.createTempFile("wav_test_corrupt_", ".wav")
        try {
            tempFile.writeBytes(ByteArray(100) { 0xFF.toByte() })
            val duration = WavUtils.getWavDurationMs(tempFile, 777L)
            assertEquals(777L, duration)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testGetWavDurationSeconds() {
        val tempFile = File.createTempFile("wav_test_secs_", ".wav")
        try {
            createStandardWav(tempFile, 5000)
            val duration = WavUtils.getWavDurationSeconds(tempFile)
            assertTrue("Duration should be ~5.0s, got $duration", duration in 4.99..5.01)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testWriteWavHeader() {
        val tempFile = File.createTempFile("wav_test_write_", ".wav")
        try {
            val pcmLength = 32000L // 1 second at 16kHz mono 16-bit
            val data = ByteArray(44 + pcmLength.toInt())
            tempFile.writeBytes(data)

            WavUtils.writeWavHeader(tempFile, pcmLength, 16000, 1, 16)

            val duration = WavUtils.getWavDurationMs(tempFile, -1L)
            assertEquals(1000L, duration)
        } finally {
            tempFile.delete()
        }
    }
}
