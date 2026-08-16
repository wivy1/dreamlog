package com.wivy.dreamlog.capture

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.Base64
import java.util.Properties
import java.util.UUID

data class ActiveNightJournal(
    val nightId: String,
    val displayDate: String,
    val startedAtEpochMillis: Long,
    val lastHeartbeatEpochMillis: Long?,
    val sessionCount: Int,
    val incompleteSessionCount: Int,
    val audioFileNames: List<String>,
    val activeSession: SessionAudioCheckpoint?,
    val eventFileName: String,
    val startedAtUtcOffsetSeconds: Int? = null,
    val lastHeartbeatUtcOffsetSeconds: Int? = null,
)

class UnreadableActiveJournalException(
    cause: Throwable?,
) : IllegalStateException(
    "Active capture journal is unreadable; recovery is required.",
    cause,
)

data class NightEndRecord(
    val nightId: String,
    val displayDate: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val reason: String,
    val interrupted: Boolean,
    val lastHeartbeatEpochMillis: Long?,
    val sessionCount: Int,
    val incompleteSessionCount: Int,
    val audioFileNames: List<String>,
    val eventFileName: String,
    val startedAtUtcOffsetSeconds: Int? = null,
    val endedAtUtcOffsetSeconds: Int? = null,
    val lastHeartbeatUtcOffsetSeconds: Int? = null,
)

data class CaptureJournalEvent(
    val eventId: String,
    val epochMillis: Long,
    val type: String,
    val attributes: Map<String, String>,
    val utcOffsetSeconds: Int? = null,
)

data class CaptureEndRecordScan(
    val records: List<NightEndRecord>,
    val unreadableFileNames: List<String>,
)

data class CaptureEventReadResult(
    val events: List<CaptureJournalEvent>,
    val malformedLineCount: Int,
    val sourceAvailable: Boolean,
)

enum class UnresolvedCaptureKind {
    ACTIVE,
    END_RECORDED_MARKER_PRESENT,
}

data class UnresolvedPriorCapture(
    val kind: UnresolvedCaptureKind,
    val activeJournal: ActiveNightJournal,
    val existingEndRecord: NightEndRecord?,
)

data class JournalRecoveryResult(
    val endRecord: NightEndRecord,
    val recoveredSessions: List<SessionAudioMetadata>,
    val completedPreviously: Boolean,
)

/**
 * Minimal M02 crash journal. It records operational state only; callers must never place dream
 * content in event attributes.
 */
