package com.example.nua.data.media

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

class SessionManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun getSessionsRootDir(): File {
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getSessionDir(videoName: String): File {
        val sanitized = videoName.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9_]"), "_")
        val timestamp = System.currentTimeMillis()
        val dir = File(getSessionsRootDir(), "session_${sanitized}_$timestamp")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getVocalChunksDir(sessionDir: File): File {
        val dir = File(sessionDir, "vocal_chunks")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveManifest(sessionDir: File, composition: MediaComposition) {
        val file = File(sessionDir, "manifest.json")
        val string = json.encodeToString(MediaComposition.serializer(), composition)
        file.writeText(string)
    }

    fun loadManifest(sessionDir: File): MediaComposition? {
        val file = File(sessionDir, "manifest.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString(MediaComposition.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun listCompletedSessions(): List<File> {
        val root = getSessionsRootDir()
        val dirs = root.listFiles { file ->
            file.isDirectory && file.name.startsWith("session_") && File(file, "manifest.json").exists()
        }
        return dirs?.sortedByDescending { File(it, "manifest.json").lastModified() } ?: emptyList()
    }

    fun deleteSession(sessionDir: File): Boolean {
        return sessionDir.deleteRecursively()
    }
}
