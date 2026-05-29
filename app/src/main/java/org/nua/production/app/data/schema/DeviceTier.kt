package org.nua.production.app.data.schema

enum class DeviceTier {
    UNKNOWN,
    BUDGET,   // < 8GB RAM: Gemma 2B via LiteRT
    PREMIUM   // >= 8GB RAM: Gemma 4 E2B + Visual RAG
}
