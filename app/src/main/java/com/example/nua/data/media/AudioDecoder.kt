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

            // We will accumulate PCM shorts to resample them
            // ShortArray holds decoded raw samples
            val decodeBuffer = ArrayList<Short>()

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
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Read shorts from buffer (16-bit PCM is standard output of MediaCodec decoders)
                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shortArray = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(shortArray)

                        for (s in shortArray) {
                            decodeBuffer.add(s)
                        }

                        // Update progress
                        if (durationUs > 0) {
                            val progress = (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format changed
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Decoder output format changed: $newFormat")
                }
            }

            // Resample all decoded short samples to target: 16kHz mono 16-bit PCM
            val resampledShorts = resampleAudio(
                decodeBuffer.toShortArray(),
                sourceSampleRate,
                TARGET_SAMPLE_RATE,
                sourceChannels
            )

            // Write the resampled shorts to the file
            val byteBuffer = ByteBuffer.allocate(resampledShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in resampledShorts) {
                byteBuffer.putShort(s)
            }
            outputStream.write(byteBuffer.array())
            totalBytesWritten = resampledShorts.size * 2L

            // Go back and write the WAV header
            outputStream.close()
            outputStream = null

            writeWavHeader(outputWavFile, totalBytesWritten)
            Log.d(TAG, "Audio successfully decoded & resampled to WAV: ${outputWavFile.absolutePath}, size=$totalBytesWritten bytes")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding video audio", e)
            return false
        } finally {
            try {
                extractor.release()
                codec?.stop()
                codec?.release()
                outputStream?.close()
            } catch (e: Exception) {
                // Ignore
            }
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

    /**
     * Resamples short PCM data from [sourceSampleRate] and [sourceChannels] to [targetSampleRate] and [TARGET_CHANNELS] (Mono).
     */
    private fun resampleAudio(
        inputPcm: ShortArray,
        sourceSampleRate: Int,
        targetSampleRate: Int,
        sourceChannels: Int
    ): ShortArray {
        // Step 1: Stereo to mono
        val monoPcm = if (sourceChannels == 2) {
            val output = ShortArray(inputPcm.size / 2)
            for (i in output.indices) {
                val left = inputPcm[i * 2].toInt()
                val right = inputPcm[i * 2 + 1].toInt()
                output[i] = ((left + right) / 2).toShort()
            }
            output
        } else if (sourceChannels > 2) {
            // Downmix multi-channel to mono (take first channel)
            val output = ShortArray(inputPcm.size / sourceChannels)
            for (i in output.indices) {
                output[i] = inputPcm[i * sourceChannels]
            }
            output
        } else {
            inputPcm
        }

        if (sourceSampleRate == targetSampleRate) {
            return monoPcm
        }

        // Step 2: Linear interpolation resampling
        val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val outputLength = (monoPcm.size / ratio).toInt()
        val resampledPcm = ShortArray(outputLength)
        for (i in 0 until outputLength) {
            val srcIndex = i * ratio
            val index = srcIndex.toInt()
            val fraction = srcIndex - index
            if (index >= monoPcm.size - 1) {
                resampledPcm[i] = monoPcm[monoPcm.size - 1]
            } else {
                val sample1 = monoPcm[index].toInt()
                val sample2 = monoPcm[index + 1].toInt()
                resampledPcm[i] = (sample1 + fraction * (sample2 - sample1)).toInt().toShort()
            }
        }
        return resampledPcm
    }

    /**
     * Writes standard 44-byte RIFF WAV header at the beginning of the file.
     */
    private fun writeWavHeader(file: File, pcmDataLength: Long) {
        val randomAccessFile = RandomAccessFile(file, "rw")
        val header = ByteArray(44)
        val totalDataLen = pcmDataLength + 36

        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen & 0xff).toByte()
        header[5] = ((totalDataLen >> 8) & 0xff).toByte()
        header[6] = ((totalDataLen >> 16) & 0xff).toByte()
        header[7] = ((totalDataLen >> 24) & 0xff).toByte()

        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1 // format = 1 (PCM)
        header[21] = 0

        header[22] = TARGET_CHANNELS.toByte() // channels
        header[23] = 0

        val longSampleRate = TARGET_SAMPLE_RATE.toLong()
        header[24] = (longSampleRate & 0xff).toByte()
        header[25] = ((longSampleRate >> 8) & 0xff).toByte()
        header[26] = ((longSampleRate >> 16) & 0xff).toByte()
        header[27] = ((longSampleRate >> 24) & 0xff).toByte()

        val byteRate = longSampleRate * TARGET_CHANNELS * 2 // 16-bit PCM is 2 bytes per sample
        header[28] = (byteRate & 0xff).toByte()
        header[29] = ((byteRate >> 8) & 0xff).toByte()
        header[30] = ((byteRate >> 16) & 0xff).toByte()
        header[31] = ((byteRate >> 24) & 0xff).toByte()

        header[32] = (TARGET_CHANNELS * 2).toByte() // block align
        header[33] = 0

        header[34] = 16 // bits per sample (16 bit)
        header[35] = 0

        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (pcmDataLength & 0xff).toByte()
        header[41] = ((pcmDataLength >> 8) & 0xff).toByte()
        header[42] = ((pcmDataLength >> 16) & 0xff).toByte()
        header[43] = ((pcmDataLength >> 24) & 0xff).toByte()

        randomAccessFile.seek(0)
        randomAccessFile.write(header)
        randomAccessFile.close()
    }
}
