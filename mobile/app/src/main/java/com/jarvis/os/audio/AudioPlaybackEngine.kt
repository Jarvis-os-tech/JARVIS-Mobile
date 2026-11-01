package com.jarvis.os.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Queues PCM without holding the network reader or blocking cancellation. */
class AudioPlaybackEngine(private val sampleRate: Int = SAMPLE_RATE_HZ) {
    private data class Chunk(val generation: Long, val bytes: ByteArray)
    private val lock = Any()
    private val queue = LinkedBlockingQueue<Chunk>()
    private var audioTrack: AudioTrack? = null
    private var worker: Thread? = null
    private var generation = 0L
    private var queuedBytes = 0L
    private var writtenFrames = 0L
    private var drained = true
    private var paused = false
    var onPlaybackDrained: (() -> Unit)? = null

    val hasPendingAudio: Boolean
        get() = synchronized(lock) { hasPendingAudioLocked() }

    private fun hasPendingAudioLocked(): Boolean {
        val played = audioTrack?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
        return queuedBytes > 0 || writtenFrames > played
    }

    private fun initializeTrack() {
        if (audioTrack != null) return
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Speaker unavailable" }
        track.play()
        audioTrack = track
    }

    fun playPcm(data: ByteArray): Int = synchronized(lock) {
        if (data.isEmpty()) return@synchronized 0
        try {
            initializeTrack()
            paused = false
            audioTrack?.play()
            queuedBytes += data.size
            drained = false
            queue.offer(Chunk(generation, data))
            if (worker?.isAlive != true) worker = thread(name = "JarvisPlayback", isDaemon = true) { drainAudio() }
            data.size
        } catch (error: Exception) {
            Log.e(TAG, "Unable to queue audio", error)
            -1
        }
    }

    private fun drainAudio() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = queue.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    var offset = 0
                    while (offset < chunk.bytes.size && !Thread.currentThread().isInterrupted) {
                        val written = synchronized(lock) {
                            if (chunk.generation != generation) return@synchronized -1
                            if (paused) return@synchronized 0
                            val track = audioTrack ?: return@synchronized -1
                            // At most 10 ms of audio per write; never wait for speaker space under the lock.
                            val count = track.write(chunk.bytes, offset, minOf(480, chunk.bytes.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                            if (count > 0) {
                                queuedBytes -= count
                                writtenFrames += count / 2
                            }
                            count
                        }
                        if (written < 0) break
                        offset += written
                        if (written == 0) Thread.sleep(3)
                    }
                }
                val notify = synchronized(lock) {
                    if (!drained && !hasPendingAudioLocked()) { drained = true; true } else false
                }
                if (notify) onPlaybackDrained?.invoke()

                // If playback is completely drained and no chunks are queued, terminate worker to prevent 200 Hz CPU wakeups in standby
                val shouldExit = synchronized(lock) {
                    if (drained && queue.isEmpty()) {
                        worker = null
                        true
                    } else false
                }
                if (shouldExit) break
            }
        } catch (_: InterruptedException) {
            // release() terminates the worker.
        } catch (error: Exception) {
            Log.e(TAG, "Playback worker failed", error)
            cancel()
        }
    }

    fun cancel() = synchronized(lock) {
        generation++
        queue.clear()
        queuedBytes = 0
        writtenFrames = 0
        drained = true
        paused = false
        audioTrack?.let { track ->
            track.pause()
            track.flush()
        }
        Log.i(TAG, "Playback interrupted: queued audio discarded")
    }

    fun pause() = synchronized(lock) { paused = true; audioTrack?.pause() }
    fun resume() = synchronized(lock) { paused = false; audioTrack?.play() }

    fun release() = synchronized(lock) {
        cancel()
        worker?.interrupt()
        worker = null
        audioTrack?.release()
        audioTrack = null
    }

    companion object {
        private const val TAG = "AudioPlaybackEngine"
        const val SAMPLE_RATE_HZ = 24000
    }
}
