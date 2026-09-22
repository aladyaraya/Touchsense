package com.insta360.kmpsdk.demo.touchscene

/** Keeps push-to-talk recognition half-duplex with spoken output. */
class VoiceTurnGate {
    private var listening = false
    private var resumeAfterSpeech = false

    fun isListening(): Boolean = listening

    fun beginListening() {
        resumeAfterSpeech = false
        listening = true
    }

    /** Returns true when an active recognizer must be cancelled before TTS starts. */
    fun speechStarted(): Boolean {
        if (!listening) return false
        listening = false
        resumeAfterSpeech = true
        return true
    }

    /** Returns true only when TTS interrupted an unfinished recognition turn. */
    fun speechFinished(): Boolean {
        if (!resumeAfterSpeech) return false
        resumeAfterSpeech = false
        listening = true
        return true
    }

    /** Returns false for late results/errors from a cancelled recognition turn. */
    fun recognitionFinished(): Boolean {
        if (!listening) return false
        listening = false
        resumeAfterSpeech = false
        return true
    }

    fun reset() {
        listening = false
        resumeAfterSpeech = false
    }
}
