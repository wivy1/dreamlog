package com.wivy.dreamlog.enrichment

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

const val ENRICHMENT_SCHEMA_VERSION = 6
const val ENRICHMENT_PROMPT_VERSION = "16"

private val SAFE_SOURCE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
private val SHA_256 = Regex("[0-9a-f]{64}")
private val ENRICHMENT_SOURCE_ALIAS = Regex("s(0|[1-9][0-9]*)")

internal fun enrichmentSourceAlias(ordinal: Int): String {
    require(ordinal >= 0) { "An enrichment source ordinal cannot be negative." }
    return "s$ordinal"
}

internal fun enrichmentSourceOrdinal(alias: String): Int? = ENRICHMENT_SOURCE_ALIAS
    .matchEntire(alias)
    ?.groupValues
    ?.get(1)
    ?.toIntOrNull()

data class SourceSegmentId(
    val sessionId: String,
    val segmentIndex: Int,
) {
    init {
        require(SAFE_SOURCE_IDENTIFIER.matches(sessionId)) { "Invalid source session ID." }
        require(segmentIndex >= 0) { "A source segment index cannot be negative." }
    }

    val encoded: String
        get() = "$sessionId:$segmentIndex"

    companion object {
        fun parse(encoded: String): SourceSegmentId {
            val separator = encoded.lastIndexOf(':')
            require(separator > 0 && separator < encoded.lastIndex) {
                "Invalid source segment ID."
            }
            val sessionId = encoded.substring(0, separator)
            val segmentIndex = encoded.substring(separator + 1).toIntOrNull()
            require(segmentIndex != null) { "Invalid source segment ID." }
            return SourceSegmentId(sessionId, segmentIndex)
        }
    }
}

/** One immutable M04 transcript segment supplied to whole-night enrichment. */
data class NightTranscriptSegment(
    val nightId: String,
    val sessionId: String,
    val sessionOrder: Int,
    val transcriptAttempt: Int,
    val segmentIndex: Int,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val text: String,
    val narrationStartedAtEpochMillis: Long? = null,
    val narrationStartedUtcOffsetSeconds: Int? = null,
) {
    init {
        require(SAFE_SOURCE_IDENTIFIER.matches(nightId)) { "Invalid source night ID." }
        require(SAFE_SOURCE_IDENTIFIER.matches(sessionId)) { "Invalid source session ID." }
        require(sessionOrder >= 0) { "A source session order cannot be negative." }
        require(transcriptAttempt > 0) { "A transcript attempt must be positive." }
        require(segmentIndex >= 0) { "A source segment index cannot be negative." }
        require(sourceStartMillis >= 0L) { "A source range cannot start before its audio." }
        require(sourceEndMillis > sourceStartMillis) { "A source range must be positive." }
        require(text.isNotBlank()) { "A source segment must contain raw transcript text." }
        require(
            (narrationStartedAtEpochMillis == null) ==
                (narrationStartedUtcOffsetSeconds == null),
        ) { "Narration start time and UTC offset must be present together." }
        narrationStartedAtEpochMillis?.let { startedAt ->
            require(startedAt >= 0L) { "A narration cannot start before the Unix epoch." }
        }
        narrationStartedUtcOffsetSeconds?.let { offset ->
            require(offset in MIN_UTC_OFFSET_SECONDS..MAX_UTC_OFFSET_SECONDS) {
                "A narration UTC offset is outside the supported range."
            }
        }
    }

    val id: SourceSegmentId
        get() = SourceSegmentId(sessionId, segmentIndex)
}

/**
 * Canonical whole-night input. Construction validates identity/range invariants and removes any
 * caller ordering ambiguity before a prompt or fingerprint is produced.
 */