class CaptureJournalStore(
    private val rootDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val utcOffsetSeconds: (Long) -> Int = ::systemUtcOffsetSeconds,
    private val eventId: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
) {
    @Synchronized
    fun beginNight(
        nightId: String,
        displayDate: String,
        startedAtEpochMillis: Long = clock(),
        startedAtUtcOffsetSeconds: Int = utcOffsetSeconds(startedAtEpochMillis),
    ): ActiveNightJournal {
        requireSafeIdentifier(nightId, "night ID")
        requireIsoDisplayDate(displayDate)
        require(startedAtEpochMillis >= 0L) { "Night start time must be nonnegative." }
        requireUtcOffsetSeconds(startedAtUtcOffsetSeconds)
        check(!activeFile.exists() && !activeTemporaryFile.exists()) {
            "An unresolved prior capture must be recovered before another night starts."
        }
        check(!endFile(nightId).exists()) { "The night ID was already used." }

        ensureDirectory(rootDirectory)
        ensureDirectory(eventsDirectory)
        ensureDirectory(endsDirectory)
        val journal = ActiveNightJournal(
            nightId = nightId,
            displayDate = displayDate,
            startedAtEpochMillis = startedAtEpochMillis,
            lastHeartbeatEpochMillis = null,
            sessionCount = 0,
            incompleteSessionCount = 0,
            audioFileNames = emptyList(),
            activeSession = null,
            eventFileName = eventFile(nightId).name,
            startedAtUtcOffsetSeconds = startedAtUtcOffsetSeconds,
        )
        writeActive(journal)
        appendEventInternal(
            active = journal,
            type = "night_started",
            epochMillis = startedAtEpochMillis,
            attributes = emptyMap(),
            stableEventId = "start_$nightId",
        )
        return journal
    }

    @Synchronized
    fun readActive(): ActiveNightJournal? {
        val candidates = listOf(activeFile, activeTemporaryFile).filter(File::isFile)
        if (candidates.isEmpty()) return null

        var lastFailure: Throwable? = null
        candidates
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                try {
                    return readActiveFile(file)
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
        throw UnreadableActiveJournalException(lastFailure)
    }

    /** Returns whether any readable or unreadable active-marker candidate still exists. */
    @Synchronized
    fun hasActiveMarker(): Boolean = activeFile.exists() || activeTemporaryFile.exists()

    /**
     * Preserves unreadable atomic marker candidates without interpreting or deleting their bytes.
     * A readable marker must always use normal recovery instead.
     */
    @Synchronized
    fun quarantineUnreadableActiveMarkers(
        quarantinedAtEpochMillis: Long = clock(),
    ): Int {
        require(quarantinedAtEpochMillis >= 0L) {
            "Quarantine time must be nonnegative."
        }
        val candidates = listOf(activeFile, activeTemporaryFile).filter(File::isFile)
        check(candidates.isNotEmpty()) {
            "There is no unreadable active marker to preserve."
        }
        check(candidates.none { runCatching { readActiveFile(it) }.isSuccess }) {
            "A readable active capture must use normal recovery."
        }

        val quarantineDirectory = File(rootDirectory, QUARANTINE_DIRECTORY_NAME)
        ensureDirectory(quarantineDirectory)
        candidates
            .sortedBy(File::getName)
            .forEachIndexed { index, source ->
                val destination = File(
                    quarantineDirectory,
                    "unreadable_${quarantinedAtEpochMillis}_${index}_${source.name}",
                )
                check(!destination.exists()) {
                    "The unreadable marker quarantine target already exists."
                }
                try {
                    Files.move(
                        source.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(source.toPath(), destination.toPath())
                }
            }
        return candidates.size
    }

    @Synchronized
    fun unresolvedPriorCapture(): UnresolvedPriorCapture? {
        val active = readActive() ?: return null
        val end = readEndRecord(active.nightId)
        return UnresolvedPriorCapture(
            kind = if (end == null) {
                UnresolvedCaptureKind.ACTIVE
            } else {
                UnresolvedCaptureKind.END_RECORDED_MARKER_PRESENT
            },
            activeJournal = active,
            existingEndRecord = end,
        )
    }

    @Synchronized
    fun checkpointSession(checkpoint: SessionAudioCheckpoint): ActiveNightJournal {
        validateCheckpoint(checkpoint)
        val active = requireActive()
        val prior = active.activeSession
        check(prior == null || prior.sessionId == checkpoint.sessionId) {
            "A different narrative session is already active."
        }
        check(checkpoint.sampleCount >= (prior?.sampleCount ?: 0L)) {
            "A session checkpoint cannot move its sample count backwards."
        }
        return active.copy(activeSession = checkpoint).also(::writeActive)
    }

    @Synchronized
    fun recordSessionFinalized(metadata: SessionAudioMetadata): ActiveNightJournal {
        validateAudioFileName(metadata.audioFileName)
        metadata.incompleteReason?.let(SessionIncompleteReason::requireValid)
        metadata.automaticSilenceTailSampleCount?.let { tailSamples ->
            require(tailSamples > 0L && tailSamples <= metadata.sampleCount) {
                "The automatic silence tail must fit within finalized audio."
            }
            check(metadata.incompleteReason == null) {
                "An incomplete session cannot have an automatic silence tail."
            }
        }
        val active = requireActive()
        val alreadyRecorded = metadata.audioFileName in active.audioFileNames
        val current = active.activeSession
        check(current == null || current.sessionId == metadata.sessionId) {
            "Finalized audio does not match the active session checkpoint."
        }
        if (alreadyRecorded) {
            return active.copy(
                activeSession = current?.takeUnless { it.sessionId == metadata.sessionId },
            ).also(::writeActive)
        }

        val updated = active.copy(
            sessionCount = active.sessionCount + 1,
            incompleteSessionCount = active.incompleteSessionCount +
                if (metadata.incompleteReason == null) 0 else 1,
            audioFileNames = active.audioFileNames + metadata.audioFileName,
            activeSession = current?.takeUnless { it.sessionId == metadata.sessionId },
        )
        writeActive(updated)
        appendEventInternal(
            active = updated,
            type = "session_finalized",
            epochMillis = metadata.finalizedAtEpochMillis,
            attributes = buildMap {
                put("audio_file", metadata.audioFileName)
                put("sample_count", metadata.sampleCount.toString())
                metadata.incompleteReason?.let { put("incomplete_reason", it) }
                metadata.automaticSilenceTailSampleCount?.let {
                    put("automatic_silence_tail_sample_count", it.toString())
                }
            },
            stableEventId = "session_${metadata.sessionId}",
        )
        return updated
    }

    @Synchronized
    fun heartbeat(
        framesRead: Long,
        gapCount: Int,
        microphoneSilenced: Boolean,
        readiness: String? = null,
        charging: Boolean? = null,
        sessionActive: Boolean? = null,
        keywordStreamProgress: KeywordStreamProgress? = null,
        epochMillis: Long = clock(),
        heartbeatUtcOffsetSeconds: Int = utcOffsetSeconds(epochMillis),
    ): ActiveNightJournal {
        require(framesRead >= 0L) { "Heartbeat frame count must be nonnegative." }
        require(gapCount >= 0) { "Heartbeat gap count must be nonnegative." }
        require(epochMillis >= 0L) { "Heartbeat time must be nonnegative." }
        readiness?.let { requireEventCode(it, "Heartbeat readiness") }
        keywordStreamProgress?.lastResetReason?.let {
            requireEventCode(it, "Keyword stream reset reason")
        }
        requireUtcOffsetSeconds(heartbeatUtcOffsetSeconds)
        val active = requireActive()
        val updated = active.copy(
            lastHeartbeatEpochMillis = epochMillis,
            lastHeartbeatUtcOffsetSeconds = heartbeatUtcOffsetSeconds,
        )
        writeActive(updated)
        appendEventInternal(
            active = updated,
            type = "heartbeat",
            epochMillis = epochMillis,
            attributes = buildMap {
                put("frames_read", framesRead.toString())
                put("gap_count", gapCount.toString())
                put("microphone_silenced", microphoneSilenced.toString())
                readiness?.let { put("readiness", it) }
                charging?.let { put("charging", it.toString()) }
                sessionActive?.let { put("session_active", it.toString()) }
                keywordStreamProgress?.let { progress ->
                    put("kws_accepted_frame_count", progress.acceptedFrameCount.toString())
                    put("kws_decode_count", progress.decodeCount.toString())
                    put("kws_reset_count", progress.resetCount.toString())
                    progress.lastResetReason?.let {
                        put("kws_last_reset_reason", it)
                    }
                }
            },
        )
        return updated
    }

    /**
     * Appends operational evidence. Attribute keys are restricted, but privacy still depends on
     * the caller never passing spoken or transcribed content as values.
     */
    @Synchronized
    fun appendEvent(
        type: String,
        attributes: Map<String, String> = emptyMap(),
        epochMillis: Long = clock(),
    ): CaptureJournalEvent {
        val active = requireActive()
        return appendEventInternal(active, type, epochMillis, attributes)
    }

    @Synchronized
    fun endNight(
        reason: String,
        interrupted: Boolean,
        endedAtEpochMillis: Long = clock(),
    ): NightEndRecord {
        SessionIncompleteReason.requireValid(reason)
        require(endedAtEpochMillis >= 0L) { "Night end time must be nonnegative." }
        val active = requireActive()
        check(active.activeSession == null) {
            "The active narrative must be finalized before ending the night."
        }
        val end = NightEndRecord(
            nightId = active.nightId,
            displayDate = active.displayDate,
            startedAtEpochMillis = active.startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            reason = reason,
            interrupted = interrupted,
            lastHeartbeatEpochMillis = active.lastHeartbeatEpochMillis,
            sessionCount = active.sessionCount,
            incompleteSessionCount = active.incompleteSessionCount,
            audioFileNames = active.audioFileNames,
            eventFileName = active.eventFileName,
            startedAtUtcOffsetSeconds = active.startedAtUtcOffsetSeconds,
            endedAtUtcOffsetSeconds = utcOffsetSeconds(endedAtEpochMillis),
            lastHeartbeatUtcOffsetSeconds = active.lastHeartbeatUtcOffsetSeconds,
        )
        finishEndRecord(active, end)
        return end
    }

    /**
     * Resolves the prior active marker without deleting session artifacts. A currently referenced
     * WAV plus any writer-owned stray partial/final WAV is first recovered as incomplete.
     */
    @Synchronized
    fun recoverUnresolved(
        audioWriter: SessionAudioWriter,
        reason: String = SessionIncompleteReason.PROCESS_INTERRUPTED,
        recoveredAtEpochMillis: Long = clock(),
    ): JournalRecoveryResult? {
        SessionIncompleteReason.requireValid(reason)
        require(recoveredAtEpochMillis >= 0L) { "Recovery time must be nonnegative." }
        val unresolved = unresolvedPriorCapture() ?: return null
        unresolved.existingEndRecord?.let { existing ->
            appendEventInternal(
                active = unresolved.activeJournal,
                type = "night_ended",
                epochMillis = existing.endedAtEpochMillis,
                attributes = mapOf(
                    "reason" to existing.reason,
                    "interrupted" to existing.interrupted.toString(),
                ),
                stableEventId = "end_${existing.nightId}",
            )
            clearActiveMarker()
            return JournalRecoveryResult(
                endRecord = existing,
                recoveredSessions = emptyList(),
                completedPreviously = true,
            )
        }

        val active = unresolved.activeJournal
        val references = listOfNotNull(active.activeSession)
        val recovered = audioWriter.recoverInterrupted(references, reason)
        val recoveredNames = recovered.map(SessionAudioMetadata::audioFileName)
        val combinedNames = (active.audioFileNames + recoveredNames).distinct()
        val newlyAttachedCount = recoveredNames.count { it !in active.audioFileNames }
        val end = NightEndRecord(
            nightId = active.nightId,
            displayDate = active.displayDate,
            startedAtEpochMillis = active.startedAtEpochMillis,
            endedAtEpochMillis = recoveredAtEpochMillis,
            reason = reason,
            interrupted = true,
            lastHeartbeatEpochMillis = active.lastHeartbeatEpochMillis,
            sessionCount = active.sessionCount + newlyAttachedCount,
            incompleteSessionCount = active.incompleteSessionCount + newlyAttachedCount,
            audioFileNames = combinedNames,
            eventFileName = active.eventFileName,
            startedAtUtcOffsetSeconds = active.startedAtUtcOffsetSeconds,
            endedAtUtcOffsetSeconds = utcOffsetSeconds(recoveredAtEpochMillis),
            lastHeartbeatUtcOffsetSeconds = active.lastHeartbeatUtcOffsetSeconds,
        )
        appendEventInternal(
            active = active,
            type = "capture_recovered",
            epochMillis = recoveredAtEpochMillis,
            attributes = mapOf(
                "reason" to reason,
                "recovered_session_count" to recovered.size.toString(),
            ),
            stableEventId = "recovery_${active.nightId}",
        )
        finishEndRecord(active, end)
        return JournalRecoveryResult(
            endRecord = end,
            recoveredSessions = recovered,
            completedPreviously = false,
        )
    }

    @Synchronized
    fun readEndRecord(nightId: String): NightEndRecord? {
        requireSafeIdentifier(nightId, "night ID")
        val file = endFile(nightId)
        if (!file.isFile) return null
        return readEndFile(file).also(::validateEndRecord)
    }

    @Synchronized
    fun latestEndRecord(): NightEndRecord? {
        if (!endsDirectory.isDirectory) return null
        return endsDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(NIGHT_FILE_PREFIX) }
            .filter { it.name.endsWith(END_SUFFIX) }
            .mapNotNull { file ->
                runCatching { readEndFile(file).also(::validateEndRecord) }.getOrNull()
            }
            .maxWithOrNull(
                compareBy<NightEndRecord> { it.endedAtEpochMillis }
                    .thenBy { it.nightId },
            )
    }

    @Synchronized
    fun scanEndRecords(): CaptureEndRecordScan {
        if (!endsDirectory.isDirectory) {
            return CaptureEndRecordScan(
                records = emptyList(),
                unreadableFileNames = emptyList(),
            )
        }
        val records = mutableListOf<NightEndRecord>()
        val unreadable = mutableListOf<String>()
        endsDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(NIGHT_FILE_PREFIX) }
            .filter { it.name.endsWith(END_SUFFIX) }
            .sortedBy { it.name }
            .forEach { file ->
                runCatching { readEndFile(file).also(::validateEndRecord) }
                    .onSuccess(records::add)
                    .onFailure { unreadable += file.name }
            }
        return CaptureEndRecordScan(
            records = records.sortedWith(
                compareByDescending<NightEndRecord> { it.startedAtEpochMillis }
                    .thenByDescending { it.nightId },
            ),
            unreadableFileNames = unreadable,
        )
    }

    @Synchronized
    fun readEvents(nightId: String): List<CaptureJournalEvent> {
        requireSafeIdentifier(nightId, "night ID")
        val file = eventFile(nightId)
        if (!file.isFile) return emptyList()
        return file.readLines(StandardCharsets.UTF_8).filter(String::isNotBlank).map(::parseEvent)
    }

    @Synchronized
    fun readEventsForImport(
        nightId: String,
        expectedFileName: String? = null,
    ): CaptureEventReadResult {
        requireSafeIdentifier(nightId, "night ID")
        val file = eventFile(nightId)
        if (expectedFileName != null && expectedFileName != file.name) {
            return CaptureEventReadResult(
                events = emptyList(),
                malformedLineCount = 0,
                sourceAvailable = false,
            )
        }
        if (!file.isFile) {
            return CaptureEventReadResult(
                events = emptyList(),
                malformedLineCount = 0,
                sourceAvailable = false,
            )
        }
        return try {
            val events = mutableListOf<CaptureJournalEvent>()
            var malformedLineCount = 0
            file.useLines(StandardCharsets.UTF_8) { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    runCatching { parseEvent(line) }
                        .onSuccess(events::add)
                        .onFailure { malformedLineCount += 1 }
                }
            }
            CaptureEventReadResult(
                events = events,
                malformedLineCount = malformedLineCount,
                sourceAvailable = true,
            )
        } catch (_: Exception) {
            CaptureEventReadResult(
                events = emptyList(),
                malformedLineCount = 0,
                sourceAvailable = false,
            )
        }
    }

    /**
     * Removes an ended journal only after its Room import commits. Audio and metadata are never
     * removed here. Any active marker postpones acknowledgement so recovery evidence cannot race
     * with cleanup.
     */
    @Synchronized
    fun acknowledgeEndedNight(nightId: String): Boolean {
        requireSafeIdentifier(nightId, "night ID")
        if (activeFile.exists() || activeTemporaryFile.exists()) return false

        val events = eventFile(nightId)
        if (events.exists() && !events.delete()) return false

        val end = endFile(nightId)
        if (end.exists() && !end.delete()) return false
        return true
    }

    private fun finishEndRecord(active: ActiveNightJournal, end: NightEndRecord) {
        writeEnd(end)
        appendEventInternal(
            active = active,
            type = "night_ended",
            epochMillis = end.endedAtEpochMillis,
            attributes = mapOf(
                "reason" to end.reason,
                "interrupted" to end.interrupted.toString(),
            ),
            stableEventId = "end_${end.nightId}",
        )
        clearActiveMarker()
    }

    private fun validateEndRecord(end: NightEndRecord) {
        end.audioFileNames.forEach(::validateAudioFileName)
    }

    private fun appendEventInternal(
        active: ActiveNightJournal,
        type: String,
        epochMillis: Long,
        attributes: Map<String, String>,
        stableEventId: String? = null,
    ): CaptureJournalEvent {
        requireEventCode(type, "event type")
        require(epochMillis >= 0L) { "Event time must be nonnegative." }
        val eventUtcOffsetSeconds = utcOffsetSeconds(epochMillis)
        requireUtcOffsetSeconds(eventUtcOffsetSeconds)
        attributes.keys.forEach { requireEventCode(it, "event attribute") }
        val identifier = stableEventId ?: eventId()
        requireSafeIdentifier(identifier, "event ID")
        val event = CaptureJournalEvent(
            eventId = identifier,
            epochMillis = epochMillis,
            type = type,
            attributes = attributes.toSortedMap(),
            utcOffsetSeconds = eventUtcOffsetSeconds,
        )
        val file = File(eventsDirectory, active.eventFileName)
        check(file.canonicalFile.parentFile == eventsDirectory.canonicalFile) {
            "Active journal contains an unsafe event filename."
        }
        if (stableEventId != null && eventIdExists(file, stableEventId)) return event

        ensureDirectory(eventsDirectory)
        FileOutputStream(file, true).use { stream ->
            stream.write(formatEvent(event).toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        return event
    }

    private fun eventIdExists(file: File, expectedId: String): Boolean {
        if (!file.isFile) return false
        return file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.filter(String::isNotBlank).any { line ->
                runCatching { parseEvent(line).eventId == expectedId }.getOrDefault(false)
            }
        }
    }

    private fun requireActive(): ActiveNightJournal =
        checkNotNull(readActive()) { "There is no active night journal." }

    private fun writeActive(active: ActiveNightJournal) {
        writePropertiesAtomically(activeFile, active.toProperties())
    }

    private fun writeEnd(end: NightEndRecord) {
        writePropertiesAtomically(endFile(end.nightId), end.toProperties())
    }

    private fun clearActiveMarker() {
        check(activeFile.delete() || !activeFile.exists()) {
            "The completed active capture marker could not be removed."
        }
        check(activeTemporaryFile.delete() || !activeTemporaryFile.exists()) {
            "The completed active capture temporary marker could not be removed."
        }
    }

    private val activeFile: File
        get() = File(rootDirectory, ACTIVE_FILE_NAME)

    private val activeTemporaryFile: File
        get() = File(rootDirectory, "$ACTIVE_FILE_NAME.part")

    private val eventsDirectory: File
        get() = File(rootDirectory, EVENTS_DIRECTORY_NAME)

    private val endsDirectory: File
        get() = File(rootDirectory, ENDS_DIRECTORY_NAME)

    private fun eventFile(nightId: String) =
        File(eventsDirectory, "$NIGHT_FILE_PREFIX$nightId$EVENT_SUFFIX")

    private fun endFile(nightId: String) =
        File(endsDirectory, "$NIGHT_FILE_PREFIX$nightId$END_SUFFIX")

    private companion object {
        const val ACTIVE_FILE_NAME = "active.properties"
        const val EVENTS_DIRECTORY_NAME = "events"
        const val ENDS_DIRECTORY_NAME = "ended"
        const val QUARANTINE_DIRECTORY_NAME = "quarantine"
        const val NIGHT_FILE_PREFIX = "n_"
        const val EVENT_SUFFIX = ".events"
        const val END_SUFFIX = ".properties"
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        val EVENT_CODE = Regex("[a-z0-9]+(?:_[a-z0-9]+)*")

        fun requireSafeIdentifier(value: String, label: String) {
            require(SAFE_IDENTIFIER.matches(value)) {
                "$label must contain only safe identifier characters."
            }
        }

        fun requireEventCode(value: String, label: String) {
            require(EVENT_CODE.matches(value)) {
                "$label must be a stable lowercase snake_case code."
            }
        }

        fun validateAudioFileName(name: String) {
            require(AUDIO_FILE_PATTERN.matches(name)) {
                "Audio filename is not a writer-owned opaque WAV."
            }
        }

        fun validateCheckpoint(checkpoint: SessionAudioCheckpoint) {
            require(checkpoint.sampleCount >= 0L)
            require(checkpoint.preRollSampleCount in 0L..checkpoint.sampleCount)
            require(checkpoint.startedAtEpochMillis >= 0L)
            validateAudioFileName(checkpoint.audioFileName)
            require(checkpoint.partialFileName == "${checkpoint.audioFileName}.part")
            checkpoint.cueStartSample?.let { require(it in 0L..checkpoint.sampleCount) }
            checkpoint.cueEndSampleExclusive?.let { end ->
                val start = requireNotNull(checkpoint.cueStartSample)
                require(end in start..checkpoint.sampleCount)
            }
            checkpoint.startedAtUtcOffsetSeconds?.let(::requireUtcOffsetSeconds)
        }

        val AUDIO_FILE_PATTERN = Regex("a_[0-9a-f]{32}\\.wav")
    }
}

