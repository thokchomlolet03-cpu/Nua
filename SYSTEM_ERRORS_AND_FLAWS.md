# Nua — System Errors, Flaws & Technical Debt Registry

> **Revision**: 7 (Final Backend Remediation)
> **Date**: 2026-05-22
> **Status**: 🟢 All active bugs resolved (both Android and Backend). Only technical debt remains.

---

## Active Issues
**None.** The system is fully stabilized.

---

## Resolved Issues — Current Session (Backend)

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

| Category | Item | Priority |
|---|---|---|
| **Testing** | ~8% test coverage (2/25 files on Android, 0 backend tests) | 🔴 High |
| **Build** | R8/ProGuard disabled (`isMinifyEnabled = false`) | 🟡 Medium |
| **Security** | No authentication on backend endpoints (`index.ts`) | 🟡 Medium |
| **Security** | No rate limiting on `/api/v1/ingest` (`index.ts`) | 🟡 Medium |
| **Schema** | `quiz_scores_json:string` violates no-ad-hoc-JSON invariant | 🟢 Minor |
| **Schema** | No schema version field for evolution | 🟢 Minor |
| **Schema** | `courseTitle` field stores `sourceVideoPath` (naming mismatch) | 🟢 Minor |
| **Build** | `com.example.nua` namespace (example domain) | 🟢 Minor |
| **Pipeline** | Two-pass TTS synthesis is wasteful | 🟢 Minor |
| **Pipeline** | 44-byte WAV header assumption (3 places) | 🟢 Minor |
| **Manifest** | `allowBackup="true"` — session data extractable via ADB | 🟢 Minor |
