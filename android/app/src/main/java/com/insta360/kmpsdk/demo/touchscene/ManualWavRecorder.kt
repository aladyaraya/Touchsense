package com.insta360.kmpsdk.demo.touchscene

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream

/** Records mono PCM until [stop] is explicitly called, then wraps it as a WAV file. */
class ManualWavRecorder {
    @Volatile
    private var recording = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var pcmOutput: ByteArrayOutputStream? = null
    private var workerFailure: Throwable? = null

    val isRecording: Boolean get() = recording

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Synchronized
    fun start(): Result<Unit> = runCatching {
        check(!recording) { "audio recording is already active" }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "unsupported audio recording format: $minimum" }
        val bufferSize = maxOf(minimum, READ_BUFFER_BYTES)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "audio recorder failed to initialize" }
        val output = ByteArrayOutputStream()
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "audio recorder did not enter recording state"
            }
        } catch (failure: Throwable) {
            recorder.release()
            throw failure
        }
        workerFailure = null
        audioRecord = recorder
        pcmOutput = output
        recording = true
        worker = Thread({ captureLoop(recorder, output, bufferSize) }, "touchscene-manual-audio").apply {
            start()
        }
    }

    fun stop(): Result<ByteArray> = finish(keepAudio = true)

    fun cancel() {
        finish(keepAudio = false)
    }

    private fun captureLoop(recorder: AudioRecord, output: ByteArrayOutputStream, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            while (recording) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) output.write(buffer, 0, count)
                else if (count != AudioRecord.ERROR_INVALID_OPERATION && count != AudioRecord.ERROR_BAD_VALUE) {
                    throw IllegalStateException("audio recorder read failed: $count")
                }
            }
        } catch (failure: Throwable) {
            if (recording) workerFailure = failure
        }
    }

    private fun finish(keepAudio: Boolean): Result<ByteArray> = runCatching {
        val recorder: AudioRecord
        val captureThread: Thread?
        val output: ByteArrayOutputStream
        synchronized(this) {
            recorder = checkNotNull(audioRecord) { "audio recording is not active" }
            output = checkNotNull(pcmOutput)
            captureThread = worker
            recording = false
        }
        runCatching { recorder.stop() }
        captureThread?.join(STOP_JOIN_TIMEOUT_MS)
        recorder.release()
        synchronized(this) {
            audioRecord = null
            worker = null
            pcmOutput = null
        }
        workerFailure?.let { throw it }
        val pcm = output.toByteArray()
        check(!keepAudio || pcm.size >= MIN_AUDIO_BYTES) { "recorded audio was too short" }
        if (keepAudio) WavPcmEncoder.encode(pcm, SAMPLE_RATE_HZ, CHANNEL_COUNT, BITS_PER_SAMPLE)
        else ByteArray(0)
    }.also {
        if (it.isFailure) forceRelease()
    }

    @Synchronized
    private fun forceRelease() {
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        worker = null
        pcmOutput = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val READ_BUFFER_BYTES = 4_096
        private const val MIN_AUDIO_BYTES = 3_200 // 100 ms at 16 kHz mono PCM16
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}

/** Pure WAV encoder kept separate so its binary output can be unit-tested on the JVM. */
internal object WavPcmEncoder {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        require(sampleRate > 0 && channels > 0 && bitsPerSample > 0)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteArrayOutputStream(44 + pcm.size).apply {
            writeAscii("RIFF")
            writeLittleEndian(36 + pcm.size, 4)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeLittleEndian(16, 4)
            writeLittleEndian(1, 2)
            writeLittleEndian(channels, 2)
            writeLittleEndian(sampleRate, 4)
            writeLittleEndian(byteRate, 4)
            writeLittleEndian(blockAlign, 2)
            writeLittleEndian(bitsPerSample, 2)
            writeAscii("data")
            writeLittleEndian(pcm.size, 4)
            write(pcm)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeLittleEndian(value: Int, bytes: Int) {
        repeat(bytes) { shift -> write(value ushr (shift * 8) and 0xff) }
    }
}