private fun ActiveNightJournal.toProperties(): Properties = Properties().apply {
    setProperty("version", "1")
    setProperty("state", "active")
    setProperty("night_id", nightId)
    setProperty("display_date", displayDate)
    setProperty("started_at_epoch_millis", startedAtEpochMillis.toString())
    startedAtUtcOffsetSeconds?.let {
        setProperty("started_at_utc_offset_seconds", it.toString())
    }
    lastHeartbeatEpochMillis?.let {
        setProperty("last_heartbeat_epoch_millis", it.toString())
    }
    lastHeartbeatUtcOffsetSeconds?.let {
        setProperty("last_heartbeat_utc_offset_seconds", it.toString())
    }
    setProperty("session_count", sessionCount.toString())
    setProperty("incomplete_session_count", incompleteSessionCount.toString())
    setProperty("audio_files", audioFileNames.joinToString(","))
    setProperty("event_file", eventFileName)
    activeSession?.let { checkpoint ->
        setProperty("active_session.id", checkpoint.sessionId)
        setProperty("active_session.audio_file", checkpoint.audioFileName)
        setProperty("active_session.partial_file", checkpoint.partialFileName)
        setProperty(
            "active_session.started_at_epoch_millis",
            checkpoint.startedAtEpochMillis.toString(),
        )
        checkpoint.startedAtUtcOffsetSeconds?.let {
            setProperty("active_session.started_at_utc_offset_seconds", it.toString())
        }
        setProperty("active_session.sample_count", checkpoint.sampleCount.toString())
        setProperty(
            "active_session.pre_roll_sample_count",
            checkpoint.preRollSampleCount.toString(),
        )
        checkpoint.cueStartSample?.let {
            setProperty("active_session.cue_start_sample", it.toString())
        }
        checkpoint.cueEndSampleExclusive?.let {
            setProperty("active_session.cue_end_sample_exclusive", it.toString())
        }
    }
}

