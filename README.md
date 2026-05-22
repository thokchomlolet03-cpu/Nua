# Nua 🎬🎙️ — Intelligent Video Lecture Translation & Dubbing Ecosystem

> [!TIP]
> **Status: 🟢 Production Ready (v4.0)** — Major TRIZ-driven architectural overhaul complete. Versioned FlatBuffers schema with typed telemetry, dynamic RIFF WAV parsing, O(log n) timeline binary search, HMAC-authenticated API, R8-optimized build. All 24 bugs resolved. 8/11 technical debt items eliminated.

**Nua** (meaning *New* or *Renewed*) is a complete, offline-first translation, dubbing, and interactive tutoring ecosystem for educational video lectures. 

The project solves a critical educational gap: while technical lectures are primarily in English, millions of students learn most effectively in **Hinglish** (Hindi sentence structure with scientific terms preserved in English) and other regional languages.

Nua operates on a **compile-then-play** philosophy:
1. **Nua Web Studio (Cloud Ingestion Backend)**: Ingests video lectures, translates content using **Gemini 3.5 Flash**, bakes interactive quizzes and RAG knowledge graphs, and packages everything into highly efficient **FlatBuffers binary bundles (`.nuab`)**.
2. **Nua Edge (Android Client)**: Plays the lectures offline by mapping the original video and regional TTS vocal tracks onto a synchronized **elastic virtual timeline**, with an integrated local **LiteRT-LM** tutor and quiz module.

> 📄 **For a deep architectural analysis of code, algorithms, and design decisions, see [DEEP_TECHNICAL_ANALYSIS.md](DEEP_TECHNICAL_ANALYSIS.md)**
>
> 🔍 **For the active bug registry and technical debt tracker, see [SYSTEM_ERRORS_AND_FLAWS.md](SYSTEM_ERRORS_AND_FLAWS.md)**

---

## 🚀 Key Architectural Innovations

### 1. Zero-Transcoding Dubbing Player
Traditional video translators perform heavy video transcoding and audio muxing on the device, which drains battery and degrades video quality. Nua keeps the source MP4 file completely unmodified. The player coordinates two media engines simultaneously:
- **Video Player**: Plays the unmodified lecture video (original audio volume ducked to 0%).
- **Vocal Player**: Dynamically queues and plays synthesized vocal WAV chunks at mapped timestamps.

### 2. Elastic Virtual Timeline
Translated speech is often longer than the original English sentence. Rather than clipping the voice or distorting the audio pitch:
```
Physical Timeline (Video): |====SEG1====|-------gap-------|====SEG2====|
                           0            5s                8s           12s

Virtual Timeline (Player): |====SEG1====|──HOLD──|-------gap-------|====SEG2====|──HOLD──|
                           0            5s       7s                10s          14s      16s
                                                 ▲                              ▲
                                            Video Freezes                  Video Freezes
```
When a dubbed segment runs longer, the player freezes the video on the last frame while the vocal player continues. Once the vocal finishes, the video resumes in sync.

### 3. Sonic Pitch-Invariant Time Warping
For minor sync drifts (`1ms to 800ms`), the engine slows down video playback (down to `0.80x`) using ExoPlayer's native **Sonic time-stretch algorithm**. Sonic uses sinusoidal overlap-add (SOLA) to stretch the video timeline to align with the audio without altering the vocal pitch.

---

## 🏗️ Ecosystem Topology

The monorepo contains both modules sharing a single data contract:

```
nua/                                  <-- Monorepo Root Workspace
├── schema/                           
│   └── nua_schema.fbs                <-- Shared FlatBuffers binary schema
├── compile_schema.sh                 <-- Compiles schema for Android & Node.js
├── backend/                          <-- Nua Web Studio (Node.js / TypeScript)
│   ├── src/
│   │   ├── index.ts                  <-- Express ingestion server
│   │   ├── agents/                   <-- Gemini 3.5 translation agent
│   │   └── packager/                 <-- FlatBuffers bundle generator
│   └── package.json
└── app/                              <-- Nua Edge (Android app module)
    └── src/main/java/com/example/nua/
        ├── data/                     <-- LiteRT-LM, Sync Engine, Vosk, AudioDecoder
        └── ui/                       <-- Compose layouts, Player with Quiz overlays
```

### Shared FlatBuffers Contract (`schema/nua_schema.fbs`)
Data is stored in memory-mapped `.nuab` binary files, allowing zero-copy deserialization on the Android client:
- **Timeline tracks**: Chronological list of `TimeSegment` events.
- **Concept hotspots**: Inline glossary tokens mapping words in subtitles to definitions.
- **Interactive quizzes**: Checkpoints blocking playback to prompt students.
- **Knowledge graph**: Nodes containing summary facts and semantic tokens for offline tutoring.

---

## 🛠️ Ingestion & Compilation Pipelines

### Cloud Ingestion Pipeline (Nua Web Studio)
Triggered via `POST /api/v1/ingest`:
- **Audio Extraction**: Extracts audio from the video URL, downmixes to mono, and resamples to 16kHz PCM WAV using `ffmpeg`.
- **Gemini Ingestion**: Calls **Gemini 3.5 Flash** (via the official `@google/genai` SDK) with the audio track. The model transcribes English speech, segments it into sentence blocks, and translates it to Hinglish.
- **Cognitive Graph Baking**: Feeds the transcript to Gemini to pre-bake a hierarchical knowledge graph of concepts.
- **Binary Packing**: Serializes everything into `.nuab` and uploads the bundle to Google Cloud Storage (GCS).

