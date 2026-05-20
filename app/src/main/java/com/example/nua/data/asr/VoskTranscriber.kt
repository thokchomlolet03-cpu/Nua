package com.example.nua.data.asr

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class TextSegment(
    val text: String,
    val startTimeSec: Double,
    val endTimeSec: Double
)

class VoskTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "VoskTranscriber"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val ZIP_FILE_NAME = "vosk-model.zip"
        private const val UNZIP_DIR_NAME = "vosk-model-en"
    }

    private var voskModel: Model? = null

    val modelDir: File
        get() = File(context.filesDir, UNZIP_DIR_NAME)

    fun isModelDownloaded(): Boolean {
        if (!modelDir.exists()) return false
        // The unzipped model folder should contain a "am" or "conf" directory
        val children = modelDir.listFiles()
        if (children.isNullOrEmpty()) return false
        // Vosk models usually unzip into a subdirectory like "vosk-model-small-en-us-0.15"
        val innerDir = children.firstOrNull { it.isDirectory }
        return innerDir != null && File(innerDir, "am").exists()
    }

    /**
     * Downloads and unzips the Vosk small English model.
     * Call from a background thread.
     */
    fun downloadModel(onProgress: (Float) -> Unit): Boolean {
        if (isModelDownloaded()) {
            onProgress(1f)
            return true
        }

        val tempZipFile = File(context.cacheDir, ZIP_FILE_NAME)
        val client = OkHttpClient()

        try {
            Log.d(TAG, "Downloading Vosk model from $MODEL_URL")
            val request = Request.Builder().url(MODEL_URL).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download model: HTTP ${response.code}")
                return false
            }

            val body = response.body ?: return false
            val contentLength = body.contentLength()
            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(tempZipFile)

            val data = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int

            while (inputStream.read(data).also { bytesRead = it } != -1) {
                outputStream.write(data, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    onProgress((totalBytesRead.toFloat() / contentLength.toFloat()) * 0.7f) // Download is 70% of work
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            Log.d(TAG, "Model downloaded. Unzipping to ${modelDir.absolutePath}")
            onProgress(0.75f)

            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            modelDir.mkdirs()

            unzip(tempZipFile, modelDir) { extractProgress ->
                onProgress(0.75f + (extractProgress * 0.25f)) // Unzipping is 25% of work
            }

            tempZipFile.delete()
            Log.d(TAG, "Vosk model unpacked successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/unpacking Vosk model", e)
            tempZipFile.delete()
            return false
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File, onProgress: (Float) -> Unit) {
        val zipInputStream = ZipInputStream(BufferedInputStream(FileInputStream(zipFile)))
        try {
            var zipEntry = zipInputStream.nextEntry
            val buffer = ByteArray(8192)
            
            // To calculate progress, we can just estimate based on file entries if size is unknown,
            // or just step progress. For simplicity, we step progress by 0.05 per entry up to 0.95
            var entriesProcessed = 0
            
            while (zipEntry != null) {
                val file = File(targetDirectory, zipEntry.name)
                val dir = if (zipEntry.isDirectory) file else file.parentFile
                if (!dir.exists() && !dir.mkdirs()) {
                    throw Exception("Failed to create directory " + dir.absolutePath)
                }
                if (!zipEntry.isDirectory) {
                    val fileOutputStream = FileOutputStream(file)
                    var count: Int
                    while (zipInputStream.read(buffer).also { count = it } != -1) {
                        fileOutputStream.write(buffer, 0, count)
                    }
                    fileOutputStream.close()
                }
                entriesProcessed++
                onProgress((entriesProcessed.toFloat() / 100f).coerceAtMost(0.95f))
                zipInputStream.closeEntry()
                zipEntry = zipInputStream.nextEntry
            }
            onProgress(1f)
        } finally {
            zipInputStream.close()
        }
    }

    /**
     * Initializes the Vosk Model from the unzipped directory.
     */
    fun initModel(): Boolean {
        if (voskModel != null) return true
        if (!isModelDownloaded()) return false

        return try {
            val children = modelDir.listFiles() ?: return false
            val innerDir = children.firstOrNull { it.isDirectory } ?: return false
            Log.d(TAG, "Loading Vosk model from: ${innerDir.absolutePath}")
            voskModel = Model(innerDir.absolutePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model", e)
            false
        }
    }

    /**
     * Transcribes the given WAV file and returns segmented sentences with timestamps.
     * Call from a background thread.
     */
    fun transcribeWav(wavFile: File, onProgress: (Float) -> Unit): List<TextSegment> {
        if (!initModel() || voskModel == null) {
            Log.e(TAG, "Vosk model not initialized")
            return emptyList()
        }

        val fileLength = wavFile.length()
        if (fileLength <= 44) return emptyList()

        val results = ArrayList<JSONObject>()
        var recognizer: Recognizer? = null
        var inputStream: FileInputStream? = null

        try {
            val rec = Recognizer(voskModel, 16000.0f)
            recognizer = rec
            rec.setWords(true) // Crucial for getting timestamps!

            inputStream = FileInputStream(wavFile)
            // Skip 44-byte WAV header
            inputStream.skip(44)

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalProcessed = 44L

            while (inputStream.read(buffer).also { bytesRead = it } >= 0) {
                totalProcessed += bytesRead
                val isFinal = rec.acceptWaveForm(buffer, bytesRead)
                if (isFinal) {
                    val resultStr = rec.result
                    if (resultStr.isNotEmpty()) {
                        results.add(JSONObject(resultStr))
                    }
                }
                onProgress((totalProcessed.toFloat() / fileLength.toFloat()).coerceIn(0f, 1f))
            }

            val finalResultStr = rec.finalResult
            if (finalResultStr.isNotEmpty()) {
                results.add(JSONObject(finalResultStr))
            }

            return segmentWords(results)

        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing audio", e)
            return emptyList()
        } finally {
            recognizer?.close()
            inputStream?.close()
        }
    }

    /**
     * Extracts word items with timestamps from the Vosk JSON results and segments them.
     */
    private fun segmentWords(jsonResults: List<JSONObject>): List<TextSegment> {
        val allWords = ArrayList<WordItem>()

        for (json in jsonResults) {
            if (!json.has("result")) continue
            val resultArr = json.getJSONArray("result")
            for (i in 0 until resultArr.length()) {
                val obj = resultArr.getJSONObject(i)
                val word = obj.getString("word")
                val start = obj.getDouble("start")
                val end = obj.getDouble("end")
                val conf = obj.optDouble("conf", 1.0)
                allWords.add(WordItem(word, start, end, conf))
            }
        }

        if (allWords.isEmpty()) return emptyList()

        val segments = ArrayList<TextSegment>()
        var currentSegmentWords = ArrayList<WordItem>()
        val maxGap = 0.8 // Max silent gap in seconds before starting a new segment
        val maxSegmentDuration = 7.0 // Target max duration of a segment in seconds
        val maxWords = 14 // Max words per segment

        for (i in allWords.indices) {
            val word = allWords[i]
            if (currentSegmentWords.isEmpty()) {
                currentSegmentWords.add(word)
            } else {
                val lastWord = currentSegmentWords.last()
                val gap = word.start - lastWord.end
                val currentDuration = word.end - currentSegmentWords.first().start

                val shouldSplit = gap > maxGap || 
                                  currentDuration > maxSegmentDuration || 
                                  currentSegmentWords.size >= maxWords

                if (shouldSplit) {
                    segments.add(buildSegmentFromWords(currentSegmentWords))
                    currentSegmentWords = ArrayList()
                    currentSegmentWords.add(word)
                } else {
                    currentSegmentWords.add(word)
                }
            }
        }

        if (currentSegmentWords.isNotEmpty()) {
            segments.add(buildSegmentFromWords(currentSegmentWords))
        }

        return segments
    }

    private fun buildSegmentFromWords(words: List<WordItem>): TextSegment {
        val text = words.joinToString(" ") { it.word }
        val start = words.first().start
        val end = words.last().end
        return TextSegment(text, start, end)
    }

    private data class WordItem(
        val word: String,
        val start: Double,
        val end: Double,
        val conf: Double
    )
}
