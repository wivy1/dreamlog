package com.wivy.dreamlog.capture

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

object SessionIncompleteReason {
    const val PROCESS_INTERRUPTED = "process_interrupted"
    const val CAPTURE_FAILED = "capture_failed"
    const val STORAGE_RESERVE_REACHED = "storage_reserve_reached"
    const val NIGHT_ENDED = "night_ended"
    const val SAFETY_STOP = "safety_stop"
    const val SERVICE_INTERRUPTED = "service_interrupted"
    const val MICROPHONE_SILENCED = "microphone_silenced"
    const val AUDIO_GAP = "audio_gap"
    const val WRITE_FAILED = "write_failed"

    internal fun requireValid(reason: String) {
        require(REASON_PATTERN.matches(reason)) {
            "Incomplete reason must be a stable lowercase snake_case code."
        }
    }

    private val REASON_PATTERN = Regex("[a-z0-9]+(?:_[a-z0-9]+)*")
}

data class SessionAudioCheckpoint(
    val sessionId: String,
    val audioFileName: String,
    val partialFileName: String,
    val startedAtEpochMillis: Long,
    val sampleCount: Long,
    val preRollSampleCount: Long,
    val cueStartSample: Long?,
    val cueEndSampleExclusive: Long?,
    val startedAtUtcOffsetSeconds: Int? = null,
)

data class SessionAudioMetadata(
    val sessionId: String,
    val audioFileName: String,
    val startedAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val sampleCount: Long,
    val preRollSampleCount: Long,
    val cueStartSample: Long?,
    val cueEndSampleExclusive: Long?,
    val incompleteReason: String?,
    val startedAtUtcOffsetSeconds: Int? = null,
    val finalizedAtUtcOffsetSeconds: Int? = null,
    val automaticSilenceTailSampleCount: Long? = null,
) {
    val isComplete: Boolean
        get() = incompleteReason == null
}

/**
 * Writes only wake-triggered narrative audio. Constructing this class performs no disk I/O;
 * [startSession] is the first operation that creates a file.
 */