private fun NightEndRecord.toProperties(): Properties = Properties().apply {
    setProperty("version", "1")
    setProperty("state", "ended")
    setProperty("night_id", nightId)
    setProperty("display_date", displayDate)
    setProperty("started_at_epoch_millis", startedAtEpochMillis.toString())
    setProperty("ended_at_epoch_millis", endedAtEpochMillis.toString())
    startedAtUtcOffsetSeconds?.let {
        setProperty("started_at_utc_offset_seconds", it.toString())
    }
    endedAtUtcOffsetSeconds?.let {
        setProperty("ended_at_utc_offset_seconds", it.toString())
    }
    setProperty("reason", reason)
    setProperty("interrupted", interrupted.toString())
    lastHeartbeatEpochMillis?.let {
        setProperty("last_heartbeat_epoch_millis", it.toString())
    }
    lastHeartbeatUtcOffsetSeconds?.let {
        setProperty("last_heartbeat_utc_offset_seconds", it.toString())
    }
    setProperty("session_count", sessionCount.toString())
    setProperty("incomplete_session_count", incompleteSessionCount.toString())
    setProperty("audio_files", audioFileNames.joinToString(","))
    setProperty("event_file", eventFileName)
}

private fun readActiveFile(file: File): ActiveNightJournal {
    val properties = loadCaptureProperties(file, expectedState = "active")
    val displayDate = properties.required("display_date")
    requireIsoDisplayDate(displayDate)
    val activeSessionId = properties.getProperty("active_session.id")
    val activeSession = activeSessionId?.let {
        SessionAudioCheckpoint(
            sessionId = it,
            audioFileName = properties.required("active_session.audio_file"),
            partialFileName = properties.required("active_session.partial_file"),
            startedAtEpochMillis = properties.requiredLong(
                "active_session.started_at_epoch_millis",
            ),
            sampleCount = properties.requiredLong("active_session.sample_count"),
            preRollSampleCount = properties.requiredLong(
                "active_session.pre_roll_sample_count",
            ),
            cueStartSample = properties.optionalLong("active_session.cue_start_sample"),
            cueEndSampleExclusive = properties.optionalLong(
                "active_session.cue_end_sample_exclusive",
            ),
            startedAtUtcOffsetSeconds =
                properties.optionalInt("active_session.started_at_utc_offset_seconds"),
        )
    }
    return ActiveNightJournal(
        nightId = properties.required("night_id"),
        displayDate = displayDate,
        startedAtEpochMillis = properties.requiredLong("started_at_epoch_millis"),
        lastHeartbeatEpochMillis = properties.optionalLong("last_heartbeat_epoch_millis"),
        sessionCount = properties.requiredInt("session_count"),
        incompleteSessionCount = properties.requiredInt("incomplete_session_count"),
        audioFileNames = properties.stringList("audio_files"),
        activeSession = activeSession,
        eventFileName = properties.required("event_file"),
        startedAtUtcOffsetSeconds =
            properties.optionalInt("started_at_utc_offset_seconds"),
        lastHeartbeatUtcOffsetSeconds =
            properties.optionalInt("last_heartbeat_utc_offset_seconds"),
    )
}

