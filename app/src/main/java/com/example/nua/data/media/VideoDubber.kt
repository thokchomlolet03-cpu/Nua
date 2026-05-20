package com.example.nua.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.nua.data.asr.TextSegment
import com.example.nua.data.asr.VoskTranscriber
import com.example.nua.data.llm.GemmaTranslator
import com.example.nua.data.tts.DubbingTtsEngine
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

class VideoDubber(private val context: Context) {

    companion object {
        private const val TAG = "VideoDubber"
    }

    interface DubbingListener {
        fun onLog(message: String)
        fun onProgress(step: String, progress: Float)
        fun onError(error: String)
        fun onCompleted(outputFile: File)
    }

    private val audioDecoder = AudioDecoder()
    private val voskTranscriber = VoskTranscriber(context)
    private val gemmaTranslator = GemmaTranslator(context)
    private val ttsEngine = DubbingTtsEngine(context)

    /**
     * Executes the complete video translation and dubbing pipeline.
     * Call from a background thread.
     */
    fun dubVideo(
        videoFile: File,
        gemmaModelPath: String?,
        mockMode: Boolean,
        listener: DubbingListener
    ) {
        val cacheDir = context.cacheDir
        val originalAudioWav = File(cacheDir, "original_audio.wav")
        val assembledAudioWav = File(cacheDir, "dubbed_assembled.wav")
        val outputVideoFile = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "dubbed_${System.currentTimeMillis()}.mp4"
        )

