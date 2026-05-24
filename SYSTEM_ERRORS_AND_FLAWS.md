# Nua — System Errors, Flaws & Technical Debt Registry

> **Revision**: 14 (v4.3 Complete Architectural Remediation)
> **Date**: 2026-05-24
> **Status**: 🟢 0 Active Vulnerabilities. 26/26 Flaws successfully patched.

---

## Active Issues (0)

**None.** All vulnerabilities and structural flaws identified in the Deep Technical Audit have been successfully resolved.

---

## Resolved in v4.3 — Deep Technical Audit & Patching

| Severity | Component | Issue | Fix Summary |
|---|---|---|---|
| 🔴 CRITICAL | `audio.ts` | SSRF Bypass via non-standard IP encodings (integer IPs) | Implemented strict IPv4/IPv6 normalization and blocked private ranges. |
| 🔴 CRITICAL | `audio.ts` | Arbitrary File Read / SSRF via FFmpeg HLS Playlists | Forced `protocol_whitelist` to `file,http,https,tcp,tls`. |
| 🔴 CRITICAL | `audio.ts` | TOCTOU DNS Rebinding SSRF | Cached resolved safe IPs and forced DNS resolution via custom HTTP agent. |
| 🔴 CRITICAL | `TelemetryStub.kt` | HMAC Cryptographic Vulnerability (Answers not hashed) | Included quiz responses string builder in HMAC payload signature. |
| 🔴 CRITICAL | `OfflineTutorEngine.kt` | NPU Concurrency Crash (Parallel inference execution) | Added `Mutex` locking to guard `executeGraphQuery` against parallel access. |
| 🔴 CRITICAL | `SyncPlayerEngine.kt` | Drift correction math breaks ADR time-stretching | Corrected ratio math to only slow down video while keeping AI audio at 1.0x. |
| 🔴 CRITICAL | `SyncPlayerEngine.kt` | Premature hard-unfreeze via `STATE_ENDED` | Tracked `currentMediaItemIndex` to unfreeze only when the correct chunk ends. |
| 🔴 CRITICAL | `SyncPlayerEngine.kt` | `AudioEffect` (Equalizer) Native Resource Leak | Explicitly called `release()` on equalizer instance during teardown. |
| 🔴 CRITICAL | `PipelineCompilerService.kt` | Notification DDoS crashing System UI | Throttled notification updates to max 2Hz (every 500ms). |
| 🟡 HIGH | `index.ts` | Unhandled Promise Rejections (`null` body, `mkdtempSync`) | Added error wrappers and strict null checks on request payloads. |
| 🟡 HIGH | `TelemetryStub.kt` | Thread Exhaustion DoS in P2P Server | Replaced unbounded threads with `Executors.newFixedThreadPool(4)`. |
| 🟡 HIGH | `TelemetryStub.kt` | Path Traversal Vulnerability in ledger payload writer | Sanitized `sessionId` using `Regex("[^a-zA-Z0-9_-]")`. |
| 🟡 HIGH | `TelemetryStub.kt` | Uncaught SecurityException for Location Permissions | Handled permission errors for `WifiP2pManager.discoverPeers`. |
| 🟡 HIGH | `VoskTranscriber.kt` | Race Condition / Native Leak in `initModel()` | Protected native init with `Mutex.withLock`. |
| 🟡 HIGH | `VoskTranscriber.kt` | Thread Safety / Native Crash in `close()` | Protected teardown sequence with `Mutex`. |
| 🟡 HIGH | `LiteRTTranslator.kt` | Race Condition / Native Leak in `initModel()` | Implemented mutex guarding around native engine bindings. |
| 🟡 HIGH | `LiteRTTranslator.kt` | Thread Safety / Native Crash in `close()` | Ensured thread-safe unbinding and deallocation via Mutex. |
| 🟡 HIGH | `OfflineTutorEngine.kt` | Race Condition / Native Leak in `initializeEngine()` | Locked engine bootstrapping to single thread. |
| 🟡 HIGH | `OfflineTutorEngine.kt` | Thread Safety / Native Crash in `close()` | Secured native release sequence with `withLock`. |
| 🟡 HIGH | `SyncPlayerEngine.kt` | Equalizer not recreated on `AudioSessionId` change | Re-initialized Equalizer instance gracefully when audio session shifts. |
| 🟡 HIGH | `PipelineCompilerService.kt` | `FileChannel.transferTo` silent failure on >2GB files | Replaced direct transfer with block-by-block `ByteBuffer` stream copy loop. |
| 🔵 MODERATE | `TranslationAgent.ts` | Prompt Injection via unsanitized context docs | Scrubbed XML tags and restricted prompt payload variables. |
| 🔵 MODERATE | `TranslationAgent.ts` | Brittle JSON parsing via `indexOf` extraction | Hardened extraction to support nested braces via counting indices. |
| 🔵 MODERATE | `VoskTranscriber.kt` | FileOutputStream resource leaks in download/unzip | Enclosed stream processing inside strict `.use {}` blocks. |
| 🔵 MODERATE | `TelemetryStub.kt` | Socket leak ignoring `errorStream` in `flushToServer` | Closed streams via `.use` and added finally blocks for connection teardown. |
| 🔵 MODERATE | `SyncPlayerEngine.kt` | `isSeeking` flag stuck state disabling drift correction | Reset flag correctly via `onPositionDiscontinuity` `DISCONTINUITY_REASON_SEEK`. |
| 🔵 MODERATE | `SyncPlayerEngine.kt` | Hardcoded volume resetting breaks room tone soft-ducking | Restored `0.05f` soft ducking floor on active intervals. |
| 🟢 LOW | `TranslationAgent.ts` | Remote Resource Leak via duplicate uploads in `withRetry` | Deleted aborted file uploads proactively inside the retry loop. |
| 🟢 LOW | `VoskTranscriber.kt` | Logic Error / Audio Artifacts due to `InputStream.skip` | Handled `.skip` return mismatch correctly inside a `while` loop. |
| 🟢 LOW | `LiteRTTranslator.kt` | Logic Error in mock punctuation stripping | Skipped punctuation correctly utilizing substring replacements.

