# Nua Software Ecosystem — Complete Deep Technical Analysis & Specification Report

> **Ecosystem Version**: 4.3 (Stabilized Production Release)  
> **Target Audience**: Low-connectivity educational environments (regional India)  
> **Ecosystem Boundary**: Hybrid cloud-edge system (Nua Web Studio cloud compiler & Nua Edge on-device playback + local compiler)

---

## 1. Executive Summary & Architectural Invariants

The **Project Nua Ecosystem** is a production-grade educational translation platform designed to run in regional India, delivering high-fidelity Hinglish lecture translations completely offline on mid-to-low-tier mobile hardware. 

### Core System Philosophy
1. **Zero-Transcoding Playback**: The source lecture video file is never modified or re-encoded. Alignment happens during playback by synchronizing an muted video player with synthesized voice assets using an elastic virtual timeline.
2. **Offline-First Resilience**: Once a `.nuab` bundle and associated audio media assets are downloaded, the client does not require any network connectivity for playback, subtitle rendering, term translation, AI tutoring, or telemetry logging.
3. **FlatBuffers Serialization**: JSON is completely eliminated from the runtime path, replaced by FlatBuffers binary payloads for memory-mapped, zero-copy deserialization on mobile.
4. **Gradle Isolation**: The client code exists under `:app`. The Node.js cloud backend exists under `backend/`. Both modules share a single FlatBuffers schema contract (`schema/nua_schema.fbs`) compiled on-demand.

---

## 2. FlatBuffers Binary Contract (`schema/nua_schema.fbs`)

The schema defines the binary serialization format for `.nuab` bundles and telemetry payloads. 

### 2.1 Complete Schema Definition
```protobuf
// Nua FlatBuffers Schema — v4.0
namespace NuaSerialization;

file_identifier "NUAB";

table Hotspot {
  token:string;
  concept_definition:string;
}

table Quiz {
  trigger_timestamp_ms:ulong;
  question:string;
  options:[string];
  correct_index:ubyte;
}

table GraphNode {
  node_id:string;
  keywords:[string];
  summary_factoid:string;
  context_tokens:[string];
  idf_tokens:[string];
  idf_values:[float];
}

table OptionSelection {
  quiz_id:string;
  selected_option_index:ubyte;
  latency_ms:uint;
  is_correct:bool;
}

table TelemetryPayload {
  session_id:string;
  completion_percentage:ubyte;
  quiz_responses:[OptionSelection];
  cryptographic_signature:string;
}

table TimeSegment {
  segment_id:string;
  video_start_ms:uint;
  video_end_ms:uint;
  audio_source_path:string;
  audio_duration_ms:uint;
  original_text:string;
  translated_text:string;
  directive:string;
  hotspots:[Hotspot];
}

table LectureSession {
  schema_version:ushort = 1;
  session_id:string;
  source_lang:string;
  target_lang:string;
  course_title:string (deprecated);
  source_video_path:string;
  timeline_tracks:[TimeSegment];
  quizzes:[Quiz];
  knowledge_graph:[GraphNode];
  telemetry_ledger:[TelemetryPayload];
}

root_type LectureSession;
```

### 2.2 Table-by-Table Architectural Analysis

#### `LectureSession` (Root Type)
- **`schema_version:ushort`**: Explicit version indicator (defaults to `1`), allowing the Android client to block older payload formats.
- **`course_title:string` (Deprecated)**: Retained for backward-compatibility; old sessions fall back to this if `source_video_path` is null.
- **`source_video_path:string`**: Relocated path string pointing to the video asset.
- **Vectors**: Serializes tracks, quizzes, the tutoring knowledge graph, and client-collected telemetry.

#### `TimeSegment`
- **`video_start_ms` & `video_end_ms`**: Bound timestamps of the original segment.
- **`audio_duration_ms`**: Spoken duration of the translated vocal audio segment (often larger than original).
- **`directive:string`**: Playback control instructions, e.g., `NORMAL_SYNC`, `FREEZE_HOLD`, or `PAD_EMPTY` (for silent failures).
- **`hotspots:[Hotspot]`**: Vector of interactive vocabulary terms in this segment.

