package com.wivy.dreamlog.feasibility

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class WakeCalibrationClipStart(
    val attemptNumber: Int,
    val target: String,
    val delivery: String,
    val relativePath: String,
    val sampleRateHz: Int,
    val intendedSamples: Int,
    val intendedDurationMillis: Long,
)

internal data class WakeCalibrationClipResult(
    val attemptNumber: Int,
    val target: String,
    val delivery: String,
    val relativePath: String,
    val samples: Int,
    val durationMillis: Long,
    val wavBytes: Long,
    val sha256: String,
)

internal data class WakeCalibrationClipDiscard(
    val attemptNumber: Int,
    val target: String,
    val delivery: String,
    val bufferedSamples: Int,
    val reason: String,
)

internal data class WakeCalibrationClipCloseResult(
    val completed: List<WakeCalibrationClipResult>,
    val discarded: WakeCalibrationClipDiscard?,
    val failure: Throwable?,
)

/**
 * Buffers only owner-marked wake windows in memory on the capture thread, then writes each
 * complete clip on a dedicated finite I/O thread under app-private files storage. Unmarked
 * microphone frames never reach this collector, and incomplete clips are never persisted.
 */
internal class WakeCalibrationClipStore(
    filesDirectory: File,
    private val runId: String,
    private val storageReserveBytes: Long,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private data class BufferedClip(
        val attemptNumber: Int,
        val target: String,
        val delivery: String,
        val relativePath: String,
        val stagedFile: File,
        val finalFile: File,
        val samples: ShortArray,
        var samplesWritten: Int = 0,
        var discardFirstFrame: Boolean = true,
    )

    private val rootDirectory = File(filesDirectory, ROOT_DIRECTORY)
    private val runDirectory = File(rootDirectory, runId)
    private val intendedSamples: Int
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DreamLog-wake-clip-io")
    }
    private val completedResults = ConcurrentLinkedQueue<WakeCalibrationClipResult>()
    private val persistenceFailure = AtomicReference<Throwable?>(null)
    private val completedCount = AtomicInteger(0)
    private val completedBytes = AtomicLong(0L)
    private val pendingWavBytes = AtomicLong(0L)
    private var activeClip: BufferedClip? = null
    private var closed = false

    val staleStagingFilesRemoved: Int

    val completedClipCount: Int
        get() = completedCount.get()

    val completedWavBytes: Long
        get() = completedBytes.get()

    val hasActiveClip: Boolean
        get() = activeClip != null

    val hasPendingWrites: Boolean
        get() = pendingWavBytes.get() > 0L

    init {
        require(SAFE_RUN_ID.matches(runId)) { "The wake calibration run ID is invalid." }
        require(storageReserveBytes >= 0L) { "The storage reserve must not be negative." }
        require(sampleRateHz > 0) { "The wake calibration sample rate must be positive." }
        require(windowMillis > 0L) { "The wake calibration window must be positive." }
        val sampleCount = sampleRateHz.toLong() * windowMillis / 1_000L
        require(sampleCount in 1..Int.MAX_VALUE.toLong()) {
            "The wake calibration window is too large."
        }
        require(sampleCount * 1_000L == sampleRateHz.toLong() * windowMillis) {
            "The wake calibration window must contain a whole number of samples."
        }
        intendedSamples = sampleCount.toInt()
        requireDirectory(rootDirectory)
        staleStagingFilesRemoved = removeStaleStagingFiles(rootDirectory)
    }

    fun start(
        attemptNumber: Int,
        target: String,
        delivery: String,
    ): WakeCalibrationClipStart {
        checkOpenAndHealthy()
        check(activeClip == null) { "The previous wake calibration clip is still buffering." }
        require(attemptNumber in 1..MAX_ATTEMPT_NUMBER) {
            "The wake calibration attempt number is invalid."
        }
        require(SAFE_LABEL.matches(target)) { "The wake calibration target is invalid." }
        require(SAFE_LABEL.matches(delivery)) { "The wake calibration delivery is invalid." }

        val expectedWavBytes = WAV_HEADER_BYTES + intendedSamples.toLong() * PCM16_BYTES
        val usableBytes = rootDirectory.usableSpace
        val reservedForPendingClips = pendingWavBytes.get()
        if (
            usableBytes < storageReserveBytes ||
            usableBytes - storageReserveBytes < expectedWavBytes + reservedForPendingClips
        ) {
            throw IOException("Not enough private storage remains for a wake calibration clip.")
        }
        requireDirectory(runDirectory)

        val stem =
            "attempt-${attemptNumber.toString().padStart(3, '0')}_${target}_${delivery}"
        val finalFile = File(runDirectory, "$stem.wav")
        val stagedFile = File(runDirectory, ".$stem.wav.part")
        check(!finalFile.exists() && !stagedFile.exists()) {
            "A wake calibration clip already exists for this attempt."
        }
        val relativePath = "$ROOT_DIRECTORY/$runId/${finalFile.name}"
        activeClip = BufferedClip(
            attemptNumber = attemptNumber,
            target = target,
            delivery = delivery,
            relativePath = relativePath,
            stagedFile = stagedFile,
            finalFile = finalFile,
            samples = ShortArray(intendedSamples),
        )
        return WakeCalibrationClipStart(
            attemptNumber = attemptNumber,
            target = target,
            delivery = delivery,
            relativePath = relativePath,
            sampleRateHz = sampleRateHz,
            intendedSamples = intendedSamples,
            intendedDurationMillis = windowMillis,
        )
    }

    /** Copies into a fixed in-memory buffer only. No filesystem I/O occurs on this path. */
    fun append(samples: ShortArray, count: Int = samples.size) {
        checkPersistenceFailure()
        require(count in 0..samples.size) { "The wake calibration sample count is invalid." }
        val clip = activeClip ?: return
        if (count > 0 && clip.discardFirstFrame) {
            clip.discardFirstFrame = false
            return
        }
        val writableSamples = minOf(count, intendedSamples - clip.samplesWritten)
        if (writableSamples > 0) {
            samples.copyInto(
                destination = clip.samples,
                destinationOffset = clip.samplesWritten,
                startIndex = 0,
                endIndex = writableSamples,
            )
            clip.samplesWritten += writableSamples
        }
        if (clip.samplesWritten == intendedSamples) {
            activeClip = null
            pendingWavBytes.addAndGet(WAV_HEADER_BYTES + clip.samples.size.toLong() * PCM16_BYTES)
            ioExecutor.execute { persist(clip) }
        }
    }

    fun drainCompleted(): List<WakeCalibrationClipResult> = buildList {
        while (true) {
            add(completedResults.poll() ?: break)
        }
    }

    fun requeueCompleted(results: List<WakeCalibrationClipResult>) {
        results.forEach(completedResults::add)
    }

    fun closeAndCollect(discardReason: String): WakeCalibrationClipCloseResult {
        require(SAFE_REASON.matches(discardReason)) {
            "The wake calibration discard reason is invalid."
        }
        if (closed) {
            return WakeCalibrationClipCloseResult(
                completed = drainCompleted(),
                discarded = null,
                failure = persistenceFailure.get(),
            )
        }
        closed = true
        val discarded = discardActive(discardReason)
        ioExecutor.shutdown()
        val terminated = try {
            ioExecutor.awaitTermination(IO_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            persistenceFailure.compareAndSet(null, error)
            false
        }
        if (!terminated) {
            ioExecutor.shutdownNow()
            persistenceFailure.compareAndSet(
                null,
                IOException("Timed out while finalizing private wake calibration clips."),
            )
        }
        removeEmptyTaskDirectories()
        return WakeCalibrationClipCloseResult(
            completed = drainCompleted(),
            discarded = discarded,
            failure = persistenceFailure.get(),
        )
    }

    fun cancelActive(reason: String): WakeCalibrationClipDiscard? {
        require(SAFE_REASON.matches(reason)) {
            "The wake calibration discard reason is invalid."
        }
        return discardActive(reason)
    }

    private fun discardActive(reason: String): WakeCalibrationClipDiscard? {
        val clip = activeClip ?: return null
        activeClip = null
        return WakeCalibrationClipDiscard(
            attemptNumber = clip.attemptNumber,
            target = clip.target,
            delivery = clip.delivery,
            bufferedSamples = clip.samplesWritten,
            reason = reason,
        )
    }

    private fun persist(clip: BufferedClip) {
        var moved = false
        try {
            persistenceFailure.get()?.let { throw IOException("Clip persistence is unavailable.", it) }
            RandomAccessFile(clip.stagedFile, "rw").use { output ->
                output.setLength(0L)
                writeWavHeader(
                    output = output,
                    sampleRateHz = sampleRateHz,
                    dataBytes = clip.samples.size * PCM16_BYTES,
                )
                writePcm16(output, clip.samples)
                output.fd.sync()
            }
            moveWithoutOverwrite(clip.stagedFile, clip.finalFile)
            moved = true
            val wavBytes = clip.finalFile.length()
            val result = WakeCalibrationClipResult(
                attemptNumber = clip.attemptNumber,
                target = clip.target,
                delivery = clip.delivery,
                relativePath = clip.relativePath,
                samples = clip.samples.size,
                durationMillis = clip.samples.size * 1_000L / sampleRateHz,
                wavBytes = wavBytes,
                sha256 = sha256(clip.finalFile),
            )
            completedResults.add(result)
            completedCount.incrementAndGet()
            completedBytes.addAndGet(wavBytes)
        } catch (error: Throwable) {
            runCatching { clip.stagedFile.delete() }
            if (moved) runCatching { clip.finalFile.delete() }
            persistenceFailure.compareAndSet(null, error)
        } finally {
            pendingWavBytes.addAndGet(
                -(WAV_HEADER_BYTES + clip.samples.size.toLong() * PCM16_BYTES),
            )
        }
    }

    private fun checkOpenAndHealthy() {
        check(!closed) { "The wake calibration clip store is closed." }
        checkPersistenceFailure()
    }

    private fun checkPersistenceFailure() {
        persistenceFailure.get()?.let { error ->
            throw IOException("A private wake calibration clip could not be saved.", error)
        }
    }

    private fun removeEmptyTaskDirectories() {
        if (runDirectory.isDirectory && runDirectory.list().isNullOrEmpty()) {
            runDirectory.delete()
        }
        if (rootDirectory.isDirectory && rootDirectory.list().isNullOrEmpty()) {
            rootDirectory.delete()
        }
    }

    companion object {
        const val ROOT_DIRECTORY = "wake-calibration"
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_WINDOW_MILLIS = 6_000L
        const val WAV_HEADER_BYTES = 44L

        private const val PCM16_BYTES = 2
        private const val PCM_WRITE_SAMPLES = 4_096
        private const val MAX_ATTEMPT_NUMBER = 999
        private const val IO_CLOSE_TIMEOUT_SECONDS = 30L
        private const val HASH_BUFFER_BYTES = 64 * 1_024
        private val SAFE_RUN_ID = Regex("[0-9a-f]{8}-[0-9a-f-]{27,36}")
        private val SAFE_LABEL = Regex("[a-z0-9_]+")
        private val SAFE_REASON = Regex("[a-z0-9_]+")

        private fun requireDirectory(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Could not create private wake calibration storage.")
            }
        }

        private fun removeStaleStagingFiles(root: File): Int {
            var removed = 0
            root.walkTopDown()
                .filter { file -> file.isFile && file.name.endsWith(".wav.part") }
                .forEach { file ->
                    if (!file.delete()) {
                        throw IOException("Could not remove an incomplete wake calibration clip.")
                    }
                    removed += 1
                }
            return removed
        }

        private fun moveWithoutOverwrite(source: File, destination: File) {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath())
            }
        }

        private fun writeWavHeader(
            output: RandomAccessFile,
            sampleRateHz: Int,
            dataBytes: Int,
        ) {
            output.writeBytes("RIFF")
            writeIntLittleEndian(output, 36 + dataBytes)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeIntLittleEndian(output, 16)
            writeShortLittleEndian(output, 1)
            writeShortLittleEndian(output, 1)
            writeIntLittleEndian(output, sampleRateHz)
            writeIntLittleEndian(output, sampleRateHz * PCM16_BYTES)
            writeShortLittleEndian(output, PCM16_BYTES)
            writeShortLittleEndian(output, 16)
            output.writeBytes("data")
            writeIntLittleEndian(output, dataBytes)
        }

        private fun writePcm16(output: RandomAccessFile, samples: ShortArray) {
            val bytes = ByteArray(PCM_WRITE_SAMPLES * PCM16_BYTES)
            var sampleOffset = 0
            while (sampleOffset < samples.size) {
                val sampleCount = minOf(PCM_WRITE_SAMPLES, samples.size - sampleOffset)
                for (index in 0 until sampleCount) {
                    val value = samples[sampleOffset + index].toInt()
                    bytes[index * PCM16_BYTES] = (value and 0xff).toByte()
                    bytes[index * PCM16_BYTES + 1] = ((value ushr 8) and 0xff).toByte()
                }
                output.write(bytes, 0, sampleCount * PCM16_BYTES)
                sampleOffset += sampleCount
            }
        }

        private fun writeIntLittleEndian(output: RandomAccessFile, value: Int) {
            output.writeByte(value and 0xff)
            output.writeByte((value ushr 8) and 0xff)
            output.writeByte((value ushr 16) and 0xff)
            output.writeByte((value ushr 24) and 0xff)
        }

        private fun writeShortLittleEndian(output: RandomAccessFile, value: Int) {
            output.writeByte(value and 0xff)
            output.writeByte((value ushr 8) and 0xff)
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }
}
