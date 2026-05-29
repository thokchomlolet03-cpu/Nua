package org.nua.production.app.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.nua.production.app.data.asr.WhisperTranscriber
import org.nua.production.app.data.media.PipelineCompilerService
import org.nua.production.app.data.media.SessionManager
import org.nua.production.app.data.schema.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus

data class FeaturedVideo(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val videoUrl: String,
    val description: String = ""
)

/**
 * ViewModel for the main screen. Manages config, Whisper download,
 * compilation lifecycle, and dubbing history.
 */

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sharedPrefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)

    private val whisperTranscriber = WhisperTranscriber(application)
    private val sessionManager = SessionManager(context.filesDir)

    // Hardware Tier
    private val _deviceTier = MutableStateFlow<DeviceTier>(
        try {
            DeviceTier.valueOf(sharedPrefs.getString("device_tier", "UNKNOWN") ?: "UNKNOWN")
        } catch (e: Exception) {
            DeviceTier.UNKNOWN
        }
    )
    val deviceTier: StateFlow<DeviceTier> = _deviceTier.asStateFlow()

    // Config states
    private val _mockMode = MutableStateFlow(sharedPrefs.getBoolean("mock_mode", false))
    val mockMode: StateFlow<Boolean> = _mockMode.asStateFlow()

    private val _gemmaModelPath = MutableStateFlow(sharedPrefs.getString("gemma_model_path", null))
    val gemmaModelPath: StateFlow<String?> = _gemmaModelPath.asStateFlow()

    private val _tutorModelPath = MutableStateFlow(sharedPrefs.getString("tutor_model_path", null))
    val tutorModelPath: StateFlow<String?> = _tutorModelPath.asStateFlow()

    // Play Asset Delivery (Gemma Model)
    private val assetPackManager: AssetPackManager = AssetPackManagerFactory.getInstance(context)
    
    private val _isDownloadingGemma = MutableStateFlow(false)
    val isDownloadingGemma: StateFlow<Boolean> = _isDownloadingGemma.asStateFlow()

    private val _gemmaDownloadProgress = MutableStateFlow(0f)
    val gemmaDownloadProgress: StateFlow<Float> = _gemmaDownloadProgress.asStateFlow()

    private val _gemmaDownloadError = MutableStateFlow<String?>(null)
    val gemmaDownloadError: StateFlow<String?> = _gemmaDownloadError.asStateFlow()

    private val _gemmaDownloadStatus = MutableStateFlow<String?>(null)
    val gemmaDownloadStatus: StateFlow<String?> = _gemmaDownloadStatus.asStateFlow()

    private val _isDownloadingTutor = MutableStateFlow(false)
    val isDownloadingTutor: StateFlow<Boolean> = _isDownloadingTutor.asStateFlow()

    private val _tutorDownloadProgress = MutableStateFlow(0f)
    val tutorDownloadProgress: StateFlow<Float> = _tutorDownloadProgress.asStateFlow()

    private val _tutorDownloadStatus = MutableStateFlow<String?>(null)
    val tutorDownloadStatus: StateFlow<String?> = _tutorDownloadStatus.asStateFlow()

    // Whisper model states
    private val _isWhisperReady = MutableStateFlow(whisperTranscriber.isModelDownloaded())
    val isWhisperReady: StateFlow<Boolean> = _isWhisperReady.asStateFlow()

    // Processing states (observed from the background Service)
    val isProcessing: StateFlow<Boolean> = PipelineCompilerService.isProcessing
    val currentStep: StateFlow<String> = PipelineCompilerService.currentStep
    val stepProgress: StateFlow<Float> = PipelineCompilerService.stepProgress
    val processingLogs: StateFlow<List<String>> = PipelineCompilerService.logs

    private val _isDownloadingVideo = MutableStateFlow(false)
    val isDownloadingVideo: StateFlow<Boolean> = _isDownloadingVideo.asStateFlow()

    // Gallery History (List of session folders containing compiled offline packages)
    private val _dubbedHistory = MutableStateFlow<List<File>>(emptyList())
    val dubbedHistory: StateFlow<List<File>> = _dubbedHistory.asStateFlow()

    // Featured Videos
    private val _featuredVideos = MutableStateFlow<List<FeaturedVideo>>(emptyList())
    val featuredVideos: StateFlow<List<FeaturedVideo>> = _featuredVideos.asStateFlow()

    // Video URL input state (hoisted from Compose local state to survive config changes)
    private val _videoUrl = MutableStateFlow("")
    val videoUrl: StateFlow<String> = _videoUrl.asStateFlow()

    fun setVideoUrl(url: String) {
        _videoUrl.value = url
    }

    private var currentDownloadingPack: String? = null

    private val assetPackStateUpdateListener = AssetPackStateUpdateListener { state ->
        when (state.status()) {
            AssetPackStatus.PENDING, AssetPackStatus.DOWNLOADING -> {
                _isDownloadingGemma.value = true
                val progress = if (state.totalBytesToDownload() > 0) {
                    state.bytesDownloaded().toFloat() / state.totalBytesToDownload()
                } else {
                    0f
                }
                _gemmaDownloadProgress.value = progress
            }
            AssetPackStatus.COMPLETED -> {
                _isDownloadingGemma.value = false
                _gemmaDownloadProgress.value = 1f
                
                val packLocation = assetPackManager.getPackLocation(currentDownloadingPack ?: "ai_models_asset_pack")
                if (packLocation != null) {
                    val assetsPath = packLocation.assetsPath()
                    // Check for either the dummy vision file or the base file
                    val visionFile = File(assetsPath, "models/gemma-4-e2b-vision.bin")
                    val gemmaFile = File(assetsPath, "models/gemma-2b-it-cpu-int4.bin")
                    
                    if (visionFile.exists()) {
                        setGemmaModelPath(visionFile.absolutePath)
                    } else if (gemmaFile.exists()) {
                        setGemmaModelPath(gemmaFile.absolutePath)
                    } else {
                        _gemmaDownloadError.value = "Model file missing in asset pack"
                    }
                }
            }
            AssetPackStatus.FAILED -> {
                _isDownloadingGemma.value = false
                _gemmaDownloadError.value = "Download failed: Error code ${state.errorCode()}"
            }
            AssetPackStatus.CANCELED -> {
                _isDownloadingGemma.value = false
            }
        }
    }

    init {
        assetPackManager.registerListener(assetPackStateUpdateListener)
        refreshHistory()
        fetchFeaturedVideos()
        
        // Auto-extract Whisper model from APK assets to internal storage if missing
        if (!whisperTranscriber.isModelDownloaded()) {
            viewModelScope.launch(Dispatchers.IO) {
                val success = whisperTranscriber.downloadModel { }
                _isWhisperReady.value = success
            }
        }

        // Auto-refresh gallery when processing completes
        viewModelScope.launch {
            isProcessing.collect { processing ->
                if (!processing) {
                    refreshHistory()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        assetPackManager.unregisterListener(assetPackStateUpdateListener)
    }

    fun setMockMode(enabled: Boolean) {
        _mockMode.value = enabled
        sharedPrefs.edit().putBoolean("mock_mode", enabled).apply()
    }

    fun performHardwareCheck(onComplete: () -> Unit) {
        if (_deviceTier.value != DeviceTier.UNKNOWN) {
            onComplete()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            
            // Total RAM in GB
            val totalRamGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            // Using 7.5 as threshold to account for OS memory reservation
            val tier = if (totalRamGB >= 7.5) DeviceTier.PREMIUM else DeviceTier.BUDGET
            
            _deviceTier.value = tier
            sharedPrefs.edit().putString("device_tier", tier.name).apply()
            
            viewModelScope.launch(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun setGemmaModelPath(path: String?) {
        _gemmaModelPath.value = path
        sharedPrefs.edit().putString("gemma_model_path", path).apply()
    }

    fun importGemmaModel(uri: android.net.Uri, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri)
                if (fileName == null || (!fileName.endsWith(".bin", ignoreCase = true) && !fileName.endsWith(".litertlm", ignoreCase = true))) {
                    Log.e("MainScreenViewModel", "Failed to import Gemma: Invalid file extension (expected .bin or .litertlm, got $fileName)")
                    viewModelScope.launch(Dispatchers.Main) { onCompleted(false) }
                    return@launch
                }
                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Null input stream")
                val destinationFile = File(context.filesDir, "gemma-4-E2B-it.litertlm")
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
                setTutorModelPath(destinationFile.absolutePath)
                viewModelScope.launch(Dispatchers.Main) { onCompleted(true) }
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to import Gemma model", e)
                viewModelScope.launch(Dispatchers.Main) { onCompleted(false) }
            }
        }
    }

    fun downloadGemmaFromDrive(fileId: String, onProgress: ((Float, String) -> Unit)? = null, onCompleted: ((Boolean) -> Unit)? = null) {
        _isDownloadingGemma.value = true
        _gemmaDownloadStatus.value = "Connecting to Google Drive..."
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destinationFile = File(context.filesDir, "gemma-4-E2B-it.litertlm")
                if (destinationFile.exists() && destinationFile.length() > 100 * 1024 * 1024) {
                    setGemmaModelPath(destinationFile.absolutePath)
                    setTutorModelPath(destinationFile.absolutePath)
                    _gemmaDownloadProgress.value = 1f
                    _gemmaDownloadStatus.value = "Model already imported locally!"
                    _tutorDownloadProgress.value = 1f
                    _tutorDownloadStatus.value = "Model already imported locally!"
                    _isDownloadingGemma.value = false
                    viewModelScope.launch(Dispatchers.Main) {
                        onProgress?.invoke(1f, "Model already imported locally!")
                        onCompleted?.invoke(true)
                    }
                    return@launch
                }

                val cookieJar = object : okhttp3.CookieJar {
                    private val cookieStore = java.util.HashMap<String, List<okhttp3.Cookie>>()
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        cookieStore[url.host] = cookies
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        return cookieStore[url.host] ?: emptyList()
                    }
                }
                val client = okhttp3.OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .build()
                
                val baseUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                var req = okhttp3.Request.Builder().url(baseUrl).build()
                var res = client.newCall(req).execute()
                
                if (!res.isSuccessful) throw Exception("Failed to connect: ${res.code}")
                
                var downloadRes = res
                val contentType = res.header("Content-Type") ?: ""
                
                if (contentType.contains("text/html")) {
                    val html = res.body?.string() ?: throw Exception("Empty HTML body")
                    val confirmMatch = Regex("name=\"confirm\" value=\"([^\"]+)\"").find(html)
                    val confirmToken = confirmMatch?.groupValues?.get(1) ?: throw Exception("Could not find confirm token. Make sure link is public.")
                    
                    val uuidMatch = Regex("name=\"uuid\" value=\"([^\"]+)\"").find(html)
                    val uuid = uuidMatch?.groupValues?.get(1) ?: ""
                    
                    val finalUrl = if (uuid.isNotEmpty()) {
                        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=$confirmToken&uuid=$uuid"
                    } else {
                        "$baseUrl&confirm=$confirmToken"
                    }
                    
                    req = okhttp3.Request.Builder().url(finalUrl).build()
                    downloadRes = client.newCall(req).execute()
                    if (!downloadRes.isSuccessful) throw Exception("Failed to download with token: ${downloadRes.code}")
                }
                
                if (destinationFile.exists()) destinationFile.delete()
                
                val body = downloadRes.body ?: throw Exception("Null body")
                val contentLength = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192 * 4)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastProgressUpdate = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 500) {
                                lastProgressUpdate = now
                                val mbRead = totalRead / (1024 * 1024)
                                val mbTotal = if (contentLength > 0) contentLength / (1024 * 1024) else 0
                                viewModelScope.launch(Dispatchers.Main) {
                                    if (mbTotal > 0) {
                                        val pct = mbRead.toFloat() / mbTotal.toFloat()
                                        _gemmaDownloadProgress.value = pct
                                        _gemmaDownloadStatus.value = "Downloading: $mbRead MB / $mbTotal MB"
                                        onProgress?.invoke(pct, "Downloading: $mbRead MB / $mbTotal MB")
                                    } else {
                                        val assumedTotal = 2600f // 2.6GB fallback
                                        val pct = (mbRead.toFloat() / assumedTotal).coerceAtMost(0.99f)
                                        _gemmaDownloadProgress.value = pct
                                        _gemmaDownloadStatus.value = "Downloading: $mbRead MB..."
                                        onProgress?.invoke(pct, "Downloading: $mbRead MB...")
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                
                setGemmaModelPath(destinationFile.absolutePath)
                setTutorModelPath(destinationFile.absolutePath)
                _gemmaDownloadProgress.value = 1f
                _gemmaDownloadStatus.value = "Gemma model downloaded and imported!"
                _isDownloadingGemma.value = false
                viewModelScope.launch(Dispatchers.Main) { onCompleted?.invoke(true) }
                
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to auto-download Gemma", e)
                _isDownloadingGemma.value = false
                _gemmaDownloadStatus.value = "Failed to download from Drive."
                viewModelScope.launch(Dispatchers.Main) { onCompleted?.invoke(false) }
            }
        }
    }

    fun downloadTutorFromDrive(fileId: String, onProgress: ((Float, String) -> Unit)? = null, onCompleted: ((Boolean) -> Unit)? = null) {
        _isDownloadingTutor.value = true
        _tutorDownloadStatus.value = "Connecting to Google Drive..."
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destinationFile = File(context.filesDir, "gemma-4-E2B-it.litertlm")
                if (destinationFile.exists() && destinationFile.length() > 100 * 1024 * 1024) {
                    setGemmaModelPath(destinationFile.absolutePath)
                    setTutorModelPath(destinationFile.absolutePath)
                    _gemmaDownloadProgress.value = 1f
                    _gemmaDownloadStatus.value = "Model already imported locally!"
                    _tutorDownloadProgress.value = 1f
                    _tutorDownloadStatus.value = "Model already imported locally!"
                    _isDownloadingTutor.value = false
                    viewModelScope.launch(Dispatchers.Main) {
                        onProgress?.invoke(1f, "Model already imported locally!")
                        onCompleted?.invoke(true)
                    }
                    return@launch
                }

                val cookieJar = object : okhttp3.CookieJar {
                    private val cookieStore = java.util.HashMap<String, List<okhttp3.Cookie>>()
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        cookieStore[url.host] = cookies
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        return cookieStore[url.host] ?: emptyList()
                    }
                }
                val client = okhttp3.OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .build()
                
                val baseUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                var req = okhttp3.Request.Builder().url(baseUrl).build()
                var res = client.newCall(req).execute()
                
                if (!res.isSuccessful) throw Exception("Failed to connect: ${res.code}")
                
                var downloadRes = res
                val contentType = res.header("Content-Type") ?: ""
                
                if (contentType.contains("text/html")) {
                    val html = res.body?.string() ?: throw Exception("Empty HTML body")
                    val confirmMatch = Regex("name=\"confirm\" value=\"([^\"]+)\"").find(html)
                    val confirmToken = confirmMatch?.groupValues?.get(1) ?: throw Exception("Could not find confirm token. Make sure link is public.")
                    
                    val uuidMatch = Regex("name=\"uuid\" value=\"([^\"]+)\"").find(html)
                    val uuid = uuidMatch?.groupValues?.get(1) ?: ""
                    
                    val finalUrl = if (uuid.isNotEmpty()) {
                        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=$confirmToken&uuid=$uuid"
                    } else {
                        "$baseUrl&confirm=$confirmToken"
                    }
                    
                    req = okhttp3.Request.Builder().url(finalUrl).build()
                    downloadRes = client.newCall(req).execute()
                    if (!downloadRes.isSuccessful) throw Exception("Failed to download with token: ${downloadRes.code}")
                }
                
                if (destinationFile.exists()) destinationFile.delete()
                
                val body = downloadRes.body ?: throw Exception("Null body")
                val contentLength = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192 * 4)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastProgressUpdate = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 500) {
                                lastProgressUpdate = now
                                val mbRead = totalRead / (1024 * 1024)
                                val mbTotal = if (contentLength > 0) contentLength / (1024 * 1024) else 0
                                viewModelScope.launch(Dispatchers.Main) {
                                    if (mbTotal > 0) {
                                        val pct = mbRead.toFloat() / mbTotal.toFloat()
                                        _tutorDownloadProgress.value = pct
                                        _tutorDownloadStatus.value = "Downloading: $mbRead MB / $mbTotal MB"
                                        onProgress?.invoke(pct, "Downloading: $mbRead MB / $mbTotal MB")
                                    } else {
                                        val assumedTotal = 2600f // consolidated Gemma model is ~2.6GB/2.8GB
                                        val pct = (mbRead.toFloat() / assumedTotal).coerceAtMost(0.99f)
                                        _tutorDownloadProgress.value = pct
                                        _tutorDownloadStatus.value = "Downloading: $mbRead MB..."
                                        onProgress?.invoke(pct, "Downloading: $mbRead MB...")
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                
                setGemmaModelPath(destinationFile.absolutePath)
                setTutorModelPath(destinationFile.absolutePath)
                _gemmaDownloadProgress.value = 1f
                _gemmaDownloadStatus.value = "Gemma model downloaded and imported!"
                _tutorDownloadProgress.value = 1f
                _tutorDownloadStatus.value = "Tutor model downloaded and imported!"
                _isDownloadingTutor.value = false
                viewModelScope.launch(Dispatchers.Main) { onCompleted?.invoke(true) }
                
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to auto-download Tutor", e)
                _isDownloadingTutor.value = false
                _tutorDownloadStatus.value = "Failed to download from Drive."
                viewModelScope.launch(Dispatchers.Main) { onCompleted?.invoke(false) }
            }
        }
    }

    fun setTutorModelPath(path: String?) {
        _tutorModelPath.value = path
        sharedPrefs.edit().putString("tutor_model_path", path).apply()
    }

    fun importTutorModel(uri: android.net.Uri, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri)
                if (fileName == null || (!fileName.endsWith(".bin", ignoreCase = true) && !fileName.endsWith(".litertlm", ignoreCase = true))) {
                    Log.e("MainScreenViewModel", "Failed to import Tutor: Invalid file extension (expected .bin or .litertlm, got $fileName)")
                    viewModelScope.launch(Dispatchers.Main) { onCompleted(false) }
                    return@launch
                }
                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Null input stream")
                val destinationFile = File(context.filesDir, "gemma-4-E2B-it.litertlm")
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
                setTutorModelPath(destinationFile.absolutePath)
                viewModelScope.launch(Dispatchers.Main) { onCompleted(true) }
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to import Tutor model", e)
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

    fun fetchFeaturedVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            // Connecting to the production backend
            val backendUrl = "https://production.nua.org/api/v1/featured"
            
            try {
                val request = Request.Builder().url(backendUrl).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<FeaturedVideo>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            list.add(FeaturedVideo(
                                id = obj.optString("id"),
                                title = obj.optString("title"),
                                thumbnailUrl = obj.optString("thumbnailUrl"),
                                videoUrl = obj.optString("videoUrl")
                            ))
                        }
                        _featuredVideos.value = list
                    } else {
                        throw Exception("HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to fetch featured videos, falling back to mock", e)
                // Fallback to mock data for demonstration purposes if backend is unreachable
                _featuredVideos.value = listOf(
                    FeaturedVideo(
                        id = "demo1",
                        title = "MIT 18.06 Linear Algebra - Lecture 1",
                        thumbnailUrl = "https://images.unsplash.com/photo-1635070041078-e363dbe005cb?w=800&q=80",
                        videoUrl = "https://archive.org/download/MIT18.06S05_MP4/01.ia.mp4"
                    ),
                    FeaturedVideo(
                        id = "demo2",
                        title = "MIT 18.06 Linear Algebra - Lecture 2",
                        thumbnailUrl = "https://images.unsplash.com/photo-1509228468518-180dd4864904?w=800&q=80",
                        videoUrl = "https://archive.org/download/MIT18.06S05_MP4/02.ia.mp4"
                    )
                )
            }
        }
    }

    fun deleteSession(sessionDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.deleteSession(sessionDir)
            refreshHistory()
        }
    }

    fun downloadPremiumAIEngine(packName: String = "ai_models_asset_pack") {
        if (_isDownloadingGemma.value) return
        _gemmaDownloadError.value = null
        currentDownloadingPack = packName
        assetPackManager.fetch(listOf(packName))
    }

    fun startDubbingLocalUri(uri: android.net.Uri) {
        if (isProcessing.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                PipelineCompilerService.clearLogs()
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
        if (isProcessing.value || _isDownloadingVideo.value) return
        _isDownloadingVideo.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val tempVideoFile = File(context.cacheDir, "downloaded_temp.mp4")

            try {
                PipelineCompilerService.clearLogs()
                val cleanUrl = url.trim().let { if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it }
                
                // Determine if we need to resolve via cloud proxy (YouTube, Web pages, etc.)
                var resolvedUrl = cleanUrl
                val isWebOrYoutubeUrl = cleanUrl.contains("youtube.com") || 
                                        cleanUrl.contains("youtu.be") || 
                                        cleanUrl.contains("facebook.com") || 
                                        cleanUrl.contains("vimeo.com") || 
                                        !cleanUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)

                if (isWebOrYoutubeUrl) {
                    PipelineCompilerService.addLog("🔍 Resolving video stream URL via cloud proxy...")
                    val resolveUrl = "https://production.nua.org/api/v1/resolve-video?url=" + java.net.URLEncoder.encode(cleanUrl, "UTF-8")
                    val resolveRequest = Request.Builder().url(resolveUrl).build()
                    try {
                        client.newCall(resolveRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                val json = org.json.JSONObject(body)
                                if (json.optString("status") == "OK") {
                                    resolvedUrl = json.getString("videoUrl")
                                    PipelineCompilerService.addLog("✅ Video stream URL resolved successfully!")
                                } else {
                                    val errorMsg = json.optString("message", "Unknown error")
                                    PipelineCompilerService.addLog("⚠️ Cloud resolver error: $errorMsg. Attempting direct fallback...")
                                }
                            } else {
                                PipelineCompilerService.addLog("⚠️ Cloud resolver failed: HTTP ${response.code}. Attempting direct fallback...")
                            }
                        }
                    } catch (resolveEx: Exception) {
                        Log.e("MainScreenViewModel", "URL resolve proxy call error", resolveEx)
                        PipelineCompilerService.addLog("⚠️ Cloud resolver connection error. Attempting direct fallback...")
                    }
                }

                val request = Request.Builder().url(resolvedUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }

                    val contentType = response.header("Content-Type") ?: ""
                    val isHtml = contentType.contains("text/html", ignoreCase = true)
                    val isVideo = contentType.contains("video/", ignoreCase = true)
                    val endsWithMp4 = resolvedUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)

                    if (isHtml || (!isVideo && !endsWithMp4)) {
                        throw Exception("Received webpage content instead of video stream. Please ensure the URL is a direct MP4 link or that the cloud proxy is reachable.")
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
                PipelineCompilerService.addLog("❌ Direct download failed: ${e.message}")
                viewModelScope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Download failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                kotlinx.coroutines.delay(3000)
            } finally {
                _isDownloadingVideo.value = false
            }
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("MainScreenViewModel", "Failed to query display name for URI: $uri", e)
        }
        return name
    }
}