private fun readEndFile(file: File): NightEndRecord {
    val properties = loadCaptureProperties(file, expectedState = "ended")
    val displayDate = properties.required("display_date")
    requireIsoDisplayDate(displayDate)
    return NightEndRecord(
        nightId = properties.required("night_id"),
        displayDate = displayDate,
        startedAtEpochMillis = properties.requiredLong("started_at_epoch_millis"),
        endedAtEpochMillis = properties.requiredLong("ended_at_epoch_millis"),
        reason = properties.required("reason"),
        interrupted = properties.required("interrupted").toBooleanStrict(),
        lastHeartbeatEpochMillis = properties.optionalLong("last_heartbeat_epoch_millis"),
        sessionCount = properties.requiredInt("session_count"),
        incompleteSessionCount = properties.requiredInt("incomplete_session_count"),
        audioFileNames = properties.stringList("audio_files"),
        eventFileName = properties.required("event_file"),
        startedAtUtcOffsetSeconds =
            properties.optionalInt("started_at_utc_offset_seconds"),
        endedAtUtcOffsetSeconds =
            properties.optionalInt("ended_at_utc_offset_seconds"),
        lastHeartbeatUtcOffsetSeconds =
            properties.optionalInt("last_heartbeat_utc_offset_seconds"),
    )
}

