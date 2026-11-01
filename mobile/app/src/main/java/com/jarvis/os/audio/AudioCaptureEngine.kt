package com.jarvis.os.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency audio recording engine delivering 16,000 Hz 16-bit mono PCM chunks.
 */
class AudioCaptureEngine(
    private val sampleRate: Int = SAMPLE_RATE_HZ,
    private val chunkByteSize: Int = CHUNK_SIZE_BYTES,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
) {
    private val isRecording = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    val isCapturing: Boolean
        get() = isRecording.get()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCapture(
        scope: CoroutineScope,
        onChunk: (ByteArray) -> Unit
    ): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "AudioCaptureEngine is already recording")
            return true
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, chunkByteSize * 4)

        try {
            val record = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed (state != STATE_INITIALIZED)")
                record.release()
                return false
            }

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                } catch (error: Exception) {
                    Log.w(TAG, "Echo cancellation unavailable: ${error.message}")
                }
            }
            record.startRecording()
            audioRecord = record
            isRecording.set(true)

            captureJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(chunkByteSize)
                Log.i(TAG, "AudioCapture loop started: $sampleRate Hz, $chunkByteSize bytes/frame, source=$audioSource")

                try {
                    while (isActive && isRecording.get()) {
                        var bytesRead = 0
                        while (bytesRead < chunkByteSize && isActive && isRecording.get()) {
                            val read = record.read(
                                buffer,
                                bytesRead,
                                chunkByteSize - bytesRead,
                                AudioRecord.READ_BLOCKING
                            )
                            if (read > 0) {
                                bytesRead += read
                            } else if (read < 0) {
                                Log.e(TAG, "AudioRecord read error: $read")
                                kotlinx.coroutines.delay(100)
                                break
                            } else {
                                kotlinx.coroutines.delay(10)
                            }
                        }

                        if (bytesRead == chunkByteSize) {
                            onChunk(buffer.clone())
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Audio capture loop exception: ${e.message}", e)
                } finally {
                    Log.i(TAG, "Audio capture loop finished")
                }
            }

            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            return false
        }
    }

    @Synchronized
    fun stopCapture() {
        if (!isRecording.compareAndSet(true, false)) {
            return
        }

        try {
            captureJob?.cancel()
            captureJob = null
            echoCanceler?.release()
            echoCanceler = null

            audioRecord?.apply {
                try {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
                }
                release()
            }
            audioRecord = null
            Log.i(TAG, "AudioCaptureEngine stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during AudioCaptureEngine teardown: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE_HZ = 16000
        const val CHUNK_SIZE_BYTES = 640 // 320 samples @ 16-bit = 20ms
    }
}
