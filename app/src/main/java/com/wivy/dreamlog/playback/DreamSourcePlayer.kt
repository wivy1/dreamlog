package com.wivy.dreamlog.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.RawAudioUseLease
import com.wivy.dreamlog.history.RawAudioUseRegistry
import java.io.File

enum class DreamSourcePlaybackPhase {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    BLOCKED_BY_CAPTURE,
    UNAVAILABLE,
    ERROR,
    RELEASED,
}

data class DreamSourcePlaybackClip(
    val nightId: String,
    val audioFileName: String,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
)

sealed interface DreamSourcePlaybackPlan {
    data class Ready(
        val clips: List<DreamSourcePlaybackClip>,
    ) : DreamSourcePlaybackPlan

    data class Unavailable(
        val message: String,
    ) : DreamSourcePlaybackPlan
}

data class DreamSourcePlaybackState(
    val phase: DreamSourcePlaybackPhase = DreamSourcePlaybackPhase.IDLE,
    val dreamId: String? = null,
    val currentSpanIndex: Int? = null,
    val spanCount: Int = 0,
    val message: String? = null,
) {
    val isPlaying: Boolean
        get() = phase == DreamSourcePlaybackPhase.PLAYING
}

/** Builds an all-or-nothing logical playback plan in persisted span order. */
internal fun buildDreamSourcePlaybackPlan(
    nightId: String,
    sourceSpans: List<DreamSourceSpanEntity>,
    sessions: List<CaptureSessionEntity>,
): DreamSourcePlaybackPlan {
    if (sourceSpans.isEmpty()) {
        return DreamSourcePlaybackPlan.Unavailable("Source audio unavailable.")
    }
    val ordered = sourceSpans.sortedBy(DreamSourceSpanEntity::spanOrder)
    if (ordered.map(DreamSourceSpanEntity::spanOrder) != ordered.indices.toList()) {
        return DreamSourcePlaybackPlan.Unavailable("The source mapping is incomplete.")
    }
    val sessionsById = sessions.associateBy(CaptureSessionEntity::sessionId)
    if (sessionsById.size != sessions.size) {
        return DreamSourcePlaybackPlan.Unavailable("The source mapping is ambiguous.")
    }

    val clips = ArrayList<DreamSourcePlaybackClip>(ordered.size)
    ordered.forEach { span ->
        if (
            span.sourceStartMillis < 0L ||
            span.sourceEndMillis <= span.sourceStartMillis ||
            span.sourceEndMillis > Int.MAX_VALUE.toLong()
        ) {
            return DreamSourcePlaybackPlan.Unavailable("The source time range is invalid.")
        }
        val session = sessionsById[span.sessionId]
            ?: return DreamSourcePlaybackPlan.Unavailable("Source audio unavailable.")
        if (session.nightId != nightId) {
            return DreamSourcePlaybackPlan.Unavailable("The source mapping belongs to another night.")
        }
        sourceAudioUnavailableMessage(session.audioState)?.let { message ->
            return DreamSourcePlaybackPlan.Unavailable(message)
        }
        clips += DreamSourcePlaybackClip(
            nightId = nightId,
            audioFileName = session.audioFileName,
            sourceStartMillis = dreamSourcePlaybackStartMillis(
                sourceStartMillis = span.sourceStartMillis,
                firstSegmentIndex = span.firstSegmentIndex,
                cueStartMillis = session.cueStartSample?.let { cueStartSample ->
                    session.sampleRateHz
                        ?.takeIf { it > 0 }
                        ?.let { sampleRateHz ->
                            cueStartSample * MILLIS_PER_SECOND / sampleRateHz
                        }
                },
            ),
            sourceEndMillis = span.sourceEndMillis,
        )
    }
    return DreamSourcePlaybackPlan.Ready(clips)
}

/**
 * Adds playback-only acoustic context before the first aligned token.
 *
 * Token timestamps are alignment points rather than guaranteed phoneme onsets, and MediaPlayer
 * seeking can otherwise cut the first syllable. The persisted logical source range is deliberately
 * left unchanged. A Dream beginning at transcript segment zero plays from shortly before the
 * recorded acknowledgement cue so the retained wake phrase and cue remain audible without the
 * entire pre-roll silence. Interior semantic splits keep a short lead to avoid replaying excessive
 * prior dream content.
 */