@ConsistentCopyVisibility
data class OrderedNightTranscript private constructor(
    val nightId: String,
    val segments: List<NightTranscriptSegment>,
    val fingerprintSha256: String,
) {
    init {
        require(SHA_256.matches(fingerprintSha256)) { "Invalid input fingerprint." }
    }

    val isEmpty: Boolean
        get() = segments.isEmpty()

    private val ordinalById = segments.mapIndexed { index, segment -> segment.id to index }.toMap()
    private val segmentById = segments.associateBy(NightTranscriptSegment::id)

    fun segment(id: SourceSegmentId): NightTranscriptSegment? = segmentById[id]

    fun ordinal(id: SourceSegmentId): Int? = ordinalById[id]

    companion object {
        fun create(
            nightId: String,
            source: List<NightTranscriptSegment>,
        ): OrderedNightTranscript {
            require(SAFE_SOURCE_IDENTIFIER.matches(nightId)) { "Invalid source night ID." }
            require(source.all { it.nightId == nightId }) {
                "Every source segment must belong to the requested night."
            }

            val sessionOrderById = mutableMapOf<String, Int>()
            val sessionIdByOrder = mutableMapOf<Int, String>()
            val attemptBySession = mutableMapOf<String, Int>()
            val narrationStartBySession = mutableMapOf<String, Pair<Long?, Int?>>()
            source.forEach { segment ->
                require(
                    sessionOrderById.putIfAbsent(segment.sessionId, segment.sessionOrder)
                        ?.let { it == segment.sessionOrder } != false,
                ) { "A source session has inconsistent chronological order." }
                require(
                    sessionIdByOrder.putIfAbsent(segment.sessionOrder, segment.sessionId)
                        ?.let { it == segment.sessionId } != false,
                ) { "Two source sessions share one chronological order." }
                require(
                    attemptBySession.putIfAbsent(segment.sessionId, segment.transcriptAttempt)
                        ?.let { it == segment.transcriptAttempt } != false,
                ) { "A source session has inconsistent transcript revisions." }
                val narrationStart =
                    segment.narrationStartedAtEpochMillis to
                        segment.narrationStartedUtcOffsetSeconds
                require(
                    narrationStartBySession.putIfAbsent(segment.sessionId, narrationStart)
                        ?.let { it == narrationStart } != false,
                ) { "A source session has inconsistent narration start metadata." }
            }

            val ordered = source.sortedWith(
                compareBy<NightTranscriptSegment> { it.sessionOrder }
                    .thenBy { it.segmentIndex }
                    .thenBy { it.sourceStartMillis },
            )
            require(ordered.map(NightTranscriptSegment::id).distinct().size == ordered.size) {
                "A source segment ID is duplicated."
            }
            ordered.groupBy(NightTranscriptSegment::sessionId).values.forEach { sessionSegments ->
                sessionSegments.zipWithNext().forEach { (previous, next) ->
                    require(next.segmentIndex > previous.segmentIndex) {
                        "Source segment indices must increase within a session."
                    }
                    require(next.sourceStartMillis >= previous.sourceEndMillis) {
                        "Source ranges must be chronological and non-overlapping."
                    }
                }
            }

            val fingerprint = fingerprint(nightId, ordered)
            return OrderedNightTranscript(nightId, ordered, fingerprint)
        }

        private fun fingerprint(
            nightId: String,
            segments: List<NightTranscriptSegment>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateField("dreamlog-enrichment-input-v2")
            digest.updateField(nightId)
            digest.updateField(segments.size.toString())
            segments.forEach { segment ->
                digest.updateField(segment.sessionId)
                digest.updateField(segment.sessionOrder.toString())
                digest.updateField(segment.transcriptAttempt.toString())
                digest.updateField(segment.narrationStartedAtEpochMillis?.toString().orEmpty())
                digest.updateField(segment.narrationStartedUtcOffsetSeconds?.toString().orEmpty())
                digest.updateField(segment.segmentIndex.toString())
                digest.updateField(segment.sourceStartMillis.toString())
                digest.updateField(segment.sourceEndMillis.toString())
                digest.updateField(segment.text)
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun MessageDigest.updateField(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
        }
    }
}

enum class EnrichedDreamKind(val wireValue: String) {
    DREAM("dream"),
    FRAGMENT("fragment"),
    ;

    companion object {
        fun fromWire(value: String): EnrichedDreamKind = entries.firstOrNull {
            it.wireValue == value
        } ?: throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_DREAM_KIND)
    }
}

enum class DreamSourceRole(val wireValue: String) {
    NARRATIVE("narrative"),
    ADDITION("addition"),
    CORRECTION("correction"),
    ;

    companion object {
        fun fromWire(value: String): DreamSourceRole = entries.firstOrNull {
            it.wireValue == value
        } ?: throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_SOURCE_ROLE)
    }
}

data class EnrichedSourceSpan(
    val role: DreamSourceRole,
    val sessionId: String,
    val startSegmentIndex: Int,
    val endSegmentIndexInclusive: Int,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val segmentIds: List<SourceSegmentId>,
) {
    init {
        require(segmentIds.isNotEmpty()) { "An enriched source span cannot be empty." }
        require(segmentIds.all { it.sessionId == sessionId }) {
            "An enriched source span cannot cross sessions."
        }
        require(startSegmentIndex == segmentIds.first().segmentIndex) {
            "An enriched source span has an invalid start segment."
        }
        require(endSegmentIndexInclusive == segmentIds.last().segmentIndex) {
            "An enriched source span has an invalid end segment."
        }
        require(sourceStartMillis >= 0L && sourceEndMillis > sourceStartMillis) {
            "An enriched source span has an invalid source range."
        }
    }
}

