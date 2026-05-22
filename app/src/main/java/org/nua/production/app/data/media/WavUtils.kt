package org.nua.production.app.data.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Centralized WAV file utilities. Replaces hardcoded 44-byte header
 * assumptions with dynamic RIFF chunk iteration.
 *
 * Handles extended headers (LIST, bext, etc.) that push the 'data'
 * chunk beyond the standard 44-byte boundary.
 */
object WavUtils {

    /**
     * Dynamically locates the 'data' chunk offset in a RIFF WAV file.
     * Iterates through RIFF sub-chunks until the 'data' marker is found.
     */
    fun findDataChunkOffset(file: File): Long {
        val raf = RandomAccessFile(file, "r")
        try {
            if (raf.length() < 12) throw IllegalArgumentException("File too small to be a valid WAV")
            raf.seek(12) // Skip RIFF header (4 bytes 'RIFF' + 4 bytes size + 4 bytes 'WAVE')
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = "" + raf.readByte().toInt().toChar() +
                        raf.readByte().toInt().toChar() +
                        raf.readByte().toInt().toChar() +
                        raf.readByte().toInt().toChar()
                val chunkSize = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                if (chunkId == "data") {
                    return raf.filePointer // Position right after 'data' + size
                }
                raf.skipBytes(chunkSize.toInt())
            }
            throw IllegalArgumentException("Invalid RIFF WAV: no 'data' chunk found")
        } finally {
            raf.close()
        }
    }

    /**
     * Reads WAV file duration in milliseconds by parsing RIFF chunks dynamically.
     * Falls back to [fallbackMs] if the file is invalid or unreadable.
     */
    fun getWavDurationMs(file: File, fallbackMs: Long): Long {
        try {
            if (!file.exists() || file.length() <= 44) return fallbackMs
            val raf = RandomAccessFile(file, "r")
            try {
                raf.seek(12) // Skip RIFF header
                var channels = 1
                var sampleRate = 16000
                var bitsPerSample = 16
                var dataSize = 0L

                while (raf.filePointer < raf.length() - 8) {
                    val chunkId = "" + raf.readByte().toInt().toChar() +
                            raf.readByte().toInt().toChar() +
                            raf.readByte().toInt().toChar() +
                            raf.readByte().toInt().toChar()
                    val chunkSize = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL

                    when (chunkId) {
                        "fmt " -> {
                            val fmtStart = raf.filePointer
                            raf.skipBytes(2) // audioFormat
                            channels = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                            sampleRate = Integer.reverseBytes(raf.readInt())
                            raf.skipBytes(6) // byteRate + blockAlign
                            bitsPerSample = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                            raf.seek(fmtStart + chunkSize)
                        }
                        "data" -> {
                            dataSize = chunkSize
                            break
                        }
                        else -> raf.skipBytes(chunkSize.toInt())
                    }
                }

                if (dataSize <= 0 || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return fallbackMs
                val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
                return if (bytesPerSecond > 0) (dataSize * 1000L) / bytesPerSecond else fallbackMs
            } finally {
                raf.close()
            }
        } catch (_: Exception) {
            return fallbackMs
        }
    }

    /**
     * Reads WAV file duration in seconds (Double precision).
     * Returns 0.0 if the file cannot be parsed.
     */
    fun getWavDurationSeconds(file: File): Double {
        return getWavDurationMs(file, 0L) / 1000.0
    }

    /**
     * Writes a standard RIFF WAV header to a file that already contains PCM data.
     * Overwrites the first 44 bytes of the file with the correct header.
     */
    fun writeWavHeader(
        file: File,
        pcmDataLength: Long,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        buffer.put('R'.code.toByte()); buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte()); buffer.put('F'.code.toByte())
        buffer.putInt((pcmDataLength + 36).toInt())
        buffer.put('W'.code.toByte()); buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte()); buffer.put('E'.code.toByte())

        // fmt sub-chunk
        buffer.put('f'.code.toByte()); buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte()); buffer.put(' '.code.toByte())
        buffer.putInt(16) // SubChunk1Size (PCM)
        buffer.putShort(1) // AudioFormat (PCM = 1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data sub-chunk
        buffer.put('d'.code.toByte()); buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte()); buffer.put('a'.code.toByte())
        buffer.putInt(pcmDataLength.toInt())

        val raf = RandomAccessFile(file, "rw")
        try {
            raf.seek(0)
            raf.write(header)
        } finally {
            raf.close()
        }
    }
}
