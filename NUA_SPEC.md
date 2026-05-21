# NUA_SPEC.md — Architectural Boundaries

## System Boundary: Nua Ecosystem

The Nua ecosystem consists of two independent modules sharing a single FlatBuffers data contract:

### Module 1: Nua Edge (Android Client)
- **Location**: Project root (`app/`)
- **Build system**: Gradle (Kotlin DSL)
- **Purpose**: On-device video playback, offline ASR, local LLM translation, TTS synthesis, dual-player sync
- **AI runtime**: LiteRT-LM (NPU-accelerated, offline)
- **Data format**: `.nuab` FlatBuffers binary bundles

### Module 2: Nua Web Studio (Cloud Backend)
- **Location**: `backend/`
- **Build system**: Node.js / TypeScript
- **Purpose**: Cloud video ingestion, Gemini 3.5 Flash translation, knowledge graph pre-baking, CDN distribution
- **AI runtime**: Gemini 3.5 Flash via `@google/genai` SDK
- **Data format**: `.nuab` FlatBuffers binary bundles

### Shared Contract
- **Schema**: `schema/nua_schema.fbs`
- **Compilation**: `./compile_schema.sh` (on-demand, not in build loop)

### Invariants
1. **Gradle isolation**: `settings.gradle.kts` includes only `:app`. The `backend/` directory is invisible to Android builds.
2. **Schema-first**: All data structures originate from `nua_schema.fbs`. No ad-hoc JSON structures.
3. **Offline-first**: The Android client must function fully without network connectivity.
4. **Zero-transcoding**: The original video is never re-encoded. Dubbing is achieved through dual-player sync at playback time.
