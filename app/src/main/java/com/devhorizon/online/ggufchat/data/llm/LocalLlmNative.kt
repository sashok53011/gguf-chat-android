package com.devhorizon.online.ggufchat.data.llm

import android.util.Log

object LocalLlmNative {
    private const val TAG = "LocalLlmNative"

    init {
        try {
            System.loadLibrary("llama_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load llama_jni: ${e.message}")
        }
    }

    interface TokenCallback {
        fun onToken(token: String)
        fun onComplete(fullResponse: String)
    }

    interface ProgressCallback {
        fun onProgress(progress: Float)
    }

    external fun nativeLoadModel(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        progressCallback: ProgressCallback
    ): Long

    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        templateName: String
    ): String

    external fun nativeCancel(handle: Long)

    external fun nativeLoadMmproj(handle: Long, mmprojPath: String): Boolean

    external fun nativeHasMmproj(handle: Long): Boolean

    /** Placeholder token the prompt must contain where the image goes (mtmd). */
    external fun nativeMediaMarker(): String

    external fun nativeGenerateStreamingVision(
        handle: Long,
        prompt: String,
        rgbBytes: ByteArray,
        width: Int,
        height: Int,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopToken: String?,
        callback: TokenCallback
    ): String

    external fun nativeGenerateStreaming(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopToken: String?,
        callback: TokenCallback
    ): String

    external fun nativeFreeModel(handle: Long)

    /** Return free native (llama) arena memory to the OS. */
    external fun nativeFreeMemory()

    external fun nativeIsModelLoaded(handle: Long): Boolean
}