### Local Edge Compiler Pipeline (Nua Edge)
When operating offline, the client can compile imported lectures using a **5-Stage Foreground Service**:
1. **Audio Extraction (`AudioDecoder`)**: Demuxes audio streams on-the-fly and resamples them using linear-interpolation downmixing via a rolling `leftovers` buffer.
2. **ASR Transcription (`VoskTranscriber` / `FirebaseTranscriber`)**: Transcribes the WAV file using a local Vosk model (~40MB) or via Gemini Cloud endpoints when online.
3. **Local Translation (`LiteRTTranslator`)**: Translates sentences to Hinglish on-device using the Google AI Edge **LiteRT-LM** engine with context sliding windows and word length limiters.
4. **Voice Synthesis (`DubbingTtsEngine`)**: Synthesizes Hindi voice segments using native Android TTS. Adapts synthesis speed (up to 2.0x) if the vocal duration exceeds the physical timeline slot.
5. **Session Packaging (`SessionManager`)**: Packs assets into a session folder and saves a local `.nuab` manifest.

---

## 🔒 Security Posture
- **ZIP Slip Protection**: Validates canonical paths of extracted entries in `VoskTranscriber` to prevent directory-traversal attacks.
- **I/O Stream Safety**: All file and network streams are wrapped in `.use {}` blocks to guarantee descriptor release.
- **Service Isolation**: The Foreground Service is unexported (`android:exported="false"`) to prevent hijacking.
- **Cryptographic Telemetry Ledger**: Telemetry analytics (progress, quiz scores) are stored locally as signed FlatBuffers payloads, authenticated with a SHA-256 hash before flushing.

---

## 🚀 Building & Running

### Prerequisites
- **JDK 17+**
- **Android SDK API 36**
- **Node.js v22+**
- **FFmpeg** installed on your system path (for backend audio extraction)

### 1. Shared Contract Compilation
To recompile the FlatBuffers schema for both Node.js and Android, run:
```bash
./compile_schema.sh
```

### 2. Running Nua Web Studio (Backend)
```bash
cd backend
npm install
# Set your Gemini API key (defaults to Mock Sandbox Mode if empty)
export GEMINI_API_KEY="your-gemini-key"
export PORT=8080
npm run dev
```
Send an ingestion request:
```bash
curl -X POST http://localhost:8080/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{"videoUrl": "https://example.com/lecture.mp4", "targetLanguage": "hi"}'
```

### 3. Running Nua Edge (Android App)
```bash
# Clean and compile debug APK
./gradlew clean assembleDebug

# Run unit tests
./gradlew test
```
The compiled APK will be output to: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📁 Codebase Inventory

| Layer | Files | LOC |
|---|---|---|
| Android Data (media, ASR, LLM, TTS, RAG, telemetry, schema) | 13 | 3,042 |
| Android UI (screens, ViewModels, navigation, theme) | 12 | 1,999 |
| Backend (TypeScript) | 4 | 515 |
| Shared Schema | 1 | 57 |
| Tests | 2 | 213 |
| **Total** | **~47** | **~6,878** |

### Android Client (Nua Edge)
- [`AudioDecoder.kt`](app/src/main/java/com/example/nua/data/media/AudioDecoder.kt): Linear-interpolating media resampler.
- [`VoskTranscriber.kt`](app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt) / [`FirebaseTranscriber.kt`](app/src/main/java/com/example/nua/data/asr/FirebaseTranscriber.kt): Transcription engines.
- [`LiteRTTranslator.kt`](app/src/main/java/com/example/nua/data/llm/LiteRTTranslator.kt): On-device LiteRT-LM translation.
- [`DubbingTtsEngine.kt`](app/src/main/java/com/example/nua/data/tts/DubbingTtsEngine.kt): Text-to-speech synchronization.
- [`SyncPlayerEngine.kt`](app/src/main/java/com/example/nua/data/media/SyncPlayerEngine.kt): Dual-player ExoPlayer coordinator.
- [`VirtualTimelineMapper.kt`](app/src/main/java/com/example/nua/data/media/VirtualTimelineMapper.kt): Timeline mappings and freeze calculations.
- [`OfflineTutorEngine.kt`](app/src/main/java/com/example/nua/data/rag/OfflineTutorEngine.kt): Pre-baked graph walking conversational RAG.
- [`TelemetryStub.kt`](app/src/main/java/com/example/nua/data/telemetry/TelemetryStub.kt): Local telemetry ledger with SHA-256 signatures.
- [`SessionManager.kt`](app/src/main/java/com/example/nua/data/media/SessionManager.kt): Session persistence and FlatBuffers I/O.
- [`NuaSchema.kt`](app/src/main/java/com/example/nua/data/schema/NuaSchema.kt): Hand-written FlatBuffers wrappers.
- [`PipelineCompilerService.kt`](app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt): 5-stage foreground compilation service.
- [`MediaComposition.kt`](app/src/main/java/com/example/nua/data/media/MediaComposition.kt): In-memory session data model.

### Cloud Ingestion (Nua Web Studio)
- [`index.ts`](backend/src/index.ts): Express routing and ingestion pipelines.
- [`TranslationAgent.ts`](backend/src/agents/TranslationAgent.ts): Gemini 3.5 translation and graph generator.
- [`NuaBundler.ts`](backend/src/packager/NuaBundler.ts): FlatBuffers binary builder.
- [`audio.ts`](backend/src/utils/audio.ts): FFmpeg audio extraction utility.
