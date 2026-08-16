package com.wivy.dreamlog.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RawRes
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CuePlaybackMarker(
    val elapsedRealtimeNanos: Long,
    val elapsedSinceRequestMillis: Long,
)

/**
 * Preloads and replays the owner-supplied acknowledgement cue.
 *
 * Playback is fixed to unity track gain on the Assistant audio attributes.
 * [rawResourceId] must identify an uncompressed mono PCM16 WAV resource.
 */
class CuePlayer(
    context: Context,
    @RawRes rawResourceId: Int,
) : Closeable {
    private val lock = Any()
    private val wav = readPcmWav(context, rawResourceId)
    private val minimumBufferBytes = AudioTrack.getMinBufferSize(
        wav.sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    private val bufferSizeBytes = maxOf(
        wav.pcm.size,
        minimumBufferBytes.coerceAtLeast(0),
    )
    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(CueAudioPreflight.audioAttributes())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(wav.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(bufferSizeBytes)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    private var closed = false
    private var hasPlayed = false
    private var requestStartedNanos = 0L
    private var firstFrameNanos = 0L
    private var markerPhase = MarkerPhase.Idle
    private var onFirstFrame: ((CuePlaybackMarker) -> Unit)? = null
    private var onComplete: ((CuePlaybackMarker) -> Unit)? = null

    val durationMillis: Long =
        (wav.frameCount * 1_000L / wav.sampleRate).coerceAtLeast(1L)

    init {
        check(audioTrack.state != AudioTrack.STATE_UNINITIALIZED) {
            "The local cue AudioTrack could not be initialized " +
                "(state=${audioTrack.state}, pcmBytes=${wav.pcm.size}, " +
                "minBufferBytes=$minimumBufferBytes, bufferBytes=$bufferSizeBytes, " +
                "sampleRate=${wav.sampleRate})."
        }
        val written = audioTrack.write(
            wav.pcm,
            0,
            wav.pcm.size,
            AudioTrack.WRITE_BLOCKING,
        )
        check(written == wav.pcm.size) {
            "The local cue could not be preloaded (wrote $written of ${wav.pcm.size} bytes)."
        }
        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            "The local cue AudioTrack did not initialize after static data was preloaded."
        }
        audioTrack.setVolume(UNITY_GAIN)
        audioTrack.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    handleMarker(track)
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun play(
        onFirstFrame: (CuePlaybackMarker) -> Unit,
        onComplete: (CuePlaybackMarker) -> Unit,
    ) {
        synchronized(lock) {
            check(!closed) { "CuePlayer is closed." }
            stopLocked()

            if (hasPlayed) {
                check(audioTrack.reloadStaticData() == AudioTrack.SUCCESS) {
                    "The local cue could not be reloaded."
                }
            }

            this.onFirstFrame = onFirstFrame
            this.onComplete = onComplete
            requestStartedNanos = SystemClock.elapsedRealtimeNanos()
            firstFrameNanos = 0L
            markerPhase = MarkerPhase.AwaitingFirstFrame
            check(audioTrack.setNotificationMarkerPosition(FIRST_FRAME_MARKER) == AudioTrack.SUCCESS) {
                "The local cue first-frame marker could not be installed."
            }
            audioTrack.play()
            hasPlayed = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            stopLocked()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopLocked()
            audioTrack.release()
        }
    }

    private fun stopLocked() {
        markerPhase = MarkerPhase.Idle
        onFirstFrame = null
        onComplete = null
        if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) {
            audioTrack.stop()
        }
    }

    private fun handleMarker(track: AudioTrack) {
        val firstCallback: ((CuePlaybackMarker) -> Unit)?
        val completeCallback: ((CuePlaybackMarker) -> Unit)?
        val marker: CuePlaybackMarker

        synchronized(lock) {
            if (closed) return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            when (markerPhase) {
                MarkerPhase.AwaitingFirstFrame -> {
                    firstFrameNanos = nowNanos
                    marker = CuePlaybackMarker(
                        elapsedRealtimeNanos = nowNanos,
                        elapsedSinceRequestMillis = nanosToMillis(
                            nowNanos - requestStartedNanos,
                        ),
                    )
                    firstCallback = onFirstFrame
                    completeCallback = null
                    markerPhase = MarkerPhase.AwaitingCompletion
                    val finalFrame = wav.frameCount.coerceAtLeast(FIRST_FRAME_MARKER + 1)
                    if (track.setNotificationMarkerPosition(finalFrame) != AudioTrack.SUCCESS) {
                        markerPhase = MarkerPhase.Idle
                        this.onFirstFrame = null
                        this.onComplete = null
                    }
                }

                MarkerPhase.AwaitingCompletion -> {
                    marker = CuePlaybackMarker(
                        elapsedRealtimeNanos = nowNanos,
                        elapsedSinceRequestMillis = nanosToMillis(
                            nowNanos - firstFrameNanos,
                        ),
                    )
                    firstCallback = null
                    completeCallback = onComplete
                    markerPhase = MarkerPhase.Idle
                    onFirstFrame = null
                    onComplete = null
                }

                MarkerPhase.Idle -> return
            }
        }

        firstCallback?.invoke(marker)
        completeCallback?.invoke(marker)
    }

    private enum class MarkerPhase {
        Idle,
        AwaitingFirstFrame,
        AwaitingCompletion,
    }

    private data class PcmWav(
        val sampleRate: Int,
        val pcm: ByteArray,
    ) {
        val frameCount: Int
            get() = pcm.size / BYTES_PER_MONO_PCM16_FRAME
    }

    private companion object {
        const val UNITY_GAIN = 1f
        const val FIRST_FRAME_MARKER = 1
        const val BYTES_PER_MONO_PCM16_FRAME = 2

        fun readPcmWav(context: Context, @RawRes rawResourceId: Int): PcmWav {
            val bytes = context.resources.openRawResource(rawResourceId).use { it.readBytes() }
            require(bytes.size >= 44) { "The local cue is not a complete WAV file." }
            require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
                "The local cue is not a RIFF/WAVE file."
            }

            var offset = 12
            var formatTag: Int? = null
            var channelCount: Int? = null
            var sampleRate: Int? = null
            var bitsPerSample: Int? = null
            var pcm: ByteArray? = null

            while (offset + 8 <= bytes.size) {
                val chunkName = ascii(bytes, offset, 4)
                val chunkSize = littleEndianInt(bytes, offset + 4)
                require(chunkSize >= 0) { "The local cue contains an invalid WAV chunk." }
                val dataOffset = offset + 8
                val dataEnd = dataOffset + chunkSize
                require(dataEnd <= bytes.size) {
                    "The local cue contains a truncated WAV chunk."
                }

                when (chunkName) {
                    "fmt " -> {
                        require(chunkSize >= 16) {
                            "The local cue has an invalid format chunk."
                        }
                        formatTag = littleEndianShort(bytes, dataOffset)
                        channelCount = littleEndianShort(bytes, dataOffset + 2)
                        sampleRate = littleEndianInt(bytes, dataOffset + 4)
                        bitsPerSample = littleEndianShort(bytes, dataOffset + 14)
                    }

                    "data" -> pcm = bytes.copyOfRange(dataOffset, dataEnd)
                }

                offset = dataEnd + (chunkSize and 1)
            }

            require(formatTag == 1) { "The local cue must use uncompressed PCM." }
            require(channelCount == 1) { "The local cue must be mono." }
            require(bitsPerSample == 16) { "The local cue must use 16-bit samples." }
            val resolvedSampleRate = requireNotNull(sampleRate) {
                "The local cue has no sample rate."
            }
            require(resolvedSampleRate > 0) {
                "The local cue has an invalid sample rate."
            }
            val resolvedPcm = requireNotNull(pcm) {
                "The local cue has no audio data chunk."
            }
            require(resolvedPcm.isNotEmpty() && resolvedPcm.size % 2 == 0) {
                "The local cue has invalid PCM data."
            }
            return PcmWav(
                sampleRate = resolvedSampleRate,
                pcm = resolvedPcm,
            )
        }

        fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)

        fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

        fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(bytes, offset, Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xffff

        fun nanosToMillis(nanos: Long): Long =
            (nanos / 1_000_000L).coerceAtLeast(0L)
    }
}
