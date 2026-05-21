# Nua 🎬🎙️

**Nua** (meaning *New* or *Renewed*) is a fully on-device video lecture translation and dubbing engine for Android.

It solves a critical educational gap: while academic videos are primarily in English, millions of students learn most effectively in **Hinglish** — Hindi sentence structure with scientific terms preserved in English. Nua processes English video lectures entirely offline, producing a synchronized Hindi-dubbed playback experience without any cloud dependency.

> 📄 **For a deep architectural analysis, see [DEEP_TECHNICAL_ANALYSIS.md](DEEP_TECHNICAL_ANALYSIS.md)**

---

## 🚀 Key Innovation: Dynamic Freeze-Frame Dubbing

Traditional video translators perform resource-heavy video transcoding and audio re-muxing on the device. Nua takes a fundamentally different approach:

### Zero-Transcoding Compiler Pipeline

The compiler service extracts the original audio, translates it, synthesizes Hindi vocal chunks, and outputs a lightweight **session package**:

```
session_{video}_{timestamp}/
  raw_lecture.mp4              # Original video (unmodified)
  manifest.json                # Sync manifest
  vocal_chunks/
    vocal_0_3500.wav           # Dubbed segment 0–3.5s
    vocal_3500_7200.wav        # Dubbed segment 3.5–7.2s
    ...
```

### Dual-Player Synchronization

During playback, two separate ExoPlayer instances run simultaneously:
- **Video Player**: Plays the original lecture video (original audio ducked to 10%)
- **Vocal Player**: Dynamically queues and plays Hindi audio chunks at mapped timestamps

### Elastic Virtual Timeline

Translated Hindi speech is often longer than the original English. Rather than clipping or warping the voice:

```
Physical Timeline:  |==SEG1==|---gap---|==SEG2==|
                    0       5s       8s       12s

Virtual Timeline:   |==SEG1==|HOLD|---gap---|==SEG2==|HOLD|
                    0       5s   7s       10s       14s  16s
                             ↑ video                 ↑ video
                             freezes                 freezes
```

When a dubbed segment runs longer, the **video freezes on the last frame** while the vocal player continues. When the vocal finishes, video automatically resumes in sync.

---

## 🛠️ The 5-Stage Compilation Pipeline

```
┌──────────────┐
│  Input Video ├──────┐
└──────────────┘      │
                      ▼
              1. Audio Extraction
              [AudioDecoder] → MediaCodec decode → downmix → resample → 16kHz mono WAV
                      │
                      ▼
              2. Speech-to-Text
              [VoskTranscriber] → Offline ASR → word-level timestamps → sentence segmentation
                      │
                      ▼
              3. Hinglish Translation
              [GemmaTranslator] → Gemma 2B INT4 (on-device) → context-aware translation
                      │
                      ▼
              4. Voice Synthesis
              [DubbingTtsEngine] → Android TTS (Hindi) → two-pass speed matching (up to 2.0x)
                      │
                      ▼
              5. Session Packaging
              [PipelineCompilerService] → manifest.json + vocal_chunks/ → ready for playback
```

### Pipeline Details

| Stage | Component | Algorithm |
|---|---|---|
| **Audio Extraction** | `AudioDecoder` | Single-pass streaming decode with on-the-fly downmix (stereo→mono) and linear-interpolation resampling via rolling `leftovers` buffer |
| **Transcription** | `VoskTranscriber` | 4KB chunk processing with Vosk small English model; segments words by gap (>0.8s), duration (>7s), and count (>14 words) |
| **Translation** | `GemmaTranslator` | MediaPipe LLM Inference with sliding-window context, duration-constrained output (`maxWords = durationSec × 3.2`), and scientific term preservation |
| **Voice Synthesis** | `DubbingTtsEngine` | Two-pass: synthesize at 1.0x → measure duration → re-synthesize at faster rate if overflow → clamp at 2.0x for intelligibility |
| **Packaging** | `PipelineCompilerService` | Foreground Service with `SupervisorJob`, thread-safe logging, duplicate-compilation guard |

---

## 🏗️ Technical Stack

| Component | Technology | Version |
|---|---|---|
| **Language** | Kotlin | 2.3.20 |
| **Min SDK** | Android 7.0 | API 24 |
| **Target SDK** | Android 16 | API 36 |
| **Build System** | Gradle + AGP | 9.1.0 / 9.0.1 |
| **UI Framework** | Jetpack Compose + Material3 | BOM 2026.03.01 |
| **Navigation** | Navigation3 | 1.0.1 |
| **Video Playback** | Android Media3 ExoPlayer | 1.3.1 |
| **Speech Recognition** | Vosk-Android | 0.3.75 |
| **LLM Inference** | MediaPipe GenAI Tasks | 0.10.14 |
| **Networking** | OkHttp | 4.12.0 |
| **Serialization** | kotlinx-serialization-json | 1.6.3 |

---

## 📐 Architecture

