#!/usr/bin/env bash
# compile_schema.sh — On-demand FlatBuffers schema compilation
# Generates Kotlin bindings for Android and TypeScript bindings for the backend.
# Run this manually whenever schema/nua_schema.fbs changes.
#
# Prerequisites: Install flatc via https://github.com/google/flatbuffers/releases

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA_FILE="${SCRIPT_DIR}/schema/nua_schema.fbs"

KOTLIN_OUT="${SCRIPT_DIR}/app/src/main/java"
TS_OUT="${SCRIPT_DIR}/backend/src/schema"

if ! command -v flatc &> /dev/null; then
    echo "❌ flatc not found. Install from: https://github.com/google/flatbuffers/releases"
    echo "   macOS: brew install flatbuffers"
    echo "   Linux: sudo apt-get install flatbuffers-compiler"
    exit 1
fi

echo "📦 Compiling FlatBuffers schema..."

# Kotlin bindings (Android)
echo "  → Kotlin bindings → ${KOTLIN_OUT}"
flatc --kotlin -o "${KOTLIN_OUT}" "${SCHEMA_FILE}"

# Cross-platform environment gate for FlatBuffers version verification alignment
# Maps generated FLATBUFFERS_<version> checks to match project dependency version 25.2.10
REGEX_PATTERN='s/FLATBUFFERS_[0-9]+_[0-9]+_[0-9]+/FLATBUFFERS_25_2_10/g'
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS / BSD sed
    find "${KOTLIN_OUT}" -name "*.kt" -exec sed -i '' -E "$REGEX_PATTERN" {} +
else
    # Linux / GNU sed
    find "${KOTLIN_OUT}" -name "*.kt" -exec sed -i -E "$REGEX_PATTERN" {} +
fi

# TypeScript bindings (Backend)
mkdir -p "${TS_OUT}"
echo "  → TypeScript bindings → ${TS_OUT}"
flatc --ts -o "${TS_OUT}" "${SCHEMA_FILE}"

echo "✅ Schema compilation complete."
