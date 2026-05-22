package com.example.nua.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecoder {

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
    }

    /**
     * Decodes the audio track from [videoFile] and saves it as a 16kHz mono 16-bit PCM WAV file at [outputWavFile].
     * Runs synchronously. Call from a background thread.
     */
    fun decodeVideoToWav(videoFile: File, outputWavFile: File, onProgress: (Float) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputStream: FileOutputStream? = null

        try {
            extractor.setDataSource(videoFile.absolutePath)
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in video file")
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false

            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            Log.d(TAG, "Source audio: mime=$mime, sampleRate=$sourceSampleRate, channels=$sourceChannels, durationUs=$durationUs")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outputStream = FileOutputStream(outputWavFile)
            // Leave space for 44-byte WAV header
            val headerSpace = ByteArray(44)
            outputStream.write(headerSpace)

            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false
            var totalBytesWritten = 0L

            var leftovers = ShortArray(0)
            var srcIndexPos = 0.0
            val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

            // 16 KB temporary writing buffer
            val writeByteBuffer = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)

            var tryAgainCount = 0

            while (!isDecoderEOS) {
                if (!isExtractorEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isExtractorEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    tryAgainCount = 0
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shortArray = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(shortArray)

                        // 1. Downmix to Mono on-the-fly
                        val framesCount = if (sourceChannels > 0) shortArray.size / sourceChannels else 0
                        val monoArray = ShortArray(framesCount)
                        for (i in 0 until framesCount) {
                            var sum = 0
                            for (c in 0 until sourceChannels) {
                                sum += shortArray[i * sourceChannels + c].toInt()
                            }
                            monoArray[i] = ((sum + sourceChannels / 2) / sourceChannels).toShort()
                        }

                        // 2. Resample stream on-the-fly and write to outputStream
                        val combined = leftovers + monoArray
                        val M = combined.size

                        while (srcIndexPos < M - 1) {
                            val idx = srcIndexPos.toInt()
                            val frac = srcIndexPos - idx
                            val s1 = combined[idx]
                            val s2 = combined[idx + 1]
                            val resampled = (s1 + frac * (s2 - s1)).toInt().toShort()

                            if (writeByteBuffer.remaining() < 2) {
                                writeByteBuffer.flip()
                                outputStream.write(writeByteBuffer.array(), 0, writeByteBuffer.limit())
                                totalBytesWritten += writeByteBuffer.limit()
                                writeByteBuffer.clear()
                            }
                            writeByteBuffer.putShort(resampled)
                            srcIndexPos += ratio
                        }

                        // Save remaining leftovers
                        val remStart = srcIndexPos.toInt()
                        if (remStart < M) {
                            leftovers = combined.copyOfRange(remStart, M)
                            srcIndexPos -= remStart
                        } else {
                            leftovers = ShortArray(0)
                            srcIndexPos = 0.0
                        }

                        if (durationUs > 0) {
                            val progress = (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isExtractorEOS) {
                        tryAgainCount++
                        if (tryAgainCount > 50) {
                            Log.e(TAG, "Decoder hung at EOS. Breaking.")
                            break
                        }
                    }
                }
            }

            // Flush remaining buffer
            if (writeByteBuffer.position() > 0) {
                writeByteBuffer.flip()
                outputStream.write(writeByteBuffer.array(), 0, writeByteBuffer.limit())
                totalBytesWritten += writeByteBuffer.limit()
                writeByteBuffer.clear()
            }

            // Write final single leftover sample if any
            if (leftovers.isNotEmpty()) {
                val lastSample = leftovers[0]
                writeByteBuffer.putShort(lastSample)
                writeByteBuffer.flip()
                outputStream.write(writeByteBuffer.array(), 0, writeByteBuffer.limit())
                totalBytesWritten += writeByteBuffer.limit()
            }

            outputStream.close()
            outputStream = null

            // Write standard WAV header
            writeWavHeader(outputWavFile, totalBytesWritten)
            Log.d(TAG, "Audio successfully decoded & resampled: ${outputWavFile.absolutePath}, size=$totalBytesWritten bytes")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding video audio", e)
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long) {
        WavUtils.writeWavHeader(file, pcmDataLength, TARGET_SAMPLE_RATE, TARGET_CHANNELS, 16)
    }
}