#### `GraphNode`
- **Tutoring Index**: Pre-bakes keywords and summary factoids.
- **`idf_tokens:[string]` & `idf_values:[float]`**: Pre-baked TF-IDF weighting lookup maps. Eliminates the need to compute term frequencies at runtime on mobile.

#### `TelemetryPayload` & `OptionSelection`
- **Type Safety**: Quiz selections are saved as structured `OptionSelection` structures containing correctness flags and millisecond latencies, avoiding the overhead of JSON parsing.

---

## 3. Hand-Written FlatBuffers Bindings (`NuaSchema.kt`)

Rather than relying on `flatc` compiler binaries, Nua uses a custom Kotlin FlatBuffers wrapper at `app/src/main/java/org/nua/production/app/data/schema/NuaSchema.kt` (481 lines) to optimize memory mapping and object allocation.

### Key Implementation Mechanisms
- **Zero-Copy Accessors**: All accessor classes (`LectureSession`, `TimeSegment`, `Hotspot`, etc.) extend the base `Table` class.
- **Object Recycling**: Implements `__reset(offset, byteBuffer)` to reuse a single instance during list iterations, completely eliminating garbage collection allocation churn.
- **Unsigned Math**: FlatBuffers unsigned types are converted safely via bitwise operations (e.g., `(bb.getInt(offset) and 0xFFFFFFFFL)` to convert `uint` to `Long`).
- **Signature Alignment**: Custom `LectureSession.finishLectureSessionBuffer` writes the `NUAB` file identifier at byte index 4 to 7.

---

## 4. Android Client (Nua Edge) Data Subsystem

The data subsystem handles media decoding, synchronization, ASR, translation, speech synthesis, telemetry, and tutoring.

### 4.1 Audio Decoder & Resampler (`AudioDecoder.kt` — 357 lines)
Decodes audio tracks from raw lecture videos and slices them into discrete, silence-aligned blocks for transcription.

#### Key Pipelines & Concurrency
- **Track Selection**: Loops through the video's media formats via `MediaExtractor` to locate the first `audio/` mime track.
- **Concurrent Execution**: Launches a downstream `slicingWorkerLoop` coroutine on `Dispatchers.Default` that consumes audio concurrently while `MediaCodec` continues demuxing.
- **Mono Downmixing**: Stereo channels are downmixed in-place on the output buffer using rounded integer arithmetic: `persistentTransferBuffer[i] = ((left + right) / 2).toShort()`.
- **Throttling Backpressure**: To prevent memory saturation during high-throughput decoding, the decoder thread yields via a `delay(50)` loop if the linear accumulator grows beyond `40 * sourceSampleRate` samples.

#### Silence-Aligned VAD Slicing Algorithm
Unlike standard ASR decoders that cut audio at arbitrary 30-second bounds, `AudioDecoder` searches for natural speech silence within a 25-to-35 second window:
```kotlin
val minSamples = CHUNK_MIN_SEC * sourceSampleRate
val maxSamples = CHUNK_MAX_SEC * sourceSampleRate
var lastSilenceIdx = -1

for (i in minSamples until min(maxSamples, linearAccumulator.size) step windowSamples) {
    var energySq = 0.0f
    val end = min(i + windowSamples, linearAccumulator.size)
    for (j in i until end) {
        val s = linearAccumulator.buffer[j].toFloat()
        energySq += s * s
    }
    val rms = sqrt(energySq / (end - i))
    if (rms < VAD_ENERGY_THRESHOLD) {
        lastSilenceIdx = end
    }
}
```
If a silence window matching `VAD_ENERGY_THRESHOLD` (500.0f) is discovered, the audio slice is truncated cleanly at that index. If no silence is found, it falls back to a hard slice at 30 seconds. This prevents JNI Whisper from splitting mid-syllable, which causes word error rate spikes.

#### Resampling Integration
Each sliced audio chunk is resampled to 16kHz on `Dispatchers.Default` via:
`val antiAliasedFloats = WhisperContext.nativeResample(sliceToProcess, sourceSampleRate)`
The floating-point samples are packaged into `AudioChunk` structures and pushed into the transcription `audioChannel`.

---

