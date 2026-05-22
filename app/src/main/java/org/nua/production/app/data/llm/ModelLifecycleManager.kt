package org.nua.production.app.data.llm

import android.content.Context
import android.util.Log
import org.nua.production.app.data.rag.OfflineTutorEngine

/**
 * TRIZ Principle 15 (State Interleaving/Dynamism) & Principle 2 (Extraction)
 * Coordinates loading/unloading of translation and tutoring models to prevent out-of-memory crashes.
 */
object ModelLifecycleManager {
    private const val TAG = "ModelLifecycleManager"
    private var activeModelType: String? = null // "TRANSLATOR" or "TUTOR"
    
    private var translatorInstance: LiteRTTranslator? = null
    private var tutorEngineInstance: OfflineTutorEngine? = null

    suspend fun switchToTutor(context: Context, tutorModelPath: String): OfflineTutorEngine {
        Log.d(TAG, "switchToTutor requested. Current active model: $activeModelType")
        if (activeModelType == "TUTOR" && tutorEngineInstance != null) {
            return tutorEngineInstance!!
        }
        
        // Unload translator if loaded
        translatorInstance?.close()
        translatorInstance = null
        Log.d(TAG, "Unloaded translator model from memory")

        // Load tutor engine
        val engine = OfflineTutorEngine(context.applicationContext)
        engine.initializeEngine(tutorModelPath)
        tutorEngineInstance = engine
        activeModelType = "TUTOR"
        Log.d(TAG, "Loaded and initialized Tutor model ($tutorModelPath)")
        return engine
    }

    suspend fun switchToTranslator(context: Context, translatorModelPath: String): LiteRTTranslator {
        Log.d(TAG, "switchToTranslator requested. Current active model: $activeModelType")
        if (activeModelType == "TRANSLATOR" && translatorInstance != null) {
            return translatorInstance!!
        }

        // Unload tutor engine if loaded
        tutorEngineInstance?.close()
        tutorEngineInstance = null
        Log.d(TAG, "Unloaded Tutor model from memory")

        // Load translator
        val translator = LiteRTTranslator(context.applicationContext)
        translator.initModel(translatorModelPath)
        translatorInstance = translator
        activeModelType = "TRANSLATOR"
        Log.d(TAG, "Loaded and initialized translator model ($translatorModelPath)")
        return translator
    }

    fun releaseAll() {
        Log.d(TAG, "releaseAll requested. Unloading all models.")
        translatorInstance?.close()
        translatorInstance = null
        tutorEngineInstance?.close()
        tutorEngineInstance = null
        activeModelType = null
    }
}
