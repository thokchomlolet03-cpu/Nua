# Nua 🎬🎙️

**Nua** (meaning *New* or *Renewed*) is a localized, on-device video translation and dubbing application for Android. 

It is designed to address a critical educational gap in Indian classrooms: while academic materials and videos are primarily in English, students learn most effectively when explained in a blend of English and Hindi (commonly known as **Hinglish**), preserving technical and scientific terms in English while translating explanatory language.

Nua processes English video lectures entirely offline, transcribing, translating, and generating a synchronized Hindi-dubbed voiceover overlay on top of the original video.

---

## 🚀 Key Innovation: Dynamic Freeze-Frame Dubbing

Traditional video translators perform resource-heavy video transcoding and audio-muxing on the device, leading to massive battery drain, slow processing, and potential memory exhaustion. 

Nua takes a completely different path:
1. **Zero-Transcoding Compiler Pipeline**: The compiler service extracts the original audio, translates/synthesizes vocal chunks, and outputs a lightweight session package consisting of:
   * The original video file (`raw_lecture.mp4`).
   * Individual vocal WAV files for each dubbed segment (`vocal_chunks/vocal_*.wav`).
   * A JSON sync manifest (`manifest.json`).
2. **Dual-Player Synchronization**: During playback, the app initializes two separate [Android Media3 ExoPlayer](https://developer.android.com/media/media3) instances:
   * **Video Player**: Plays the original lecture video with the original audio track ducked/muted.
   * **Vocal Player**: Dynamically queues and plays the dubbed Hindi audio chunks at their mapped virtual timestamps.
3. **Elastic Virtual Timeline**: Translated speech is often longer than the original English speech. Rather than clipping the translated audio or artificially warping the speaker's voice to fit the original timing, Nua's `VirtualTimelineMapper` dynamically maps a **virtual timeline** to the physical video.
   * When a dubbed Hindi segment runs longer than the original English speech, the **Video Player pauses and freezes on the last frame** while the **Vocal Player continues to play**.
   * Once the vocal segment finishes, the video automatically unfreezes and resumes playing in sync.

---

## 🛠️ The 5-Stage Compilation Pipeline

The translation and compiler workflow runs in a foreground [PipelineCompilerService](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/PipelineCompilerService.kt):

```
┌─────────────────┐      1. Audio Extraction
│  Input Video    ├─────────────────────────────┐
└─────────────────┘                             │
                                                ▼
┌─────────────────┐      2. Transcription       [AudioDecoder] Decodes and resamples
│  manifest.json  │◄────────────────────────────┴─► 16kHz mono PCM WAV. (Memory-safe)
└────────┬────────┘
         │
         │               3. Hinglish Translation
         ├─────────────────────────────► [GemmaTranslator] Gemma 2B via MediaPipe
         │                               translates English text, constraining length.
         │
         │               4. Voice Synthesis
         ├─────────────────────────────► [DubbingTtsEngine] Android TTS synthesizes Hindi
         │                               WAV chunks, adjusting speech rate up to 2.0x.
         ▼
┌─────────────────┐      5. Packaging
│ Session Folder  ├─────────────────────────────► Writes manifest metadata and places
└─────────────────┘                               all vocal clips in vocal_chunks/.
```

1. **Audio Extraction ([AudioDecoder](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/media/AudioDecoder.kt))**: Decodes the video's audio track, downmixes it to mono, and resamples it on-the-fly to a 16kHz WAV file. Resampling uses linear interpolation with a rolling 16KB writing buffer to avoid memory footprint creep.
2. **ASR Transcription ([VoskTranscriber](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/asr/VoskTranscriber.kt))**: Performs offline Automatic Speech Recognition (ASR) using a lightweight, local Vosk model to segment the speech with exact word-level start and end timestamps.
3. **Hinglish Translation ([GemmaTranslator](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/llm/GemmaTranslator.kt))**: Leverages an on-device LLM (Gemma 2B INT4 via MediaPipe LLM Inference API) with specialized system instructions to translate English phrases to natural Hinglish, preserving scientific terminology (e.g., *Force*, *Cell*, *Gravity*) while translating explanation.
4. **Voice Synthesis ([DubbingTtsEngine](file:///Users/lolet/Downloads/Nua/app/src/main/java/com/example/nua/data/tts/DubbingTtsEngine.kt))**: Uses Android's native Text-to-Speech (TTS) engine configured for Hindi language. It automatically monitors synthesized duration and re-synthesizes at faster speech rates (up to 2.0x) if the Hindi translation overflows the allocated video segment.
5. **Package Output**: Writes the dubbed audio files and sync coordinates to a local directory ready for playback.

---

## 🏗️ Technical Stack

* **Min SDK**: Android 7.0 (API 24)
* **Compile SDK**: Android 16 (API 36)
* **Build System**: Gradle 9.1.0 & Android Gradle Plugin 9.0.1 (featuring built-in Kotlin compilation support)
* **Core Language**: Kotlin (JVM 17 toolchain compatibility)
* **UI Framework**: Jetpack Compose (using Navigation3 for screens routing)
* **Audio/Video Playback**: Android Media3 ExoPlayer & ExoPlayer UI components
* **Speech/AI Models**:
  * Vosk-Android ASR (Offline speech recognition)
  * MediaPipe GenAI Tasks (On-device LLM execution)

---

## 📦 Model Requirements & Setup

To run Nua in **Real Mode** (processing actual videos), you must download the offline models via the **Setup** screen:

1. **ASR Model**: A 40MB English Vosk-small language model (e.g. `vosk-model-small-en-us-0.15`). The app downloads this model as a ZIP archive, unzips it to local storage, and initializes it locally.
2. **LLM Model**: Gemma 2B INT4 in MediaPipe `.bin` format. Copy this model to the application's internal files directory (or download it via the app).
3. **Hindi TTS Engine**: Ensure that the device has the Google Speech Services engine installed with Hindi (India) voice packages downloaded offline (System Settings -> Language & Input -> Text-to-Speech).

*Note: For debugging/testing without model downloads, the application provides a **Mock Mode** that generates simulated segments and offline translation placeholders.*

---

## 🧪 Testing

Nua includes unit tests for verifying the virtual timeline offset calculations. To run tests:
```bash
./gradlew test
```
See [VirtualTimelineMapperTest.kt](file:///Users/lolet/Downloads/Nua/app/src/test/java/com/example/nua/data/media/VirtualTimelineMapperTest.kt) for reference.

---

## 📜 Development & Contributions

### Building from Source
To compile the application and build a debug APK:
```bash
./gradlew assembleDebug
```
The resulting APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
