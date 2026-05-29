package org.nua.production.app.data.sync

import android.util.Log

/**
 * High-performance state projection model mirroring compiled FlatBuffer attributes.
 */
data class TelemetryState(
    val sessionId: String,
    val completionPercentage: Int,
    val sequenceVersion: Long, // Auto-incrementing version step counter
    val deviceTimestamp: Long  // Wall-clock reference (ignored for data sorting logic)
)

class ConflictResolver {

    /**
     * Reconciles a localized data conflict between existing local storage state 
     * and a payload received over the P2P mesh interface.
     *
     * Discards wall-clock comparisons completely to guarantee immunity from clock-drift.
     *
     * @param local The current record stored in the local device FlatBuffer ledger.
     * @param incoming The peer record extracted from an incoming mesh socket connection.
     * @return The structurally superior record that should be committed to disk.
     */
    fun resolveMerge(local: TelemetryState?, incoming: TelemetryState): TelemetryState {
        if (local == null) {
            Log.d("ConflictResolver", "No local snapshot exists for session: ${incoming.sessionId}. Accepting incoming packet.")
            return incoming
        }

        Log.d("ConflictResolver", "Reconciling state for session [${incoming.sessionId}] -> " +
                "Local Version: v${local.sequenceVersion}, Incoming Version: v${incoming.sequenceVersion}")

        // Deterministic Monotonic Evaluation Rule: Higher iteration number wins
        return if (incoming.sequenceVersion > local.sequenceVersion) {
            Log.i("ConflictResolver", "Incoming state mutation is newer (v${incoming.sequenceVersion} > v${local.sequenceVersion}). Overwriting local state.")
            incoming
        } else {
            Log.d("ConflictResolver", "Incoming state is stale or identical (v${incoming.sequenceVersion} <= v${local.sequenceVersion}). Retaining local records.")
            local
        }
    }
}
