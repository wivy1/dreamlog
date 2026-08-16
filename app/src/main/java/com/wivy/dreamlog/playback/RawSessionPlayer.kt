package com.wivy.dreamlog.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.wivy.dreamlog.history.RawAudioUseLease
import com.wivy.dreamlog.history.RawAudioUseRegistry
import java.io.File

enum class RawSessionPlaybackPhase {
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

data class RawSessionPlaybackTarget(
    val nightId: String,
    val audioFileName: String,
    val startPositionMillis: Long = 0L,
)

data class RawSessionPlaybackState(
    val phase: RawSessionPlaybackPhase = RawSessionPlaybackPhase.IDLE,
    val target: RawSessionPlaybackTarget? = null,
    val message: String? = null,
) {
    val isPlaying: Boolean
        get() = phase == RawSessionPlaybackPhase.PLAYING

    val isPreparing: Boolean
        get() = phase == RawSessionPlaybackPhase.PREPARING
}

/**
 * Plays one app-private raw narrative session at a time.
 *
 * Construct and call this class on the main thread. [onStateChanged] can assign directly to
 * Compose state. The owner must call [release] when its UI leaves composition.
 */
class RawSessionPlayer(
    context: Context,
    private val onStateChanged: (RawSessionPlaybackState) -> Unit = {},
) {
    private val filesDirectory = context.applicationContext.filesDir
    private var player: MediaPlayer? = null
    private var target: RawSessionPlaybackTarget? = null
    private var rawAudioUseLease: RawAudioUseLease? = null
    private var released = false

    var state: RawSessionPlaybackState = RawSessionPlaybackState()
        private set

    /**
     * Starts, pauses, or resumes a retained session.
     *
     * [captureActive] is an explicit safety gate: saved audio is never started while night
     * listening is active. Passing a missing, corrupt, or pending evidence state publishes a
     * disabled state without constructing a player.
     */
    fun playOrPause(
        nightId: String,
        audioFileName: String,
        audioEvidenceState: String,
        captureActive: Boolean,
        startPositionMillis: Long = 0L,
    ) {
        val requestedTarget = RawSessionPlaybackTarget(
            nightId = nightId,
            audioFileName = audioFileName,
            startPositionMillis = startPositionMillis,
        )
        if (released) {
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.RELEASED,
                    target = requestedTarget,
                    message = "Playback is no longer available on this screen.",
                ),
            )
            return
        }
        if (captureActive) {
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.BLOCKED_BY_CAPTURE,
                    target = requestedTarget,
                    message = "Stop night listening before playing saved audio.",
                ),
            )
            return
        }

        unavailableMessage(audioEvidenceState)?.let { message ->
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.UNAVAILABLE,
                    target = requestedTarget,
                    message = message,
                ),
            )
            return
        }
        if (startPositionMillis < 0L || startPositionMillis > Int.MAX_VALUE.toLong()) {
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.ERROR,
                    target = requestedTarget,
                    message = "This transcript timestamp cannot be played.",
                ),
            )
            return
        }

        if (requestedTarget == target) {
            when (state.phase) {
                RawSessionPlaybackPhase.PLAYING -> {
                    pauseCurrent()
                    return
                }

                RawSessionPlaybackPhase.PAUSED -> {
                    resumeCurrent()
                    return
                }

                RawSessionPlaybackPhase.PREPARING -> {
                    stop()
                    return
                }

                else -> Unit
            }
        }

        val useLease = RawAudioUseRegistry.processWide.tryAcquireUse(nightId)
        if (useLease == null) {
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.UNAVAILABLE,
                    target = requestedTarget,
                    message = "Raw audio is being updated. Try playback again in a moment.",
                ),
            )
            return
        }
        val file = try {
            resolveCanonicalRawSessionFile(
                filesDirectory = filesDirectory,
                nightId = nightId,
                audioFileName = audioFileName,
            )
        } catch (_: Exception) {
            useLease.close()
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.ERROR,
                    target = requestedTarget,
                    message = "This raw audio reference is invalid.",
                ),
            )
            return
        }
        if (!file.isFile || !file.canRead()) {
            useLease.close()
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.UNAVAILABLE,
                    target = requestedTarget,
                    message = "This raw audio file is no longer available.",
                ),
            )
            return
        }

        prepare(requestedTarget, file, useLease)
    }

    fun stop() {
        if (released) return
        releaseCurrent()
        publish(RawSessionPlaybackState())
    }

    fun release() {
        if (released) return
        releaseCurrent()
        released = true
        publish(
            RawSessionPlaybackState(
                phase = RawSessionPlaybackPhase.RELEASED,
            ),
        )
    }

    private fun prepare(
        requestedTarget: RawSessionPlaybackTarget,
        file: File,
        useLease: RawAudioUseLease,
    ) {
        releaseCurrent()
        rawAudioUseLease = useLease
        val newPlayer = try {
            MediaPlayer()
        } catch (_: Exception) {
            releaseCurrent()
            publishPlaybackError(requestedTarget)
            return
        }
        try {
            newPlayer.apply {
                setAudioAttributes(PLAYBACK_AUDIO_ATTRIBUTES)
                setDataSource(file.absolutePath)
            }
        } catch (_: Exception) {
            safeRelease(newPlayer)
            releaseCurrent()
            publishPlaybackError(requestedTarget)
            return
        }

        player = newPlayer
        target = requestedTarget
        newPlayer.setOnPreparedListener { preparedPlayer ->
            if (preparedPlayer !== player || target != requestedTarget) return@setOnPreparedListener
            if (requestedTarget.startPositionMillis == 0L) {
                startPrepared(preparedPlayer, requestedTarget)
            } else {
                preparedPlayer.setOnSeekCompleteListener { soughtPlayer ->
                    if (soughtPlayer !== player || target != requestedTarget) {
                        return@setOnSeekCompleteListener
                    }
                    soughtPlayer.setOnSeekCompleteListener(null)
                    startPrepared(soughtPlayer, requestedTarget)
                }
                try {
                    preparedPlayer.seekTo(
                        requestedTarget.startPositionMillis,
                        MediaPlayer.SEEK_CLOSEST,
                    )
                } catch (_: IllegalStateException) {
                    failCurrent(requestedTarget)
                }
            }
        }
        newPlayer.setOnCompletionListener { completedPlayer ->
            if (completedPlayer !== player || target != requestedTarget) {
                safeRelease(completedPlayer)
                return@setOnCompletionListener
            }
            releaseCurrent()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.COMPLETED,
                    target = requestedTarget,
                ),
            )
        }
        newPlayer.setOnErrorListener { failedPlayer, _, _ ->
            if (failedPlayer === player && target == requestedTarget) {
                failCurrent(requestedTarget)
            } else {
                safeRelease(failedPlayer)
            }
            true
        }
        publish(
            RawSessionPlaybackState(
                phase = RawSessionPlaybackPhase.PREPARING,
                target = requestedTarget,
            ),
        )
        try {
            newPlayer.prepareAsync()
        } catch (_: IllegalStateException) {
            failCurrent(requestedTarget)
        }
    }

    private fun pauseCurrent() {
        val currentPlayer = player ?: return
        val currentTarget = target ?: return
        try {
            currentPlayer.pause()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.PAUSED,
                    target = currentTarget,
                ),
            )
        } catch (_: IllegalStateException) {
            failCurrent(currentTarget)
        }
    }

    private fun startPrepared(
        preparedPlayer: MediaPlayer,
        requestedTarget: RawSessionPlaybackTarget,
    ) {
        try {
            preparedPlayer.start()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.PLAYING,
                    target = requestedTarget,
                ),
            )
        } catch (_: IllegalStateException) {
            failCurrent(requestedTarget)
        }
    }

    private fun resumeCurrent() {
        val currentPlayer = player ?: return
        val currentTarget = target ?: return
        try {
            currentPlayer.start()
            publish(
                RawSessionPlaybackState(
                    phase = RawSessionPlaybackPhase.PLAYING,
                    target = currentTarget,
                ),
            )
        } catch (_: IllegalStateException) {
            failCurrent(currentTarget)
        }
    }

    private fun failCurrent(failedTarget: RawSessionPlaybackTarget) {
        releaseCurrent()
        publishPlaybackError(failedTarget)
    }

    private fun publishPlaybackError(failedTarget: RawSessionPlaybackTarget) {
        publish(
            RawSessionPlaybackState(
                phase = RawSessionPlaybackPhase.ERROR,
                target = failedTarget,
                message = "This raw audio file could not be played.",
            ),
        )
    }

    private fun releaseCurrent() {
        val current = player
        player = null
        target = null
        rawAudioUseLease?.close()
        rawAudioUseLease = null
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

    private fun publish(newState: RawSessionPlaybackState) {
        state = newState
        onStateChanged(newState)
    }

    private companion object {
        val PLAYBACK_AUDIO_ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        fun unavailableMessage(audioEvidenceState: String): String? =
            when (audioEvidenceState) {
                "retained" -> null
                "missing" -> "This raw audio file is missing."
                "corrupt" -> "This raw audio file is corrupt and cannot be played."
                "pending_recovery" -> "This session audio is not ready for playback."
                "deleted" -> "Raw audio was deleted for this night."
                "expired" -> "Audio expired."
                else -> "This session has no playable raw audio."
            }

        fun safeRelease(mediaPlayer: MediaPlayer) {
            runCatching { mediaPlayer.release() }
        }
    }
}