data class EnrichedDreamDraft(
    val order: Int,
    val kind: EnrichedDreamKind,
    val generatedTitle: String?,
    val generatedText: String,
    val uncertain: Boolean,
    val sourceSpans: List<EnrichedSourceSpan>,
) {
    init {
        require(order >= 0) { "A dream order cannot be negative." }
        require(generatedTitle == null || generatedTitle.isNotBlank()) {
            "A generated title cannot be blank."
        }
        require(generatedText.isNotBlank()) { "Generated dream text cannot be blank." }
        require(sourceSpans.isNotEmpty()) { "Every generated dream needs source spans." }
        require(sourceSpans.map(EnrichedSourceSpan::sessionId).distinct().size == 1) {
            "A generated dream cannot cross wakeword capture sessions."
        }
    }
}

data class ValidatedEnrichment(
    val schemaVersion: Int,
    val attempt: Int,
    val inputFingerprintSha256: String,
    val dreams: List<EnrichedDreamDraft>,
) {
    init {
        require(schemaVersion == ENRICHMENT_SCHEMA_VERSION) {
            "Unsupported enrichment schema version."
        }
        require(attempt > 0) { "An enrichment attempt must be positive." }
        require(SHA_256.matches(inputFingerprintSha256)) { "Invalid input fingerprint." }
        require(dreams.map(EnrichedDreamDraft::order) == dreams.indices.toList()) {
            "Generated dreams must have contiguous chronological order."
        }
    }
}

internal enum class EnrichmentOutputReason(val safeDetail: String) {
    OUTPUT_TOO_LARGE("The local model returned more output than the schema permits."),
    MALFORMED_JSON("The local model returned malformed JSON."),
    WRONG_FIELDS("The local model returned missing or unknown fields."),
    INVALID_FIELD_TYPE("The local model returned a field with the wrong type."),
    EXPECTED_OBJECT("The local model returned a non-object root or part."),
    INVALID_DREAM_LABEL("The local model returned an invalid dream label."),
    INVALID_DREAM_KIND("The local model returned an invalid dream kind."),
    INVALID_SOURCE_ROLE("The local model returned an invalid source role."),
    INVALID_ALIAS("The local model returned an invalid source alias."),
    UNKNOWN_ALIAS("The local model cited an unknown source alias."),
    RANGE_ORDER("A returned dream part ends before it starts."),
    NONCONTIGUOUS_PARTS("Returned dream parts are not chronological and contiguous."),
    INCOMPLETE_COVERAGE("Returned dream parts do not cover every source alias."),
    MISSING_SEMANTIC_BOUNDARY("An explicit dream reference did not begin a separate part."),
    NEW_LABEL_WITHOUT_CUE("A new dream label did not begin at an explicit transition."),
    NEW_DREAM_REUSED_LABEL("An explicit new dream reused an earlier label."),
    UNKNOWN_RETURN_LABEL("A dream return referenced a label that was not introduced earlier."),
    SCHEMA_MISMATCH("The local model output did not match the enrichment schema."),
}

class EnrichmentOutputException internal constructor(
    internal val reason: EnrichmentOutputReason,
) : IllegalArgumentException(reason.safeDetail)

internal fun supportedWords(text: String): List<String> = WORD.findAll(
    text.lowercase(Locale.US).replace('\u2019', '\''),
).map { it.value }.toList()

internal fun sourceExpressesUncertainty(text: String): Boolean = UNCERTAINTY.containsMatchIn(
    text.lowercase(Locale.US).replace('\u2019', '\''),
)

internal fun sourceExpressesIncompleteRecall(text: String): Boolean {
    val normalized = supportedWords(text).joinToString(separator = " ")
    return "cannot remember the rest" in normalized || "can't remember the rest" in normalized
}

internal fun sourceExpressesCorrection(text: String): Boolean {
    val normalized = supportedWords(text).joinToString(separator = " ")
    return normalized == "correction" ||
        normalized.startsWith("correction ") ||
        normalized.startsWith("actually ") ||
        normalized.startsWith("i mean ") ||
        normalized.startsWith("no i mean ") ||
        normalized.startsWith("no the ")
}

internal fun sourceExpressesAddition(text: String): Boolean {
    val normalized = supportedWords(text).joinToString(separator = " ")
    return "remember more about the same" in normalized ||
        normalized.startsWith("i remember more ") ||
        normalized.startsWith("another detail ")
}

private val WORD = Regex("[a-z0-9]+(?:'[a-z0-9]+)?")
private val UNCERTAINTY = Regex(
    "\\b(?:maybe|might|perhaps|possibly|probably|unsure|uncertain|i think|not sure|" +
        "can't remember|cannot remember)\\b",
)

private const val MIN_UTC_OFFSET_SECONDS = -18 * 60 * 60
private const val MAX_UTC_OFFSET_SECONDS = 18 * 60 * 60