### 4.2 Foreground Compiler Service (`PipelineCompilerService.kt` — 444 lines)
Orchestrates the offline 5-stage compilation pipeline: Import Video → Extract Audio & Transcribe → Translate → Synthesis (TTS) → Serialize Manifest.

#### Lifecycle & Concurrency
- **Foreground Sticky Service**: Declared with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` to prevent Android OS termination during active compilation.
- **Safe Coroutine Scoping**: Structured under a dedicated `SupervisorJob()` and `Main` coroutine scope. All background stages run sequentially using the main service scope.
- **Asymptotic UI Progress Flow**: Because local Whisper ASR speeds vary by hardware, the service updates the progress bar during transcription using a time-decaying asymptotic function:
  `progressPct = 0.1f + 0.85f * (1.0f - exp(-2.4 * (elapsed / T)))`
  This updates progress dynamically and clamps it at 95% until transcription completes.
- **Failsafe Channel Closure**: To prevent deadlocks when ASR failures occur, a concurrent watcher coroutine listens for transcription errors and closes the channel to unblock the `AudioDecoder`:
  ```kotlin
  val failsafeJob = launch {
      val result = transcriptionJob.await()
      if (result.isFailure) audioChannel.close()
  }
  ```

#### Battery-Safeguarded WorkManager Loop
To protect the device against battery exhaustion, the service monitors power status at semantic boundaries:
```kotlin
private fun checkBatteryAndPersist(sessionDir: File, stage: String): Boolean {
    File(sessionDir, "pipeline_state.json").writeText("{\"stage\":\"$stage\"}")
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val batteryStatus = registerReceiver(null, filter)
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPct = level * 100 / scale.toFloat()
    
    if (batteryPct > 0 && batteryPct < 15.0f) {
        // Enqueue WorkManager to resume when charging or battery normalizes
        val workRequest = OneTimeWorkRequestBuilder<PipelineResumeWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("PipelineResume", ExistingWorkPolicy.REPLACE, workRequest)
        return false
    }
    return true
}
```
If battery drops below 15%, the service serializes its current state to `pipeline_state.json` and halts execution.

---

### 4.3 Elastic Dual-Player Sync Engine (`SyncPlayerEngine.kt` — 424 lines)
Synchronizes muted video playback (`videoPlayer`) with localized synthesized audio voice chunks (`audioPlayer`).

#### Playback Synchronization Strategy
- **Zone 1: Clock Skewing (drift 1–800ms)**: Adjusts the speed of the video player using ExoPlayer's native Sonic algorithm (which uses Sinusoidal Overlap-Add for pitch-invariant time-stretching). The video player's speed is scaled down dynamically (minimum 0.80x) via:
  `val videoScalingRatio = nativeSegmentDurationMs / targetAudioDurationMs`
  This allows the video to slow down to match a longer Hindi translation, keeping the audio at 1.0x pitch.
- **Zone 2: Hard Freeze (drift > 800ms)**: The video player is paused while the audio player continues. The audio position drives the virtual timeline. When the audio segment completes, the video unfreezes:
  ```kotlin
  if (isCurrentChunkFinished) {
      virtualTimeMs = interval.virtualEndMs + 1
      handleAudioSegmentComplete()
  }
  ```
- **Zone 3: Soft Ducking**: During active dubbed segments, the video player's volume is set to `0.01f` to keep the background room tone audible, then restored to `1.0f` during gaps.

#### Temporal Frustum Culling
To prevent ExoPlayer from overloading memory with too many active audio files, the engine only prepares audio files that are within a 2-minute sliding window of the current virtual playhead:
`val dist = abs(interval.virtualStartMs - virtualTimeMs)`
If the distance is greater than 120,000ms, the audio asset is unloaded.

---

### 4.4 Virtual Timeline Mapper (`VirtualTimelineMapper.kt` — 200 lines)
Maintains the mapping between the physical video timeline and the virtual playback timeline (which is expanded by audio holds).

```
Physical Timeline (Video): |====SEG1====|-------gap-------|====SEG2====|
                           0            5s                8s           12s

Virtual Timeline (Player): |====SEG1====|──HOLD──|-------gap-------|====SEG2====|──HOLD──|
                           0            5s       7s                10s          14s      16s