class SessionAudioWriter(
    private val audioDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val utcOffsetSeconds: (Long) -> Int = ::systemUtcOffsetSeconds,
    private val opaqueId: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
) {
    fun startSession(
        preRoll: ShortArray,
        startedAtEpochMillis: Long = clock(),
    ): ActiveSession {
        require(startedAtEpochMillis >= 0L) { "Session start time must be nonnegative." }
        ensureDirectory(audioDirectory)

        val files = allocateSessionFiles()
        val output = try {
            RandomAccessFile(files.partial, "rw").also { file ->
                file.setLength(0L)
                file.write(emptyWavHeader())
                writePcm16(file, preRoll, 0, preRoll.size)
            }
        } catch (failure: Throwable) {
            files.partial.delete()
            throw failure
        }

        return ActiveSession(
            sessionId = files.sessionId,
            finalFile = files.final,
            partialFile = files.partial,
            metadataFile = files.metadata,
            output = output,
            startedAtEpochMillis = startedAtEpochMillis,
            startedAtUtcOffsetSeconds = utcOffsetSeconds(startedAtEpochMillis).also(
                ::requireUtcOffsetSeconds,
            ),
            initialSampleCount = preRoll.size.toLong(),
        )
    }

    fun readMetadata(audioFileName: String): SessionAudioMetadata? {
        val sessionId = sessionIdFromFinalName(audioFileName) ?: return null
        return readMetadataFile(metadataFile(sessionId))
    }

    /**
     * Reads complete writer-owned WAV/metadata pairs without mutating recovery state.
     *
     * This is intentionally stricter than [readMetadata]: an artifact is returned only when
     * both exact filenames, metadata identity and bounds, and the complete PCM WAV shape match.
     * Callers can therefore use this to recover a finalization that reached disk immediately
     * before its journal attachment was durably recorded without adopting unrelated files.
     */
    fun discoverFinalizedAudio(): List<SessionAudioMetadata> {
        val canonicalDirectory = runCatching { audioDirectory.canonicalFile }.getOrNull()
            ?: return emptyList()
        if (!canonicalDirectory.isDirectory) return emptyList()

        return canonicalDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .mapNotNull { file ->
                val sessionId = sessionIdFromFinalName(file.name) ?: return@mapNotNull null
                val canonicalAudio = runCatching { file.canonicalFile }.getOrNull()
                    ?: return@mapNotNull null
                if (!canonicalAudio.isFile || canonicalAudio.parentFile != canonicalDirectory) {
                    return@mapNotNull null
                }
                val canonicalMetadata = runCatching {
                    File(canonicalDirectory, "$FILE_PREFIX$sessionId$METADATA_SUFFIX").canonicalFile
                }.getOrNull() ?: return@mapNotNull null
                if (!canonicalMetadata.isFile || canonicalMetadata.parentFile != canonicalDirectory) {
                    return@mapNotNull null
                }
                val metadata = readMetadataFile(canonicalMetadata) ?: return@mapNotNull null
                metadata.takeIf {
                    isValidFinalizedPair(
                        sessionId = sessionId,
                        audioFile = canonicalAudio,
                        metadata = metadata,
                    )
                }
            }
            .sortedWith(
                compareBy<SessionAudioMetadata> { it.startedAtEpochMillis }
                    .thenBy { it.finalizedAtEpochMillis }
                    .thenBy { it.audioFileName },
            )
            .toList()
    }

    /**
     * Finalizes writer-owned partial WAVs and final WAVs without metadata after a process crash.
     * Explicit references are also marked incomplete even if their final metadata was written
     * before the active journal could be cleared. Repeating recovery is side-effect free.
     */
    fun recoverInterrupted(
        references: Collection<SessionAudioCheckpoint> = emptyList(),
        reason: String = SessionIncompleteReason.PROCESS_INTERRUPTED,
    ): List<SessionAudioMetadata> {
        SessionIncompleteReason.requireValid(reason)
        if (!audioDirectory.isDirectory) return emptyList()

        val referencesBySession = references.associateBy { reference ->
            requireValidCheckpointIdentity(reference)
            reference.sessionId
        }
        val candidateSessionIds = linkedSetOf<String>()
        audioDirectory.listFiles().orEmpty().forEach { file ->
            sessionIdFromPartialName(file.name)?.let(candidateSessionIds::add)
            val finalSessionId = sessionIdFromFinalName(file.name)
            if (
                finalSessionId != null &&
                readMetadataFile(metadataFile(finalSessionId)) == null
            ) {
                candidateSessionIds += finalSessionId
            }
        }
        candidateSessionIds += referencesBySession.keys

        return candidateSessionIds.sorted().mapNotNull { sessionId ->
            val reference = referencesBySession[sessionId]
            recoverSession(sessionId, reference, reason)
        }
    }

    inner class ActiveSession internal constructor(
        val sessionId: String,
        private val finalFile: File,
        private val partialFile: File,
        private val metadataFile: File,
        private var output: RandomAccessFile?,
        private val startedAtEpochMillis: Long,
        private val startedAtUtcOffsetSeconds: Int,
        initialSampleCount: Long,
    ) {
        private var sampleCount = initialSampleCount
        private val preRollSampleCount = initialSampleCount
        private var cueStartSample: Long? = null
        private var cueEndSampleExclusive: Long? = null
        private var terminal = false

        @Synchronized
        fun syncBufferedAudio() {
            checkOpen()
            checkNotNull(output).fd.sync()
        }

        @Synchronized
        fun append(
            samples: ShortArray,
            offset: Int = 0,
            count: Int = samples.size - offset,
        ) {
            checkOpen()
            checkRange(samples.size, offset, count)
            if (count == 0) return

            val file = checkNotNull(output)
            check(sampleCount <= MAX_SAMPLE_COUNT - count) {
                "The WAV data chunk would exceed the PCM WAV size limit."
            }
            writePcm16(file, samples, offset, count)
            sampleCount += count
        }

        @Synchronized
        fun markCueStart(): Long {
            checkOpen()
            check(cueStartSample == null) { "Cue start was already recorded." }
            return sampleCount.also { cueStartSample = it }
        }

        @Synchronized
        fun markCueEnd(): Long {
            checkOpen()
            check(cueStartSample != null) { "Cue end cannot precede cue start." }
            check(cueEndSampleExclusive == null) { "Cue end was already recorded." }
            return sampleCount.also { cueEndSampleExclusive = it }
        }

        @Synchronized
        fun checkpoint(): SessionAudioCheckpoint =
            SessionAudioCheckpoint(
                sessionId = sessionId,
                audioFileName = finalFile.name,
                partialFileName = partialFile.name,
                startedAtEpochMillis = startedAtEpochMillis,
                sampleCount = sampleCount,
                preRollSampleCount = preRollSampleCount,
                cueStartSample = cueStartSample,
                cueEndSampleExclusive = cueEndSampleExclusive,
                startedAtUtcOffsetSeconds = startedAtUtcOffsetSeconds,
            )

        @Synchronized
        fun finalizeComplete(
            automaticSilenceTailSampleCount: Long,
        ): SessionAudioMetadata {
            check(cueStartSample != null && cueEndSampleExclusive != null) {
                "A complete session requires both cue sample offsets."
            }
            require(automaticSilenceTailSampleCount > 0L) {
                "A complete session requires a positive automatic silence tail."
            }
            return finalizeSession(
                incompleteReason = null,
                automaticSilenceTailSampleCount = automaticSilenceTailSampleCount,
            )
        }

        @Synchronized
        fun finalizeIncomplete(reason: String): SessionAudioMetadata {
            SessionIncompleteReason.requireValid(reason)
            return finalizeSession(
                incompleteReason = reason,
                automaticSilenceTailSampleCount = null,
            )
        }

        private fun finalizeSession(
            incompleteReason: String?,
            automaticSilenceTailSampleCount: Long?,
        ): SessionAudioMetadata {
            checkOpen()
            terminal = true

            val actualSampleCount: Long
            try {
                val file = checkNotNull(output)
                actualSampleCount = normalizeAndPatchWav(file)
            } finally {
                runCatching { output?.close() }
                output = null
            }

            atomicMove(partialFile, finalFile)
            val finalizedAtEpochMillis = clock()
            automaticSilenceTailSampleCount?.let { tailSamples ->
                check(tailSamples <= actualSampleCount) {
                    "The automatic silence tail exceeds the finalized audio."
                }
            }
            val metadata = SessionAudioMetadata(
                sessionId = sessionId,
                audioFileName = finalFile.name,
                startedAtEpochMillis = startedAtEpochMillis,
                finalizedAtEpochMillis = finalizedAtEpochMillis,
                sampleRateHz = SAMPLE_RATE_HZ,
                channelCount = CHANNEL_COUNT,
                bitsPerSample = BITS_PER_SAMPLE,
                sampleCount = actualSampleCount,
                preRollSampleCount = preRollSampleCount.coerceAtMost(actualSampleCount),
                cueStartSample = cueStartSample?.takeIf { it <= actualSampleCount },
                cueEndSampleExclusive = validCueEnd(actualSampleCount),
                incompleteReason = incompleteReason,
                startedAtUtcOffsetSeconds = startedAtUtcOffsetSeconds,
                finalizedAtUtcOffsetSeconds =
                    utcOffsetSeconds(finalizedAtEpochMillis).also(
                        ::requireUtcOffsetSeconds,
                    ),
                automaticSilenceTailSampleCount = automaticSilenceTailSampleCount,
            )
            writeMetadata(metadataFile, metadata)
            return metadata
        }

        private fun validCueEnd(actualSampleCount: Long): Long? {
            val start = cueStartSample ?: return null
            return cueEndSampleExclusive?.takeIf { end ->
                start <= end && end <= actualSampleCount
            }
        }

        private fun checkOpen() {
            check(!terminal && output != null) { "Session audio writer is already finalized." }
        }
    }

    private fun recoverSession(
        sessionId: String,
        reference: SessionAudioCheckpoint?,
        reason: String,
    ): SessionAudioMetadata? {
        var finalFile = finalFile(sessionId)
        val partialFile = partialFile(sessionId)
        var recoveredSessionId = sessionId

        if (partialFile.isFile) {
            if (finalFile.exists()) {
                val replacement = allocateSessionFiles()
                check(replacement.partial.delete()) {
                    "Could not release a recovery filename."
                }
                atomicMove(partialFile, replacement.partial)
                recoveredSessionId = replacement.sessionId
                finalFile = replacement.final
                return recoverPartial(
                    sessionId = recoveredSessionId,
                    partialFile = replacement.partial,
                    finalFile = finalFile,
                    reference = reference?.copy(
                        sessionId = recoveredSessionId,
                        audioFileName = finalFile.name,
                        partialFileName = replacement.partial.name,
                    ),
                    reason = reason,
                )
            }
            return recoverPartial(
                sessionId = recoveredSessionId,
                partialFile = partialFile,
                finalFile = finalFile,
                reference = reference,
                reason = reason,
            )
        }

        if (!finalFile.isFile) return null
        val existingMetadata = readMetadataFile(metadataFile(sessionId))
        if (existingMetadata?.incompleteReason != null) return existingMetadata
        if (reference == null && existingMetadata != null) return null

        val actualSamples = RandomAccessFile(finalFile, "rw").use(::normalizeAndPatchWav)
        val metadata = recoveredMetadata(
            sessionId = sessionId,
            finalFile = finalFile,
            actualSampleCount = actualSamples,
            reference = reference,
            priorMetadata = existingMetadata,
            reason = reason,
        )
        writeMetadata(metadataFile(sessionId), metadata)
        return metadata
    }

    private fun recoverPartial(
        sessionId: String,
        partialFile: File,
        finalFile: File,
        reference: SessionAudioCheckpoint?,
        reason: String,
    ): SessionAudioMetadata {
        val originalModifiedAt = partialFile.lastModified().coerceAtLeast(0L)
        val actualSamples = RandomAccessFile(partialFile, "rw").use(::normalizeAndPatchWav)
        atomicMove(partialFile, finalFile)
        val metadata = recoveredMetadata(
            sessionId = sessionId,
            finalFile = finalFile,
            actualSampleCount = actualSamples,
            reference = reference,
            priorMetadata = null,
            reason = reason,
            fallbackStartedAt = originalModifiedAt,
        )
        writeMetadata(metadataFile(sessionId), metadata)
        return metadata
    }

    private fun recoveredMetadata(
        sessionId: String,
        finalFile: File,
        actualSampleCount: Long,
        reference: SessionAudioCheckpoint?,
        priorMetadata: SessionAudioMetadata?,
        reason: String,
        fallbackStartedAt: Long = finalFile.lastModified().coerceAtLeast(0L),
    ): SessionAudioMetadata {
        val start = priorMetadata?.cueStartSample ?: reference?.cueStartSample
        val end = priorMetadata?.cueEndSampleExclusive ?: reference?.cueEndSampleExclusive
        val validStart = start?.takeIf { it in 0L..actualSampleCount }
        val validEnd = end?.takeIf {
            validStart != null && it in validStart..actualSampleCount
        }
        val finalizedAtEpochMillis = clock()
        val startedAtEpochMillis = priorMetadata?.startedAtEpochMillis
            ?: reference?.startedAtEpochMillis
            ?: fallbackStartedAt
        return SessionAudioMetadata(
            sessionId = sessionId,
            audioFileName = finalFile.name,
            startedAtEpochMillis = startedAtEpochMillis,
            finalizedAtEpochMillis = finalizedAtEpochMillis,
            sampleRateHz = SAMPLE_RATE_HZ,
            channelCount = CHANNEL_COUNT,
            bitsPerSample = BITS_PER_SAMPLE,
            sampleCount = actualSampleCount,
            preRollSampleCount = (
                priorMetadata?.preRollSampleCount ?: reference?.preRollSampleCount ?: 0L
                ).coerceIn(0L, actualSampleCount),
            cueStartSample = validStart,
            cueEndSampleExclusive = validEnd,
            incompleteReason = priorMetadata?.incompleteReason ?: reason,
            startedAtUtcOffsetSeconds = priorMetadata?.startedAtUtcOffsetSeconds
                ?: reference?.startedAtUtcOffsetSeconds
                ?: utcOffsetSeconds(startedAtEpochMillis).also(::requireUtcOffsetSeconds),
            finalizedAtUtcOffsetSeconds =
                utcOffsetSeconds(finalizedAtEpochMillis).also(::requireUtcOffsetSeconds),
            automaticSilenceTailSampleCount = null,
        )
    }

    private fun allocateSessionFiles(): SessionFiles {
        repeat(MAX_ID_ATTEMPTS) {
            val sessionId = opaqueId()
            require(OPAQUE_ID_PATTERN.matches(sessionId)) {
                "Opaque session IDs must contain exactly 32 lowercase hexadecimal characters."
            }
            val partial = partialFile(sessionId)
            if (!partial.createNewFile()) return@repeat
            return SessionFiles(
                sessionId = sessionId,
                final = finalFile(sessionId),
                partial = partial,
                metadata = metadataFile(sessionId),
            )
        }
        error("Could not allocate a unique opaque session filename.")
    }

    private fun requireValidCheckpointIdentity(checkpoint: SessionAudioCheckpoint) {
        require(OPAQUE_ID_PATTERN.matches(checkpoint.sessionId)) {
            "Recovery checkpoint has an invalid session ID."
        }
        require(checkpoint.audioFileName == finalFile(checkpoint.sessionId).name) {
            "Recovery checkpoint final filename does not match its session ID."
        }
        require(checkpoint.partialFileName == partialFile(checkpoint.sessionId).name) {
            "Recovery checkpoint partial filename does not match its session ID."
        }
    }

    private fun finalFile(sessionId: String) = File(audioDirectory, "$FILE_PREFIX$sessionId$WAV_SUFFIX")

    private fun partialFile(sessionId: String) =
        File(audioDirectory, "$FILE_PREFIX$sessionId$WAV_SUFFIX$PART_SUFFIX")

    private fun metadataFile(sessionId: String) =
        File(audioDirectory, "$FILE_PREFIX$sessionId$METADATA_SUFFIX")

    private data class SessionFiles(
        val sessionId: String,
        val final: File,
        val partial: File,
        val metadata: File,
    )

    private fun isValidFinalizedPair(
        sessionId: String,
        audioFile: File,
        metadata: SessionAudioMetadata,
    ): Boolean {
        if (
            metadata.sessionId != sessionId ||
            metadata.audioFileName != "$FILE_PREFIX$sessionId$WAV_SUFFIX" ||
            metadata.startedAtEpochMillis < 0L ||
            metadata.finalizedAtEpochMillis < metadata.startedAtEpochMillis ||
            metadata.sampleRateHz != SAMPLE_RATE_HZ ||
            metadata.channelCount != CHANNEL_COUNT ||
            metadata.bitsPerSample != BITS_PER_SAMPLE ||
            metadata.sampleCount !in 0L..MAX_SAMPLE_COUNT ||
            metadata.preRollSampleCount !in 0L..metadata.sampleCount ||
            metadata.cueStartSample?.let { it !in 0L..metadata.sampleCount } == true ||
            metadata.cueEndSampleExclusive?.let { end ->
                val start = metadata.cueStartSample
                start == null || end !in start..metadata.sampleCount
            } == true ||
            metadata.automaticSilenceTailSampleCount?.let { tail ->
                metadata.incompleteReason != null || tail <= 0L || tail > metadata.sampleCount
            } == true
        ) {
            return false
        }

        val dataBytes = runCatching {
            Math.multiplyExact(metadata.sampleCount, BLOCK_ALIGN.toLong())
        }.getOrNull() ?: return false
        if (audioFile.length() != WAV_HEADER_BYTES + dataBytes) return false
        val expectedHeader = runCatching { wavHeader(dataBytes) }.getOrNull() ?: return false
        return runCatching {
            RandomAccessFile(audioFile, "r").use { input ->
                val actualHeader = ByteArray(WAV_HEADER_BYTES)
                input.readFully(actualHeader)
                actualHeader.contentEquals(expectedHeader)
            }
        }.getOrDefault(false)
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_BYTES = 44

        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BYTE_RATE = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val BLOCK_ALIGN = CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - 36L
        private const val MAX_SAMPLE_COUNT = MAX_WAV_DATA_BYTES / BYTES_PER_SAMPLE
        private const val MAX_ID_ATTEMPTS = 8
        private const val FILE_PREFIX = "a_"
        private const val WAV_SUFFIX = ".wav"
        private const val PART_SUFFIX = ".part"
        private const val METADATA_SUFFIX = ".properties"
        private const val METADATA_VERSION = "1"
        private val OPAQUE_ID_PATTERN = Regex("[0-9a-f]{32}")

        private fun sessionIdFromFinalName(name: String): String? =
            name.removePrefix(FILE_PREFIX)
                .removeSuffix(WAV_SUFFIX)
                .takeIf {
                    name == "$FILE_PREFIX$it$WAV_SUFFIX" && OPAQUE_ID_PATTERN.matches(it)
                }

        private fun sessionIdFromPartialName(name: String): String? =
            name.removePrefix(FILE_PREFIX)
                .removeSuffix("$WAV_SUFFIX$PART_SUFFIX")
                .takeIf {
                    name == "$FILE_PREFIX$it$WAV_SUFFIX$PART_SUFFIX" &&
                        OPAQUE_ID_PATTERN.matches(it)
                }

        private fun emptyWavHeader(): ByteArray = wavHeader(dataBytes = 0L)

        private fun wavHeader(dataBytes: Long): ByteArray {
            require(dataBytes in 0L..MAX_WAV_DATA_BYTES)
            return ByteBuffer.allocate(WAV_HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt((36L + dataBytes).toInt())
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1)
                    putShort(CHANNEL_COUNT.toShort())
                    putInt(SAMPLE_RATE_HZ)
                    putInt(BYTE_RATE)
                    putShort(BLOCK_ALIGN.toShort())
                    putShort(BITS_PER_SAMPLE.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(dataBytes.toInt())
                }
                .array()
        }

        private fun writePcm16(
            file: RandomAccessFile,
            samples: ShortArray,
            offset: Int,
            count: Int,
        ) {
            checkRange(samples.size, offset, count)
            var sourceOffset = offset
            var remaining = count
            val buffer = ByteArray(
                minOf(PCM_WRITE_BUFFER_BYTES / BYTES_PER_SAMPLE, count) * BYTES_PER_SAMPLE,
            )
            while (remaining > 0) {
                val samplesThisWrite = minOf(remaining, buffer.size / BYTES_PER_SAMPLE)
                var byteOffset = 0
                repeat(samplesThisWrite) {
                    val sample = samples[sourceOffset++].toInt()
                    buffer[byteOffset++] = sample.toByte()
                    buffer[byteOffset++] = (sample ushr 8).toByte()
                }
                file.write(buffer, 0, byteOffset)
                remaining -= samplesThisWrite
            }
        }

        private fun normalizeAndPatchWav(file: RandomAccessFile): Long {
            if (file.length() < WAV_HEADER_BYTES) {
                file.setLength(WAV_HEADER_BYTES.toLong())
            }
            var dataBytes = file.length() - WAV_HEADER_BYTES
            if (dataBytes % BYTES_PER_SAMPLE != 0L) {
                dataBytes -= dataBytes % BYTES_PER_SAMPLE
                file.setLength(WAV_HEADER_BYTES + dataBytes)
            }
            require(dataBytes <= MAX_WAV_DATA_BYTES) {
                "The recovered WAV data chunk exceeds the PCM WAV size limit."
            }
            file.seek(0L)
            file.write(wavHeader(dataBytes))
            file.fd.sync()
            return dataBytes / BYTES_PER_SAMPLE
        }

        private fun checkRange(size: Int, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset <= size - count) {
                "PCM sample range is outside the source array."
            }
        }

        private const val PCM_WRITE_BUFFER_BYTES = 8 * 1024
    }
}

