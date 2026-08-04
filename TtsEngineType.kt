package com.example.app_abdelbaset

enum class TtsEngineType {
    ANDROID_TTS,       // LocalTtsEngine - Android built-in
    SHERPA_SUPERTONIC, // Sherpa-ONNX Supertonic (supertonic-3-tts)
    SHERPA_VITS_PIPER  // Sherpa-ONNX VITS Piper (e.g., en_US-bryce-medium)
}