internal fun dreamSourcePlaybackStartMillis(
    sourceStartMillis: Long,
    firstSegmentIndex: Int,
    cueStartMillis: Long?,
): Long {
    require(sourceStartMillis >= 0L) { "The source start must be non-negative." }
    require(firstSegmentIndex >= 0) { "The first source segment index must be non-negative." }
    if (firstSegmentIndex == 0) {
        return cueStartMillis
            ?.minus(FIRST_SOURCE_PRE_CUE_CONTEXT_MILLIS)
            ?.coerceAtLeast(0L)
            ?: 0L
    }
    return (sourceStartMillis - INTERIOR_SOURCE_PLAYBACK_LEAD_IN_MILLIS).coerceAtLeast(0L)
}

internal fun sourceAudioUnavailableMessage(audioEvidenceState: String): String? =
    when (audioEvidenceState) {
        AudioEvidenceState.RETAINED -> null
        AudioEvidenceState.EXPIRED -> "Audio expired. The source transcript is still available."
        AudioEvidenceState.DELETED ->
            "Raw audio was deleted for this night. The source transcript is still available."

        AudioEvidenceState.PENDING_RECOVERY ->
            "Source audio needs recovery before it can be played."

        AudioEvidenceState.CORRUPT -> "Source audio is corrupt and cannot be played."
        AudioEvidenceState.MISSING -> "Source audio unavailable."
        else -> "Source audio unavailable."
    }

internal fun nextDreamSourceSpanIndex(
    currentSpanIndex: Int,
    spanCount: Int,
): Int? {
    require(spanCount > 0) { "A playback sequence needs at least one span." }
    require(currentSpanIndex in 0 until spanCount) { "The playback span index is invalid." }
    return (currentSpanIndex + 1).takeIf { it < spanCount }
}

internal fun resolvedDreamSourceEndMillis(
    sourceStartMillis: Long,
    sourceEndMillis: Long,
    actualDurationMillis: Long?,
): Long? {
    if (actualDurationMillis == null) return sourceEndMillis
    if (sourceStartMillis >= actualDurationMillis) return null
    if (
        sourceEndMillis > actualDurationMillis &&
        sourceEndMillis - actualDurationMillis > SOURCE_DURATION_TOLERANCE_MILLIS
    ) {
        return null
    }
    return minOf(sourceEndMillis, actualDurationMillis)
}

/**
 * Plays retained source ranges one after another without creating a spliced file.
 *
 * Construct and call this class on the main thread. The owner must call [release] when the dream
 * detail screen leaves composition.
 */