```

#### Mapping Algorithms
- **WAV Duration Parsing**: Instead of relying on metadata, `VirtualTimelineMapper` reads the standard 44-byte WAV header on-demand to compute duration:
  `vocalDur = (fileSize - 44) * 1000 / (sampleRate * channels * bytesPerSample)`
- **$O(\log n)$ Binary Search Mapping**: Linear scans are replaced with binary searches over pre-sorted start position arrays to translate timestamps:
  ```kotlin
  fun getVirtualTimeMs(physicalTimeMs: Long): Long {
      var lo = 0
      var hi = physicalStartPositions.size - 1
      var idx = -1
      while (lo <= hi) {
          val mid = (lo + hi) ushr 1
          if (physicalStartPositions[mid] <= physicalTimeMs) {
              idx = mid
              lo = mid + 1
          } else {
              hi = mid - 1
          }
      }
      if (idx < 0) return physicalTimeMs
      val interval = intervals[idx]
      return if (physicalTimeMs <= interval.originalEndMs) {
          interval.virtualStartMs + (physicalTimeMs - interval.originalStartMs)
      } else {
          physicalTimeMs + interval.cumulativeHoldBeforeMs + interval.holdMs
      }
  }
  ```

---

### 4.5 Offline Whisper ASR (`WhisperTranscriber.kt` — 201 lines)
Wraps native C++ Whisper.cpp libraries.

#### Pipeline Flow
- **Single Context Restriction**: To prevent memory thrashing on low-end hardware, only one `WhisperContext` instance is active at a time:
  `private var activeContext: WhisperContext? = null`
- **Syllable Density Assessment**: The transcriber begins with the INT8-quantized `ggml-tiny.en-q8_0.bin` model. After processing the first audio chunk, it checks text density:
  `if (textLength < audioDuration * 2)`
  If the length is abnormally low, the transcriber automatically releases the tiny context and upgrades to `ggml-base.en-q8_0.bin` for better accuracy.
- **Context Preservation**: A sliding window of up to 224 tokens is retained between chunks to maintain context across segments.

---

### 4.6 LiteRT Translator & Model Lifecycle (`LiteRTTranslator.kt` — 305 lines)
Uses Google's LiteRT-LM (AI Edge) for on-device translation.

#### Architecture
- **Inference Concurrency**: A `translationMutex` guards translation requests, protecting the native LiteRT engine from concurrent access crashes.
- **Mock Translation Dictionary**: A hardcoded 18-word fallback dictionary handles simple syntax mappings if the model is not loaded.
- **Model Lifecycle Management (`ModelLifecycleManager.kt`)**: Coordinates loading and unloading of translation and tutoring models. Unloads the translation model before loading the tutor model to prevent out-of-memory errors on 4GB RAM devices.

---

### 4.7 Dubbing TTS Engine (`DubbingTtsEngine.kt` — 195 lines)
Synthesizes Hinglish translations into localized Hindi vocal files using the system's native `TextToSpeech` API.

#### Syllable-Density Duration Analyzer
To prevent synthesized audio from running excessively long, the engine estimates duration before synthesizing:
```kotlin
fun estimatePhoneticDurationMs(text: String, baseSpeechRate: Float = 1.0f): Long {
    val devanagariVowels = listOf('\u093E', '\u093F', '\u0940', '\u0941', '\u0942', '\u0947', '\u0948', '\u094B', '\u094C', '\u0902')
    val syllableCount = text.count { it in devanagariVowels } + (text.length / 3)
    val baselineWordsPerMinute = 135.0f * baseSpeechRate
    val wordCount = text.split("\\s+".toRegex()).size.coerceAtLeast(1)
    return ((wordCount / baselineWordsPerMinute) * 60.0f * 1000.0f).toLong() + (syllableCount * 45L)
}
```
If the estimate exceeds the video segment's bounds, the TTS speed is adjusted dynamically (up to 2.0x):
`currentRate = (estimatedDurationSec / targetDurationSeconds).toFloat().coerceIn(1.0f, 2.0f)`

---

### 4.8 Offline RAG Tutor & Tool Execution (`OfflineTutorEngine.kt` — 301 lines)
Allows students to ask questions about the lecture and receive answers grounded in the pre-baked knowledge graph.

#### TF-IDF Search Walk
On budget devices, the tutor walks the knowledge graph using a local TF-IDF algorithm:
```kotlin
for (token in userQueryTokensList) {
    val tf = (tfMap[token] ?: 0).toDouble() / totalTokensInDoc.coerceAtLeast(1)
    val idf = idfMap[token]?.toDouble() ?: 0.0
    tfIdfScore += tf * idf
}
```
The node with the highest score is injected into the model's prompt. Premium devices bypass this search and load the entire transcript directly into the model's 128k context window.

#### Tool Execution Parser (`ToolCallParser.kt` — 138 lines)
If the model determines that a system action is required, it returns a `<tool_call>` block:
`<tool_call> {"name": "set_alarm", "arguments": {"time": "15:30"}} </tool_call>`
The parser extracts the JSON, executes the corresponding system broadcast or intent (e.g., `AlarmClock.ACTION_SET_ALARM`, `ACTION_SEEK_TO`), and returns the result to the model in a `<tool_response>` block.

---

### 4.9 Telemetry & Wi-Fi P2P Mesh Network (`TelemetryStub.kt` — 544 lines)
Aggregates telemetry data and syncs it across devices in offline environments using Wi-Fi Direct.

#### local Telemetry Persistence
Progress and quiz scores are serialized to FlatBuffers `.tlm` files and saved locally. The store is capped at 100 files, deleting the oldest when exceeded.

#### Authenticated P2P Handshake
Devices automatically discover peers and exchange telemetry payloads. To prevent spoofing, the connection uses a challenge-response handshake:

```
PEER A (Group Owner)                              PEER B (Client)
        |                                                |
        |------ 16-Byte Random Challenge -------------->|
        |                                                |
        |                                       [Computes HMAC]
        |<----- 32-Byte HMAC Signature ------------------|
        |                                                |
 [Verifies HMAC]                                         |
        |------ Auth Status (1 = Success) -------------->|
        |                                                |
        |<----- TelemetryPayload (.tlm file) ------------|