internal fun ensureDirectory(directory: File) {
    if (directory.isDirectory) return
    check(directory.mkdirs() || directory.isDirectory) {
        "Could not create capture directory: ${directory.absolutePath}"
    }
}

internal fun atomicMove(source: File, target: File) {
    require(source.parentFile?.canonicalFile == target.parentFile?.canonicalFile) {
        "Atomic capture moves must remain in one directory."
    }
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException(
            "The capture directory does not support required atomic moves.",
            unsupported,
        )
    }
}

internal fun atomicReplace(source: File, target: File) {
    require(source.parentFile?.canonicalFile == target.parentFile?.canonicalFile) {
        "Atomic capture replacements must remain in one directory."
    }
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException(
            "The capture directory does not support required atomic replacements.",
            unsupported,
        )
    }
}

internal fun writePropertiesAtomically(target: File, properties: Properties) {
    ensureDirectory(checkNotNull(target.parentFile))
    val temporary = File(target.parentFile, "${target.name}.part")
    FileOutputStream(temporary, false).use { stream ->
        properties.store(stream, null)
        stream.flush()
        stream.fd.sync()
    }
    atomicReplace(temporary, target)
}

private fun writeMetadata(file: File, metadata: SessionAudioMetadata) {
    val properties = Properties().apply {
        setProperty("version", "1")
        setProperty("session_id", metadata.sessionId)
        setProperty("audio_file", metadata.audioFileName)
        setProperty("started_at_epoch_millis", metadata.startedAtEpochMillis.toString())
        setProperty("finalized_at_epoch_millis", metadata.finalizedAtEpochMillis.toString())
        metadata.startedAtUtcOffsetSeconds?.let {
            setProperty("started_at_utc_offset_seconds", it.toString())
        }
        metadata.finalizedAtUtcOffsetSeconds?.let {
            setProperty("finalized_at_utc_offset_seconds", it.toString())
        }
        setProperty("sample_rate_hz", metadata.sampleRateHz.toString())
        setProperty("channel_count", metadata.channelCount.toString())
        setProperty("bits_per_sample", metadata.bitsPerSample.toString())
        setProperty("sample_count", metadata.sampleCount.toString())
        setProperty("pre_roll_sample_count", metadata.preRollSampleCount.toString())
        metadata.cueStartSample?.let { setProperty("cue_start_sample", it.toString()) }
        metadata.cueEndSampleExclusive?.let {
            setProperty("cue_end_sample_exclusive", it.toString())
        }
        metadata.incompleteReason?.let { setProperty("incomplete_reason", it) }
        metadata.automaticSilenceTailSampleCount?.let {
            setProperty("automatic_silence_tail_sample_count", it.toString())
        }
    }
    writePropertiesAtomically(file, properties)
}

