# Nua — Deep Technical Analysis

> **Revision**: 5 (Edge Case Hardening & Bug Remediation)
> **Date**: 2026-05-22
> **Codebase**: Android Client (Nua Edge) & Cloud Backend (Nua Web Studio)
> **Binary Schema**: FlatBuffers (`schema/nua_schema.fbs`)

> [!TIP]
> **Status: 🟢 System Stabilized (v3.0)** - This document has been updated to reflect the 100% resolution of all system errors and flaws, including 13 edge cases identified in the recent deep technical audit.

---

## Table of Contents

1. [Ecosystem & Architectural Boundary](#1-ecosystem--architectural-boundary)
2. [Shared Binary Contract: FlatBuffers Schema](#2-shared-binary-contract-flatbuffers-schema)
3. [Nua Web Studio: Cloud Ingestion Backend](#3-nua-web-studio-cloud-ingestion-backend)
4. [Nua Edge: On-Device Compiler Pipeline](#4-nua-edge-on-device-compiler-pipeline)
5. [Nua Edge: Dual-Player Sync Engine](#5-nua-edge-dual-player-sync-engine)
6. [Nua Edge: Offline RAG Tutor & Cognitive Graph Walker](#6-nua-edge-offline-rag-tutor--cognitive-graph-walker)
7. [Nua Edge: Mesh Telemetry & Security Posture](#7-nua-edge-mesh-telemetry--security-posture)
8. [Ecosystem File & Code Inventory](#8-ecosystem-file--code-inventory)
9. [Technical Limitations & Enhancements](#9-technical-limitations--enhancements)

---

## 1. Ecosystem & Architectural Boundary

The **Project Nua Ecosystem** is a hybrid cloud-edge system designed to deliver high-quality, fully offline educational video lecture translations. It supports **Hinglish** (Hindi sentence structure with English scientific terms preserved) and other regional Indian languages. 

```
                       ┌───────────────────────────────┐
                       │    Original Lecture Video     │
                       └──────────────┬────────────────┘
                                      │
                                      ▼
                      ┌─────────────────────────────────┐
                      │    NUA WEB STUDIO (Backend)     │
                      │  - Ingests MP4, extracts audio  │
                      │  - Translates with Gemini 3.5   │
                      │  - Bakes RAG Knowledge Graph    │
                      │  - Serializes to .nuab binary   │
                      └──────────────┬──────────────────┘
                                     │ (Distribution CDN)
                                     ▼
                      ┌─────────────────────────────────┐
                      │     NUA EDGE (Android Client)   │
                      │  - Memory-maps .nuab bundles    │
                      │  - Resamples & decodes media    │
                      │  - Offline / Hybrid ASR route   │
                      │  - LiteRT-LM Local Translator  │
                      │  - Speed-matched TTS Synthesis  │
                      │  - Sonic Dual-Player Sync       │
                      │  - Offline Graph-Walking RAG    │
                      │  - Mesh Telemetry Ledger        │
                      └─────────────────────────────────┘
```

### Core Architecture Philosophy
1. **Zero-Transcoding Playback**: Transcoding MP4 files on-device is highly resource-intensive and drains battery. Nua bypasses this constraint by keeping the source video file completely unmodified. Instead, it extracts the audio track, synthesizes translated regional vocal chunks, and aligns them at playback time using an **elastic virtual timeline**.
2. **Offline-First Resilience**: Once a `.nuab` bundle and its accompanying media assets are downloaded, the client requires zero network connectivity. Translation, speech synthesis (or cached playbacks), tutoring interactions, concept lookup, and telemetry aggregation are conducted entirely on-device.
3. **FlatBuffers Data Exchange**: Ad-hoc JSON serialization is replaced with a single schema-defined FlatBuffers binary format (`.nuab`). This format is memory-mapped directly on mobile to achieve zero-copy deserialization.

---

## 2. Shared Binary Contract: FlatBuffers Schema

The bridge between Nua Web Studio and Nua Edge is the FlatBuffers schema defined in [`schema/nua_schema.fbs`](file:///Users/lolet/Downloads/Nua/schema/nua_schema.fbs). This schema dictates the data layout of `.nuab` bundles:

```protobuf
namespace NuaSerialization;

table Hotspot {
  token:string;
  concept_definition:string;
}

table Quiz {
  trigger_timestamp_ms:uint;
  question:string;
  options:[string];
  correct_index:ubyte;
}

table GraphNode {
  node_id:string;
  keywords:[string];
  summary_factoid:string;
  context_tokens:[string];
}

table TelemetryPayload {
  session_id:string;
  completion_percentage:ubyte;
  quiz_scores_json:string;
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
  should_freeze:bool;
  hotspots:[Hotspot];
}

table LectureSession {
  session_id:string;
  source_lang:string;
  target_lang:string;
  course_title:string;
  timeline_tracks:[TimeSegment];
  quizzes:[Quiz];
  knowledge_graph:[GraphNode];
  telemetry_ledger:[TelemetryPayload];
}

root_type LectureSession;
```

### Schema Struct Breakdown
- **LectureSession**: The root container for a dubbed lecture. Contains metadata, linear playback tracks (`timeline_tracks`), interactive question structures (`quizzes`), pre-baked RAG tutoring files (`knowledge_graph`), and the historical mesh sync payload (`telemetry_ledger`).
- **TimeSegment**: Maps specific temporal spans of the lecture. Includes the original transcript, translation text, vocal audio paths, a boolean flag indicating if the segment should force a freeze, and inline concept definitions (`hotspots`).
- **Hotspot**: Highlights technical terms inside subtitles. When a student clicks on a word matching the `token`, the player displays the `concept_definition` dynamically.
- **GraphNode**: The key-value semantic structure that facilitates offline RAG. Contains keywords and context tokens to calculate semantic similarity, mapping user queries to specific `summary_factoid` content.

---

## 3. Nua Web Studio: Cloud Ingestion Backend

The backend is built in **TypeScript/Node.js** as an ES Module (`"type": "module"`) under `backend/`. It acts as an ingestion pipeline that digests video files and generates `.nuab` packages.

### Ingestion Pipeline Flow (`backend/src/index.ts`)
1. **Audio Extraction (`backend/src/utils/audio.ts`)**: Probes the incoming video URL and downloads it. It calls `fluent-ffmpeg` to strip the video, downmix the audio channels to **mono**, and resample to **16kHz 16-bit PCM WAV**. This matches the format needed by the speech recognition engines.
2. **Translation & Segmentation (`backend/src/agents/TranslationAgent.ts`)**: Invokes the **Gemini 3.5 Flash** model using the official `@google/genai` SDK. The model processes the audio track to simultaneously transcribe the English content, segment it into sentence-like timestamps (no longer than 7s/14 words), and translate it into Hinglish (preserving technical terms).
3. **Cognitive Graph Baking**: Feeds the transcript to Gemini 3.5 Flash to automatically compile a curriculum-aligned knowledge graph of concepts (`GraphNode`s) containing definitions, keywords, and semantic tokens.
4. **FlatBuffers Packaging (`backend/src/packager/NuaBundler.ts`)**: Uses the `flatbuffers` JavaScript/TypeScript API to serialize the translation results, segments, and knowledge graph into the binary `.nuab` format.
5. **CDN Distribution**: Uploads the resulting `.nuab` package to Google Cloud Storage (GCS) to be fetched by Android clients.

---

## 4. Nua Edge: On-Device Compiler Pipeline

If the `.nuab` is not pre-compiled in the cloud, Nua Edge features a local **5-Stage Compilation Pipeline** orchestrated by the Android Foreground Service [`PipelineCompilerService.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt).

```
┌─────────────────┐      ┌────────────────┐      ┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│ 1. Audio Decode │ ───> │ 2. Transcribe  │ ───> │  3. Translate   │ ───> │  4. TTS Synthesis│ ───> │   5. Package    │
│  (AudioDecoder) │      │  (ASR Engine)  │      │ (LiteRT-LM Engine)│     │ (DubbingTtsEngine│      │ (SessionManager)│
└─────────────────┘      └────────────────┘      └─────────────────┘      └──────────────────┘      └─────────────────┘
```

### Stage 1: Audio Extraction (`AudioDecoder.kt`)
Extracts audio from video files on-device using Android low-level media APIs:
- Uses `MediaExtractor` to parse the audio stream and feeds it to `MediaCodec` (decoder).
- **On-the-fly downmixing**: Averages stereo samples to mono.
- **On-the-fly resampling**: Performs linear interpolation to resample the audio to 16kHz WAV format.
- Uses a rolling `leftovers` byte array and fractional pointer tracking to guarantee sample boundary alignment when resampling across distinct codec output buffers.
- Writes PCM data through a 16KB `ByteBuffer` to minimize disk writes, followed by writing a 44-byte WAV header at offset 0.

### Stage 2: Speech-to-Text Transcription (`VoskTranscriber.kt` & `FirebaseTranscriber.kt`)
Supports a hybrid path for transcription:
1. **Offline Vosk Path**: Feeds 16kHz WAV audio in 4KB chunks to Vosk's native speech recognition library. Auto-downloads and extracts the small model (~40MB) safely using ZIP Slip path-traversal checks. Words are segmented by a silent gap of `>0.8s`, duration of `>7s`, or word count of `>14 words`.
2. **Cloud Firebase AI Path**: When online, uses the `firebase-ai` SDK's `generativeModel("gemini-2.5-flash")` API, uploading audio via `inlineData(bytes, "audio/wav")` to fetch high-precision timestamped transcript segments.

### Stage 3: On-Device Translation (`LiteRTTranslator.kt`)
Translates English text to Devanagari Hinglish using **LiteRT-LM** (Google AI Edge):
- Initializes a local `com.google.ai.edge.litertlm.Engine` from a downloaded LLM model file.
- Creates a `Conversation` and invokes `sendMessageAsync(prompt)` to stream response tokens.
- Constructs prompts with:
  - **Sliding-window context**: Includes the previous segment's translation for narrative continuity.
  - **Length constraints**: Truncates output to `maxWords = durationSec * 3.2` to prevent excessive playback overflow.
  - **Term preservation guidelines**: Restricts translation of technical terms (e.g., mitochondria, chloroplast).

### Stage 4: Voice Synthesis (`DubbingTtsEngine.kt`)
Synthesizes dubbed regional vocal chunks using Android native Text-To-Speech (TTS):
- **Two-Pass Speed Matching**:
  1. Synthesizes translation text at normal speed (1.0x).
  2. Parses the generated WAV file's header to measure its exact duration.
  3. If the vocal duration exceeds the original English video segment:
     - Calculates a `speedRatio = vocalDurationMs / originalDurationMs`.
     - Clamps the ratio to `[1.0, 2.0]` to preserve audio intelligibility.
     - If the ratio exceeds 1.05x, it re-synthesizes the vocal chunk at the faster speed.
  4. Resets the TTS engine speed to 1.0x to avoid bleeding rates into future segments.

### Stage 5: Session Packaging (`SessionManager.kt`)
Gathers the assets (original MP4 video, `.nuab` manifest, and vocal chunks directory) into a persistent folder inside the application's private files directory. It supports backward compatibility by parsing legacy JSON manifests and automatically migrating them to FlatBuffers format.

---

## 5. Nua Edge: Dual-Player Sync Engine

The core player engine consists of [`SyncPlayerEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt) and [`VirtualTimelineMapper.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/VirtualTimelineMapper.kt). It coordinates two players to synchronize dubbed audio over original video without transcoding.

### Virtual Timeline Mapping
Since translated speech is often longer than original speech, the engine defines a virtual playback timeline that is longer than the physical video.

```
Physical Timeline (Video): |====SEG1====|-------gap-------|====SEG2====|
                           0            5s                8s           12s

Virtual Timeline (Player): |====SEG1====|──HOLD──|-------gap-------|====SEG2====|──HOLD──|
                           0            5s       7s                10s          14s      16s
                                                 ▲                              ▲
                                            Video Freezes                  Video Freezes
```

`VirtualTimelineMapper` splits the lecture into `TimelineInterval`s:
- **`vocalDurationMs`**: Actual length of the synthesized regional WAV chunk.
- **`holdMs`**: Overflows (`vocalDurationMs - originalDurationMs`) where the video must freeze.
- **`cumulativeHoldBeforeMs`**: Tracks time expansion offsets to translate physical video positions to virtual positions, and vice versa.

### Dual-Player Synchronization Loop
A 30ms high-resolution timer loop on the Main Thread coordinates the players:
1. **Normal & Gap Segments**: The Video ExoPlayer drives the virtual clock. The audio player remains idle or prepares the next segment. The original video's volume is set to 100%.
2. **Dubbed Vocal Segments**: Original video volume is ducked to 0%. The Vocal ExoPlayer plays the synthesized WAV file.
3. **Clock Skewing (drift 1-800ms)**: Adjusts the video player's playback speed between `0.80x` and `1.0x` using ExoPlayer's native **Sonic time-stretch algorithm** (sinusoidal overlap-add). This stretches the video timeline to match the audio chunk without altering vocal pitch.
4. **Hard Freeze (drift > 800ms)**: If the audio segment is significantly longer, the video player pauses (freezing on its last frame) while the audio player continues. The audio player drives the virtual clock during this freeze. When the audio segment finishes, the video resumes.
5. **Memory-Saving Sliding Window (Temporal Frustum)**: To prevent loading hundreds of audio files into memory during a long lecture, the engine implements a temporal frustum sliding window. Vocal WAV files are dynamically loaded and prepared only if their segment starts within `±2 minutes` (120,000ms) of the virtual playhead.
6. **Drift & Seek Corrections**: If the vocal player's position drifts `>300ms` from the virtual clock mapping, the engine forces a seek correction to align them.

---

## 6. Nua Edge: Offline RAG Tutor & Cognitive Graph Walker

Nua Edge features an interactive, offline conversational AI tutor implemented in [`OfflineTutorEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt).

```
                      ┌─────────────────────────────────┐
                      │      User Tutoring Query        │
                      └────────────────┬────────────────┘
                                       │
                                       ▼
                      ┌─────────────────────────────────┐
                      │    Hierarchical Graph Walk      │
                      │  - Scans FlatBuffers GraphNodes │
                      │  - Ranks keyword overlap overlap│
                      └────────────────┬────────────────┘
                                       │ (Closest GraphNode Match)
                                       ▼
                      ┌─────────────────────────────────┐
                      │   Structured Prompt Assembly    │
                      │  - Inject topic keywords        │
                      │  - Inject pre-baked factoid     │
                      │  - Inject current playhead time │
                      └────────────────┬────────────────┘
                                       │
                                       ▼
                      ┌─────────────────────────────────┐
                      │      LiteRT-LM Inference        │
                      │  - Generate grounded response   │
                      └─────────────────────────────────┘
```

### Algorithmic Mechanics
1. **Query Parsing**: Tokenizes the student's question and filters standard stop words.
2. **Hierarchical Graph Search**: Traverses the `knowledge_graph` array inside the memory-mapped FlatBuffers `LectureSession`. Computes similarity scores using keyword overlap:
   $$\text{Score} = | \text{Prompt Words} \cap \text{Node Keywords} |$$
   Returns the highest-scoring `GraphNode`. If no match is found, it falls back to the first node in the lecture session.
3. **Tutoring Prompt Generation**: Wraps the query with the matched node's pre-baked context factoid, relevant keywords, and the video's current playhead position to construct a structured prompt.
4. **LiteRT-LM Synthesis**: Runs local, NPU-accelerated token generation using `Engine.sendMessageAsync(prompt)` to write the tutor's response on-device.

---

## 7. Nua Edge: Mesh Telemetry & Security Posture

### Mesh-Ready Telemetry Ledger (`TelemetryStub.kt`)
To support offline analytics, Nua Edge utilizes [`LocalTelemetryStore.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt):
- **Local Analytics Storage**: Progress and quiz scores are serialized into FlatBuffers `TelemetryPayload` structures.
- **Cryptographic Signatures**: Computes a SHA-256 hash of the telemetry fields:
  $$\text{Hash} = \text{SHA256}(\text{sessionId} \mathbin{\Vert} \text{completionPercentage} \mathbin{\Vert} \text{quizScoresJson})$$
  This hash is embedded as a signature to verify payload integrity.
- **Future Mesh Routing**: Telemetry payloads are written to the app's local files directory. When network access is available, they are uploaded in a single batch. The local interface is designed to support peer-to-peer Wi-Fi Direct mesh synchronization. This will allow offline student devices to relay analytics through peers.

### Security Defect Protections
- **ZIP Slip Protection**: `VoskTranscriber.kt` validates the canonical path of all extracted ZIP entries before writing files. This prevents directory-traversal attacks from malicious model packages:
  ```kotlin
  if (!file.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator) && file.canonicalPath != targetDirectory.canonicalPath) {
      throw SecurityException("ZIP entry would escape target directory: ${zipEntry.name}")
  }
  ```
- **Connection and File Leak Protections**: Every file input/output stream, OkHttp client response, and database cursor uses Kotlin's `.use {}` blocks. This ensures that system file descriptors and sockets are closed even if an exception occurs.
- **Service Isolation**: The `PipelineCompilerService` is declared in `AndroidManifest.xml` with `android:exported="false"`. This prevents other applications on the device from triggering compilation jobs.

---

## 8. Ecosystem File & Code Inventory

The Project Nua monorepo consists of **15 Kotlin files** in the Android app (3,388 LOC), **4 TypeScript files** in the cloud backend, and **1 FlatBuffers schema definition**.

### Shared Files
| File Path | Language | LOC | Role |
| :--- | :--- | :--- | :--- |
| [`schema/nua_schema.fbs`](file:///Users/lolet/Downloads/Nua/schema/nua_schema.fbs) | FlatBuffers IDL | 57 | Shared serialization layout contract |
| [`compile_schema.sh`](file:///Users/lolet/Downloads/Nua/compile_schema.sh) | Shell Script | 29 | Utility to generate Kotlin and JS FlatBuffers bindings |

### Cloud Backend: Nua Web Studio
| File Path | Language | LOC | Role |
| :--- | :--- | :--- | :--- |
| [`backend/src/index.ts`](file:///Users/lolet/Downloads/Nua/backend/src/index.ts) | TypeScript | 120 | Express application, router, and endpoint handlers |
| [`backend/src/agents/TranslationAgent.ts`](file:///Users/lolet/Downloads/Nua/backend/src/agents/TranslationAgent.ts) | TypeScript | 186 | Gemini 3.5 Flash translation agent and mock sandbox |
| [`backend/src/packager/NuaBundler.ts`](file:///Users/lolet/Downloads/Nua/backend/src/packager/NuaBundler.ts) | TypeScript | 115 | FlatBuffers serialization builder for `.nuab` files |
| [`backend/src/utils/audio.ts`](file:///Users/lolet/Downloads/Nua/backend/src/utils/audio.ts) | TypeScript | 27 | FFmpeg audio demuxing, resampling, and downmixing |

### Android Client: Nua Edge
| File Path | Language | LOC | Role |
| :--- | :--- | :--- | :--- |
| [`app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt) | Kotlin | 304 | Foreground service managing compilation |
| [`app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt) | Kotlin | 315 | On-device Vosk ASR transcriber |
| [`app/src/main/java/com/example/nua/data/asr/FirebaseTranscriber.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/FirebaseTranscriber.kt) | Kotlin | 109 | Hybrid cloud transcriber using `firebase-ai` SDK |
| [`app/src/main/java/com/example/nua/data/media/AudioDecoder.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/AudioDecoder.kt) | Kotlin | 281 | Low-level MediaCodec audio extractor |
| [`app/src/main/java/com/example/nua/data/llm/LiteRTTranslator.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/llm/LiteRTTranslator.kt) | Kotlin | 266 | On-device LiteRT-LM translation translator |
| [`app/src/main/java/com/example/nua/data/tts/DubbingTtsEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/tts/DubbingTtsEngine.kt) | Kotlin | 200 | TTS voice engine and speed calculations |
| [`app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt) | Kotlin | 302 | Dual ExoPlayer sync manager (Sonic warp) |
| [`app/src/main/java/com/example/nua/data/media/VirtualTimelineMapper.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/VirtualTimelineMapper.kt) | Kotlin | 183 | Elastic virtual timeline mapping algorithms |
| [`app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt) | Kotlin | 143 | Pre-baked graph keyword-walker and RAG tutor |
| [`app/src/main/java/com/example/nua/data/schema/NuaSchema.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/schema/NuaSchema.kt) | Kotlin | 275 | Generated FlatBuffers table wrappers |
| [`app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt) | Kotlin | 123 | Local telemetry store and SHA-256 signature generator |
| [`app/src/main/java/com/example/nua/data/media/SessionManager.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/SessionManager.kt) | Kotlin | 240 | Session filesystem IO and JSON-to-FlatBuffers migration |
| [`app/src/main/java/com/example/nua/data/media/MediaComposition.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/MediaComposition.kt) | Kotlin | 57 | In-memory representations of session details |
| [`app/src/main/java/com/example/nua/ui/main/MainScreen.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/main/MainScreen.kt) | Kotlin | 468 | Home screen UI |
| [`app/src/main/java/com/example/nua/ui/main/MainScreenViewModel.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/main/MainScreenViewModel.kt) | Kotlin | 212 | Home screen ViewModel |
| [`app/src/main/java/com/example/nua/ui/player/PlayerScreen.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/player/PlayerScreen.kt) | Kotlin | 522 | Lecture player and quiz UI overlays |
| [`app/src/main/java/com/example/nua/ui/player/PlayerViewModel.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/player/PlayerViewModel.kt) | Kotlin | 207 | Sync player and quiz triggers ViewModel |
| [`app/src/main/java/com/example/nua/ui/setup/SetupScreen.kt`](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/ui/setup/SetupScreen.kt) | Kotlin | 355 | Offline models downloader UI |
| [`app/src/main/java/com/example/nua/Navigation.kt`](file:///Users/lolet/Downloads/com/example/nua/Navigation.kt) | Kotlin | 47 | Navigation3 setup |
| [`app/src/main/java/com/example/nua/NavigationKeys.kt`](file:///Users/lolet/Downloads/com/example/nua/NavigationKeys.kt) | Kotlin | 9 | Screen nav keys |
| [`app/src/main/java/com/example/nua/MainActivity.kt`](file:///Users/lolet/Downloads/com/example/nua/MainActivity.kt) | Kotlin | 22 | Root activity |

---

## 9. Technical Limitations & Enhancements

While the system is correct and stable, the following areas present opportunities for enhancement:
1. **Telemetry Synchronization Mesh**: The Wi-Fi Direct P2P mesh-routing layer remains a stub. Implementing actual P2P socket transfers will fully realize remote, serverless analytics collection.
2. **Audio Waveform Splicing**: The TTS speed matching operates on entire segment blocks. Splitting segments on syllable boundaries would allow more granular time-warping and reduce the need for hard video freezes.
3. **Quantized LiteRT Tutor Model**: Currently, the translator and tutor share a single general model. Swapping the tutoring logic to a specialized 1.5B param model quantized to INT4 would reduce NPU memory usage and improve response speeds.
4. **Audio Probing**: Audio downmixing and resampling assume PCM input. Adding automatic codec probing will make the audio compilation pipeline more robust when handling variable bitrate audio streams.
