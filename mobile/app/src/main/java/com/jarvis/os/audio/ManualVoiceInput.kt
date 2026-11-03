package com.jarvis.os.audio

import java.util.ArrayDeque

/** Ordered manual-VAD framing, including bounded speech captured during connection setup. */
class ManualVoiceInput(
    private val send: (Frame) -> Boolean,
    private val silenceFrames: Int = 20,
    private val preRollFrames: Int = 12,
    private val maxBufferedFrames: Int = 800
) {
    sealed class Frame {
        object Start : Frame()
        data class Audio(val pcm: ByteArray) : Frame()
        object End : Frame()
    }
    data class Result(val opened: Boolean = false, val closed: Boolean = false, val interrupted: Boolean = false)
    class TransportFailure : IllegalStateException("Voice upload unavailable; repeat the request after reconnecting")
    private val preRoll = ArrayDeque<ByteArray>()
    private val pending = ArrayDeque<Frame>()
    private var active = false
    private var ready = false
    private var open = false
    private var quietFrames = 0
    private var hadSpeech = false

    @get:Synchronized val turnOpen: Boolean get() = open
    @Synchronized fun start() { reset(); active = true }
    @Synchronized fun reset() {
        active = false; ready = false; open = false; quietFrames = 0; hadSpeech = false
        pending.clear(); preRoll.clear()
    }
    /** Returns true if early speech already supplies the user's first request. */
    @Synchronized fun connected(): Boolean {
        if (!active) return false
        ready = true
        while (pending.isNotEmpty()) {
            if (!send(pending.removeFirst())) { reset(); throw TransportFailure() }
        }
        return hadSpeech
    }
    @Synchronized fun process(pcm: ByteArray, speech: Boolean, speakerPlaying: Boolean, deliberateBargeIn: Boolean): Result {
        if (!active) return Result()
        var opened = false
        var interrupted = false
        if (!open) {
            preRoll.addLast(pcm)
            while (preRoll.size > preRollFrames) preRoll.removeFirst()
            if (!speech || (speakerPlaying && !deliberateBargeIn)) return Result()
            open = true; quietFrames = 0; hadSpeech = true; opened = true; interrupted = speakerPlaying
            emit(Frame.Start)
            while (preRoll.isNotEmpty()) emit(Frame.Audio(preRoll.removeFirst()))
        } else emit(Frame.Audio(pcm))
        quietFrames = if (speech) 0 else quietFrames + 1
        if (quietFrames >= silenceFrames) {
            emit(Frame.End)
            open = false; quietFrames = 0; preRoll.clear()
            return Result(opened, closed = true, interrupted = interrupted)
        }
        return Result(opened, interrupted = interrupted)
    }
    private fun emit(frame: Frame) {
        if (ready) {
            if (!send(frame)) { reset(); throw TransportFailure() }
        } else {
            if (pending.size >= maxBufferedFrames) { reset(); throw TransportFailure() }
            pending.addLast(frame)
        }
    }
}
