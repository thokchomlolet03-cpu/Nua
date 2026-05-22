package com.example.nua.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nua.data.asr.VoskTranscriber
import com.example.nua.data.media.PipelineCompilerService
import com.example.nua.data.media.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * ViewModel for the main screen. Manages config, Vosk download,
 * compilation lifecycle, and dubbing history.
 */

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sharedPrefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)

    private val voskTranscriber = VoskTranscriber(context)
    private val sessionManager = SessionManager(context)

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

    // Processing states (observed from the background Service)
    val isProcessing: StateFlow<Boolean> = PipelineCompilerService.isProcessing
    val currentStep: StateFlow<String> = PipelineCompilerService.currentStep
    val stepProgress: StateFlow<Float> = PipelineCompilerService.stepProgress
    val processingLogs: StateFlow<List<String>> = PipelineCompilerService.logs

    // Gallery History (List of session folders containing compiled offline packages)
    private val _dubbedHistory = MutableStateFlow<List<File>>(emptyList())
    val dubbedHistory: StateFlow<List<File>> = _dubbedHistory.asStateFlow()

    // Video URL input state (hoisted from Compose local state to survive config changes)
    private val _videoUrl = MutableStateFlow("")
    val videoUrl: StateFlow<String> = _videoUrl.asStateFlow()

    fun setVideoUrl(url: String) {
        _videoUrl.value = url
    }

    init {
        refreshHistory()
        // Auto-refresh gallery when processing completes
        viewModelScope.launch {
            isProcessing.collect { processing ->
                if (!processing) {
                    refreshHistory()
                }
            }
        }
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
                
                inputStream.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }
                
                setGemmaModelPath(destinationFile.absolutePath)
                viewModelScope.launch(Dispatchers.Main) { onCompleted(true) }
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to import Gemma model", e)
                viewModelScope.launch(Dispatchers.Main) { onCompleted(false) }
            }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = sessionManager.listCompletedSessions()
            _dubbedHistory.value = sessions
        }
    }

    fun deleteSession(sessionDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.deleteSession(sessionDir)
            refreshHistory()
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

    fun startDubbingLocalUri(uri: android.net.Uri) {
        if (isProcessing.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "imported_input.mp4")
                if (tempFile.exists()) tempFile.delete()

                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Failed to open stream")
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }

                // Trigger Foreground Service
                val intent = Intent(context, PipelineCompilerService::class.java).apply {
                    action = PipelineCompilerService.ACTION_START
                    putExtra(PipelineCompilerService.EXTRA_VIDEO_PATH, tempFile.absolutePath)
                    putExtra(PipelineCompilerService.EXTRA_GEMMA_MODEL_PATH, gemmaModelPath.value)
                    putExtra(PipelineCompilerService.EXTRA_MOCK_MODE, mockMode.value)
                }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to import video URI", e)
            }
        }
    }

    fun startDubbingVideoFromUrl(url: String) {
        if (isProcessing.value) return

        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val tempVideoFile = File(context.cacheDir, "downloaded_temp.mp4")

            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        PipelineCompilerService.addLog("❌ Failed to download video: HTTP ${response.code}")
                        return@launch
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    body.byteStream().use { input ->
                        FileOutputStream(tempVideoFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                            output.flush()
                        }
                    }
                }

                // Trigger Foreground Service
                val intent = Intent(context, PipelineCompilerService::class.java).apply {
                    action = PipelineCompilerService.ACTION_START
                    putExtra(PipelineCompilerService.EXTRA_VIDEO_PATH, tempVideoFile.absolutePath)
                    putExtra(PipelineCompilerService.EXTRA_GEMMA_MODEL_PATH, gemmaModelPath.value)
                    putExtra(PipelineCompilerService.EXTRA_MOCK_MODE, mockMode.value)
                }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "URL download error", e)
                PipelineCompilerService.addLog("❌ Error downloading video: ${e.message}")
            }
        }
    }
}
