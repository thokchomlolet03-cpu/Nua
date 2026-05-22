# Nua — System Errors, Flaws & Technical Debt Registry

> **Revision**: 4 (Exhaustive Ecosystem Audit)
> **Date**: 2026-05-22
> **Status**: 🟡 8 active items identified, 13 previously resolved

---

## Active Issues

### 🔴 Critical

#### B1 — Leaking CoroutineScope in MainScreenViewModel
- **File**: `MainScreenViewModel.kt:173`
- **Description**: `startDubbingVideoFromUrl()` creates a standalone `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is not tied to `viewModelScope`. This scope survives ViewModel clearing and cannot be cancelled, causing a memory/coroutine leak.
- **Fix**: Replace with `viewModelScope.launch(Dispatchers.IO) { ... }`.
- **Impact**: Memory leak + potential background work after user navigates away.

---

### 🟡 Moderate

#### B2 — Hotspots Silently Dropped During FlatBuffers Serialization
- **File**: `SessionManager.kt:76`
- **Description**: `hotspotsOffset = 0` in `saveManifest()` means no hotspots vector is written to the `.nuab` file. Hotspots survive in-memory during a session but are permanently lost when saved and reloaded from disk.
- **Fix**: Serialize hotspot data properly: iterate `seg.hotspots`, create `Hotspot.createHotspot()` for each, build a hotspots vector, and pass it to `TimeSegment.createTimeSegment()`.
- **Impact**: Vocabulary hotspots disappear after app restart.

#### B3 — Streaming Translation Bypasses Mutex
- **File**: `LiteRTTranslator.kt:134–151`
- **Description**: `translateStreaming()` does NOT acquire the `translationMutex` that protects `translate()`. If both are called concurrently, they could access the LiteRT-LM `Engine` simultaneously, causing undefined behavior or crashes.
- **Fix**: Wrap the streaming path in `translationMutex.withLock { ... }` or use a shared access pattern.
- **Impact**: Potential crash if streaming and blocking translation overlap.

#### B4 — Thread-Unsafe Quiz Deduplication Set
- **File**: `PlayerViewModel.kt:71`
- **Description**: `shownQuizTimestamps = mutableSetOf()` is a plain `HashSet`. If `onStateUpdate` fires from a non-main thread while `seekTo` modifies the set on the main thread, a `ConcurrentModificationException` could occur.
- **Fix**: Replace with `Collections.synchronizedSet(mutableSetOf())` or `ConcurrentHashMap.newKeySet()`.
- **Impact**: Potential crash during seek + quiz trigger race.

#### B5 — PAD_EMPTY Directive Lost in Round-Trip
- **File**: `SessionManager.kt:223`
- **Description**: The FlatBuffers schema only stores a `shouldFreeze: bool` flag. On deserialization, segments are mapped to either `FREEZE_HOLD` or `NORMAL_SYNC`. The `PAD_EMPTY` directive (assigned when TTS fails) is lost, causing silent segments to be treated as normal sync segments after save/load.
- **Fix**: Add a `directive:string` field to the FlatBuffers `TimeSegment` table, or use a `ubyte` enum.
- **Impact**: Subtle playback timing issues for segments where TTS originally failed.

---

### 🟢 Minor

#### B6 — Dead Memory-Mapping Code in loadManifestBinary
- **File**: `SessionManager.kt:147–149`
- **Description**: `RandomAccessFile` and `FileChannel` are opened inside `use {}` blocks but never used for memory-mapping. The actual read is done via `file.readBytes()`. This is dead code from an abandoned memory-mapping implementation.
- **Fix**: Remove `RandomAccessFile`/`FileChannel`, keep `file.readBytes()`.
- **Impact**: Code cleanliness; no functional impact.

#### B7 — Audio Downmix Integer Truncation
- **File**: `AudioDecoder.kt:123`
- **Description**: Stereo-to-mono downmix uses `sum / sourceChannels` (integer division), which truncates rather than rounds. For stereo input, this loses ~0.5 LSB of precision.
- **Fix**: Use `(sum + sourceChannels / 2) / sourceChannels` for proper rounding.
- **Impact**: Negligible audio quality loss; inaudible in practice.

#### B8 — RAG Tutor Aggressive Partial Matching
- **File**: `OfflineTutorEngine.kt:120`
- **Description**: Bidirectional partial matching (`keyword.contains(promptWord)`) causes false positives. For example, `"photosynthesis".contains("the")` evaluates to `true`, polluting relevance scores.
- **Fix**: Use whole-word matching or minimum token length threshold (e.g., skip words < 3 characters).
- **Impact**: Tutor may select wrong knowledge graph node for short common words.

---

## Previously Resolved Issues (v3.0)

All 13 edge cases from the Phase 3 audit have been resolved:

| ID | Severity | Fix Summary |
|---|---|---|
| C1 | 🔴 Critical | ZIP Slip vulnerability patched with canonical path validation |
| C2 | 🔴 Critical | TTS race condition fixed with `AtomicBoolean` signaling |
| C3 | 🔴 Critical | Non-volatile `synthesisSuccess` fixed with `@Volatile` |
| C4 | 🔴 Critical | Thread-safe `addLog()` with `synchronized(_logs)` block |
| C5 | 🔴 Critical | `codec.stop()` removed from finally block (IllegalStateException) |
| C6 | 🟡 Moderate | Division by zero guard in AudioDecoder downmix |
| M7 | 🟡 Moderate | OkHttp response body leak fixed with `.use {}` |
| M8 | 🟡 Moderate | Hardcoded unzip progress replaced with two-pass entry counting |
| M9 | 🟡 Moderate | TTS speech rate reset after synthesis |
| M11 | 🟡 Moderate | Duplicate compilation guard added |
| M12 | 🟡 Moderate | Session directory collision fixed with timestamp suffix |
| M13 | 🟡 Moderate | Vosk model close method added |
| L9 | 🟢 Minor | Deprecated `stopForeground(true)` replaced with API-level check |

---

## Technical Debt Registry

| Category | Item | Priority |
|---|---|---|
| **Testing** | ~8% test coverage (2/25 files) | 🔴 High |
| **Build** | R8/ProGuard disabled (`isMinifyEnabled = false`) | 🔴 High |
| **Security** | No authentication on backend endpoints | 🔴 High |
| **Security** | No rate limiting on `/api/v1/ingest` | 🟡 Medium |
| **Schema** | `quiz_scores_json:string` violates no-ad-hoc-JSON invariant | 🟡 Medium |
| **Schema** | No schema version field for evolution | 🟡 Medium |
| **Schema** | `courseTitle` field stores `sourceVideoPath` (naming mismatch) | 🟡 Medium |
| **Build** | `com.example.nua` namespace (example domain) | 🟡 Medium |
| **UI** | `ClickableText` deprecated in PlayerScreen | 🟡 Medium |
| **UI** | `videoUrl` is local compose state (lost on config change) | 🟢 Minor |
| **Theme** | Dead `LightColorScheme`, unused `dynamicColor` param, dead `Build` import | 🟢 Minor |
| **Theme** | `AccentPink` color defined but never used | 🟢 Minor |
| **Theme** | `Type.kt` nearly empty — all styling is inline | 🟢 Minor |
| **Backend** | Gemini response parsing uses greedy regex | 🟢 Minor |
| **Backend** | `NuaBundler` creates unnecessary `Buffer.from()` copy | 🟢 Minor |
| **Pipeline** | Two-pass TTS synthesis is wasteful | 🟢 Minor |
| **Pipeline** | 44-byte WAV header assumption (3 places) | 🟢 Minor |
| **Manifest** | `allowBackup="true"` — session data extractable via ADB | 🟢 Minor |