        try {
            // Step 1: Decode Audio
            listener.onLog("Step 1/5: Extracting and resampling original audio...")
            listener.onProgress("Extracting Audio", 0.0f)
            
            val decodeSuccess = audioDecoder.decodeVideoToWav(videoFile, originalAudioWav) { progress ->
                listener.onProgress("Extracting Audio", progress)
            }

            if (!decodeSuccess || !originalAudioWav.exists()) {
                listener.onError("Failed to extract audio track from video.")
                return
            }
            listener.onLog("Audio extracted successfully: ${originalAudioWav.length()} bytes")

            // Step 2: Speech-to-Text Transcription
            listener.onLog("Step 2/5: Transcribing speech offline with Vosk...")
            listener.onProgress("Transcribing", 0.0f)

            if (!mockMode && !voskTranscriber.isModelDownloaded()) {
                listener.onError("Vosk language model is not downloaded. Please download it in Setup first.")
                return
            }

            val segments = voskTranscriber.transcribeWav(originalAudioWav) { progress ->
                listener.onProgress("Transcribing", progress)
            }

            if (segments.isEmpty()) {
                listener.onLog("No speech segments detected in the video. Dubbing a silent track.")
            } else {
                listener.onLog("Transcribed ${segments.size} speech segments.")
                segments.forEachIndexed { i, seg ->
                    listener.onLog("  [Segment ${i+1}] (${String.format("%.1fs", seg.startTimeSec)} - ${String.format("%.1fs", seg.endTimeSec)}): \"${seg.text}\"")
                }
            }

            // Step 3: LLM Translation
            listener.onLog("Step 3/5: Translating text using Gemma...")
            listener.onProgress("Translating", 0.0f)

            if (!mockMode) {
                if (gemmaModelPath.isNullOrEmpty()) {
                    listener.onError("Gemma model path is not set. Go to Setup to import a model.")
                    return
                }
                val loaded = gemmaTranslator.initModel(gemmaModelPath)
                if (!loaded) {
                    listener.onError("Failed to initialize Gemma model from $gemmaModelPath")
                    return
                }
            }

            val translatedSegments = ArrayList<TextSegment>()
            for (i in segments.indices) {
                val seg = segments[i]
                listener.onLog("Translating segment ${i+1}/${segments.size}...")
                
                val translation = gemmaTranslator.translate(seg.text, mockMode)
                translatedSegments.add(TextSegment(translation, seg.startTimeSec, seg.endTimeSec))
                
                listener.onLog("  Original: \"${seg.text}\"")
                listener.onLog("  Translated: \"$translation\"")
                
                listener.onProgress("Translating", (i + 1).toFloat() / segments.size.toFloat())
            }

            // Step 4: Text-To-Speech Synthesis
            listener.onLog("Step 4/5: Generating dubbed audio voice track...")
            listener.onProgress("Generating Voice", 0.0f)

            val ttsTempFiles = ArrayList<File>()
            val sampleRate = 16000 // We will build the assembled track at 16kHz
            val durationSec = getVideoDuration(videoFile)
            listener.onLog("Video total duration: ${String.format("%.2f", durationSec)} seconds")

            // Initialize assembled silent WAV file
            createSilentWavFile(assembledAudioWav, durationSec, sampleRate)

            for (i in translatedSegments.indices) {
                val seg = translatedSegments[i]
                val ttsFile = File(cacheDir, "tts_seg_$i.wav")
                ttsTempFiles.add(ttsFile)

                listener.onLog("Synthesizing segment ${i+1}/${translatedSegments.size}...")
                val targetDur = seg.endTimeSec - seg.startTimeSec

                val ttsSuccess = ttsEngine.synthesizeSpeech(seg.text, targetDur, ttsFile)
                if (ttsSuccess && ttsFile.exists()) {
                    // Mux this segment into the assembled file at its timestamp
                    val ttsDur = ttsEngine.getWavDurationSeconds(ttsFile)
                    listener.onLog("  Voice generated: ${String.format("%.2f", ttsDur)}s (Target: ${String.format("%.2f", targetDur)}s)")
                    
                    mixAudioSegment(assembledAudioWav, ttsFile, seg.startTimeSec, sampleRate)
                } else {
                    listener.onLog("  Warning: Failed to generate voice for segment ${i+1}")
                }
                listener.onProgress("Generating Voice", (i + 1).toFloat() / translatedSegments.size.toFloat())
            }

            // Step 5: Mux Video and Dubbed Audio
            listener.onLog("Step 5/5: Rendering final video...")
            listener.onProgress("Rendering Video", 0.0f)

            val muxSuccess = mergeVideoAndAudio(videoFile, assembledAudioWav, outputVideoFile) { progress ->
                listener.onProgress("Rendering Video", progress)
            }

            if (!muxSuccess || !outputVideoFile.exists()) {
                listener.onError("Failed to mux video and dubbed audio track.")
                return
            }

            // Cleanup temp files
            originalAudioWav.delete()
            assembledAudioWav.delete()
            ttsTempFiles.forEach { it.delete() }

            listener.onLog("Translation and Dubbing completed successfully!")
            listener.onProgress("Completed", 1.0f)
            listener.onCompleted(outputVideoFile)

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline execution error", e)
            listener.onError("An unexpected error occurred during processing: ${e.message}")
        }
    }

    private fun getVideoDuration(videoFile: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            durationMs / 1000.0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video duration", e)
            0.0
        } finally {
            retriever.release()
        }
    }

    private fun createSilentWavFile(file: File, durationSec: Double, sampleRate: Int) {
        if (file.exists()) {
            file.delete()
        }
        val fileOutputStream = FileOutputStream(file)
        val pcmDataLength = (durationSec * sampleRate * 2).toLong() // 16-bit = 2 bytes per sample

        // Write 44-byte header space
        fileOutputStream.write(ByteArray(44))

        // Write silent PCM data in chunks (8KB at a time)
        val silentChunk = ByteArray(8192)
        var bytesWritten = 0L
        while (bytesWritten < pcmDataLength) {
            val toWrite = (pcmDataLength - bytesWritten).coerceAtMost(8192).toInt()
            fileOutputStream.write(silentChunk, 0, toWrite)
            bytesWritten += toWrite
        }
        fileOutputStream.flush()
        fileOutputStream.close()

        // Write wav header
        writeWavHeader(file, pcmDataLength, sampleRate)
    }

    private fun mixAudioSegment(assembledFile: File, segmentFile: File, startTimeSec: Double, sampleRate: Int) {
        try {
            val segmentInputStream = FileInputStream(segmentFile)
            segmentInputStream.skip(44) // Skip WAV header

            val segmentBytes = segmentInputStream.readBytes()
            segmentInputStream.close()

            if (segmentBytes.isEmpty()) return

            val randomAccessFile = RandomAccessFile(assembledFile, "rw")
            val byteOffset = (startTimeSec * sampleRate * 2).toLong() + 44 // Account for 44-byte WAV header

            val fileLength = randomAccessFile.length()
            if (byteOffset < fileLength) {
                randomAccessFile.seek(byteOffset)
                // Ensure we don't write past the end of the silent track
                val bytesToWrite = (fileLength - byteOffset).coerceAtMost(segmentBytes.size.toLong()).toInt()
                randomAccessFile.write(segmentBytes, 0, bytesToWrite)
            }
            randomAccessFile.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error mixing segment file at $startTimeSec s", e)
        }
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long, sampleRate: Int) {
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

        header[22] = 1 // channels = 1 (Mono)
        header[23] = 0

        val longSampleRate = sampleRate.toLong()
        header[24] = (longSampleRate & 0xff).toByte()
        header[25] = ((longSampleRate >> 8) & 0xff).toByte()
        header[26] = ((longSampleRate >> 16) & 0xff).toByte()
        header[27] = ((longSampleRate >> 24) & 0xff).toByte()

        val byteRate = longSampleRate * 1 * 2 // 16-bit PCM is 2 bytes per sample
        header[28] = (byteRate & 0xff).toByte()
        header[29] = ((byteRate >> 8) & 0xff).toByte()
        header[30] = ((byteRate >> 16) & 0xff).toByte()
        header[31] = ((byteRate >> 24) & 0xff).toByte()

        header[32] = 2 // block align (1 channel * 2 bytes)
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

    /**
     * Muxes original video (without audio) and new dubbed WAV audio using Media3 Transformer.
     */
    private fun mergeVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val latch = CountDownLatch(1)
        var success = true
        var exportException: ExportException? = null

        // 1. Prepare video source (removing original audio track)
        val videoMediaItem = MediaItem.Builder()
            .setUri(videoFile.absolutePath)
            .build()
        val videoEditedItem = EditedMediaItem.Builder(videoMediaItem)
            .setRemoveAudio(true)
            .build()

        // 2. Prepare audio source
        val audioMediaItem = MediaItem.Builder()
            .setUri(audioFile.absolutePath)
            .build()
        val audioEditedItem = EditedMediaItem.Builder(audioMediaItem)
            .build()

        // 3. Create composition sequences
        val videoSequence = EditedMediaItemSequence(listOf(videoEditedItem))
        val audioSequence = EditedMediaItemSequence(listOf(audioEditedItem))

        val composition = Composition.Builder(listOf(videoSequence, audioSequence))
            .build()

        // 4. Build Transformer
        val transformer = Transformer.Builder(context)
            .build()

        // Handler to post updates and query progress
        val mainHandler = Handler(Looper.getMainLooper())
        
        // Progress polling runnable
        val progressRunnable = object : Runnable {
            override fun run() {
                val progressHolder = androidx.media3.transformer.ProgressHolder()
                val progressState = transformer.getProgress(progressHolder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress.toFloat() / 100.0f
                    onProgress(pct)
                }
                if (latch.count > 0) {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                latch.countDown()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exception: ExportException) {
                success = false
                exportException = exception
                latch.countDown()
            }
        })

        if (outputFile.exists()) {
            outputFile.delete()
        }

        // Start transformer on the main thread
        mainHandler.post {
            try {
                transformer.start(composition, outputFile.absolutePath)
                mainHandler.post(progressRunnable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Media3 Transformer", e)
                success = false
                latch.countDown()
            }
        }

        try {
            latch.await()
        } catch (e: Exception) {
            Log.e(TAG, "Muxing thread interrupted", e)
            success = false
        }

        if (!success) {
            Log.e(TAG, "Media3 Transformer failed", exportException)
        }

        return success
    }
}
