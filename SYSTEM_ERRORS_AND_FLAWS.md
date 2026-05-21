# Project Nua Ecosystem — System Errors, Flaws, and Vulnerability Audit

This document tracks all identified errors, security flaws, architectural deficiencies, and performance limitations across the entire **Project Nua Ecosystem** (including the **Nua Edge** Android Client and the **Nua Web Studio** Cloud Ingestion Backend).

> [!NOTE]
> This audit has been compiled to track outstanding issues and guide remediation efforts.

---

## 📊 Summary of Findings

| ID | Component | Severity | Description | Target File / Code Reference |
| :--- | :--- | :--- | :--- | :--- |
| **C1** | Android Client | 🔴 Critical | **Sync Timeline Collapse / Video Freeze Bypass**: `PlaybackSegment` is created without `audioDurationMs`, defaulting to the original video duration and bypassing freeze/hold logic. | [`PipelineCompilerService.kt#L195-204`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt#L195-204) |
| **C2** | Cloud Backend | 🔴 Critical | **Missing Audio Input in Translation Agent**: The ingestion translation agent invokes Gemini 3.5 Flash without passing the extracted lecture audio file payload. | [`TranslationAgent.ts#L70-73`](file:///Users/lolet/Downloads/Nua/backend/src/agents/TranslationAgent.ts#L70-73) |
| **C3** | Android Client | 🔴 Critical | **On-Device Quiz and Knowledge Graph Data Loss**: Legacy manifest migration hardcodes quiz and graph offsets to `0`, discarding data and deleting the source JSON. | [`SessionManager.kt#L86-96`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt#L86-96) |
| **C4** | Android Client | 🔴 Critical | **ASR Memory Exhaustion (OOM Vulnerability)**: `FirebaseTranscriber` reads the entire WAV audio file into memory in one go, crashing on longer lectures. | [`FirebaseTranscriber.kt#L51`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/FirebaseTranscriber.kt#L51) |
| **C5** | Android Client | 🔴 Critical | **Corrupt/Partial Vosk Model Unpack Leak**: Unzipping errors clean up the temp ZIP but leave partial directories, leading to subsequent false-positive initialization checks. | [`VoskTranscriber.kt#L109-113`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt#L109-113) |
| **M1** | Android Client | 🟡 Moderate | **Unreleased FlatBuffers Memory-Mapped Files**: Closing the channel and random-access file immediately after mapping does not unmap the buffer, locking files. | [`SessionManager.kt#L113-118`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt#L113-118) |
| **M2** | Android Client | 🟡 Moderate | **ExoPlayer View Click / Gesture Interception**: Native `PlayerView` consumes all touch events, completely ignoring Compose's `.clickable` pause toggle wrapper. | [`PlayerScreen.kt#L142-163`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/player/PlayerScreen.kt#L142-163) |
| **M3** | Android Client | 🟡 Moderate | **Infinite Telemetry Disk Accumulation**: Telemetry events write `.tlm` files to disk indefinitely, but the flush method is a no-op stub that never deletes files. | [`TelemetryStub.kt#L113`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt#L113) |
| **M4** | Android Client | 🟡 Moderate | **Tutor Search RAG Hallucination**: Failing to match keywords in the knowledge graph forces fallback to index `0`, grounding unrelated prompts in the wrong context. | [`OfflineTutorEngine.kt#L130-134`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt#L130-134) |
| **M5** | Android Client | 🟡 Moderate | **Heavy Main-Thread Audio Pipeline Jank**: Rebuilding and preparing ExoPlayer items on vocal chunk transitions causes UI stuttering and thread blockages. | [`SyncPlayerEngine.kt#L245-253`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt#L245-253) |
| **M6** | Android Client | 🟡 Moderate | **Spamming PlaybackParameters / Speed Updates**: Re-applying `PlaybackParameters` every 30ms causes ExoPlayer thread contention and massive system logs. | [`SyncPlayerEngine.kt#L98-129`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt#L98-129) |
| **M7** | Android Client | 🟡 Moderate | **Download Coupled to ViewModel Lifecycle**: Downloading lecture video files is scoped to the ViewModel, immediately aborting if the user rotates the screen. | [`MainScreenViewModel.kt#L170-211`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/main/MainScreenViewModel.kt#L170-211) |
| **L1** | Cloud Backend | 🟢 Minor | **FFmpeg Ingestion Hanging Vulnerability**: Video download/stream extraction does not enforce timeouts or validate protocols, posing a request-blocking threat. | [`audio.ts#L8-26`](file:///Users/lolet/Downloads/Nua/backend/src/utils/audio.ts#L8-26) |
| **L2** | Cloud Backend | 🟢 Minor | **Manual Offset FlatBuffer Serialization**: Building binary buffers via manual table indices in Node.js creates high fragility and risks schema incompatibility. | [`NuaBundler.ts#L33-43`](file:///Users/lolet/Downloads/Nua/backend/src/packager/NuaBundler.ts#L33-43) |
| **L3** | Android Client | 🟢 Minor | **Unsynchronized Mutable Engine Instance**: The LiteRT-LM model engine is closed, initialized, and read across threads without volatile fields or synchronization. | [`LiteRTTranslator.kt#L37-62`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/llm/LiteRTTranslator.kt#L37-62) |

---

## 🔴 1. Critical Flaws

### C1: Sync Timeline Collapse / Video Freeze Bypass
* **Location**: [`PipelineCompilerService.kt#L195-204`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt#L195-204)
* **Root Cause**: During on-device compilation, vocal chunk segments are generated and appended. However, the `PlaybackSegment` is initialized without setting the `audioDurationMs` parameter. It defaults to `null`. 
* **Impact**:
  1. In [`SessionManager.kt#L72`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt#L72), if `audioDurationMs` is null, it falls back to: `seg.audioDurationMs ?: (seg.endMs - seg.startMs)`. This represents the *original English* segment duration, not the actual generated Hindi wav file duration.
  2. During playback, [`VirtualTimelineMapper.kt#L41-47`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/VirtualTimelineMapper.kt#L41-47) loads the manifest, sees the non-null value representing the original video segment duration, and completely bypasses calling `getWavDurationMs(file)`.
  3. Consequently, `vocalDur` is set equal to `originalDur`, making `holdMs` resolve to `0`. The video freezing mechanism is bypassed entirely, breaking timeline synchronization and letting the video speed past the vocal audio track.

### C2: Missing Audio Input in Cloud Translation Agent
* **Location**: [`TranslationAgent.ts#L70-73`](file:///Users/lolet/Downloads/Nua/backend/src/agents/TranslationAgent.ts#L70-73)
* **Root Cause**: The method `translateLecture` extracts the audio channel to `wavPath`, then starts a Gemini 3.5 Flash content generation request. However, it passes only the text instructions in `contents: systemPrompt` and never attaches the audio file data!
* **Impact**: The model receives a prompt instructing it to "Analyze the provided lecture audio..." but receives no audio payload. This causes the Gemini model to fail or hallucinate translation timestamps and text entirely out of context.

### C3: On-Device Quiz and Knowledge Graph Data Loss
* **Location**: [`SessionManager.kt#L86-96`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt#L86-96)
* **Root Cause**: In legacy JSON migration, the JSON manifest is loaded containing list items for `quizzes` and other metadata. However, inside `saveManifest`, when constructing the FlatBuffers table:
  ```kotlin
  val root = LectureSession.createLectureSession(
      builder,
      ...
      quizzesOffset = 0,
      knowledgeGraphOffset = 0,
      telemetryLedgerOffset = 0
  )
  ```
  Quizzes and the knowledge graph are explicitly hardcoded to offset `0`.
* **Impact**: Any quiz arrays or knowledge graph arrays compiled in legacy JSON manifests are permanently discarded. Because `migrateJsonToNuab` immediately calls `jsonFile.delete()`, this leads to permanent user data loss.

### C4: ASR Memory Exhaustion (OOM Vulnerability)
* **Location**: [`FirebaseTranscriber.kt#L51`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/FirebaseTranscriber.kt#L51)
* **Root Cause**: The method `transcribeWav` performs:
  ```kotlin
  val audioBytes = wavFile.readBytes()
  ```
  which reads the entire file into a contiguous `ByteArray` on the JVM heap.
* **Impact**: Lecture audio files are typically 20 minutes to 1 hour long. A 30-minute 16kHz mono WAV file is ~57MB, and stereo files are even larger. Reading this into memory in a single block quickly exhausts the Android heap space, throwing an `OutOfMemoryError` and crashing the application.

### C5: Corrupt/Partial Vosk Model Unpack Leak
* **Location**: [`VoskTranscriber.kt#L109-113`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt#L109-113)
* **Root Cause**: If the model download finishes but the unzipping operation is interrupted or throws an exception, the catch block deletes the temporary ZIP file but does not clean up the target directory `modelDir`.
* **Impact**: A partial/corrupt unzipped model remains on-disk. On the next application run, [`isModelDownloaded()`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt#L37-45) finds the directories and reports that the model is fully ready. Subsequent ASR requests then crash during native `Model()` instantiation.

---

## 🟡 2. Moderate Flaws

### M1: Unreleased FlatBuffers Memory-Mapped Files
* **Location**: [`SessionManager.kt#L113-118`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt#L113-118)
* **Root Cause**: When loading the manifest, the method memory-maps the file via `channel.map(...)`. It then immediately closes the `FileChannel` and `RandomAccessFile` in an attempt to release resources:
  ```kotlin
  val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
  channel.close()
  raf.close()
  ```
* **Impact**: Closing the file channel/handle does not unmap the buffer on Java (due to how `MappedByteBuffer` is structured in the JVM). The buffer retains the lock on the file. Any subsequent attempt to delete, overwrite, or update this session file (e.g., during recompilation or cleaning up history) fails with a write block or sharing violation exception.

### M2: ExoPlayer View Click / Gesture Interception
* **Location**: [`PlayerScreen.kt#L142-163`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/player/PlayerScreen.kt#L142-163)
* **Root Cause**: The `.clickable { viewModel.togglePlayPause() }` modifier is attached to the parent `Box` wrapping the player. However, the native Android `PlayerView` embedded inside via `AndroidView` consumes all touch events internally.
* **Impact**: Gestures never bubble up to the parent Compose container. Tapping on the video screen fails to toggle play/pause, forcing the user to tap the small, floating play button below.

### M3: Infinite Telemetry Disk Accumulation (Storage Leak)
* **Location**: [`TelemetryStub.kt#L113`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt#L113)
* **Root Cause**: Every progress track or quiz answer generates a `.tlm` file on local storage inside `telemetry_ledger`. However, because the server sync network layer is currently deferred to a future phase, `flushToServer()` is a no-op stub:
  ```kotlin
  override suspend fun flushToServer() {
      // STUB: Network transmission deferred to mesh-network implementation phase
      val pending = pendingCount()
      if (pending > 0) {
          Log.i(TAG, "Telemetry flush requested ($pending entries pending). " +
                  "Network relay not yet implemented — entries retained locally.")
      }
  }
  ```
* **Impact**: Local telemetry files accrue on the device indefinitely. Over time, this leads to uncontrolled storage bloat, eventually filling the user's disk and causing system crashes.

### M4: Tutor Search RAG Hallucination
* **Location**: [`OfflineTutorEngine.kt#L130-134`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt#L130-134)
* **Root Cause**: When a student asks a tutoring question, the engine scores graph nodes based on keyword overlap. If there are no keyword matches, the engine falls back to selecting index `0`:
  ```kotlin
  if (bestNode == null && session.knowledgeGraphLength > 0) {
      bestNode = session.knowledgeGraph(0)
  }
  ```
* **Impact**: If a student asks a general interface question (e.g., "how to pause?") or a query completely outside the lecture scope, the engine grounds the prompt in index `0` (e.g., "Photosynthesis"). The AI then responds with unrelated content, causing confusion and RAG hallucinations.

### M5: Heavy Main-Thread Audio Pipeline Jank
* **Location**: [`SyncPlayerEngine.kt#L245-253`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt#L245-253)
* **Root Cause**: During continuous playback, transitioning from one Hindi vocal chunk to another triggers:
  ```kotlin
  audioPlayer.stop()
  audioPlayer.setMediaItem(MediaItem.fromUri(path))
  audioPlayer.prepare()
  audioPlayer.seekTo(playheadMs)
  ```
* **Impact**: Calling `stop()`, `prepare()`, and `seekTo()` synchronously on the main thread every few seconds forces the ExoPlayer pipeline to rebuild and synchronize. This blocks the main thread, causing noticeable UI jank and audio stuttering.

### M6: Spamming PlaybackParameters / Speed Updates
* **Location**: [`SyncPlayerEngine.kt#L98-129`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt#L98-129)
* **Root Cause**: The sync alignment evaluator is invoked inside the tick loop every 30ms. If a segment has a drift, the engine repeatedly invokes:
  ```kotlin
  videoPlayer.playbackParameters = PlaybackParameters(clampedRatio)
  ```
* **Impact**: Changing playback parameters on ExoPlayer is an expensive operation that forces internal state recalculations. Invoking this every 30ms causes thread contention, playback stutter, and floods logcat with state update messages.

### M7: Download Coupled to ViewModel Lifecycle
* **Location**: [`MainScreenViewModel.kt#L170-211`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/main/MainScreenViewModel.kt#L170-211)
* **Root Cause**: The `startDubbingVideoFromUrl` method launches video downloading directly inside the `viewModelScope` on `Dispatchers.IO`.
* **Impact**: If the user leaves the screen, rotates the device (which destroys and recreates the view model), or navigates away, the `viewModelScope` is cancelled. The download coroutine terminates mid-flight, resulting in corrupt, incomplete files in the cache.

---

## 🟢 3. Minor Flaws

### L1: FFmpeg Ingestion Hanging Vulnerability
* **Location**: [`audio.ts#L8-26`](file:///Users/lolet/Downloads/Nua/backend/src/utils/audio.ts#L8-26)
* **Root Cause**: The backend `extractAudioChannel` function calls the local `ffmpeg` process directly using the user-provided `videoUrl` without performing protocol validation or setting execution timeout limits.
* **Impact**: If the URL points to a slow-responding or hanging network stream, the FFmpeg process blocks execution indefinitely. This leaks work directories and clogs node event loops.

### L2: Manual Offset FlatBuffer Serialization
* **Location**: [`NuaBundler.ts#L33-43`](file:///Users/lolet/Downloads/Nua/backend/src/packager/NuaBundler.ts#L33-43)
* **Root Cause**: The Node.js package builder manually serializes FlatBuffers by hardcoding table field indexes (`builder.addFieldOffset(0, ...)`, `builder.startObject(9)`, etc.) instead of utilizing code generated from the schema.
* **Impact**: Any change to the FlatBuffers schema file requires manually rewriting serialization offsets. This is highly error-prone and risks silent structure corruption on version mismatch.

### L3: Unsynchronized Mutable Engine Instance
* **Location**: [`LiteRTTranslator.kt#L37-62`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/llm/LiteRTTranslator.kt#L37-62)
* **Root Cause**: The mutable `engine` reference is closed and re-initialized across multiple threads. However, the `engine` property is neither marked as `@Volatile` nor wrapped in thread-safe locks/synchronized blocks.
* **Impact**: Concurrent translation requests or closing the player while initialization is in progress can lead to race conditions, null pointer exceptions, or native memory corruption crashes.

---

## 🛠️ Proposed Remediation Plan

### Phase 1: Critical Fixes
1. **Sync Timeline Correction**: Update [`PipelineCompilerService.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt) to calculate and pass the `audioDurationMs` parameter inside the `PlaybackSegment` constructor.
2. **Translation Audio Payload**: Update [`TranslationAgent.ts`](file:///Users/lolet/Downloads/Nua/backend/src/agents/TranslationAgent.ts) to upload the `wavPath` file to the Gemini API using the File API, and pass the file reference in `contents`.
3. **Data Loss Prevention**: Update [`SessionManager.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt) to serialize `quizzes` and `knowledgeGraph` items from the loaded `MediaComposition` rather than hardcoding their offsets to `0`.
4. **ASR Streaming / Chunking**: Update [`FirebaseTranscriber.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/FirebaseTranscriber.kt) to upload audio using the Cloud Storage URI or chunk the audio file stream rather than calling `readBytes()`.
5. **Clean Vosk Unpacking**: Modify [`VoskTranscriber.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt) to delete the `modelDir` recursively if the download or unzip operation throws an error.

### Phase 2: Moderate Fixes
1. **ExoPlayer Click Handler**: Bind the play/pause trigger directly to the `PlayerView` click listener within the Compose `AndroidView` factory in [`PlayerScreen.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/player/PlayerScreen.kt).
2. **Telemetry Pruning**: Add a size or count threshold to [`LocalTelemetryStore`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt) to delete the oldest `.tlm` files once the directory exceeds a limit (e.g., max 100 entries).
3. **Tutor RAG Context Nullability**: Pass a null context instead of fallback index `0` in [`OfflineTutorEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt) when no keyword matches the query.
4. **ExoPlayer Concatenation**: Replace individual `setMediaItem()` calls in [`SyncPlayerEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt) with a pre-built playlist or use a single player instance with `seekTo(chunkIndex, position)`.
5. **Background Download Service**: Move video downloads out of the ViewModel scope in [`MainScreenViewModel.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/main/MainScreenViewModel.kt) and execute them via Android's `DownloadManager` or a background Service.