```

The payload's signature is verified before writing:
`val expectedHash = computeHmacSha256("$sessionId|$compPercent|$responses", signingSecret)`
If the signature is valid, the file is saved locally to be uploaded to the server when a network connection is available.

---

## 5. C/C++ NDK JNI Subsystem

Exposes native GGML and Whisper.cpp features to the Kotlin runtime.

### 5.1 Native Sinc Resampler (`jni.c` — lines 236-284)
Performs high-fidelity audio resampling using a pre-calculated Sinc Lookup Table to prevent aliasing noise.

```c
JNIEXPORT jfloatArray JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_nativeResample(
        JNIEnv *env, jobject thiz, jshortArray input_samples, jint source_sample_rate) {
    
    jsize input_len = (*env)->GetArrayLength(env, input_samples);
    jshort *shorts = (*env)->GetShortArrayElements(env, input_samples, NULL);
    
    double ratio = (double)source_sample_rate / 16000.0;
    jsize output_len = (jsize)(input_len / ratio);
    
    jfloatArray result = (*env)->NewFloatArray(env, output_len);
    jfloat *output_floats = (*env)->GetFloatArrayElements(env, result, NULL);
    
    for (jsize i = 0; i < output_len; ++i) {
        double center_src = i * ratio;
        int center_idx = (int)center_src;
        
        float sample_val = 0.0f;
        float weight_sum = 0.0f;
        
        for (int j = -SINC_ZERO_CROSSINGS; j <= SINC_ZERO_CROSSINGS; ++j) {
            int src_idx = center_idx + j;
            if (src_idx >= 0 && src_idx < input_len) {
                float distance = (float)(center_src - src_idx);
                float abs_distance = fabsf(distance);
                
                int lut_idx = (int)(abs_distance * SINC_OVERSAMPLING);
                if (lut_idx >= 0 && lut_idx < SINC_LUT_SIZE) {
                    float weight = SINC_LUT[lut_idx];
                    sample_val += (shorts[src_idx] / 32768.0f) * weight;
                    weight_sum += weight;
                }
            }
        }
        if (weight_sum > 0.0f) sample_val /= weight_sum;
        if (sample_val > 1.0f) sample_val = 1.0f;
        if (sample_val < -1.0f) sample_val = -1.0f;
        
        output_floats[i] = sample_val;
    }
    (*env)->ReleaseFloatArrayElements(env, result, output_floats, 0);
    (*env)->ReleaseShortArrayElements(env, input_samples, shorts, JNI_ABORT);
    return result;
}
```

### 5.2 Build Configuration (`CMakeLists.txt` — 88 lines)
- **CPU Optimizations**:
  - Compiles with `GGML_USE_CPU`.
  - Targets `arm64-v8a` with ARMv8.2-a FP16 acceleration (`-march=armv8.2-a+fp16`).
  - Targets `armeabi-v7a` with NEON vfpv4 acceleration (`-mfpu=neon-vfpv4`).
- **Release Optimization**: Linker strips unused symbols (`-Wl,--gc-sections` and `-flto`) to keep the binary size minimal.

---

## 6. Cloud Backend (Nua Web Studio)

Built with Node.js and TypeScript, the cloud backend handles ingestion, translation, knowledge graph generation, and bundles them into `.nuab` files.

### 6.1 Server Endpoints (`index.ts` — 200 lines)
- **`GET /health`**: Returns system mode (`MOCK` or `PRODUCTION`) and version.
- **`GET /api/v1/featured`**: Returns featured videos (loads from Firestore, falls back to mock list if Firestore is offline).
- **`POST /api/v1/telemetry`**: Receives telemetry data. Verified using HMAC signatures.
- **`GET /api/v1/resolve-video`**: Resolves video streaming URLs (e.g. YouTube) using `yt-dlp`.

### 6.2 Translation Agent (`TranslationAgent.ts` — 234 lines)
- **Audio Upload**: Uploads audio files to the Gemini API (`genAI.files.upload`).
- **Devanagari Prompting**: Prompt instructs Gemini 3.5 Flash to translate the transcription to Hinglish (Hindi sentence structure with English technical terms preserved).
- **Retry Mechanism**: Implements exponential backoff: `delay = 1000 * 2^attempt`.
- **Knowledge Graph Compilation**: Summarizes transcripts and returns knowledge graphs as JSON arrays.

---

## 7. Build System & ProGuard Rules

### 7.1 Gradle Properties
- **Compile/Target SDK**: `36` (Android 16)
- **Min SDK**: `24` (Android 7.0)
- **Kotlin Compiler**: `2.3.20`
- **Android Gradle Plugin**: `9.0.1`

### 7.2 ProGuard & Obfuscation Rules (`app/proguard-rules.pro`)
To protect native dependencies from being stripped during minification, the project defines rules for JNI, FlatBuffers, and LiteRT:

```proguard
# FlatBuffers Serialization
-keep class com.google.flatbuffers.** { *; }