```
┌──────────────────────────────────────────────────────┐
│                     UI Layer                          │
│  MainScreen · PlayerScreen · SetupScreen              │
│  (Jetpack Compose + collectAsStateWithLifecycle)      │
├──────────────────────────────────────────────────────┤
│                  ViewModel Layer                      │
│  MainScreenViewModel            PlayerViewModel       │
│  Import + gallery + download    Dual-player sync      │
├──────────────────────────────────────────────────────┤
│              Pipeline Service Layer                   │
│  PipelineCompilerService (Foreground Service)         │
│  5-stage orchestration · static StateFlow comms       │
├──────────────────────────────────────────────────────┤
│                  Data Layer                           │
│  VoskTranscriber · GemmaTranslator · DubbingTtsEngine │
│  AudioDecoder · SessionManager · VirtualTimelineMapper│
└──────────────────────────────────────────────────────┘
```

### Threading Model

| Operation | Thread | Mechanism |
|---|---|---|
| Pipeline compilation | `Dispatchers.IO` | Coroutine scope |
| ExoPlayer operations | Main | `withContext(Dispatchers.Main)` |
| Sync loop (30ms tick) | Main | `Handler(Looper.getMainLooper())` |
| TTS synthesis callbacks | TTS engine thread | `AtomicBoolean` + `CountDownLatch` |
| UI state | Main | `collectAsStateWithLifecycle()` |

---

## 📦 Model Requirements & Setup

To run Nua in **Real Mode** (non-mock), download the offline models via the **Setup** screen:

| Model | Size | Source |
|---|---|---|
| **Vosk English (small)** | ~40MB | Auto-downloaded from alphacephei.com |
| **Gemma 2B INT4** | ~1.2GB | MediaPipe `.bin` format — copy to app files |
| **Hindi TTS Voice** | ~50MB | Google Speech Services (System Settings) |

> **Mock Mode**: For development/testing without model downloads, enable Mock Mode in Setup. The app generates simulated translation segments.

---

## 📊 Codebase Metrics

| Metric | Value |
|---|---|
| Source files | 19 Kotlin |
| Total LOC | 3,388 |
| Dependencies | 15 |
| APK size (excl. models) | ~32MB |
| Runtime memory (Gemma loaded) | ~1.5GB |
| Compile warnings | 3 (non-blocking) |
| Known bugs | 0 |

---

## 🧪 Testing

Nua includes unit tests for the virtual timeline offset calculations:

```bash
./gradlew test
```

See [`VirtualTimelineMapperTest.kt`](app/src/test/java/com/example/nua/data/media/VirtualTimelineMapperTest.kt) for reference.

---

## 📜 Building from Source

```bash
# Clone the repository
git clone https://github.com/thokchomlolet03-cpu/Nua.git
cd Nua

# Build debug APK
./gradlew assembleDebug

# The APK is generated at:
# app/build/outputs/apk/debug/app-debug.apk
```

**Requirements**:
- JDK 17+
- Android SDK with API 36 installed
- ~2GB disk space for build cache

---

## 🔒 Security

- **ZIP Slip protection**: Canonical path validation on all archive extraction
- **Stream safety**: All file I/O wrapped in `.use {}` blocks
- **Service isolation**: Pipeline service is not exported (`android:exported="false"`)
- **Thread safety**: `AtomicBoolean`, `synchronized`, and proper dispatcher usage
- **Scoped storage**: Legacy `READ_EXTERNAL_STORAGE` capped at SDK 32

---

## 📁 Project Structure

```
app/src/main/java/com/example/nua/
├── MainActivity.kt                  # Entry point
├── Navigation.kt                    # Navigation3 routing
├── NavigationKeys.kt                # Serializable nav keys
├── data/
│   ├── asr/
│   │   └── VoskTranscriber.kt       # Offline speech-to-text (315 LOC)
│   ├── llm/
│   │   └── GemmaTranslator.kt      # On-device translation (224 LOC)
│   ├── tts/
│   │   └── DubbingTtsEngine.kt     # Hindi TTS + speed matching (200 LOC)
│   └── media/
│       ├── PipelineCompilerService.kt  # Pipeline orchestrator (304 LOC)
│       ├── AudioDecoder.kt          # Audio extraction + resampling (281 LOC)
│       ├── VirtualTimelineMapper.kt # Elastic timeline engine (183 LOC)
│       ├── SessionManager.kt       # Session CRUD (64 LOC)
│       └── MediaComposition.kt     # Data models (26 LOC)
├── ui/
│   ├── main/
│   │   ├── MainScreen.kt           # Home screen (468 LOC)
│   │   └── MainScreenViewModel.kt  # Import + gallery logic (212 LOC)
│   ├── player/
│   │   ├── PlayerScreen.kt         # Playback UI (284 LOC)
│   │   └── PlayerViewModel.kt      # Sync engine (297 LOC)
│   └── setup/
│       └── SetupScreen.kt          # Model setup (355 LOC)
└── theme/
    ├── Color.kt                     # Neon accent palette
    ├── Theme.kt                     # Material3 dark theme
    └── Type.kt                      # Typography
```

---

## 📄 License

This project is developed as an educational technology initiative. See individual file headers for licensing details.