internal fun resolveCanonicalRawSessionFile(
    filesDirectory: File,
    nightId: String,
    audioFileName: String,
): File {
    require(SAFE_NIGHT_ID.matches(nightId)) {
        "Night ID contains unsafe path characters."
    }
    require(OPAQUE_AUDIO_FILE.matches(audioFileName)) {
        "Audio filename is not a writer-owned opaque WAV."
    }

    val canonicalFilesDirectory = filesDirectory.canonicalFile
    val audioRoot = File(canonicalFilesDirectory, RAW_AUDIO_DIRECTORY).canonicalFile
    require(audioRoot.toPath().startsWith(canonicalFilesDirectory.toPath())) {
        "Raw audio root escaped app-private storage."
    }

    val nightDirectory = File(audioRoot, nightId).canonicalFile
    require(nightDirectory.parentFile == audioRoot) {
        "Night audio directory escaped the raw audio root."
    }

    val audioFile = File(nightDirectory, audioFileName).canonicalFile
    require(audioFile.parentFile == nightDirectory) {
        "Raw audio file escaped its night directory."
    }
    return audioFile
}

private const val RAW_AUDIO_DIRECTORY = "capture/audio"
private val SAFE_NIGHT_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
private val OPAQUE_AUDIO_FILE = Regex("a_[0-9a-f]{32}\\.wav")
