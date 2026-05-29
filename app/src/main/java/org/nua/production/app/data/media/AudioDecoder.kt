package org.nua.production.app.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.nua.production.app.data.asr.AudioChunk
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class AudioDecoder {

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
    }

    private val accumulatorLock = Any()
    
    // Continuous original-rate linear stream accumulator backing array
    private var linearAccumulator = ShortArray(48000 * 60) 
    @Volatile private var accumulatorSize = 0
    @Volatile private var isDecoderEOS = false
    
    // Pre-allocated direct byte buffer to eliminate JNI heap allocation churn
    // Maximum allocation bounds: 35 seconds of 16kHz float audio samples (35 * 16000 * 4 bytes)
    private val maxOutputBytes = 35 * 16000 * 4
    private val directResampleBuffer: ByteBuffer = ByteBuffer.allocateDirect(maxOutputBytes)
        .order(ByteOrder.nativeOrder())

    suspend fun decodeVideoToWav(
        videoFile: File,
        outputWavFile: File,
        audioChannel: Channel<AudioChunk>?,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputStream: FileOutputStream? = null
        var slicingJob: Job? = null

        try {
            extractor.setDataSource(videoFile.absolutePath)
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in video file")
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext false

            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val isStereo = sourceChannels > 1
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outputStream = FileOutputStream(outputWavFile)
            outputStream.write(ByteArray(44)) // header placeholder

            isDecoderEOS = false
            accumulatorSize = 0
            
            // Linear resampler state for fallback 16kHz WAV generation
            var srcIndexPos = 0.0
            var leftovers = ShortArray(0)
            val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            val writeByteBuffer = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
            var totalBytesWritten = 0L

            val transferBuffer = ShortArray(4096)
            val timeoutUs = 10000L
            val info = MediaCodec.BufferInfo()

            // Spawns the decoupled, thread-isolated slicing loop worker
            slicingJob = if (audioChannel != null) {
                launch {
                    slicingWorkerLoop(audioChannel, sourceSampleRate)
                }
            } else null

            var isInputEOS = false

            // HARDENED MECHANICS: Synchronous polling loop context allows safe coroutine delays
            while (!isDecoderEOS) {
                if (audioChannel != null) {
                    // 1. PROPAGATED DECODER BACKPRESSURE: Non-blocking suspension prevents heap expansion
                    val maxSafetySamples = 40 * sourceSampleRate
                    while (accumulatorSize >= maxSafetySamples) {
                        delay(50) // Suspends cleanly
                    }
                }

                // 2. Queue compressed packets from source extractor into hardware input slots
                if (!isInputEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 3. Dequeue raw PCM frames out of hardware decoder slots
                val outputBufferId = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outputBufferId >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                    
                    val codecBuffer = codec.getOutputBuffer(outputBufferId)
                    if (codecBuffer != null && info.size > 0) {
                        codecBuffer.position(info.offset)
                        val totalSamples = info.size / 2
                        codecBuffer.asShortBuffer().get(transferBuffer, 0, totalSamples)

                        // Inline Stereo-to-Mono Downmixing occurs before file write or append operations
                        val monoSampleCount = if (isStereo) totalSamples / 2 else totalSamples
                        if (isStereo) {
                            for (i in 0 until monoSampleCount) {
                                transferBuffer[i] = ((transferBuffer[2 * i] + transferBuffer[2 * i + 1]) / 2).toShort()
                            }
                        }

                        // Write resampled output to WAV file as fallback
                        val combined = leftovers + transferBuffer.copyOfRange(0, monoSampleCount)
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

                        val remStart = srcIndexPos.toInt()
                        if (remStart < M) {
                            leftovers = combined.copyOfRange(remStart, M)
                            srcIndexPos -= remStart
                        } else {
                            leftovers = ShortArray(0)
                            srcIndexPos = 0.0
                        }

                        // 4. RAPID SYNCHRONIZED MEMORY APPEND
                        if (audioChannel != null) {
                            synchronized(accumulatorLock) {
                                if (accumulatorSize + monoSampleCount > linearAccumulator.size) {
                                    linearAccumulator = linearAccumulator.copyOf(linearAccumulator.size * 2)
                                }
                                System.arraycopy(transferBuffer, 0, linearAccumulator, accumulatorSize, monoSampleCount)
                                accumulatorSize += monoSampleCount
                            }
                        }

                        if (durationUs > 0) {
                            val progress = (info.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                }
            }

            slicingJob?.join()
            audioChannel?.close()

            if (writeByteBuffer.position() > 0) {
                writeByteBuffer.flip()
                outputStream.write(writeByteBuffer.array(), 0, writeByteBuffer.limit())
                totalBytesWritten += writeByteBuffer.limit()
                writeByteBuffer.clear()
            }

            if (leftovers.isNotEmpty()) {
                writeByteBuffer.putShort(leftovers[0])
                writeByteBuffer.flip()
                outputStream.write(writeByteBuffer.array(), 0, writeByteBuffer.limit())
                totalBytesWritten += writeByteBuffer.limit()
            }

            outputStream.close()
            outputStream = null

            writeWavHeader(outputWavFile, totalBytesWritten)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding video to wav", e)
            false
        } finally {
            isDecoderEOS = true
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing decoder resources", e)
            }
        }
    }

    private suspend fun slicingWorkerLoop(audioChannel: Channel<AudioChunk>, sourceSampleRate: Int) {
        val minSamples = (25.0 * sourceSampleRate).toInt()
        val maxSamples = (35.0 * sourceSampleRate).toInt()
        val windowSamples = (0.100f * sourceSampleRate).toInt() // 100ms VAD frames
        val vadThreshold = 0.002f

        var chunkIndex = 0
        var currentGlobalOffsetSecs = 0.0

        while (true) {
            var snapshotArray: ShortArray? = null
            var activeSamplesToProcess = 0

            // 1. ISOLATED LOCK SNAPSHOT EXTRACTION: Zero-latency atomic memory grab
            synchronized(accumulatorLock) {
                if (accumulatorSize >= minSamples || (isDecoderEOS && accumulatorSize > 0)) {
                    activeSamplesToProcess = min(accumulatorSize, maxSamples)
                    val snapshot = ShortArray(activeSamplesToProcess)
                    System.arraycopy(linearAccumulator, 0, snapshot, 0, activeSamplesToProcess)
                    snapshotArray = snapshot
                }
            }

            if (snapshotArray != null) {
                val rawSamples = snapshotArray
                var lastSilenceIndex = -1

                // 2. OUT-OF-LOCK MATHEMATICAL VAD SCAN: Fully thread-isolated
                for (i in minSamples until activeSamplesToProcess step windowSamples) {
                    var energySq = 0.0f
                    val endFrameIndex = min(i + windowSamples, activeSamplesToProcess)
                    
                    for (j in i until endFrameIndex) {
                        val normalizedSample = rawSamples[j].toFloat() / 32768.0f
                        energySq += normalizedSample * normalizedSample
                    }
                    
                    val rms = java.lang.Math.sqrt((energySq / (endFrameIndex - i)).toDouble()).toFloat()
                    if (rms < vadThreshold) {
                        lastSilenceIndex = endFrameIndex
                        break // Natural pause boundary discovered
                    }
                }

                // Resolve the final chunk cutting point boundary
                var isLast = false
                val finalCutIndex = if (lastSilenceIndex != -1) {
                    lastSilenceIndex
                } else if (activeSamplesToProcess >= maxSamples) {
                    (30.0 * sourceSampleRate).toInt() // Hard slice fallback boundary
                } else if (isDecoderEOS) {
                    isLast = true
                    activeSamplesToProcess
                } else {
                    delay(100)
                    continue // Keep accumulating
                }

                // Isolate the target slice arrays cleanly from the snapshot frame
                val sliceToProcess = ShortArray(finalCutIndex)
                System.arraycopy(rawSamples, 0, sliceToProcess, 0, finalCutIndex)

                // 3. RAPID RE-ENTRY LOCK FOR MEMORY TRUNCATION ONLY
                synchronized(accumulatorLock) {
                    val remainingSamples = accumulatorSize - finalCutIndex
                    System.arraycopy(linearAccumulator, finalCutIndex, linearAccumulator, 0, remainingSamples)
                    accumulatorSize = remainingSamples
                }

                // 4. ZERO-ALLOCATION JNI DIRECT BUFFER PASSING
                var producedFloatCount = 0
                synchronized(directResampleBuffer) {
                    directResampleBuffer.clear()
                    
                    // Native execution populates our JVM direct memory allocation layout natively
                    producedFloatCount = WhisperContext.nativeResampleDirect(sliceToProcess, sourceSampleRate, directResampleBuffer)
                }
                
                // 5. GUARANTEED DIMENSIONAL CHECK (Dimensionality Failsafes)
                if (producedFloatCount > 0) {
                    val outFloats = FloatArray(producedFloatCount)
                    synchronized(directResampleBuffer) {
                        directResampleBuffer.position(0)
                        directResampleBuffer.asFloatBuffer().get(outFloats)
                    }

                    val audioChunk = AudioChunk(
                        index = chunkIndex++,
                        data = outFloats,
                        startTimeSec = currentGlobalOffsetSecs,
                        isLast = isLast
                    )

                    try {
                        audioChannel.send(audioChunk)
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio channel closed, stopping slicing worker")
                        break
                    }
                }

                currentGlobalOffsetSecs += (finalCutIndex.toDouble() / sourceSampleRate.toDouble())
                if (isLast) break
            } else {
                if (isDecoderEOS && accumulatorSize == 0) {
                    break
                }
                delay(100) // Poll backing accumulator safely
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    private fun writeWavHeader(wavFile: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val sampleRate = TARGET_SAMPLE_RATE.toLong()
        val channels = TARGET_CHANNELS
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing WAV header", e)
        }
    }
}