---

## Resolved in v4.1 — Stabilized and Hardened

| Severity | Component | Issue | Fix Summary |
|---|---|---|---|
| 🔴 CRITICAL | `TelemetryStub.kt` | Hardcoded HMAC fallback and unauthenticated TCP peer socket | Injected signingSecret dynamically; implemented random challenge-response P2P socket authentication handshake. |
| 🔴 CRITICAL | `TelemetryStub.kt` | Trivially forgeable SHA-256 signature on telemetry payload | Upgraded signature verification to secure HMAC-SHA256. |
| 🔴 CRITICAL | `ModelLifecycleManager.kt` | Singleton accessed concurrently without synchronization | Added `Mutex` locking on all model loading/unloading tasks. |
| 🔴 CRITICAL | `PlayerScreen.kt` | Overlapping hotspot ranges corrupt annotated subtitles | Sorted and filtered overlapping hotspots using sweep-line interval scheduling. |
| 🔴 CRITICAL | `PlayerViewModel.kt` | ViewModel cleanup cancelled on cleared | Ran player releasing and telemetry flushing in non-cancellable scope. |
| 🔴 CRITICAL | `PipelineCompilerService.kt` | Static companion states blocking compiles | Explicitly reset StateFlow statuses in `onCreate()` and `onDestroy()`. |
| 🔴 CRITICAL | `index.ts` | Hardcoded fallback HMAC secret bypass | Removed fallback; rejected verification if key is missing. |
| 🔴 CRITICAL | `NuaSchema.kt` | 32-bit timestamp truncation | Upgraded `Quiz.trigger_timestamp_ms` to `ulong` (64-bit) in FlatBuffers. |
| 🟡 HIGH | `VirtualTimelineMapper.kt` | Constructor blocks main thread with WAV header reads | Decoupled constructor from I/O; introduced async factory `create` method. |
| 🟡 HIGH | `WavUtils.kt` | skipBytes truncation on >2GB WAV files | Replaced `skipBytes(toInt())` with 64-bit safe `seek` operations. |
| 🟡 HIGH | `index.ts` | Timing-unsafe body verification | Re-routed HMAC calculation to raw body buffer and verified with `crypto.timingSafeEqual`. |
| 🟡 HIGH | `audio.ts` | FFmpeg audio extraction SSRF | Implemented domain DNS resolution and IP checks blocking private/local addresses. |
| 🟡 HIGH | `TranslationAgent.ts` | Unvalidated LLM response deserialization | Added runtime field structure and type validators for translated responses. |
| 🟡 HIGH | `build.gradle.kts` | Lint warning suppressions | Set `abortOnError = true` and enabled release build checks. |
| 🟡 HIGH | `Navigation.kt` | UnsafeOptInUsageError propagation from Compose router | Suppressed experimental API propagation via `@androidx.annotation.OptIn` on `MainNavigation`. |
| 🟢 MINOR | `PlayerScreen.kt` | Incorrect generic marker applied to `@OptIn` block | Separated `@OptIn` parameters and directly annotated composable with `@androidx.media3.common.util.UnstableApi`. |
| 🟢 MINOR | `PlayerScreen.kt` | Deprecated Compose Icon invocation | Migrated `Icons.Filled.Send` to `Icons.AutoMirrored.Filled.Send` and handled receiver mismatches. |

---

## Resolved in v4.0 — Technical Debt Eliminated

| Former Debt Item | Resolution |
|---|---|
| 🔴 ~8% test coverage | Added `SchemaValidationTest.kt` (5 tests) + `WavUtilsTest.kt` (8 tests) + `TelemetryStoreTest.kt` (4 tests) |
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
| 🟢 Quantized tutor model | Automated compilation, quantization, and packaging pipeline via `tools/compile_tutor_model.py` |
| 🟢 O(n) Playhead Scan | Optimized search in `VirtualTimelineMapper.kt` to $O(\log n)$ using binary search on pre-sorted arrays |
| 🟢 Telemetry Integration | Integrated `LocalTelemetryStore` in `PlayerViewModel` and `PlayerScreen` to track completion and quiz option selections, flushing offline records on release |

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

**None.** All technical debt has been successfully resolved.
