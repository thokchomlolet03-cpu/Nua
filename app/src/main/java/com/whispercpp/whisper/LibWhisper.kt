package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(@Volatile private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope: CoroutineScope = CoroutineScope(
        executor.asCoroutineDispatcher()
    )

    suspend fun transcribeDataWithTokens(
        data: FloatArray,
        prevTokens: IntArray?,
        tokenCount: Int,
        printTimestamp: Boolean = true
    ): Pair<String, IntArray> = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        LibWhisper.fullTranscribeWithTokens(ptr, numThreads, data, prevTokens, tokenCount)
        val textCount = LibWhisper.getTextSegmentCount(ptr)
        
        val newTokensList = mutableListOf<Int>()
        for (i in 0 until textCount) {
            val segmentTokens = LibWhisper.getTextSegmentTokens(ptr, i)
            newTokensList.addAll(segmentTokens.toList())
        }
        
        val transcription = buildString {
            for (i in 0 until textCount) {
                if (printTimestamp) {
                    val textTimestamp = "[${toTimestamp(LibWhisper.getTextSegmentT0(ptr, i))} --> ${toTimestamp(LibWhisper.getTextSegmentT1(ptr, i))}]"
                    val textSegment = LibWhisper.getTextSegment(ptr, i)
                    append("$textTimestamp: $textSegment\n")
                } else {
                    append(LibWhisper.getTextSegment(ptr, i))
                }
            }
        }
        
        return@withContext Pair(transcription, newTokensList.toIntArray())
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext LibWhisper.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext LibWhisper.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            LibWhisper.freeContext(ptr)
            ptr = 0
        }
        executor.shutdown()
    }

    // NOTE: finalize() intentionally removed. Calling LibWhisper.freeContext()
    // on the JVM finalizer thread races with the executor-bound release() method
    // and can hang the finalizer thread permanently. Always call release() explicitly.

    companion object {
        fun detectBestBackend(context: android.content.Context): HardwareBackend {
            val supportsNnapi = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1
            val supportsVulkan = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            
            val backend = when {
                supportsNnapi -> HardwareBackend.NNAPI
                supportsVulkan -> HardwareBackend.VULKAN
                else -> HardwareBackend.CPU
            }
            Log.i(LOG_TAG, "ASR Prioritization: NNAPI=$supportsNnapi, Vulkan=$supportsVulkan. Selected Backend: $backend")
            return backend
        }

        fun createContextFromFile(context: android.content.Context, filePath: String): WhisperContext {
            val backend = detectBestBackend(context)
            val useGpu = (backend == HardwareBackend.NNAPI || backend == HardwareBackend.VULKAN)
            val ptr = LibWhisper.initContext(filePath, useGpu)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(context: android.content.Context, stream: InputStream): WhisperContext {
            val backend = detectBestBackend(context)
            val useGpu = (backend == HardwareBackend.NNAPI || backend == HardwareBackend.VULKAN)
            val ptr = LibWhisper.initContextFromInputStream(stream, useGpu)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(context: android.content.Context, assetManager: AssetManager, assetPath: String): WhisperContext {
            val backend = detectBestBackend(context)
            val useGpu = (backend == HardwareBackend.NNAPI || backend == HardwareBackend.VULKAN)
            val ptr = LibWhisper.initContextFromAsset(assetManager, assetPath, useGpu)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return LibWhisper.getSystemInfo()
        }
        
        fun nativeResample(inputSamples: ShortArray, sourceSampleRate: Int): FloatArray {
            return LibWhisper.nativeResample(inputSamples, sourceSampleRate)
        }
        
        fun nativeResampleDirect(inputSamples: ShortArray, sourceSampleRate: Int, outBuf: java.nio.ByteBuffer): Int {
            return LibWhisper.nativeResampleDirect(inputSamples, sourceSampleRate, outBuf)
        }
    }
}

enum class HardwareBackend {
    NNAPI,
    VULKAN,
    CPU
}

private class LibWhisper {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream, useGpu: Boolean): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String, useGpu: Boolean): Long
        external fun initContext(modelPath: String, useGpu: Boolean): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun fullTranscribeWithTokens(contextPtr: Long, numThreads: Int, audioData: FloatArray, prevTokens: IntArray?, tokenCount: Int): Int
        external fun nativeResample(inputSamples: ShortArray, sourceSampleRate: Int): FloatArray
        @JvmStatic
        external fun nativeResampleDirect(inputSamples: ShortArray, sourceSampleRate: Int, outBuf: java.nio.ByteBuffer): Int
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentTokens(contextPtr: Long, index: Int): IntArray
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

//  500 -> 00:05.000
// 6000 -> 01:00.000
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}