package com.wivy.dreamlog.feasibility

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface FixtureRecordingOutcome {
    data class Success(
        val recordedAtEpochMillis: Long,
        val durationMillis: Long,
        val reachedTimeLimit: Boolean,
    ) : FixtureRecordingOutcome

    data class Failure(val message: String) : FixtureRecordingOutcome
}

/**
 * A foreground-only, finite PCM recorder. The worker owns the AudioRecord and WAV file, while
 * [stop] only signals it and unblocks a pending read.
 */
internal class FixtureWavRecorder {
    private val lock = Any()
    private val keepRecording = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    fun start(
        recordingFile: File,
        completedFile: File,
        onFinished: (FixtureRecordingOutcome) -> Unit,
    ): Result<Unit> = runCatching {
        synchronized(lock) {
            check(worker?.isAlive != true) { "Another fixture is already recording." }
        }
        check(recordingFile.parentFile?.isDirectory == true) {
            "Fixture storage is unavailable."
        }
        check(!recordingFile.exists() || recordingFile.delete()) {
            "An unfinished fixture recording could not be replaced."
        }

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Android did not provide a usable microphone buffer." }
        val bufferSize = maxOf(minimumBuffer, READ_BUFFER_BYTES)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "The microphone recorder could not initialize."
        }

        runCatching { recorder.startRecording() }
            .onFailure { recorder.release() }
            .getOrThrow()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            "Android did not start microphone capture."
        }

        keepRecording.set(true)
        audioRecord = recorder
        val startedAtEpochMillis = System.currentTimeMillis()
        val recordingWorker = Thread(
            {
                val outcome = recordToWav(
                    recorder = recorder,
                    bufferSize = bufferSize,
                    recordingFile = recordingFile,
                    completedFile = completedFile,
                    startedAtEpochMillis = startedAtEpochMillis,
                )
                try {
                    onFinished(outcome)
                } finally {
                    synchronized(lock) {
                        audioRecord = null
                        worker = null
                    }
                }
            },
            "DreamLog-fixture-recorder",
        )
        synchronized(lock) {
            worker = recordingWorker
        }
        runCatching { recordingWorker.start() }
            .onFailure {
                keepRecording.set(false)
                synchronized(lock) {
                    worker = null
                    audioRecord = null
                }
                runCatching { recorder.stop() }
                recorder.release()
            }
            .getOrThrow()
    }

    fun stop(waitForCompletion: Boolean = false) {
        keepRecording.set(false)
        val recorder = audioRecord
        runCatching {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        if (waitForCompletion) {
            val activeWorker = worker
            if (activeWorker != null && activeWorker !== Thread.currentThread()) {
                runCatching { activeWorker.join(STOP_JOIN_MILLIS) }
                if (activeWorker.isAlive) {
                    runCatching { recorder?.release() }
                    runCatching { activeWorker.join(RELEASE_JOIN_MILLIS) }
                }
            }
        }
    }

    private fun recordToWav(
        recorder: AudioRecord,
        bufferSize: Int,
        recordingFile: File,
        completedFile: File,
        startedAtEpochMillis: Long,
    ): FixtureRecordingOutcome {
        var dataBytes = 0L
        var reachedTimeLimit = false
        val deadline = SystemClock.elapsedRealtime() + MAX_RECORDING_MILLIS
        val result = runCatching {
            RandomAccessFile(recordingFile, "rw").use { output ->
                output.setLength(0L)
                output.write(ByteArray(WAV_HEADER_BYTES))
                val buffer = ByteArray(bufferSize)
                while (keepRecording.get()) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        reachedTimeLimit = true
                        keepRecording.set(false)
                        break
                    }
                    val count = recorder.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    when {
                        count > 0 -> {
                            check(count % BLOCK_ALIGNMENT == 0) {
                                "Android returned an incomplete PCM16 sample."
                            }
                            output.write(buffer, 0, count)
                            dataBytes += count
                        }

                        !keepRecording.get() -> break
                        count == AudioRecord.ERROR_DEAD_OBJECT ->
                            error("Android disconnected the microphone recorder.")
                        count < 0 -> error("Android microphone read failed ($count).")
                    }
                }
                check(dataBytes > 0L) { "No microphone audio was captured." }
                output.seek(0L)
                output.write(wavHeader(dataBytes))
                output.fd.sync()
            }
            moveCompletedRecording(recordingFile, completedFile)
        }

        keepRecording.set(false)
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        recorder.release()

        return result.fold(
            onSuccess = {
                FixtureRecordingOutcome.Success(
                    recordedAtEpochMillis = startedAtEpochMillis,
                    durationMillis = dataBytes * 1_000L / BYTES_PER_SECOND,
                    reachedTimeLimit = reachedTimeLimit,
                )
            },
            onFailure = { failure ->
                recordingFile.delete()
                FixtureRecordingOutcome.Failure(
                    failure.message ?: "Fixture recording failed.",
                )
            },
        )
    }

    private fun moveCompletedRecording(recordingFile: File, completedFile: File) {
        runCatching {
            Files.move(
                recordingFile.toPath(),
                completedFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                recordingFile.toPath(),
                completedFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    private fun wavHeader(dataBytes: Long): ByteArray =
        ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt((36L + dataBytes).toInt())
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1.toShort())
            .putShort(CHANNEL_COUNT.toShort())
            .putInt(SAMPLE_RATE_HZ)
            .putInt(BYTES_PER_SECOND)
            .putShort(BLOCK_ALIGNMENT.toShort())
            .putShort(BITS_PER_SAMPLE.toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(dataBytes.toInt())
            .array()

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BLOCK_ALIGNMENT = CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * BLOCK_ALIGNMENT
        const val WAV_HEADER_BYTES = 44
        const val READ_BUFFER_BYTES = 3_200
        const val MAX_RECORDING_MILLIS = 60_000L
        const val STOP_JOIN_MILLIS = 1_500L
        const val RELEASE_JOIN_MILLIS = 500L
    }
}