private fun readMetadataFile(file: File): SessionAudioMetadata? {
    if (!file.isFile) return null
    return runCatching {
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        require(properties.getProperty("version") == "1")
        val incompleteReason = properties.getProperty("incomplete_reason")
        incompleteReason?.let(SessionIncompleteReason::requireValid)
        SessionAudioMetadata(
            sessionId = properties.required("session_id"),
            audioFileName = properties.required("audio_file"),
            startedAtEpochMillis = properties.requiredLong("started_at_epoch_millis"),
            finalizedAtEpochMillis = properties.requiredLong("finalized_at_epoch_millis"),
            sampleRateHz = properties.requiredInt("sample_rate_hz"),
            channelCount = properties.requiredInt("channel_count"),
            bitsPerSample = properties.requiredInt("bits_per_sample"),
            sampleCount = properties.requiredLong("sample_count"),
            preRollSampleCount = properties.requiredLong("pre_roll_sample_count"),
            cueStartSample = properties.optionalLong("cue_start_sample"),
            cueEndSampleExclusive = properties.optionalLong("cue_end_sample_exclusive"),
            incompleteReason = incompleteReason,
            startedAtUtcOffsetSeconds =
                properties.optionalUtcOffsetSeconds("started_at_utc_offset_seconds"),
            finalizedAtUtcOffsetSeconds =
                properties.optionalUtcOffsetSeconds("finalized_at_utc_offset_seconds"),
            automaticSilenceTailSampleCount =
                properties.optionalLong("automatic_silence_tail_sample_count"),
        )
    }.getOrNull()
}

internal fun Properties.required(key: String): String =
    requireNotNull(getProperty(key)) { "Missing capture property: $key" }

internal fun Properties.requiredLong(key: String): Long = required(key).toLong()

internal fun Properties.requiredInt(key: String): Int = required(key).toInt()

internal fun Properties.optionalLong(key: String): Long? = getProperty(key)?.toLong()

private fun Properties.optionalUtcOffsetSeconds(key: String): Int? =
    getProperty(key)?.toInt()?.also(::requireUtcOffsetSeconds)
