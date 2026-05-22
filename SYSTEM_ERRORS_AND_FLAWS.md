# Nua — System Errors, Flaws & Technical Debt Registry

> **Revision**: 5 (Post-Remediation — All Active Bugs Fixed)
> **Date**: 2026-05-22
> **Status**: 🟢 All active bugs resolved. Only technical debt remains.

---

## Active Issues

### 🟢 All Clear
No active bugs remain. All 8 previously active issues (B1–B8) and 13 Phase 3 issues have been fully resolved.

---

## Resolved Issues — Current Session (v3.1)

| ID | Severity | Fix Summary | File(s) |
|---|---|---|---|
| B1 | 🔴 Critical | Replaced leaked `CoroutineScope` with `viewModelScope` | `MainScreenViewModel.kt` |
| B2 | 🟡 Moderate | Properly serialized hotspots via `Hotspot.createHotspot` + vector | `SessionManager.kt` |
| B3 | 🟡 Moderate | Wrapped streaming translation in `translationMutex.withLock` | `LiteRTTranslator.kt` |
| B4 | 🟡 Moderate | Replaced `mutableSetOf` with `Collections.synchronizedSet` | `PlayerViewModel.kt` |
| B5 | 🟡 Moderate | Added `directive:string` field to FlatBuffers schema for lossless round-trip | `nua_schema.fbs`, `NuaSchema.kt`, `SessionManager.kt` |
| B6 | 🟢 Minor | Removed dead `RandomAccessFile`/`FileChannel` wrappers | `SessionManager.kt` |
| B7 | 🟢 Minor | Fixed audio downmix truncation with proper rounding | `AudioDecoder.kt` |
| B8 | 🟢 Minor | Fixed aggressive RAG matching with length-gated exact word match | `OfflineTutorEngine.kt` |

### Additional Technical Debt Resolved

| Fix | Details | File(s) |
|---|---|---|
| Deprecated `ClickableText` migrated | Replaced with modern `Text` + `LinkAnnotation.Clickable` | `PlayerScreen.kt` |
| Theme dead code removed | Removed `LightColorScheme`, dead `Build` import, `dynamicColor` param, 6 unused colors | `Theme.kt`, `Color.kt` |
| `videoUrl` state hoisted | Moved from local Compose state to ViewModel-backed `StateFlow` | `MainScreenViewModel.kt`, `MainScreen.kt` |

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

## Remaining Technical Debt (Low Priority)

| Category | Item | Priority |
|---|---|---|
| **Testing** | ~8% test coverage (2/25 files) | 🔴 High |
| **Build** | R8/ProGuard disabled (`isMinifyEnabled = false`) | 🟡 Medium |
| **Security** | No authentication on backend endpoints | 🟡 Medium |
| **Security** | No rate limiting on `/api/v1/ingest` | 🟡 Medium |
| **Schema** | `quiz_scores_json:string` violates no-ad-hoc-JSON invariant | 🟢 Minor |
| **Schema** | No schema version field for evolution | 🟢 Minor |
| **Schema** | `courseTitle` field stores `sourceVideoPath` (naming mismatch) | 🟢 Minor |
| **Build** | `com.example.nua` namespace (example domain) | 🟢 Minor |
| **Backend** | Gemini response parsing uses greedy regex | 🟢 Minor |
| **Backend** | `NuaBundler` creates unnecessary `Buffer.from()` copy | 🟢 Minor |
| **Pipeline** | Two-pass TTS synthesis is wasteful | 🟢 Minor |
| **Pipeline** | 44-byte WAV header assumption (3 places) | 🟢 Minor |
| **Manifest** | `allowBackup="true"` — session data extractable via ADB | 🟢 Minor |
