# Nua — System Errors, Flaws & Technical Debt Registry

> **Revision**: 9 (v4.0 Final Audit Complete)
> **Date**: 2026-05-22
> **Status**: 🟢 All bugs resolved. 10/11 technical debt items eliminated in v4.0 overhaul.

---

## Active Issues
**None.** The system is fully stabilized.

---

## Resolved in v4.0 — Technical Debt Eliminated

| Former Debt Item | Resolution |
|---|---|
| 🔴 ~8% test coverage | Added `SchemaValidationTest.kt` (5 tests) + `WavUtilsTest.kt` (8 tests) |
| 🟡 R8/ProGuard disabled | Enabled in `build.gradle.kts` with `proguard-rules.pro` |
| 🟡 No backend authentication | HMAC-SHA256 signature verification via `x-nua-signature` header |
| 🟡 No rate limiting | `express-rate-limit` (5 req/15min) on `/api/v1/ingest` |
| 🟢 `quiz_scores_json:string` | Replaced with typed `quiz_responses:[OptionSelection]` |
| 🟢 No schema version field | Added `schema_version:ushort = 1` + `file_identifier "NUAB"` |
| 🟢 `courseTitle` naming mismatch | Added `source_video_path:string`; `course_title` deprecated |
| 🟢 44-byte WAV header assumption | Dynamic RIFF chunk parser in `WavUtils.kt` |
| 🟢 `allowBackup="true"` | Changed to `false` + `fullBackupContent="false"` |
| 🟢 Phonetic duration missing | Added `estimatePhoneticDurationMs()` in `DubbingTtsEngine.kt` |
| 🟢 `com.example.nua` namespace | Fully migrated to production namespace `org.nua.production.app` |
| 🟢 Telemetry P2P mesh relay | Implemented dynamic `WifiDirectMeshManager` in `TelemetryStub.kt` |

---

## Resolved Issues — Previous Sessions (Backend)

| ID | Severity | Fix Summary |
|---|---|---|
| B9 | 🔴 Critical | Regenerated FlatBuffers TS schema; updated `NuaBundler.ts` to supply `directive` |
| B10 | 🟡 Moderate | Implemented exponential backoff `withRetry` loop for Gemini API calls in `TranslationAgent.ts` |
| B11 | 🟡 Moderate | Replaced greedy regex with precise `indexOf` / `lastIndexOf` bracket extraction in `TranslationAgent.ts` |

---

## Resolved Issues — Previous Sessions (Android)

| ID | Severity | Fix Summary |
|---|---|---|
| B1 | 🔴 Critical | Replaced leaked `CoroutineScope` with `viewModelScope` |
| B2 | 🟡 Moderate | Properly serialized hotspots via `Hotspot.createHotspot` + vector |
| B3 | 🟡 Moderate | Wrapped streaming translation in `translationMutex.withLock` |
| B4 | 🟡 Moderate | Replaced `mutableSetOf` with `Collections.synchronizedSet` |
| B5 | 🟡 Moderate | Added `directive:string` field to FlatBuffers schema for lossless round-trip |
| B6 | 🟢 Minor | Removed dead `RandomAccessFile`/`FileChannel` wrappers |
| B7 | 🟢 Minor | Fixed audio downmix truncation with proper rounding |
| B8 | 🟢 Minor | Fixed aggressive RAG matching with length-gated exact word match |

*(Additionally, 13 edge cases C1-C6, M7-M13, L9 from Phase 3 audit were resolved in prior sessions).*

---

## Remaining Technical Debt (Low Priority)

| Category | Item | Priority | Notes |
|---|---|---|---|
| **AI** | Quantized tutor model | 🟢 Minor | Requires dedicated model training pipeline |
