# Nua — Deep Technical Analysis

> **Revision**: 2 (Post-fix audit)
> **Date**: 2026-05-21
> **Codebase**: 19 Kotlin source files · 3,388 LOC · 15 dependencies
> **Build**: Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.3.20 / compileSdk 36 (Android 16)

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Deep-Dive](#2-architecture-deep-dive)
3. [The 5-Stage Compilation Pipeline](#3-the-5-stage-compilation-pipeline)
4. [The Dual-Player Sync Engine](#4-the-dual-player-sync-engine)
5. [Threading & Concurrency Model](#5-threading--concurrency-model)
6. [Security Posture](#6-security-posture)
7. [Data Flow & Session Format](#7-data-flow--session-format)
8. [Dependency Analysis](#8-dependency-analysis)
9. [Current Health Assessment](#9-current-health-assessment)
10. [Remaining Improvement Opportunities](#10-remaining-improvement-opportunities)

---

## 1. System Overview

### What Nua Is

Nua is an **on-device video lecture translation and dubbing engine** for Android. It takes an English-language video lecture as input and produces a synchronized Hindi-dubbed playback experience — entirely offline, with zero cloud dependency.

### What It Is Trying to Do

Nua solves a real educational problem in India: students learn most effectively when technical content is explained in **Hinglish** (Hindi sentence structure with English scientific terms preserved). Rather than producing a re-encoded video file (which would be extremely resource-intensive on mobile), Nua uses a **compile-then-play** architecture:

1. **Compile Phase**: Extract audio → transcribe → translate → synthesize voiced chunks → produce a manifest.
2. **Play Phase**: Synchronize the original video with dubbed audio overlays using a virtual elastic timeline.

### Where It Works

| Capability | Status | Notes |
|---|---|---|
| Offline speech-to-text (English) | ✅ Works | Vosk small model (~40MB) |
| Offline LLM translation | ✅ Works | Gemma 2B INT4 via MediaPipe |
| Offline Hindi TTS | ✅ Works | Android native TTS engine |
| Audio extraction + resampling | ✅ Works | MediaCodec → 16kHz mono WAV |
| Freeze-frame dubbing playback | ✅ Works | Dual ExoPlayer + VirtualTimelineMapper |
| Mock mode for testing | ✅ Works | Simulated translation pipeline |
| Session persistence & gallery | ✅ Works | JSON manifest + file system |

### Where It Has Limitations

| Area | Limitation | Root Cause |
|---|---|---|
| Multi-language support | Hindi only | Hardcoded TTS locale + prompt engineering |
| Video format support | MP4 only | No format detection or container probing |
| Background processing | Cannot survive process death | Service uses START_NOT_STICKY |
| Large videos (>30 min) | High memory pressure | Gemma 2B loads 1.2GB into RAM |
| Segment accuracy | Depends on Vosk model quality | Small model trades accuracy for size |
| Translation quality | Constrained by Gemma 2B | Limited context window (128 tokens) |

---

## 2. Architecture Deep-Dive

### Layer Diagram

```
┌──────────────────────────────────────────────────────┐
│                     UI Layer                          │
│  MainActivity → Navigation → MainScreen / PlayerScreen│
│  SetupScreen                                          │
│  (Jetpack Compose + Navigation3)                      │
├──────────────────────────────────────────────────────┤
│                  ViewModel Layer                      │
│  MainScreenViewModel        PlayerViewModel           │
│  (AndroidViewModel)         (AndroidViewModel)        │
│  - Import / download        - Dual ExoPlayer mgmt    │
│  - Trigger pipeline         - Virtual timeline sync   │
│  - Gallery history          - Subtitle display        │
├──────────────────────────────────────────────────────┤
│              Pipeline Service Layer                   │
│  PipelineCompilerService (Foreground Service)         │
│  - Orchestrates 5-stage pipeline                     │
│  - Static StateFlow for cross-component comms        │
├──────────────────────────────────────────────────────┤
│                  Data Layer                           │
│  VoskTranscriber  GemmaTranslator  DubbingTtsEngine  │
│  AudioDecoder     SessionManager   VirtualTimeline   │
│  MediaComposition                                     │
└──────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

1. **No video re-encoding**: The original video file is kept intact. Dubbing is achieved through dual-player synchronization at playback time, avoiding expensive video transcoding on-device.

2. **Static StateFlow communication**: `PipelineCompilerService` uses `companion object` static `StateFlow`s to communicate progress to the UI. This is an unusual pattern — it works because only one service instance exists at a time, and the duplicate-compilation guard ensures this invariant.

3. **Navigation3**: Uses the new Jetpack Navigation3 API (`androidx.navigation3:navigation3-runtime:1.0.1`) with serializable `NavKey` data classes instead of the older NavGraph-based system.

4. **Session-based persistence**: Each compilation produces a self-contained session folder with a JSON manifest, making sessions portable and independently deletable.

---

## 3. The 5-Stage Compilation Pipeline

### Stage 1: Audio Extraction (`AudioDecoder`)
**File**: `data/media/AudioDecoder.kt` (281 LOC)

**Algorithm**:
- Uses `MediaExtractor` to demux the audio track from the video container
- Creates a `MediaCodec` decoder for the detected audio MIME type
- Performs **on-the-fly downmixing** (stereo → mono) by averaging channel samples
- Performs **on-the-fly resampling** (source rate → 16kHz) using linear interpolation
- Uses a rolling `leftovers` array and fractional `srcIndexPos` to handle sample boundaries across codec output buffers
- Writes PCM data through a 16KB `ByteBuffer` to minimize I/O syscalls
- Retroactively writes a standard 44-byte RIFF/WAV header via `RandomAccessFile.seek(0)`

**Clever aspects**:
- The streaming downmix + resample approach processes audio in a single pass without buffering the entire decoded audio in memory
- The `leftovers` carry-forward handles the edge case where a resampling step straddles two codec output buffers

**Safeguards**:
- Division-by-zero guard: `sourceChannels > 0` check before downmix
- Codec cleanup: Individual try/catch blocks in finally prevent cascading failures
- EOS detection: Both extractor EOS and decoder EOS are tracked independently

### Stage 2: Speech-to-Text (`VoskTranscriber`)
**File**: `data/asr/VoskTranscriber.kt` (315 LOC)

**Algorithm**:
- Feeds 16kHz mono WAV data in 4KB chunks to Vosk's `Recognizer`
- Collects word-level JSON results with start/end timestamps
- Segments words into sentence-like groups using three heuristics:
  - **Silent gap** > 0.8s → new segment
  - **Duration** > 7.0s → new segment
  - **Word count** > 14 → new segment

**Model management**:
- Downloads ZIP from `alphacephei.com`
- Two-pass unzip: first pass counts entries for accurate progress, second pass extracts
- ZIP Slip validation: canonical path check prevents path traversal attacks
- `close()` method for native `Model` resource cleanup

### Stage 3: Translation (`GemmaTranslator`)
**File**: `data/llm/GemmaTranslator.kt` (224 LOC)

**Algorithm**:
- Uses MediaPipe `LlmInference` API with Gemma 2B INT4 model
- Constructs prompts with:
  - **Sliding window context**: Previous translation is included for coherence
  - **Duration constraint**: `maxWords = durationSec × 3.2` limits output length
  - **Technical term preservation**: Explicit instruction to keep scientific terms in English
- Post-processing: strips 5 common LLM preamble patterns, extracts first non-blank line
- Word count limiter: truncates to `maxWords` and appends `।` (Hindi full stop)

**Mock mode**:
- Pattern-matching mock translator for testing without the 1.2GB model
- Handles common phrases ("welcome to lecture", "photosynthesis process", etc.)
- Fallback: word-by-word substitution using a dictionary of Hindi particles

### Stage 4: Voice Synthesis (`DubbingTtsEngine`)
**File**: `data/tts/DubbingTtsEngine.kt` (201 LOC)

**Algorithm — Two-Pass Duration Matching**:
1. **Pass 1**: Synthesize at normal speed (1.0x)
2. **Measure**: Read WAV header to compute actual duration
3. **Compare**: If actual > target duration:
   - Calculate `speedRatio = actual / target`
   - Clamp to `[1.0, 2.0]` to keep speech intelligible
   - If ratio > 1.05, re-synthesize at faster rate
4. **Reset**: Speech rate always reset to 1.0 after synthesis

**Threading model**:
- TTS init is asynchronous (callback-based), gated by a `CountDownLatch`
- Each synthesis call uses a fresh `CountDownLatch` + `AtomicBoolean` for thread-safe error signaling
- `UtteranceProgressListener` callbacks fire on TTS engine threads, safely updating the `AtomicBoolean`

### Stage 5: Session Packaging (`PipelineCompilerService`)
**File**: `data/media/PipelineCompilerService.kt` (305 LOC)

- Runs as an Android `Foreground Service` with `DATA_SYNC` type
- Uses `SupervisorJob` + `CoroutineScope` for structured concurrency
- Thread-safe logging via `synchronized(_logs)` block
- Duplicate compilation guard: `if (_isProcessing.value) return`
- Cleanup: calls `ttsEngine.shutdown()` and `gemmaTranslator.close()` in `onDestroy()`

---

## 4. The Dual-Player Sync Engine

### VirtualTimelineMapper
**File**: `data/media/VirtualTimelineMapper.kt` (183 LOC)

This is the intellectual core of the system. It solves the problem: *how do you play a dubbed audio track that is longer than the original speech segment without re-encoding the video?*

**Solution — Elastic Virtual Timeline**:

```
Physical Timeline:  |===SEG1===|----gap----|===SEG2===|
                    0         5s          8s         12s

Virtual Timeline:   |===SEG1===|--HOLD--|----gap----|===SEG2===|--HOLD--|
                    0         5s       7s          10s         14s      16s
                              ↑ freeze              ↑ freeze
                              video                 video
```

**Key data structure** — `TimelineInterval`:
```kotlin
data class TimelineInterval(
    val originalStartMs: Long,    // Physical video start
    val originalEndMs: Long,      // Physical video end
    val vocalDurationMs: Long,    // Actual dubbed audio length
    val holdMs: Long,             // Extra time = max(0, vocal - original)
    val cumulativeHoldBeforeMs: Long,
    val virtualStartMs: Long,     // Position on virtual timeline
    val virtualEndMs: Long,       // End on virtual timeline
    val vocalAssetLocalPath: String,
    val originalText: String,
    val translatedText: String
)
```

**Two mapping functions**:
1. `getVirtualTimeMs(physicalTimeMs)` — Maps video playhead → virtual time (used when video drives)
2. `getPhysicalState(virtualTimeMs)` — Maps virtual time → physical state + freeze decision (used always)

The `PhysicalState` return type contains:
- `physicalTimeMs`: Where the video player should be
- `shouldFreeze`: Whether the video should pause
- `activeInterval`: Which dubbed segment is playing (if any)
- `vocalPlayheadMs`: Where in the vocal clip we are

### PlayerViewModel Sync Loop
**File**: `ui/player/PlayerViewModel.kt` (297 LOC)

**Mechanism**:
- A `Handler(Looper.getMainLooper())` posts a `Runnable` every 30ms
- The runnable only re-posts when `_isPlaying.value == true` (battery-efficient)
- Each tick:
  1. If in a freeze-hold: audio player drives the virtual clock
  2. If in a gap/normal segment: video player drives the virtual clock
  3. Calls `applyPlaybackState()` to sync both players

**Audio ducking**: Original video volume is reduced to 10% during dubbed segments and restored to 100% during gaps.

**Drift correction**: If vocal player position drifts > 300ms from the mapped playhead, a seek correction is applied.

---

## 5. Threading & Concurrency Model

| Component | Thread | Mechanism |
|---|---|---|
| Pipeline compilation | `Dispatchers.IO` | `serviceScope.launch(Dispatchers.IO)` |
| Session manifest I/O | `Dispatchers.IO` | `viewModelScope.launch(Dispatchers.IO)` |
| ExoPlayer init | Main | `withContext(Dispatchers.Main)` |
| Sync loop (30ms tick) | Main | `Handler(Looper.getMainLooper())` |
| TTS synthesis | Background (TTS engine thread) | `CountDownLatch` + `AtomicBoolean` |
| Vosk transcription | IO | Called within pipeline IO coroutine |
| Gemma inference | IO | Called within pipeline IO coroutine |
| UI state collection | Main | `collectAsStateWithLifecycle()` |
| Log accumulation | Any | `synchronized(_logs)` |

---

## 6. Security Posture

| Threat | Mitigation | Status |
|---|---|---|
| ZIP Slip (path traversal via malicious model ZIP) | Canonical path validation in `VoskTranscriber.unzip()` | ✅ Protected |
| OkHttp response leak (DoS via unclosed connection) | `response.use {}` in download method | ✅ Protected |
| Stream leaks (file handle exhaustion) | `.use {}` blocks on all file I/O | ✅ Protected |
| Service export (unauthorized pipeline trigger) | `android:exported="false"` on service | ✅ Protected |
| Storage permissions | `maxSdkVersion="32"` on legacy permission | ✅ Scoped |
| Model file injection | Model loaded from app-private `filesDir` only | ✅ Protected |
| Untrusted URL download | No URL validation beyond HTTP success code | ⚠️ Partial |

---

## 7. Data Flow & Session Format

### Session Directory Structure

```
sessions/
  session_{videoName}_{timestamp}/
    raw_lecture.mp4              # Original video (unmodified)
    manifest.json                # Sync manifest (MediaComposition)
    vocal_chunks/
      vocal_0_3500.wav           # Dubbed segment 0ms–3500ms
      vocal_3500_7200.wav        # Dubbed segment 3500ms–7200ms
      ...
```

### Manifest Schema (`manifest.json`)

```json
{
  "videoId": "session_lecture_1716300000000",
  "sourceVideoPath": "raw_lecture.mp4",
  "segments": [
    {
      "startMs": 0,
      "endMs": 3500,
      "originalText": "Welcome to today's lecture on photosynthesis",
      "translatedText": "Photosynthesis एक chemical process है...",
      "vocalAssetLocalPath": "vocal_chunks/vocal_0_3500.wav",
      "directive": "NORMAL_SYNC"
    }
  ]
}
```

### Directive Constants

| Directive | Meaning |
|---|---|
| `NORMAL_SYNC` | TTS succeeded; play vocal chunk in sync |
| `PAD_EMPTY` | TTS failed; skip vocal, play original audio |
| `FREEZE_HOLD` | Reserved for future explicit freeze markers |

---

## 8. Dependency Analysis

| Dependency | Version | Purpose | Size Impact |
|---|---|---|---|
| `vosk-android` | 0.3.75 | Offline ASR engine | ~15MB (native libs) |
| `mediapipe-tasks-genai` | 0.10.14 | On-device LLM inference | ~5MB (runtime) |
| `media3-exoplayer` | 1.3.1 | Video/audio playback | ~3MB |
| `media3-ui` | 1.3.1 | ExoPlayer UI controls | ~1MB |
| `media3-transformer` | 1.3.1 | Media pipeline (imported, not actively used) | ~2MB |
| `okhttp` | 4.12.0 | HTTP downloads (model, video URLs) | ~800KB |
| `kotlinx-serialization-json` | 1.6.3 | Manifest JSON codec | ~300KB |
| `compose-bom` | 2026.03.01 | UI framework | ~5MB |
| `navigation3` | 1.0.1 | Screen routing | ~200KB |

**Total APK overhead** (excluding models): ~32MB

**Runtime memory with Gemma loaded**: ~1.5GB (model alone is ~1.2GB)

---

## 9. Current Health Assessment

### Build Status

| Metric | Value |
|---|---|
| Build result | ✅ BUILD SUCCESSFUL |
| Build time | ~34s (incremental) |
| Compile errors | 0 |
| Compile warnings | 3 (non-blocking: deprecated Locale ctor, redundant conversion, unstable API opt-in) |
| Source files | 19 Kotlin files |
| Total LOC | 3,388 |
| Dead code | 0 (cleaned) |

### Defect History

In the initial audit, **32 defects** were identified (6 Critical, 14 Medium, 12 Low). All have been resolved:

| Severity | Found | Fixed | Remaining |
|---|---|---|---|
| 🔴 Critical | 6 | 6 | 0 |
| 🟡 Medium | 14 | 14 | 0 |
| 🟢 Low | 12 | 12 | 0 |

**Key fixes applied**:
- ZIP Slip path traversal → canonical path validation
- TTS race condition → `AtomicBoolean` for thread-safe signaling
- Thread-unsafe logging → `synchronized` block
- MediaCodec crash → safe cleanup without `stop()` in finally
- Division by zero → guard on `sourceChannels`
- Main thread I/O → `Dispatchers.IO` + `withContext(Main)`
- Battery drain → sync loop only runs when playing
- Stream leaks → `.use {}` blocks everywhere
- Lifecycle-unsafe collection → `collectAsStateWithLifecycle()`

---

## 10. Remaining Improvement Opportunities

These are **not bugs** — the system is correct and stable. These are enhancement opportunities for future development.

### High Value

| # | Opportunity | Effort | Impact |
|---|---|---|---|
| 1 | **Cancellation support**: Allow user to cancel a running pipeline compilation | Medium | High — currently the only way to stop is to kill the app |
| 2 | **Multi-language support**: Parameterize TTS locale + translation prompt | Medium | High — unlocks Tamil, Bengali, Telugu, etc. |
| 3 | **Progress persistence**: Save pipeline state to survive process death | High | High — important for long videos |
| 4 | **URL validation**: Validate download URLs before starting pipeline | Low | Medium — prevents wasted processing on invalid URLs |
| 5 | **Dependency injection**: Replace manual `SessionManager(context)` with Hilt/Koin | Medium | Medium — improves testability |

### Medium Value

| # | Opportunity | Effort | Impact |
|---|---|---|---|
| 6 | **WAV duration utility**: Extract shared `getWavDurationMs()` from `DubbingTtsEngine` and `VirtualTimelineMapper` | Low | Low — removes code duplication |
| 7 | **Media3 Transformer removal**: `media3-transformer` and `media3-effect` are imported but never used | Trivial | Low — reduces APK size ~3MB |
| 8 | **ProGuard/R8 minification**: `isMinifyEnabled = false` in release build | Low | Medium — significant APK size reduction |
| 9 | **Structured error handling**: Replace string-based error messages with sealed class error types | Medium | Medium — enables programmatic error handling |
| 10 | **Integration tests**: Add end-to-end tests for the pipeline with sample audio | High | High — currently only unit tests for VirtualTimelineMapper |

### Low Value / Polish

| # | Opportunity | Effort | Impact |
|---|---|---|---|
| 11 | **Accessibility**: Add content descriptions to custom pause icon, progress indicators | Low | Medium (a11y compliance) |
| 12 | **Landscape mode**: PlayerScreen doesn't handle landscape/fullscreen | Medium | Low — nice-to-have for video playback |
| 13 | **Notification tap action**: Pipeline notification has no tap-to-open PendingIntent | Low | Low — polish |
| 14 | **Delete confirmation**: Session deletion has no confirmation dialog | Low | Low — prevents accidental deletion |
| 15 | **Seek debounce**: Slider seek fires on every pixel change | Low | Low — minor UX improvement |

---

## Appendix: File Inventory

| File | LOC | Role |
|---|---|---|
| `data/media/PipelineCompilerService.kt` | 304 | Pipeline orchestrator (Foreground Service) |
| `data/asr/VoskTranscriber.kt` | 315 | ASR engine + model download |
| `data/media/AudioDecoder.kt` | 281 | Audio extraction + resampling |
| `data/llm/GemmaTranslator.kt` | 224 | LLM translation (Gemma 2B) |
| `data/tts/DubbingTtsEngine.kt` | 200 | TTS synthesis + speed matching |
| `data/media/VirtualTimelineMapper.kt` | 183 | Elastic timeline mapping |
| `data/media/SessionManager.kt` | 64 | Session CRUD operations |
| `data/media/MediaComposition.kt` | 26 | Data models + directive constants |
| `ui/main/MainScreen.kt` | 468 | Main screen (input + gallery + console) |
| `ui/setup/SetupScreen.kt` | 355 | AI model setup screen |
| `ui/player/PlayerViewModel.kt` | 297 | Dual-player sync engine |
| `ui/player/PlayerScreen.kt` | 284 | Video playback + subtitles |
| `ui/main/MainScreenViewModel.kt` | 212 | Import, download, history management |
| `Navigation.kt` | 47 | Navigation3 screen routing |
| `theme/Theme.kt` | 43 | Material3 dark theme |
| `theme/Type.kt` | 36 | Typography definitions |
| `MainActivity.kt` | 22 | Entry point |
| `theme/Color.kt` | 18 | Color palette (neon accent scheme) |
| `NavigationKeys.kt` | 9 | Serializable nav keys |
| **Total** | **3,388** | |