class DreamSourcePlayer(
    context: Context,
    private val onStateChanged: (DreamSourcePlaybackState) -> Unit = {},
) {
    private val filesDirectory = context.applicationContext.filesDir
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var activeDreamId: String? = null
    private var activeClips: List<DreamSourcePlaybackClip> = emptyList()
    private var activeFiles: List<File> = emptyList()
    private var rawAudioUseLease: RawAudioUseLease? = null
    private var currentSpanIndex = 0
    private var boundaryRunnable: Runnable? = null
    private var generation = 0L
    private var released = false

    var state: DreamSourcePlaybackState = DreamSourcePlaybackState()
        private set

    fun playOrPause(
        dreamId: String,
        plan: DreamSourcePlaybackPlan,
        captureActive: Boolean,
    ) {
        require(dreamId.isNotBlank()) { "A dream ID is required for source playback." }
        if (released) {
            publish(
                DreamSourcePlaybackState(
                    phase = DreamSourcePlaybackPhase.RELEASED,
                    dreamId = dreamId,
                    message = "Playback is no longer available on this screen.",
                ),
            )
            return
        }
        if (captureActive) {
            clearPlayback()
            publish(
                DreamSourcePlaybackState(
                    phase = DreamSourcePlaybackPhase.BLOCKED_BY_CAPTURE,
                    dreamId = dreamId,
                    message = "Stop night listening before playing saved audio.",
                ),
            )
            return
        }
        if (plan is DreamSourcePlaybackPlan.Unavailable) {
            clearPlayback()
            publish(
                DreamSourcePlaybackState(
                    phase = DreamSourcePlaybackPhase.UNAVAILABLE,
                    dreamId = dreamId,
                    message = plan.message,
                ),
            )
            return
        }
        plan as DreamSourcePlaybackPlan.Ready
        if (activeDreamId == dreamId && activeClips == plan.clips) {
            when (state.phase) {
                DreamSourcePlaybackPhase.PLAYING -> {
                    pauseCurrent()
                    return
                }

                DreamSourcePlaybackPhase.PAUSED -> {
                    resumeCurrent()
                    return
                }

                DreamSourcePlaybackPhase.PREPARING -> {
                    stop()
                    return
                }

                else -> Unit
            }
        }

        val useLease = RawAudioUseRegistry.processWide.tryAcquireUse(
            plan.clips.map(DreamSourcePlaybackClip::nightId),
        )
        if (useLease == null) {
            clearPlayback()
            publishUnavailable(
                dreamId,
                "Raw audio is being updated. Try playback again in a moment.",
            )
            return
        }
        val resolvedFiles = runCatching {
            plan.clips.map { clip ->
                resolveCanonicalRawSessionFile(
                    filesDirectory = filesDirectory,
                    nightId = clip.nightId,
                    audioFileName = clip.audioFileName,
                )
            }
        }.getOrElse {
            useLease.close()
            clearPlayback()
            publishUnavailable(dreamId, "This source audio reference is invalid.")
            return
        }
        if (resolvedFiles.any { !it.isFile || !it.canRead() }) {
            useLease.close()
            clearPlayback()
            publishUnavailable(dreamId, "Source audio unavailable.")
            return
        }

        clearPlayback()
        rawAudioUseLease = useLease
        activeDreamId = dreamId
        activeClips = plan.clips
        activeFiles = resolvedFiles
        currentSpanIndex = 0
        prepareCurrentSpan()
    }

    fun stop() {
        if (released) return
        clearPlayback()
        publish(DreamSourcePlaybackState())
    }

    fun release() {
        if (released) return
        clearPlayback()
        released = true
        publish(DreamSourcePlaybackState(phase = DreamSourcePlaybackPhase.RELEASED))
    }

    private fun prepareCurrentSpan() {
        val dreamId = activeDreamId ?: return
        val clip = activeClips.getOrNull(currentSpanIndex) ?: return
        val file = activeFiles.getOrNull(currentSpanIndex) ?: return
        releasePlayerOnly()
        val expectedGeneration = generation
        val expectedIndex = currentSpanIndex
        val player = runCatching { MediaPlayer() }.getOrElse {
            failCurrent("This source audio file could not be played.")
            return
        }
        try {
            player.setAudioAttributes(PLAYBACK_AUDIO_ATTRIBUTES)
            player.setDataSource(file.absolutePath)
        } catch (_: Exception) {
            safeRelease(player)
            failCurrent("This source audio file could not be played.")
            return
        }
        mediaPlayer = player
        player.setOnPreparedListener { prepared ->
            if (!isCurrent(prepared, expectedGeneration, expectedIndex)) return@setOnPreparedListener
            val duration = prepared.duration.takeIf { it > 0 }?.toLong()
            if (duration != null && clip.sourceStartMillis >= duration) {
                failCurrent("The source time range is outside the retained audio.")
                return@setOnPreparedListener
            }
            if (clip.sourceStartMillis == 0L) {
                startPrepared(prepared, expectedGeneration, expectedIndex)
            } else {
                prepared.setOnSeekCompleteListener { sought ->
                    if (!isCurrent(sought, expectedGeneration, expectedIndex)) {
                        return@setOnSeekCompleteListener
                    }
                    sought.setOnSeekCompleteListener(null)
                    startPrepared(sought, expectedGeneration, expectedIndex)
                }
                runCatching {
                    prepared.seekTo(clip.sourceStartMillis, MediaPlayer.SEEK_CLOSEST)
                }.onFailure {
                    failCurrent("This source audio range could not be opened.")
                }
            }
        }
        player.setOnCompletionListener { completed ->
            if (isCurrent(completed, expectedGeneration, expectedIndex)) {
                advanceFromCurrentSpan()
            } else {
                safeRelease(completed)
            }
        }
        player.setOnErrorListener { failed, _, _ ->
            if (isCurrent(failed, expectedGeneration, expectedIndex)) {
                failCurrent("This source audio file could not be played.")
            } else {
                safeRelease(failed)
            }
            true
        }
        publish(
            DreamSourcePlaybackState(
                phase = DreamSourcePlaybackPhase.PREPARING,
                dreamId = dreamId,
                currentSpanIndex = currentSpanIndex,
                spanCount = activeClips.size,
            ),
        )
        runCatching { player.prepareAsync() }.onFailure {
            failCurrent("This source audio file could not be played.")
        }
    }

    private fun startPrepared(
        player: MediaPlayer,
        expectedGeneration: Long,
        expectedIndex: Int,
    ) {
        if (!isCurrent(player, expectedGeneration, expectedIndex)) return
        val clip = activeClips[expectedIndex]
        val duration = player.duration.takeIf { it > 0 }?.toLong()
        val effectiveEnd = resolvedDreamSourceEndMillis(
            sourceStartMillis = clip.sourceStartMillis,
            sourceEndMillis = clip.sourceEndMillis,
            actualDurationMillis = duration,
        )
        if (effectiveEnd == null || effectiveEnd <= clip.sourceStartMillis) {
            failCurrent("The source time range is outside the retained audio.")
            return
        }
        try {
            player.start()
            publishCurrent(DreamSourcePlaybackPhase.PLAYING)
            scheduleBoundaryCheck(
                player = player,
                expectedGeneration = expectedGeneration,
                expectedIndex = expectedIndex,
                sourceEndMillis = effectiveEnd,
            )
        } catch (_: IllegalStateException) {
            failCurrent("This source audio file could not be played.")
        }
    }

    private fun pauseCurrent() {
        val player = mediaPlayer ?: return
        cancelBoundaryCheck()
        try {
            player.pause()
            publishCurrent(DreamSourcePlaybackPhase.PAUSED)
        } catch (_: IllegalStateException) {
            failCurrent("This source audio file could not be paused.")
        }
    }

    private fun resumeCurrent() {
        val player = mediaPlayer ?: return
        val clip = activeClips.getOrNull(currentSpanIndex) ?: return
        val duration = player.duration.takeIf { it > 0 }?.toLong()
        val effectiveEnd = resolvedDreamSourceEndMillis(
            sourceStartMillis = clip.sourceStartMillis,
            sourceEndMillis = clip.sourceEndMillis,
            actualDurationMillis = duration,
        )
        if (effectiveEnd == null || effectiveEnd <= clip.sourceStartMillis) {
            failCurrent("The source time range is outside the retained audio.")
            return
        }
        try {
            player.start()
            publishCurrent(DreamSourcePlaybackPhase.PLAYING)
            scheduleBoundaryCheck(
                player = player,
                expectedGeneration = generation,
                expectedIndex = currentSpanIndex,
                sourceEndMillis = effectiveEnd,
            )
        } catch (_: IllegalStateException) {
            failCurrent("This source audio file could not be resumed.")
        }
    }

    private fun scheduleBoundaryCheck(
        player: MediaPlayer,
        expectedGeneration: Long,
        expectedIndex: Int,
        sourceEndMillis: Long,
    ) {
        cancelBoundaryCheck()
        val check = object : Runnable {
            override fun run() {
                if (!isCurrent(player, expectedGeneration, expectedIndex)) return
                val currentPosition = runCatching { player.currentPosition.toLong() }
                    .getOrElse {
                        failCurrent("This source audio range could not be read.")
                        return
                    }
                if (currentPosition + END_TOLERANCE_MILLIS >= sourceEndMillis) {
                    advanceFromCurrentSpan()
                    return
                }
                val remaining = sourceEndMillis - currentPosition
                handler.postDelayed(
                    this,
                    remaining.coerceIn(MIN_BOUNDARY_POLL_MILLIS, MAX_BOUNDARY_POLL_MILLIS),
                )
            }
        }
        boundaryRunnable = check
        handler.post(check)
    }

    private fun advanceFromCurrentSpan() {
        cancelBoundaryCheck()
        val next = nextDreamSourceSpanIndex(currentSpanIndex, activeClips.size)
        releasePlayerOnly()
        if (next == null) {
            val completedDreamId = activeDreamId
            val completedCount = activeClips.size
            activeDreamId = null
            activeClips = emptyList()
            activeFiles = emptyList()
            rawAudioUseLease?.close()
            rawAudioUseLease = null
            publish(
                DreamSourcePlaybackState(
                    phase = DreamSourcePlaybackPhase.COMPLETED,
                    dreamId = completedDreamId,
                    currentSpanIndex = completedCount - 1,
                    spanCount = completedCount,
                ),
            )
            return
        }
        currentSpanIndex = next
        prepareCurrentSpan()
    }

    private fun failCurrent(message: String) {
        val failedDreamId = activeDreamId
        val failedIndex = currentSpanIndex.takeIf { activeClips.isNotEmpty() }
        val failedCount = activeClips.size
        clearPlayback()
        publish(
            DreamSourcePlaybackState(
                phase = DreamSourcePlaybackPhase.ERROR,
                dreamId = failedDreamId,
                currentSpanIndex = failedIndex,
                spanCount = failedCount,
                message = message,
            ),
        )
    }

    private fun publishUnavailable(dreamId: String, message: String) {
        publish(
            DreamSourcePlaybackState(
                phase = DreamSourcePlaybackPhase.UNAVAILABLE,
                dreamId = dreamId,
                message = message,
            ),
        )
    }

    private fun publishCurrent(phase: DreamSourcePlaybackPhase) {
        publish(
            DreamSourcePlaybackState(
                phase = phase,
                dreamId = activeDreamId,
                currentSpanIndex = currentSpanIndex,
                spanCount = activeClips.size,
            ),
        )
    }

    private fun isCurrent(
        player: MediaPlayer,
        expectedGeneration: Long,
        expectedIndex: Int,
    ): Boolean =
        player === mediaPlayer &&
            expectedGeneration == generation &&
            expectedIndex == currentSpanIndex &&
            activeDreamId != null

    private fun clearPlayback() {
        generation += 1L
        cancelBoundaryCheck()
        releasePlayerOnly()
        activeDreamId = null
        activeClips = emptyList()
        activeFiles = emptyList()
        rawAudioUseLease?.close()
        rawAudioUseLease = null
        currentSpanIndex = 0
    }

    private fun releasePlayerOnly() {
        val current = mediaPlayer
        mediaPlayer = null
        if (current != null) {
            runCatching {
                current.setOnPreparedListener(null)
                current.setOnSeekCompleteListener(null)
                current.setOnCompletionListener(null)
                current.setOnErrorListener(null)
            }
            safeRelease(current)
        }
    }

    private fun cancelBoundaryCheck() {
        boundaryRunnable?.let(handler::removeCallbacks)
        boundaryRunnable = null
    }

    private fun publish(newState: DreamSourcePlaybackState) {
        state = newState
        onStateChanged(newState)
    }

    private companion object {
        const val END_TOLERANCE_MILLIS = 12L
        const val MIN_BOUNDARY_POLL_MILLIS = 16L
        const val MAX_BOUNDARY_POLL_MILLIS = 50L

        val PLAYBACK_AUDIO_ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        fun safeRelease(player: MediaPlayer) {
            runCatching { player.release() }
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val FIRST_SOURCE_PRE_CUE_CONTEXT_MILLIS = 2_000L
private const val INTERIOR_SOURCE_PLAYBACK_LEAD_IN_MILLIS = 300L
private const val SOURCE_DURATION_TOLERANCE_MILLIS = 50L