private fun loadCaptureProperties(file: File, expectedState: String): Properties =
    Properties().apply {
        FileInputStream(file).use(::load)
        require(getProperty("version") == "1") { "Unsupported capture journal version." }
        require(getProperty("state") == expectedState) { "Unexpected capture journal state." }
    }

private fun Properties.stringList(key: String): List<String> =
    getProperty(key).orEmpty().split(',').filter(String::isNotBlank)

private fun Properties.optionalInt(key: String): Int? =
    getProperty(key)?.toInt()?.also(::requireUtcOffsetSeconds)

private fun formatEvent(event: CaptureJournalEvent): String {
    val attributes = event.attributes.entries.joinToString(";") { (key, value) ->
        "$key=${encodeEventValue(value)}"
    }
    return buildString {
        append(event.epochMillis)
        append('\t')
        append(event.eventId)
        append('\t')
        append(event.type)
        append('\t')
        event.utcOffsetSeconds?.let {
            requireUtcOffsetSeconds(it)
            append(it)
        }
        append('\t')
        append(attributes)
        append('\n')
    }
}

private fun parseEvent(line: String): CaptureJournalEvent {
    val columns = line.split('\t', limit = 5)
    require(columns.size == 4 || columns.size == 5) {
        "Malformed capture journal event."
    }
    val attributeColumn = if (columns.size == 5) columns[4] else columns[3]
    val attributes = if (attributeColumn.isEmpty()) {
        emptyMap()
    } else {
        attributeColumn.split(';').associate { encodedAttribute ->
            val separator = encodedAttribute.indexOf('=')
            require(separator > 0) { "Malformed capture journal event attribute." }
            encodedAttribute.substring(0, separator) to
                decodeEventValue(encodedAttribute.substring(separator + 1))
        }
    }
    return CaptureJournalEvent(
        eventId = columns[1],
        epochMillis = columns[0].toLong(),
        type = columns[2],
        attributes = attributes,
        utcOffsetSeconds = if (columns.size == 5 && columns[3].isNotEmpty()) {
            columns[3].toInt().also(::requireUtcOffsetSeconds)
        } else {
            null
        },
    )
}

private fun encodeEventValue(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeEventValue(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

private fun requireIsoDisplayDate(value: String) {
    require(runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)) {
        "Display date must use the yyyy-MM-dd ISO format."
    }
}
