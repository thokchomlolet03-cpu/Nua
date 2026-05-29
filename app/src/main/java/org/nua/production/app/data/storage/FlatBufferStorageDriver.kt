package org.nua.production.app.data.storage

import android.content.Context
import android.util.Log
import com.google.flatbuffers.FlatBufferBuilder
import org.nua.production.app.data.schema.TelemetryRecord
import java.io.File
import java.io.FileOutputStream

class FlatBufferStorageDriver private constructor(context: Context) {

    private val targetDirectory: File = File(context.applicationContext.cacheDir, "telemetry_records").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        @Volatile
        private var INSTANCE: FlatBufferStorageDriver? = null

        fun getInstance(context: Context): FlatBufferStorageDriver {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FlatBufferStorageDriver(context).also { INSTANCE = it }
            }
        }

        internal fun resetInstance() {
            INSTANCE = null
        }
    }

    /**
     * Serializes a transactional telemetry state into a standalone, zero-copy binary file
     * on disk under strict isolated file boundaries.
     */
    fun writeRecord(sessionId: String, completionPercentage: Int, sequenceVersion: Long): File? {
        val builder = FlatBufferBuilder(128)

        // Compile fields inside FlatBuffer construction queues
        val sessionIdOffset = builder.createString(sessionId)
        
        TelemetryRecord.startTelemetryRecord(builder)
        TelemetryRecord.addSessionId(builder, sessionIdOffset)
        TelemetryRecord.addCompletionPercentage(builder, completionPercentage)
        TelemetryRecord.addSequenceVersion(builder, sequenceVersion.toULong())
        TelemetryRecord.addTimestamp(builder, System.currentTimeMillis().toULong())
        
        val rootOffset = TelemetryRecord.endTelemetryRecord(builder)
        builder.finish(rootOffset)

        // Extract the raw structural byte array directly
        val binaryData = builder.sizedByteArray()

        // Write out directly to an isolated target file boundary (.tlm)
        val fileTarget = File(targetDirectory, "TX_${sessionId}_v${sequenceVersion}.tlm")
        
        return try {
            FileOutputStream(fileTarget).use { stream ->
                stream.write(binaryData)
                stream.flush()
            }
            Log.d("FlatBufferStorageDriver", "Successfully decoupled data boundary. Record stored: ${fileTarget.name}")
            fileTarget
        } catch (e: Exception) {
            Log.e("FlatBufferStorageDriver", "Failed to write isolated binary segment to storage disk", e)
            null
        }
    }

    fun getRecordDirectory(): File = targetDirectory
}
