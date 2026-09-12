package org.nua.assess

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Local-only host for the shared assessment UI. No analytics, cloud or accounts. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val inferenceActive = AtomicBoolean(false)
    private var engine: Engine? = null
    @Volatile private var ready = false
    @Volatile private var modelStatus = "No model imported · built-in hints ready"
    private var pickerRequest: String? = null
    private var exportText: String? = null
    private val modelFile get() = File(filesDir, "assessment.litertlm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(), "NuaNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                return loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "Blocked".byteInputStream())
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        setContentView(web)
        // Keep controls below system bars on edge-to-edge Android versions.
        web.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        worker.execute {
            try { ModelFiles.recover(modelFile); if (modelFile.exists()) loadModel() }
            catch (_: Exception) { modelStatus = "Previous model recovery failed. Retain your original model file." }
        }
    }

    private fun loadModel() {
        ready = false
        modelStatus = "Loading local model…"
        engine?.close(); engine = null
        try {
            val candidate = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU(), maxNumTokens = 1024))
            try { candidate.initialize(); engine = candidate; ready = true; modelStatus = "On-device AI · imported LiteRT-LM model" }
            catch (e: Exception) { candidate.close(); throw e }
        } catch (_: Exception) { modelStatus = "Model could not load · check compatibility and device memory" }
    }

    private fun reply(id: String, value: JSONObject) {
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript("window.nuaReply(${JSONObject.quote(id)},$value)", null) }
    }
    private fun error(id: String, message: String) = reply(id, JSONObject().put("error", message))

    inner class Bridge {
        @JavascriptInterface fun request(id: String, method: String, raw: String) {
            if (!id.matches(Regex("[0-9]{1,12}")) || raw.length > 250000) return
            val p = try { JSONObject(raw) } catch (_: Exception) { error(id, "Invalid request."); return }
            when (method) {
                "status" -> reply(id, JSONObject().put("available", ready).put("label", modelStatus))
                "hint" -> {
                    if (!inferenceActive.compareAndSet(false, true)) { error(id, "A local hint is already running."); return }
                    worker.execute {
                    try {
                    val q = p.optString("question")
                    val language = p.optString("language")
                    if (p.optString("task") != "guided" || q.trim().length !in 3..400 || language !in listOf("en", "hi")) {
                        error(id, "Invalid guided hint request."); return@execute
                    }
                    val current = engine
                    if (!ready || current == null) { error(id, "Import a compatible local model in Settings first."); return@execute }
                    // Only the bundled UI can invoke this bridge. It constructs the
                    // same versioned prompt used by the desktop adapter.
                    val prompt = p.optString("prompt")
                    if (prompt.length !in 100..6000) { error(id, "Missing shared hint instructions."); return@execute }
                    try {
                        val text = current.createConversation().use { conversation ->
                            val timedOut = AtomicBoolean(false)
                            val finished = AtomicBoolean(false)
                            val lock = Any()
                            val timeout = deadlines.schedule({ synchronized(lock) {
                                if (!finished.get()) { timedOut.set(true); conversation.cancelProcess() }
                            } }, 60, TimeUnit.SECONDS)
                            try {
                                val result = conversation.sendMessage(prompt).toString()
                                check(!timedOut.get()) { "Local model timed out." }
                                result
                            } finally { synchronized(lock) { finished.set(true); timeout.cancel(false) } }
                        }
                            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim().take(900)
                        if (text.isEmpty()) error(id, "Model returned no usable hint.")
                        else reply(id, JSONObject().put("text", text).put("model", "imported LiteRT-LM · CPU").put("source", "android-local"))
                    } catch (_: Exception) { error(id, "Local inference failed. Use a built-in prompt.") }
                    } finally { inferenceActive.set(false) }
                    }
                }
                "import", "export" -> runOnUiThread {
                    if (pickerRequest != null) { error(id, "A file picker is already open."); return@runOnUiThread }
                    pickerRequest = id
                    val exporting = method == "export"
                    exportText = if (exporting) p.optString("text") else null
                    val intent = Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (exporting) "application/json" else "*/*"
                        if (exporting) putExtra(Intent.EXTRA_TITLE, p.optString("name", "nua-evidence.json").replace(Regex("[^a-zA-Z0-9.-]"), "_"))
                    }
                    try { startActivityForResult(intent, if (exporting) 102 else 101) }
                    catch (_: Exception) { pickerRequest = null; error(id, "No document picker is available.") }
                }
                else -> error(id, "Unknown operation.")
            }
        }
    }

    @Deprecated("Activity result bridge kept dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pickerRequest ?: return
        pickerRequest = null
        val uri: Uri = data?.data ?: run { error(id, "File selection cancelled."); return }
        if (resultCode != RESULT_OK) { error(id, "File selection cancelled."); return }
        val output = exportText; exportText = null
        worker.execute {
            try {
                if (requestCode == 102) {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write((output ?: "{}").toByteArray()) }
                    reply(id, JSONObject().put("saved", true))
                } else {
                    val temp = File(filesDir, "model-import.tmp")
                    try {
                        contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { target ->
                            val buffer = ByteArray(65536); var total = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 2500000000L) { "Model exceeds the 2.5 GB import limit." }; target.write(buffer, 0, n) }
                            require(total > 1024) { "Model file is too small." }
                        } }
                        ready = false; engine?.close(); engine = null
                        try {
                            ModelFiles.replace(temp, modelFile) { loadModel(); ready }
                            reply(id, JSONObject().put("imported", true))
                        } catch (failure: Exception) {
                            if (modelFile.exists()) loadModel()
                            error(id, "Import failed. " + if (ready) "Previous model restored." else failure.message)
                        }
                    } finally { temp.delete() }
                }
            } catch (e: Exception) { error(id, e.message ?: "File operation failed.") }
        }
    }
    override fun onDestroy() {
        web.removeJavascriptInterface("NuaNative"); web.destroy()
        worker.execute { engine?.close(); engine = null; deadlines.shutdown() }; worker.shutdown()
        super.onDestroy()
    }
}