# kotlinx.serialization
-keepclassmembers class * {
    *** Companion;
}

# Vosk ASR JNI Bindings
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
```

---

## 8. Test Suite

Nua maintains a unit and fuzz testing suite that achieves 100% coverage of core serialization and audio math operations.

### 8.1 Schema Verification (`SchemaValidationTest.kt` — 247 lines)
Ensures FlatBuffers payloads serialize and deserialize cleanly.
- `verifySchemaVersionRoundTrip()`: Validates that root schema versions and segment parameters are preserved.
- `verifyOptionSelectionRoundTrip()`: Verifies that quiz responses and latencies serialize correctly.
- `verifyQuizAndKnowledgeGraphRoundTrip()`: Asserts that knowledge graphs and quizzes are written without data loss.

### 8.2 Audio Math & Wav Verification (`WavUtilsTest.kt` — 243 lines)
Validates that WAV parsing operations are robust.
- `testFindDataChunkOffset()`: Confirms that data chunk offsets are parsed correctly in both standard and extended WAV files.
- `testGetWavDurationMs()`: Tests duration calculations.
- `testWavFuzzing()`: Feeds randomized byte arrays to the WAV parser to verify that it fails gracefully without throwing unhandled exceptions.

---

# 9. Complete Component Map

```
Nua Ecosystem
├── schema/
│   └── nua_schema.fbs            [Shared FlatBuffers Contract]
│
├── backend/                      [Cloud Ingestion Module]
│   ├── src/
│   │   ├── index.ts              [Express Server]
│   │   ├── agents/
│   │   │   └── TranslationAgent.ts [Gemini 3.5 Ingest]
│   │   ├── packager/
│   │   │   └── NuaBundler.ts     [FlatBuffers compiler]
│   │   └── utils/
│   │       ├── audio.ts          [FFmpeg Extractor]
│   │       └── queue-mediator.ts [Async Task Queue]
│   └── package.json
│
└── app/                          [Android Client Module]
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── cpp/              [Native JNI Core]
        │   │   └── whisper.cpp/
        │   │       └── examples/whisper.android/lib/src/main/jni/whisper/
        │   │           ├── jni.c           [Sinc Resampler]
        │   │           ├── sinc_lut.c      [Static Look-Up Table]
        │   │           ├── sinc_lut.h      [LUT Definitions]
        │   │           └── CMakeLists.txt  [NDK Compiler Targets]
        │   │
        │   └── java/
        │       ├── com/whispercpp/whisper/
        │       │   ├── LibWhisper.kt       [ASR Native Bindings]
        │       │   └── WhisperCpuConfig.kt [CPU Threading Config]
        │       │
        │       └── org/nua/production/app/
        │           ├── MainActivity.kt     [Preload Orchestration]
        │           ├── Navigation.kt       [Jetpack Compose Router]
        │           │
        │           ├── data/               [Local Data Subsystems]
        │           │   ├── asr/
        │           │   │   ├── WhisperTranscriber.kt     [ASR Pipeline]
        │           │   │   ├── FirebaseTranscriber.kt    [Cloud ASR Fallback]
        │           │   │   ├── AcousticSyllableSplicer.kt[ASR Syllable Splicer]
        │           │   │   └── VoiceAgentController.kt   [Voice-First UX]
        │           │   ├── llm/
        │           │   │   ├── LiteRTTranslator.kt       [Gemma Translation]
        │           │   │   ├── ModelLifecycleManager.kt  [Memory Interleaver]
        │           │   │   └── ToolCallParser.kt         [Intent Exec Block]
        │           │   ├── media/
        │           │   │   ├── AudioDecoder.kt           [Silence-Slicer VAD]
        │           │   │   ├── PipelineCompilerService.kt[WorkManager Core]
        │           │   │   ├── PipelineResumeWorker.kt   [Battery Sentinel]
        │           │   │   ├── SessionManager.kt         [Nuab I/O Storage]
        │           │   │   ├── SyncPlayerEngine.kt       [Dual-Player Sync]
        │           │   │   ├── VirtualTimelineMapper.kt  [Binary Search Map]
        │           │   │   └── WavUtils.kt               [RIFF Chunk Walker]
        │           │   ├── rag/
        │           │   │   ├── OfflineTutorEngine.kt     [TF-IDF Graph Walk]
        │           │   │   └── MultimodalVisionEngine.kt [ExoPlayer LVM Frame Bridge]
        │           │   ├── schema/
        │           │   │   ├── NuaSchema.kt              [Hand-crafted Wrappers]
        │           │   │   └── DeviceTier.kt             [Hardware Enum Classifier]
        │           │   └── telemetry/
        │           │       └── TelemetryStub.kt          [Wi-Fi Direct P2P Mesh]
        │           │
        │           └── ui/                 [Jetpack Compose UI]
        │               ├── main/
        │               │   ├── MainScreen.kt
        │               │   ├── MainScreenViewModel.kt
        │               │   └── HardwareCheckScreen.kt    [NPU Benchmark UI]
        │               ├── player/
        │               │   ├── PlayerScreen.kt
        │               │   └── PlayerViewModel.kt
        │               └── setup/
        │                   └── SetupScreen.kt
        │
        └── test/                 [Robust Unit Testing Harness]
            └── java/org/nua/production/app/data/
                ├── media/
                │   ├── SchemaValidationTest.kt
                │   ├── SessionFuzzTest.kt
                │   ├── VirtualTimelineMapperTest.kt
                │   └── WavUtilsTest.kt
                └── telemetry/
                    └── TelemetryStoreTest.kt
