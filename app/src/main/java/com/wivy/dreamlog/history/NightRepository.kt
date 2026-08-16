package com.wivy.dreamlog.history

import com.wivy.dreamlog.capture.ActiveNightJournal
import com.wivy.dreamlog.capture.CaptureJournalEvent
import com.wivy.dreamlog.capture.CaptureJournalStore
import com.wivy.dreamlog.capture.NightEndRecord
import com.wivy.dreamlog.capture.SessionAudioCheckpoint
import com.wivy.dreamlog.capture.SessionAudioMetadata
import com.wivy.dreamlog.capture.SessionAudioWriter
import com.wivy.dreamlog.capture.captureMicrophoneSilencedState
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

internal data class RawAudioRetentionResult(
    val expiredNightIds: List<String> = emptyList(),
    val deferredNightIds: List<String> = emptyList(),
    val failureCount: Int = 0,
)

class NightRepository(
    private val dao: NightDao,
    private val journalStore: CaptureJournalStore,
    private val audioRootDirectory: File,
    private val transcriptionDao: TranscriptionDao? = null,
    private val enrichmentDao: EnrichmentDao? = null,
    private val offsetAtEpochMillis: (Long) -> Int = { epochMillis ->
        ZoneId.systemDefault()
            .rules
            .getOffset(Instant.ofEpochMilli(epochMillis))
            .totalSeconds
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val rawAudioRetentionMillis: () -> Long? = { null },
    private val transcriptionStateReconciler: ((String) -> Unit)? =
        transcriptionDao?.let { transcription ->
            { nightId: String -> transcription.reconcileNightState(nightId) }
        },
) {
    @Synchronized
    fun prepareStartingNight(
        nightId: String,
        displayDate: String,
        startedAtEpochMillis: Long,
        startedUtcOffsetSeconds: Int,
    ) {
        requireSafeIdentifier(nightId)
        requireUtcOffset(startedUtcOffsetSeconds)
        val existing = dao.readNight(nightId)?.night
        check(existing == null) { "The night ID already exists in local history." }
        dao.upsertCaptureGraph(
            night = NightEntity(
                nightId = nightId,
                displayDate = displayDate,
                startedAtEpochMillis = startedAtEpochMillis,
                startedUtcOffsetSeconds = startedUtcOffsetSeconds,
                endedAtEpochMillis = null,
                endedUtcOffsetSeconds = null,
                captureState = NightCaptureState.STARTING,
                endReason = null,
                interrupted = false,
                lastHeartbeatEpochMillis = null,
                lastHeartbeatUtcOffsetSeconds = null,
                reportedSessionCount = 0,
                reportedIncompleteSessionCount = 0,
                hadMicrophoneSilencing = false,
                hadAudioGap = false,
                rawAudioState = RawAudioState.NONE,
                transcriptionState = ProcessingState.NOT_STARTED,
                transcriptionFailure = null,
                enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
                enrichmentFailure = null,
                importWarning = null,
            ),
            sessions = emptyList(),
            events = emptyList(),
        )
    }

    @Synchronized
    fun markStartFailed(
        nightId: String,
        reason: String,
    ) {
        val existing = dao.readNight(nightId) ?: return
        val endedAt = clock()
        dao.upsertCaptureGraph(
            night = existing.night.copy(
                endedAtEpochMillis = endedAt,
                endedUtcOffsetSeconds = offset(endedAt),
                captureState = NightCaptureState.INTERRUPTED,
                endReason = reason,
                interrupted = true,
                importWarning = "Listening did not start; review the preflight checks and retry.",
            ),
            sessions = emptyList(),
            events = emptyList(),
        )
    }

    @Synchronized
    fun reconcile(runtimeActiveNightId: String?): HistoryLoadResult {
        val active = journalStore.readActive()
        val scan = journalStore.scanEndRecords()
        var importedNightCount = 0
        var acknowledgedNightCount = 0
        var warningCount = scan.unreadableFileNames.size

        scan.records.forEach { end ->
            if (active?.nightId == end.nightId) return@forEach
            val import = importEndedNight(end)
            importedNightCount += 1
            warningCount += import.warningCount
            if (
                import.canAcknowledge &&
                journalStore.acknowledgeEndedNight(end.nightId)
            ) {
                acknowledgedNightCount += 1
            }
        }

        active?.let {
            val import = importActiveNight(
                active = it,
                isRuntimeActive = runtimeActiveNightId == it.nightId,
            )
            importedNightCount += 1
            warningCount += import.warningCount
        }

        reconcileUnfinishedRoomNights(
            runtimeActiveNightId = runtimeActiveNightId,
            activeJournalNightId = active?.nightId,
        )
        transcriptionStateReconciler?.let { reconcileTranscriptionState ->
            dao.readHistory()
                .asSequence()
                .map(NightWithDetails::night)
                .filter { night ->
                    night.captureState == NightCaptureState.ENDED ||
                        night.captureState == NightCaptureState.INTERRUPTED
                }
                .forEach { night -> reconcileTranscriptionState(night.nightId) }
        }

        val retention = rawAudioRetentionMillis()?.let(::expireRawAudio)
        return HistoryLoadResult(
            nights = readHistory(),
            importedNightCount = importedNightCount,
            acknowledgedNightCount = acknowledgedNightCount,
            warningCount = warningCount + (retention?.failureCount ?: 0),
        )
    }

    @Synchronized
    fun readHistory(): List<NightRecord> =
        dao.readHistory().map(::toRecord)

    @Synchronized
    fun readNight(nightId: String): NightRecord? =
        dao.readNight(nightId)?.let(::toRecord)

    @Synchronized
    fun editDream(
        dreamId: String,
        currentTitle: String?,
        currentText: String,
    ): Boolean = requireNotNull(enrichmentDao) {
        "Dream editing is not configured."
    }.editDream(
        dreamId = dreamId,
        currentTitle = currentTitle,
        currentText = currentText,
        editedAtEpochMillis = clock(),
    )

    @Synchronized
    fun deleteDream(dreamId: String): Boolean = requireNotNull(enrichmentDao) {
        "Dream deletion is not configured."
    }.deleteDream(
        dreamId = dreamId,
        deletedAtEpochMillis = clock(),
    )

    @Synchronized
    fun restoreDream(dreamId: String): Boolean = requireNotNull(enrichmentDao) {
        "Dream restoration is not configured."
    }.restoreDream(dreamId)

    @Synchronized
    fun deleteNightRawAudio(nightId: String): Boolean {
        requireSafeIdentifier(nightId)
        val deletionLease = RawAudioUseRegistry.processWide.tryAcquireDeletion(nightId)
            ?: error("Raw audio is still in use. Stop playback or local processing and try again.")
        deletionLease.use {
            val existing = dao.readNight(nightId) ?: return false
            requireFinalizedArchiveMutation(existing.night)
            check(!journalStore.hasActiveMarker()) {
                "Raw audio cannot be deleted while capture recovery evidence is active."
            }
            deleteCanonicalNightAudioDirectory(nightId)
            return dao.markNightRawAudioDeleted(nightId)
        }
    }

    @Synchronized
    fun deleteWholeNight(nightId: String): Boolean {
        requireSafeIdentifier(nightId)
        val deletionLease = RawAudioUseRegistry.processWide.tryAcquireDeletion(nightId)
            ?: error("This night is still in use. Stop playback or local processing and try again.")
        deletionLease.use {
            val existing = dao.readNight(nightId) ?: return false
            requireFinalizedArchiveMutation(existing.night)
            check(journalStore.acknowledgeEndedNight(nightId)) {
                "The night cannot be deleted while capture recovery evidence is active."
            }
            deleteCanonicalNightAudioDirectory(nightId)
            check(dao.deleteNight(nightId) == 1) {
                "The selected night could not be deleted."
            }
            return true
        }
    }

    @Synchronized
    internal fun expireRawAudio(retentionMillis: Long): RawAudioRetentionResult {
        require(retentionMillis >= 0L) {
            "The raw-audio retention period must be nonnegative."
        }
        val now = clock()
        val records = readHistory()
        val candidates = RawAudioRetentionPolicy.selectExpiredNightIds(
            records = records,
            nowEpochMillis = now,
            retentionMillis = retentionMillis,
            inUseNightIds = RawAudioUseRegistry.processWide.snapshotInUseNightIds(),
        )
        if (candidates.isEmpty()) return RawAudioRetentionResult()
        if (journalStore.hasActiveMarker()) {
            return RawAudioRetentionResult(deferredNightIds = candidates)
        }

        val expiredNightIds = mutableListOf<String>()
        val deferredNightIds = mutableListOf<String>()
        var failureCount = 0
        candidates.forEach { nightId ->
            val deletionLease = RawAudioUseRegistry.processWide.tryAcquireDeletion(nightId)
            if (deletionLease == null) {
                deferredNightIds += nightId
                return@forEach
            }
            deletionLease.use {
                val current = readNight(nightId) ?: return@use
                val stillEligible = RawAudioRetentionPolicy.selectExpiredNightIds(
                    records = listOf(current),
                    nowEpochMillis = clock(),
                    retentionMillis = retentionMillis,
                ).singleOrNull() == nightId
                if (!stillEligible) return@use

                runCatching {
                    deleteCanonicalNightAudioDirectory(nightId)
                    val updatedSessions = current.sessions.map { session ->
                        if (session.audioState == AudioEvidenceState.RETAINED) {
                            session.copy(audioState = AudioEvidenceState.EXPIRED)
                        } else {
                            session
                        }
                    }
                    dao.upsertCaptureGraph(
                        night = current.night.copy(
                            rawAudioState = if (updatedSessions.isEmpty()) {
                                RawAudioState.NONE
                            } else {
                                RawAudioState.UNAVAILABLE
                            },
                        ),
                        sessions = updatedSessions,
                        events = emptyList(),
                    )
                    transcriptionStateReconciler?.invoke(nightId)
                }.fold(
                    onSuccess = { expiredNightIds += nightId },
                    onFailure = { failureCount += 1 },
                )
            }
        }
        return RawAudioRetentionResult(
            expiredNightIds = expiredNightIds,
            deferredNightIds = deferredNightIds,
            failureCount = failureCount,
        )
    }

    private fun requireFinalizedArchiveMutation(night: NightEntity) {
        check(
            night.captureState == NightCaptureState.ENDED ||
                night.captureState == NightCaptureState.INTERRUPTED,
        ) { "Only an ended night can be changed from the archive." }
        check(night.endedAtEpochMillis != null) {
            "The selected night has not finished finalizing."
        }
    }

    private fun canonicalNightAudioDirectory(nightId: String): File {
        requireSafeIdentifier(nightId)
        val canonicalRoot = audioRootDirectory.canonicalFile
        val nightDirectory = File(canonicalRoot, nightId).canonicalFile
        require(nightDirectory.parentFile == canonicalRoot) {
            "The selected night audio directory escaped app-private storage."
        }
        return nightDirectory
    }

    private fun deleteCanonicalNightAudioDirectory(nightId: String) {
        val nightDirectory = canonicalNightAudioDirectory(nightId)
        if (!nightDirectory.exists()) return
        check(nightDirectory.isDirectory) {
            "The selected night audio path is not a directory."
        }
        check(nightDirectory.deleteRecursively() && !nightDirectory.exists()) {
            "Not all raw audio for the selected night could be deleted."
        }
    }

    private fun importEndedNight(end: NightEndRecord): ImportResult {
        val existing = dao.readNight(end.nightId)
        val eventRead = journalStore.readEventsForImport(
            nightId = end.nightId,
            expectedFileName = end.eventFileName,
        )
        val writer = SessionAudioWriter(canonicalNightAudioDirectory(end.nightId))
        val existingSessions = existing?.sessions.orEmpty().associateBy { it.sessionId }
        val journalAudioFileNames = end.audioFileNames.distinct()
        val journalAudioFileNameSet = journalAudioFileNames.toSet()
        val journalOrder = journalAudioFileNames
            .mapIndexed { index, audioFileName -> audioFileName to index }
            .toMap()
        val metadataByAudioFileName = linkedMapOf<String, SessionAudioMetadata?>().apply {
            journalAudioFileNames.forEach { audioFileName ->
                put(audioFileName, writer.readMetadata(audioFileName))
            }
        }
        val discovered = writer.discoverFinalizedAudio()
            .filter { metadata -> metadata.isWithin(end) }
        discovered.forEach { metadata ->
            metadataByAudioFileName[metadata.audioFileName] = metadata
        }
        val recoveredAudioFileNames = discovered
            .map(SessionAudioMetadata::audioFileName)
            .filterNot(journalAudioFileNameSet::contains)
            .toSet()
        val orderedAudio = metadataByAudioFileName
            .map { (audioFileName, metadata) ->
                FinalizedAudioImport(
                    audioFileName = audioFileName,
                    metadata = metadata,
                    existing = runCatching {
                        existingSessions[sessionIdFromAudioFile(audioFileName)]
                    }.getOrNull(),
                    journalOrder = journalOrder[audioFileName],
                )
            }
            .sortedWith(
                compareBy<FinalizedAudioImport> {
                    it.metadata?.startedAtEpochMillis
                        ?: it.existing?.startedAtEpochMillis
                        ?: Long.MAX_VALUE
                }.thenBy { it.journalOrder ?: Int.MAX_VALUE }
                    .thenBy { it.audioFileName },
            )
        val sessions = orderedAudio.mapIndexed { index, audio ->
            sessionFromFinalizedAudio(
                nightId = end.nightId,
                captureOrder = index,
                audioFileName = audio.audioFileName,
                metadata = audio.metadata,
                existing = audio.existing,
            )
        }
        val newlyRecoveredSessionCount = sessions.count { session ->
            session.audioFileName in recoveredAudioFileNames &&
                session.sessionId !in existingSessions
        }
        val effectiveSessionCount = sessions.size
        val effectiveIncompleteSessionCount = maxOf(
            end.incompleteSessionCount,
            sessions.count { it.incompleteReason != null },
        ).coerceAtMost(effectiveSessionCount)
        val events = mapEvents(
            nightId = end.nightId,
            source = eventRead.events,
            existing = existing?.events.orEmpty(),
        ).toMutableList()
        if (!eventRead.sourceAvailable) {
            events += importWarningEvent(
                nightId = end.nightId,
                epochMillis = end.endedAtEpochMillis,
                offsetSeconds = explicitOrExistingOffset(
                    explicit = end.endedAtUtcOffsetSeconds,
                    epochMillis = end.endedAtEpochMillis,
                    existingEpochMillis = existing?.night?.endedAtEpochMillis,
                    existingOffsetSeconds = existing?.night?.endedUtcOffsetSeconds,
                ),
                code = "journal_event_source_unavailable",
            )
        }
        if (eventRead.malformedLineCount > 0) {
            events += importWarningEvent(
                nightId = end.nightId,
                epochMillis = end.endedAtEpochMillis,
                offsetSeconds = explicitOrExistingOffset(
                    explicit = end.endedAtUtcOffsetSeconds,
                    epochMillis = end.endedAtEpochMillis,
                    existingEpochMillis = existing?.night?.endedAtEpochMillis,
                    existingOffsetSeconds = existing?.night?.endedUtcOffsetSeconds,
                ),
                code = "journal_event_parse_failure",
            )
        }

        val startedOffset = explicitOrExistingOffset(
            explicit = end.startedAtUtcOffsetSeconds,
            epochMillis = end.startedAtEpochMillis,
            existingEpochMillis = existing?.night?.startedAtEpochMillis,
            existingOffsetSeconds = existing?.night?.startedUtcOffsetSeconds,
        )
        val endedOffset = explicitOrExistingOffset(
            explicit = end.endedAtUtcOffsetSeconds,
            epochMillis = end.endedAtEpochMillis,
            existingEpochMillis = existing?.night?.endedAtEpochMillis,
            existingOffsetSeconds = existing?.night?.endedUtcOffsetSeconds,
        )
        val heartbeatOffset = end.lastHeartbeatEpochMillis?.let { heartbeat ->
            explicitOrExistingOffset(
                explicit = end.lastHeartbeatUtcOffsetSeconds,
                epochMillis = heartbeat,
                existingEpochMillis = existing?.night?.lastHeartbeatEpochMillis,
                existingOffsetSeconds = existing?.night?.lastHeartbeatUtcOffsetSeconds,
            )
        }
        val warnings = buildList {
            if (
                end.startedAtUtcOffsetSeconds == null ||
                end.endedAtUtcOffsetSeconds == null ||
                (
                    end.lastHeartbeatEpochMillis != null &&
                        end.lastHeartbeatUtcOffsetSeconds == null
                    )
            ) {
                add("Legacy timestamps use the device time-zone rules captured during import.")
            }
            if (eventRead.malformedLineCount > 0) {
                add("Some operational event evidence is unreadable; the source journal was kept.")
            }
            if (!eventRead.sourceAvailable) {
                add(
                    "Operational event evidence is missing or unreadable; " +
                        "the source journal was kept.",
                )
            }
            if (sessions.any { it.audioState == AudioEvidenceState.MISSING }) {
                add("One or more referenced audio files are missing.")
            }
            if (sessions.any { it.audioState == AudioEvidenceState.CORRUPT }) {
                add("One or more referenced audio files or metadata records are corrupt.")
            }
            if (journalAudioFileNames.size != end.sessionCount) {
                add("The journal-reported session count does not match its audio references.")
            }
            if (recoveredAudioFileNames.isNotEmpty()) {
                add(
                    "Recovered ${recoveredAudioFileNames.size} finalized session(s) whose " +
                        "journal attachment was not durably recorded.",
                )
            }
        }
        val rawAudioState = aggregateRawAudioState(
            reportedSessionCount = effectiveSessionCount,
            sessions = sessions,
        )
        val priorGeneratedGraph = existing != null &&
            (
                existing.dreams.isNotEmpty() ||
                    existing.night.enrichmentState == ProcessingState.COMPLETE
                )
        val protectedGeneratedGraph = existing?.hasProtectedDreamChanges() == true
        val recoveredGraphFailure = when {
            newlyRecoveredSessionCount == 0 || !priorGeneratedGraph -> null
            protectedGeneratedGraph -> RECOVERED_SESSION_PROTECTED_GRAPH_FAILURE
            else -> RECOVERED_SESSION_STALE_GRAPH_FAILURE
        }
        val night = NightEntity(
            nightId = end.nightId,
            displayDate = end.displayDate,
            startedAtEpochMillis = end.startedAtEpochMillis,
            startedUtcOffsetSeconds = startedOffset,
            endedAtEpochMillis = end.endedAtEpochMillis,
            endedUtcOffsetSeconds = endedOffset,
            captureState = if (end.interrupted) {
                NightCaptureState.INTERRUPTED
            } else {
                NightCaptureState.ENDED
            },
            endReason = end.reason,
            interrupted = end.interrupted,
            lastHeartbeatEpochMillis = end.lastHeartbeatEpochMillis,
            lastHeartbeatUtcOffsetSeconds = heartbeatOffset,
            reportedSessionCount = effectiveSessionCount,
            reportedIncompleteSessionCount = effectiveIncompleteSessionCount,
            hadMicrophoneSilencing = events.any(::isSilencingEvent),
            hadAudioGap = events.any { it.type == "audio_gap" },
            rawAudioState = rawAudioState,
            transcriptionState = if (newlyRecoveredSessionCount > 0) {
                ProcessingState.NOT_STARTED
            } else {
                existing?.night?.transcriptionState ?: ProcessingState.NOT_STARTED
            },
            transcriptionFailure = if (newlyRecoveredSessionCount > 0) {
                null
            } else {
                existing?.night?.transcriptionFailure
            },
            enrichmentState = if (recoveredGraphFailure != null) {
                ProcessingState.FAILED
            } else {
                existing?.night?.enrichmentState
                    ?: ProcessingState.WAITING_FOR_TRANSCRIPTION
            },
            enrichmentFailure = recoveredGraphFailure
                ?: existing?.night?.enrichmentFailure,
            importWarning = warnings.joinToString(" ").ifBlank { null },
        )
        dao.upsertCaptureGraph(night, sessions, events)
        return ImportResult(
            warningCount = warnings.size,
            canAcknowledge =
                eventRead.sourceAvailable && eventRead.malformedLineCount == 0,
        )
    }

    private fun importActiveNight(
        active: ActiveNightJournal,
        isRuntimeActive: Boolean,
    ): ImportResult {
        val existing = dao.readNight(active.nightId)
        val eventRead = journalStore.readEventsForImport(
            nightId = active.nightId,
            expectedFileName = active.eventFileName,
        )
        val writer = SessionAudioWriter(File(audioRootDirectory, active.nightId))
        val existingSessions = existing?.sessions.orEmpty().associateBy { it.sessionId }
        val finalized = active.audioFileNames.mapIndexed { index, audioFileName ->
            sessionFromFinalizedAudio(
                nightId = active.nightId,
                captureOrder = index,
                audioFileName = audioFileName,
                metadata = writer.readMetadata(audioFileName),
                existing = existingSessions[sessionIdFromAudioFile(audioFileName)],
            )
        }
        val activeSession = active.activeSession
            ?.takeUnless { checkpoint ->
                finalized.any { it.sessionId == checkpoint.sessionId }
            }
            ?.let { checkpoint ->
                sessionFromCheckpoint(
                    nightId = active.nightId,
                    captureOrder = finalized.size,
                    checkpoint = checkpoint,
                    existing = existingSessions[checkpoint.sessionId],
                )
            }
        val pendingSessionCount = if (activeSession == null) 0 else 1
        val sessions = finalized + listOfNotNull(activeSession)
        val events = mapEvents(
            nightId = active.nightId,
            source = eventRead.events,
            existing = existing?.events.orEmpty(),
        ).toMutableList()
        if (!eventRead.sourceAvailable) {
            val warningTime = active.lastHeartbeatEpochMillis ?: active.startedAtEpochMillis
            events += importWarningEvent(
                nightId = active.nightId,
                epochMillis = warningTime,
                offsetSeconds = offset(warningTime),
                code = "journal_event_source_unavailable",
            )
        }
        if (eventRead.malformedLineCount > 0) {
            val warningTime = active.lastHeartbeatEpochMillis ?: active.startedAtEpochMillis
            events += importWarningEvent(
                nightId = active.nightId,
                epochMillis = warningTime,
                offsetSeconds = offset(warningTime),
                code = "journal_event_parse_failure",
            )
        }
        val warnings = buildList {
            if (active.startedAtUtcOffsetSeconds == null) {
                add("The start UTC offset was inferred from device time-zone rules.")
            }
            if (eventRead.malformedLineCount > 0) {
                add("Some operational event evidence is unreadable; recovery remains available.")
            }
            if (!eventRead.sourceAvailable) {
                add(
                    "Operational event evidence is missing or unreadable; " +
                        "recovery remains available.",
                )
            }
            if (!isRuntimeActive) {
                add("The prior listening process ended before the night was finalized.")
            }
        }
        val startedOffset = explicitOrExistingOffset(
            explicit = active.startedAtUtcOffsetSeconds,
            epochMillis = active.startedAtEpochMillis,
            existingEpochMillis = existing?.night?.startedAtEpochMillis,
            existingOffsetSeconds = existing?.night?.startedUtcOffsetSeconds,
        )
        val heartbeatOffset = active.lastHeartbeatEpochMillis?.let { heartbeat ->
            explicitOrExistingOffset(
                explicit = active.lastHeartbeatUtcOffsetSeconds,
                epochMillis = heartbeat,
                existingEpochMillis = existing?.night?.lastHeartbeatEpochMillis,
                existingOffsetSeconds = existing?.night?.lastHeartbeatUtcOffsetSeconds,
            )
        }
        val night = NightEntity(
            nightId = active.nightId,
            displayDate = active.displayDate,
            startedAtEpochMillis = active.startedAtEpochMillis,
            startedUtcOffsetSeconds = startedOffset,
            endedAtEpochMillis = null,
            endedUtcOffsetSeconds = null,
            captureState = if (isRuntimeActive) {
                NightCaptureState.ACTIVE
            } else {
                NightCaptureState.RECOVERY_REQUIRED
            },
            endReason = if (isRuntimeActive) null else "recovery_required",
            interrupted = !isRuntimeActive,
            lastHeartbeatEpochMillis = active.lastHeartbeatEpochMillis,
            lastHeartbeatUtcOffsetSeconds = heartbeatOffset,
            reportedSessionCount = active.sessionCount + pendingSessionCount,
            reportedIncompleteSessionCount = active.incompleteSessionCount +
                if (isRuntimeActive) 0 else pendingSessionCount,
            hadMicrophoneSilencing = events.any(::isSilencingEvent),
            hadAudioGap = events.any { it.type == "audio_gap" },
            rawAudioState = aggregateRawAudioState(
                reportedSessionCount = active.sessionCount + pendingSessionCount,
                sessions = sessions,
            ),
            transcriptionState =
                existing?.night?.transcriptionState ?: ProcessingState.NOT_STARTED,
            transcriptionFailure = existing?.night?.transcriptionFailure,
            enrichmentState = existing?.night?.enrichmentState
                ?: ProcessingState.WAITING_FOR_TRANSCRIPTION,
            enrichmentFailure = existing?.night?.enrichmentFailure,
            importWarning = warnings.joinToString(" ").ifBlank { null },
        )
        dao.upsertCaptureGraph(night, sessions, events)
        return ImportResult(
            warningCount = warnings.size,
            canAcknowledge = false,
        )
    }

    private fun reconcileUnfinishedRoomNights(
        runtimeActiveNightId: String?,
        activeJournalNightId: String?,
    ) {
        dao.readUnfinishedNights().forEach { unfinished ->
            if (
                unfinished.nightId == runtimeActiveNightId ||
                unfinished.nightId == activeJournalNightId
            ) {
                return@forEach
            }
            val endTime = unfinished.lastHeartbeatEpochMillis ?: unfinished.startedAtEpochMillis
            dao.upsertCaptureGraph(
                night = unfinished.copy(
                    endedAtEpochMillis = endTime,
                    endedUtcOffsetSeconds = unfinished.lastHeartbeatUtcOffsetSeconds
                        ?: unfinished.startedUtcOffsetSeconds,
                    captureState = NightCaptureState.INTERRUPTED,
                    endReason = if (unfinished.captureState == NightCaptureState.STARTING) {
                        "start_unconfirmed"
                    } else {
                        "service_interrupted"
                    },
                    interrupted = true,
                    importWarning =
                        "Listening stopped without a recoverable capture journal.",
                ),
                sessions = emptyList(),
                events = emptyList(),
            )
        }
    }

    private fun sessionFromFinalizedAudio(
        nightId: String,
        captureOrder: Int,
        audioFileName: String,
        metadata: SessionAudioMetadata?,
        existing: CaptureSessionEntity?,
    ): CaptureSessionEntity {
        val sessionId = sessionIdFromAudioFile(audioFileName)
        val inspectedEvidence = inspectAudioEvidence(nightId, audioFileName, metadata)
        val evidence = existing?.audioState
            ?.takeIf { it == AudioEvidenceState.DELETED || it == AudioEvidenceState.EXPIRED }
            ?: inspectedEvidence
        val startedAt = metadata?.startedAtEpochMillis ?: existing?.startedAtEpochMillis
        val finalizedAt = metadata?.finalizedAtEpochMillis ?: existing?.finalizedAtEpochMillis
        return CaptureSessionEntity(
            sessionId = sessionId,
            nightId = nightId,
            captureOrder = captureOrder,
            startedAtEpochMillis = startedAt,
            startedUtcOffsetSeconds = startedAt?.let {
                explicitOrExistingOffset(
                    explicit = metadata?.startedAtUtcOffsetSeconds,
                    epochMillis = it,
                    existingEpochMillis = existing?.startedAtEpochMillis,
                    existingOffsetSeconds = existing?.startedUtcOffsetSeconds,
                )
            },
            finalizedAtEpochMillis = finalizedAt,
            finalizedUtcOffsetSeconds = finalizedAt?.let {
                explicitOrExistingOffset(
                    explicit = metadata?.finalizedAtUtcOffsetSeconds,
                    epochMillis = it,
                    existingEpochMillis = existing?.finalizedAtEpochMillis,
                    existingOffsetSeconds = existing?.finalizedUtcOffsetSeconds,
                )
            },
            // A finalized metadata record is authoritative even when its nullable reason is
            // deliberately absent. Using Elvis here preserved the temporary recovery_required
            // sentinel imported from an active checkpoint and made a later clean finalization
            // appear incomplete forever.
            incompleteReason = if (metadata != null) {
                metadata.incompleteReason
            } else {
                existing?.incompleteReason
            },
            audioFileName = audioFileName,
            audioState = evidence,
            sampleRateHz = metadata?.sampleRateHz ?: existing?.sampleRateHz,
            channelCount = metadata?.channelCount ?: existing?.channelCount,
            bitsPerSample = metadata?.bitsPerSample ?: existing?.bitsPerSample,
            sampleCount = metadata?.sampleCount ?: existing?.sampleCount,
            preRollSampleCount = metadata?.preRollSampleCount ?: existing?.preRollSampleCount,
            cueStartSample = if (metadata != null) {
                metadata.cueStartSample
            } else {
                existing?.cueStartSample
            },
            cueEndSampleExclusive = if (metadata != null) {
                metadata.cueEndSampleExclusive
            } else {
                existing?.cueEndSampleExclusive
            },
            automaticSilenceTailSampleCount = if (metadata != null) {
                metadata.automaticSilenceTailSampleCount
            } else {
                existing?.automaticSilenceTailSampleCount
            },
        )
    }

    private fun SessionAudioMetadata.isWithin(end: NightEndRecord): Boolean =
        startedAtEpochMillis in end.startedAtEpochMillis..end.endedAtEpochMillis &&
            finalizedAtEpochMillis in startedAtEpochMillis..end.endedAtEpochMillis

    private fun NightWithDetails.hasProtectedDreamChanges(): Boolean =
        dreams.any { sourceDream ->
            val dream = sourceDream.dream
            dream.ownerEdited ||
                dream.editedAtEpochMillis != null ||
                dream.deletedAtEpochMillis != null ||
                dream.currentTitle != dream.generatedTitle ||
                dream.currentText != dream.generatedText
        }

    private fun sessionFromCheckpoint(
        nightId: String,
        captureOrder: Int,
        checkpoint: SessionAudioCheckpoint,
        existing: CaptureSessionEntity?,
    ): CaptureSessionEntity =
        CaptureSessionEntity(
            sessionId = checkpoint.sessionId,
            nightId = nightId,
            captureOrder = captureOrder,
            startedAtEpochMillis = checkpoint.startedAtEpochMillis,
            startedUtcOffsetSeconds = explicitOrExistingOffset(
                explicit = checkpoint.startedAtUtcOffsetSeconds,
                epochMillis = checkpoint.startedAtEpochMillis,
                existingEpochMillis = existing?.startedAtEpochMillis,
                existingOffsetSeconds = existing?.startedUtcOffsetSeconds,
            ),
            finalizedAtEpochMillis = null,
            finalizedUtcOffsetSeconds = null,
            incompleteReason = "recovery_required",
            audioFileName = checkpoint.audioFileName,
            audioState = AudioEvidenceState.PENDING_RECOVERY,
            sampleRateHz = null,
            channelCount = null,
            bitsPerSample = null,
            sampleCount = checkpoint.sampleCount,
            preRollSampleCount = checkpoint.preRollSampleCount,
            cueStartSample = checkpoint.cueStartSample,
            cueEndSampleExclusive = checkpoint.cueEndSampleExclusive,
            automaticSilenceTailSampleCount = null,
        )

    private fun inspectAudioEvidence(
        nightId: String,
        audioFileName: String,
        metadata: SessionAudioMetadata?,
    ): String {
        val directory = File(audioRootDirectory, nightId)
        val audio = File(directory, audioFileName)
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull()
            ?: return AudioEvidenceState.CORRUPT
        if (
            runCatching { audio.canonicalFile.parentFile == canonicalDirectory }
                .getOrDefault(false)
                .not()
        ) {
            return AudioEvidenceState.CORRUPT
        }
        if (!audio.isFile) return AudioEvidenceState.MISSING
        if (metadata == null) return AudioEvidenceState.CORRUPT
        if (
            metadata.sessionId != sessionIdFromAudioFile(audioFileName) ||
            metadata.audioFileName != audioFileName ||
            metadata.sampleRateHz <= 0 ||
            metadata.channelCount <= 0 ||
            metadata.bitsPerSample <= 0 ||
            metadata.bitsPerSample % 8 != 0 ||
            metadata.sampleCount < 0 ||
            metadata.preRollSampleCount !in 0L..metadata.sampleCount ||
            metadata.automaticSilenceTailSampleCount?.let { tailSamples ->
                tailSamples <= 0L ||
                    tailSamples > metadata.sampleCount ||
                    metadata.incompleteReason != null
            } == true
        ) {
            return AudioEvidenceState.CORRUPT
        }
        if (
            metadata.cueStartSample != null &&
            metadata.cueStartSample !in 0L..metadata.sampleCount
        ) {
            return AudioEvidenceState.CORRUPT
        }
        if (
            metadata.cueEndSampleExclusive != null &&
            (
                metadata.cueStartSample == null ||
                    metadata.cueEndSampleExclusive !in
                    metadata.cueStartSample..metadata.sampleCount
                )
        ) {
            return AudioEvidenceState.CORRUPT
        }
        val expectedDataBytes = runCatching {
            Math.multiplyExact(
                metadata.sampleCount,
                Math.multiplyExact(
                    metadata.channelCount,
                    metadata.bitsPerSample / 8,
                ).toLong(),
            )
        }.getOrNull() ?: return AudioEvidenceState.CORRUPT
        if (audio.length() != WAV_HEADER_BYTES + expectedDataBytes) {
            return AudioEvidenceState.CORRUPT
        }
        val header = ByteArray(12)
        val headerValid = runCatching {
            FileInputStream(audio).use { input ->
                input.read(header) == header.size &&
                    header.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
                    header.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WAVE"
            }
        }.getOrDefault(false)
        return if (headerValid) AudioEvidenceState.RETAINED else AudioEvidenceState.CORRUPT
    }

    private fun mapEvents(
        nightId: String,
        source: List<CaptureJournalEvent>,
        existing: List<NightEventEntity>,
    ): List<NightEventEntity> {
        val combined = existing.associateByTo(linkedMapOf()) { it.eventId }
        source.forEach { event ->
            val prior = combined[event.eventId]
            combined[event.eventId] = NightEventEntity(
                nightId = nightId,
                eventId = event.eventId,
                sessionId = event.attributes["session_id"],
                epochMillis = event.epochMillis,
                utcOffsetSeconds = explicitOrExistingOffset(
                    explicit = event.utcOffsetSeconds,
                    epochMillis = event.epochMillis,
                    existingEpochMillis = prior?.epochMillis,
                    existingOffsetSeconds = prior?.utcOffsetSeconds,
                ),
                type = event.type,
                encodedAttributes = encodeAttributes(event.attributes),
            )
        }
        return combined.values.toList()
    }

    private fun importWarningEvent(
        nightId: String,
        epochMillis: Long,
        offsetSeconds: Int,
        code: String,
    ): NightEventEntity =
        NightEventEntity(
            nightId = nightId,
            eventId = "${code}_$nightId",
            sessionId = null,
            epochMillis = epochMillis,
            utcOffsetSeconds = offsetSeconds,
            type = "journal_import_warning",
            encodedAttributes = encodeAttributes(mapOf("code" to code)),
        )

    private fun aggregateRawAudioState(
        reportedSessionCount: Int,
        sessions: List<CaptureSessionEntity>,
    ): String {
        if (reportedSessionCount == 0 && sessions.isEmpty()) return RawAudioState.NONE
        if (sessions.any { it.audioState == AudioEvidenceState.PENDING_RECOVERY }) {
            return RawAudioState.PENDING_RECOVERY
        }
        val retainedCount = sessions.count { it.audioState == AudioEvidenceState.RETAINED }
        return when {
            retainedCount > 0 &&
                retainedCount == sessions.size &&
                sessions.size == reportedSessionCount -> RawAudioState.RETAINED

            retainedCount > 0 -> RawAudioState.PARTIAL
            else -> RawAudioState.UNAVAILABLE
        }
    }

    private fun isSilencingEvent(event: NightEventEntity): Boolean {
        val attributes = decodeAttributes(event.encodedAttributes)
        return captureMicrophoneSilencedState(event.type, attributes) == true
    }

    private fun explicitOrExistingOffset(
        explicit: Int?,
        epochMillis: Long,
        existingEpochMillis: Long?,
        existingOffsetSeconds: Int?,
    ): Int =
        when {
            existingEpochMillis == epochMillis && existingOffsetSeconds != null ->
                existingOffsetSeconds

            explicit != null -> explicit
            else -> offset(epochMillis)
        }.also(::requireUtcOffset)

    private fun offset(epochMillis: Long): Int =
        offsetAtEpochMillis(epochMillis).also(::requireUtcOffset)

    private fun toRecord(source: NightWithDetails): NightRecord =
        source.sessions.associate { it.sessionId to it.captureOrder }.let { captureOrders ->
            NightRecord(
                night = source.night,
                sessions = source.sessions.sortedWith(
                    compareBy<CaptureSessionEntity> { it.captureOrder }
                        .thenBy { it.startedAtEpochMillis ?: Long.MAX_VALUE }
                        .thenBy { it.sessionId },
                ),
                events = source.events.sortedWith(
                    compareBy<NightEventEntity> { it.epochMillis }
                        .thenBy { it.eventId },
                ),
                transcripts = source.transcripts
                    .map { sourceTranscript ->
                        SessionTranscriptRecord(
                            transcript = sourceTranscript.transcript,
                            segments = sourceTranscript.segments.sortedBy {
                                it.segmentIndex
                            },
                        )
                    }
                    .sortedWith(
                        compareBy<SessionTranscriptRecord> {
                            captureOrders[it.transcript.sessionId] ?: Int.MAX_VALUE
                        }.thenBy { it.transcript.sessionId },
                    ),
                enrichmentRuns = source.enrichmentRuns.sortedWith(
                    compareBy<EnrichmentRunEntity> { it.attemptNumber }
                        .thenBy { it.runId },
                ),
                dreams = source.dreams
                    .filter { it.dream.deletedAtEpochMillis == null }
                    .map { sourceDream ->
                        DreamRecord(
                            dream = sourceDream.dream,
                            sourceSpans = sourceDream.sourceSpans.sortedWith(
                                compareBy<DreamSourceSpanEntity> { it.spanOrder }
                                    .thenBy { it.sessionId }
                                    .thenBy { it.firstSegmentIndex },
                            ),
                        )
                    }
                    .sortedWith(
                        compareBy<DreamRecord> { it.dream.dreamOrder }
                            .thenBy { it.dream.dreamId },
                    ),
                hasProtectedDreamChanges = source.dreams.any { sourceDream ->
                    val dream = sourceDream.dream
                    dream.ownerEdited ||
                        dream.editedAtEpochMillis != null ||
                        dream.deletedAtEpochMillis != null ||
                        dream.currentTitle != dream.generatedTitle ||
                        dream.currentText != dream.generatedText
                },
            )
        }

    private data class ImportResult(
        val warningCount: Int,
        val canAcknowledge: Boolean,
    )

    private data class FinalizedAudioImport(
        val audioFileName: String,
        val metadata: SessionAudioMetadata?,
        val existing: CaptureSessionEntity?,
        val journalOrder: Int?,
    )

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        val AUDIO_FILE_PATTERN = Regex("a_([0-9a-f]{32})\\.wav")
        const val MAX_UTC_OFFSET_SECONDS = 18 * 60 * 60
        const val RECOVERED_SESSION_STALE_GRAPH_FAILURE =
            "A finalized capture session was recovered after this generated reading was " +
                "created. Run local enrichment again after transcription completes. " +
                "[code=finalized_session_recovered; retryable=true]"
        const val RECOVERED_SESSION_PROTECTED_GRAPH_FAILURE =
            "A finalized capture session was recovered after this reading was created. " +
                "Owner edits or deletions were preserved; review the raw transcript before " +
                "any deliberate reprocessing. " +
                "[code=finalized_session_recovered_protected; retryable=false]"

        fun requireSafeIdentifier(value: String) {
            require(SAFE_IDENTIFIER.matches(value)) { "Unsafe night identifier." }
        }

        fun requireUtcOffset(offsetSeconds: Int) {
            require(offsetSeconds in -MAX_UTC_OFFSET_SECONDS..MAX_UTC_OFFSET_SECONDS) {
                "UTC offset is outside the supported range."
            }
        }

        fun sessionIdFromAudioFile(audioFileName: String): String =
            requireNotNull(AUDIO_FILE_PATTERN.matchEntire(audioFileName))
                .groupValues[1]

        fun encodeAttributes(attributes: Map<String, String>): String =
            attributes.toSortedMap().entries.joinToString(";") { (key, value) ->
                "$key=${
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
                }"
            }

        fun decodeAttributes(encoded: String): Map<String, String> {
            if (encoded.isBlank()) return emptyMap()
            return encoded.split(';').associate { item ->
                val separator = item.indexOf('=')
                require(separator > 0)
                item.substring(0, separator) to String(
                    Base64.getUrlDecoder().decode(item.substring(separator + 1)),
                    StandardCharsets.UTF_8,
                )
            }
        }
    }
}
