package com.example.nua.ui.main

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nua.data.asr.VoskTranscriber
import com.example.nua.data.media.VideoDubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sharedPrefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)

    private val voskTranscriber = VoskTranscriber(context)
    private val videoDubber = VideoDubber(context)

    // Config states
    private val _mockMode = MutableStateFlow(sharedPrefs.getBoolean("mock_mode", true))
    val mockMode: StateFlow<Boolean> = _mockMode.asStateFlow()

    private val _gemmaModelPath = MutableStateFlow(sharedPrefs.getString("gemma_model_path", null))
    val gemmaModelPath: StateFlow<String?> = _gemmaModelPath.asStateFlow()

    // Vosk model states
    private val _isVoskModelDownloaded = MutableStateFlow(voskTranscriber.isModelDownloaded())
    val isVoskModelDownloaded: StateFlow<Boolean> = _isVoskModelDownloaded.asStateFlow()

    private val _isDownloadingVosk = MutableStateFlow(false)
    val isDownloadingVosk: StateFlow<Boolean> = _isDownloadingVosk.asStateFlow()

    private val _voskDownloadProgress = MutableStateFlow(0f)
    val voskDownloadProgress: StateFlow<Float> = _voskDownloadProgress.asStateFlow()

    // Processing states
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentStep = MutableStateFlow("")
    val currentStep: StateFlow<String> = _currentStep.asStateFlow()

    private val _stepProgress = MutableStateFlow(0f)
    val stepProgress: StateFlow<Float> = _stepProgress.asStateFlow()

    private val _processingLogs = MutableStateFlow<List<String>>(emptyList())
    val processingLogs: StateFlow<List<String>> = _processingLogs.asStateFlow()

    // Gallery History
    private val _dubbedHistory = MutableStateFlow<List<File>>(emptyList())
    val dubbedHistory: StateFlow<List<File>> = _dubbedHistory.asStateFlow()

    init {
        refreshHistory()
    }

    fun setMockMode(enabled: Boolean) {
        _mockMode.value = enabled
        sharedPrefs.edit().putBoolean("mock_mode", enabled).apply()
    }

    fun setGemmaModelPath(path: String?) {
        _gemmaModelPath.value = path
        sharedPrefs.edit().putString("gemma_model_path", path).apply()
    }

    fun importGemmaModel(uri: android.net.Uri, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Null input stream")
                val destinationFile = File(context.filesDir, "gemma_model.bin")
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                
                val outputStream = FileOutputStream(destinationFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
                
                setGemmaModelPath(destinationFile.absolutePath)
                onCompleted(true)
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to import Gemma model", e)
                onCompleted(false)
            }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val files = dir.listFiles { file ->
                file.isFile && file.name.startsWith("dubbed_") && file.name.endsWith(".mp4")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
            _dubbedHistory.value = files
        }
    }

    fun downloadVoskModel() {
        if (_isDownloadingVosk.value) return
        _isDownloadingVosk.value = true
        _voskDownloadProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            val success = voskTranscriber.downloadModel { progress ->
                _voskDownloadProgress.value = progress
            }
            _isDownloadingVosk.value = false
            _isVoskModelDownloaded.value = success
        }
    }

    private fun addLog(message: String) {
        val current = _processingLogs.value.toMutableList()
        current.add(message)
        _processingLogs.value = current
    }

    fun startDubbingLocalUri(uri: android.net.Uri) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        _processingLogs.value = emptyList()
        _currentStep.value = "Importing Video"
        _stepProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("Importing video file...")
                val tempFile = File(context.cacheDir, "imported_input.mp4")
                if (tempFile.exists()) tempFile.delete()

                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Failed to open stream")
                val outputStream = FileOutputStream(tempFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                // Let's get total size for progress if possible
                val assetFileDescriptor = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")
                } catch (e: Exception) {
                    null
                }
                val totalLength = assetFileDescriptor?.length ?: -1L
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (totalLength > 0) {
                        _stepProgress.value = totalBytesRead.toFloat() / totalLength.toFloat()
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
                assetFileDescriptor?.close()

                addLog("Import complete: ${tempFile.length()} bytes.")
                // Run the actual dubbing
                _isProcessing.value = false // reset flag so startDubbingLocalVideo can run
                startDubbingLocalVideo(tempFile)
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to import video URI", e)
                addLog("❌ Error importing video: ${e.message}")
                _isProcessing.value = false
            }
        }
    }

    /**
     * Dabs a local video file.
     */
    fun startDubbingLocalVideo(videoFile: File) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        _processingLogs.value = emptyList()
        
        viewModelScope.launch(Dispatchers.IO) {
            addLog("Initializing translation workflow for local video: ${videoFile.name}...")
            videoDubber.dubVideo(
                videoFile = videoFile,
                gemmaModelPath = _gemmaModelPath.value,
                mockMode = _mockMode.value,
                listener = object : VideoDubber.DubbingListener {
                    override fun onLog(message: String) {
                        addLog(message)
                    }

                    override fun onProgress(step: String, progress: Float) {
                        _currentStep.value = step
                        _stepProgress.value = progress
                    }

                    override fun onError(error: String) {
                        addLog("❌ Error: $error")
                        _isProcessing.value = false
                    }

                    override fun onCompleted(outputFile: File) {
                        addLog("🎉 Success! Dubbed video saved at: ${outputFile.name}")
                        _isProcessing.value = false
                        refreshHistory()
                    }
                }
            )
        }
    }

    /**
     * Downloads a video from a URL and then dubs it.
     */
    fun startDubbingVideoFromUrl(url: String) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        _processingLogs.value = emptyList()
        _currentStep.value = "Downloading Video"
        _stepProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            addLog("Downloading video from link: $url...")
            val client = OkHttpClient()
            val tempVideoFile = File(context.cacheDir, "downloaded_temp.mp4")

            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    addLog("❌ Failed to download video: HTTP ${response.code}")
                    _isProcessing.value = false
                    return@launch
                }

                val body = response.body
                if (body == null) {
                    addLog("❌ Video download failed: empty response body")
                    _isProcessing.value = false
                    return@launch
                }

                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(tempVideoFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                        _stepProgress.value = progress
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                addLog("Video downloaded successfully. Size: ${tempVideoFile.length()} bytes.")
                
                // Now run the dubbing pipeline
                videoDubber.dubVideo(
                    videoFile = tempVideoFile,
                    gemmaModelPath = _gemmaModelPath.value,
                    mockMode = _mockMode.value,
                    listener = object : VideoDubber.DubbingListener {
                        override fun onLog(message: String) {
                            addLog(message)
                        }

                        override fun onProgress(step: String, progress: Float) {
                            _currentStep.value = step
                            _stepProgress.value = progress
                        }

                        override fun onError(error: String) {
                            addLog("❌ Error: $error")
                            _isProcessing.value = false
                            tempVideoFile.delete()
                        }

                        override fun onCompleted(outputFile: File) {
                            addLog("🎉 Success! Dubbed video saved at: ${outputFile.name}")
                            _isProcessing.value = false
                            tempVideoFile.delete()
                            refreshHistory()
                        }
                    }
                )

            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "URL download error", e)
                addLog("❌ Error downloading video: ${e.message}")
                _isProcessing.value = false
                tempVideoFile.delete()
            }
        }
    }
}
