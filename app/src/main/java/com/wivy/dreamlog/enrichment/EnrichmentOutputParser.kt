package com.wivy.dreamlog.enrichment

import java.util.Locale

object EnrichmentOutputParser {
    fun parse(
        outputJson: String,
        input: OrderedNightTranscript,
        expectedAttempt: Int,
    ): ValidatedEnrichment {
        require(expectedAttempt > 0) { "An enrichment attempt must be positive." }
        if (outputJson.length > MAX_OUTPUT_CHARACTERS) {
            throw EnrichmentOutputException(EnrichmentOutputReason.OUTPUT_TOO_LARGE)
        }
        return try {
            parseValidated(outputJson, input, expectedAttempt)
        } catch (known: EnrichmentOutputException) {
            throw known
        } catch (_: RuntimeException) {
            throw EnrichmentOutputException(EnrichmentOutputReason.SCHEMA_MISMATCH)
        }
    }

    private fun parseValidated(
        outputJson: String,
        input: OrderedNightTranscript,
        expectedAttempt: Int,
    ): ValidatedEnrichment {
        val root = StrictJsonParser(outputJson).parse().asObject()
        root.requireKeys(ROOT_KEYS)
        val units = input.toEnrichmentSourceUnits()
        val rawModelParts = root.requireArray("parts").values.map { value ->
            val part = value.asObject()
            part.requireKeys(PART_KEYS)
            val dreamLabel = canonicalModelDreamLabel(part.requireString("dream"))
            val modelKind = EnrichedDreamKind.fromWire(part.requireString("kind"))
            val modelUncertain = part.requireBoolean("uncertain")
            val startOrdinal = requireKnownAlias(part.requireString("start"), units.size)
            val endOrdinal = requireKnownAlias(part.requireString("end"), units.size)
            if (endOrdinal < startOrdinal) {
                throw EnrichmentOutputException(EnrichmentOutputReason.RANGE_ORDER)
            }
            ModelPart(
                dreamLabel = dreamLabel,
                startOrdinal = startOrdinal,
                endOrdinal = endOrdinal,
                kind = modelKind,
                uncertain = modelUncertain,
            )
        }

        val modelParts = rawModelParts.mapIndexed { index, part ->
            val nextStart = rawModelParts.getOrNull(index + 1)?.startOrdinal
            if (
                nextStart != null &&
                part.endOrdinal == nextStart &&
                nextStart > part.startOrdinal
            ) {
                part.copy(endOrdinal = nextStart - 1)
            } else {
                part
            }
        }
        var nextRequiredOrdinal = 0
        modelParts.forEach { part ->
            if (part.startOrdinal != nextRequiredOrdinal) {
                throw EnrichmentOutputException(EnrichmentOutputReason.NONCONTIGUOUS_PARTS)
            }
            nextRequiredOrdinal = part.endOrdinal + 1
        }
        if (nextRequiredOrdinal != units.size) {
            throw EnrichmentOutputException(EnrichmentOutputReason.INCOMPLETE_COVERAGE)
        }

        val isolatedParts = modelParts.flatMap { part -> part.isolateCaptureSessions(units) }
        val normalizedParts = isolatedParts.forceExplicitNewDreamBoundaries(units)
        val partStarts = normalizedParts.mapTo(mutableSetOf(), ModelPart::startOrdinal)
        units.forEachIndexed { index, unit ->
            if (unit.cue in REQUIRED_SEMANTIC_PART_START_CUES && index !in partStarts) {
                throw EnrichmentOutputException(EnrichmentOutputReason.MISSING_SEMANTIC_BOUNDARY)
            }
        }

        val labelsBySession = mutableMapOf<Int, LinkedHashMap<String, String>>()
        val groupedParts = linkedMapOf<SessionDreamKey, MutableList<ModelPart>>()
        normalizedParts.forEach { part ->
            val firstUnit = units[part.startOrdinal]
            val labels = labelsBySession.getOrPut(firstUnit.sessionOrder) { linkedMapOf() }
            val wasKnown = part.dreamLabel in labels
            if (labels.isNotEmpty() && !wasKnown && firstUnit.cue !in NEW_LABEL_CUES) {
                throw EnrichmentOutputException(EnrichmentOutputReason.NEW_LABEL_WITHOUT_CUE)
            }
            if (firstUnit.cue == EnrichmentCue.NEW_DREAM && wasKnown) {
                throw EnrichmentOutputException(EnrichmentOutputReason.NEW_DREAM_REUSED_LABEL)
            }
            if (
                firstUnit.cue == EnrichmentCue.DREAM_REFERENCE &&
                labels.isNotEmpty() &&
                !wasKnown
            ) {
                throw EnrichmentOutputException(EnrichmentOutputReason.UNKNOWN_RETURN_LABEL)
            }
            val canonicalLabel = labels.getOrPut(part.dreamLabel) { "d${labels.size}" }
            val key = SessionDreamKey(firstUnit.sessionOrder, canonicalLabel)
            groupedParts.getOrPut(key) { mutableListOf() } += part
        }

        val dreams = groupedParts.values.mapIndexed { dreamIndex, parts ->
            val sourceUnits = parts.flatMap { part ->
                units.subList(part.startOrdinal, part.endOrdinal + 1)
            }
            check(sourceUnits.map(EnrichmentSourceUnit::sessionOrder).distinct().size == 1) {
                "A normalized dream crossed a capture-session boundary."
            }
            val orphanContinuation = sourceUnits.first().cue in CONTINUATION_CUES
            val sources = sourceUnits.deriveRoles(allowFirstCueRole = orphanContinuation)
            val sourceText = sources.joinToString(separator = " ") { it.unit.text }
            val sourceUncertain = sourceExpressesUncertainty(sourceText)
            val sourceIncomplete = sourceExpressesIncompleteRecall(sourceText)
            val explicitDreamBoundary = parts.any { part ->
                val beginsAtExplicitDream =
                    units[part.startOrdinal].cue == EnrichmentCue.NEW_DREAM
                val followedByExplicitDream = units.getOrNull(part.endOrdinal + 1)?.let { following ->
                    following.sessionOrder == units[part.endOrdinal].sessionOrder &&
                        following.cue == EnrichmentCue.NEW_DREAM
                } == true
                beginsAtExplicitDream || followedByExplicitDream
            }
            EnrichedDreamDraft(
                order = dreamIndex,
                // One recurring label is one logical Dream. A fragmentary returned part may
                // make it uncertain, but does not demote an otherwise substantive Dream. A
                // forced explicit boundary also establishes Dream identity on both sides; an
                // independently incomplete source or orphan continuation remains a fragment.
                kind = if (
                    orphanContinuation ||
                    sourceIncomplete
                ) {
                    EnrichedDreamKind.FRAGMENT
                } else if (
                    explicitDreamBoundary ||
                    parts.any { it.kind == EnrichedDreamKind.DREAM }
                ) {
                    EnrichedDreamKind.DREAM
                } else {
                    EnrichedDreamKind.FRAGMENT
                },
                generatedTitle = null,
                generatedText = sources.toReadableText(),
                uncertain = orphanContinuation ||
                    sourceUncertain ||
                    parts.any(ModelPart::uncertain),
                sourceSpans = sources.toSpans(),
            )
        }
        return ValidatedEnrichment(
            schemaVersion = ENRICHMENT_SCHEMA_VERSION,
            attempt = expectedAttempt,
            inputFingerprintSha256 = input.fingerprintSha256,
            dreams = dreams,
        )
    }

    private fun requireKnownAlias(
        alias: String,
        unitCount: Int,
    ): Int {
        val ordinal = enrichmentSourceOrdinal(alias)
            ?: throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_ALIAS)
        if (ordinal !in 0 until unitCount) {
            throw EnrichmentOutputException(EnrichmentOutputReason.UNKNOWN_ALIAS)
        }
        return ordinal
    }

    /**
     * Dream labels are temporary identity keys, not persisted content. The prompt asks for d0,
     * d1, and so on, but equivalent short labels such as D0, dream-0, or first are safe because
     * the parser immediately remaps them to capture-local canonical labels. All source coverage,
     * cue, recurrence, and capture-boundary validation remains unchanged.
     */
    private fun canonicalModelDreamLabel(value: String): String {
        val normalized = value
            .trim()
            .lowercase(Locale.US)
            .replace(LABEL_SEPARATOR, "-")
        if (!MODEL_LABEL.matches(normalized)) {
            throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_DREAM_LABEL)
        }
        val numeric = NUMERIC_LABEL.matchEntire(normalized)?.groupValues?.get(1)
        return if (numeric == null) normalized else "d$numeric"
    }

    private fun ModelPart.isolateCaptureSessions(
        units: List<EnrichmentSourceUnit>,
    ): List<ModelPart> {
        val result = mutableListOf<ModelPart>()
        var isolatedStart = startOrdinal
        for (index in (startOrdinal + 1)..endOrdinal) {
            if (units[index].sessionOrder != units[index - 1].sessionOrder) {
                result += copy(startOrdinal = isolatedStart, endOrdinal = index - 1)
                isolatedStart = index
            }
        }
        result += copy(startOrdinal = isolatedStart)
        return result
    }

    /**
     * An explicit new-dream cue is a deterministic boundary even when the model returns one
     * range across it. This preserves D-36's safety floor while leaving ambiguous, cue-free
     * semantic grouping to the model. Labels remain capture-local and sequential so a later
     * explicit return can still reuse the referenced label.
     */
    private fun List<ModelPart>.forceExplicitNewDreamBoundaries(
        units: List<EnrichmentSourceUnit>,
    ): List<ModelPart> {
        val knownLabelsBySession = mutableMapOf<Int, LinkedHashSet<String>>()
        return buildList {
            this@forceExplicitNewDreamBoundaries.forEach { part ->
                val sessionOrder = units[part.startOrdinal].sessionOrder
                val knownLabels = knownLabelsBySession.getOrPut(sessionOrder) {
                    linkedSetOf()
                }
                var pieceStart = part.startOrdinal
                var pieceLabel = if (units[pieceStart].cue == EnrichmentCue.NEW_DREAM) {
                    knownLabels.nextUnusedDreamLabel()
                } else {
                    part.dreamLabel
                }

                for (boundary in (part.startOrdinal + 1)..part.endOrdinal) {
                    if (units[boundary].cue != EnrichmentCue.NEW_DREAM) continue
                    add(
                        part.copy(
                            dreamLabel = pieceLabel,
                            startOrdinal = pieceStart,
                            endOrdinal = boundary - 1,
                        ),
                    )
                    knownLabels += pieceLabel
                    pieceStart = boundary
                    pieceLabel = knownLabels.nextUnusedDreamLabel()
                }

                add(
                    part.copy(
                        dreamLabel = pieceLabel,
                        startOrdinal = pieceStart,
                    ),
                )
                knownLabels += pieceLabel
            }
        }
    }

    private fun Set<String>.nextUnusedDreamLabel(): String {
        var ordinal = size
        while ("d$ordinal" in this) ordinal += 1
        return "d$ordinal"
    }

    private fun List<EnrichmentSourceUnit>.deriveRoles(
        allowFirstCueRole: Boolean,
    ): List<ResolvedSource> {
        var sessionId: String? = null
        var activeRole: DreamSourceRole? = null
        return mapIndexed { index, unit ->
            val unitSessionId = unit.segments.first().sessionId
            if (unitSessionId != sessionId) activeRole = null
            activeRole = when (unit.cue) {
                EnrichmentCue.CORRECTION -> DreamSourceRole.CORRECTION
                EnrichmentCue.ADDITION -> DreamSourceRole.ADDITION
                EnrichmentCue.NEW_DREAM,
                EnrichmentCue.DREAM_REFERENCE,
                -> null
                else -> activeRole
            }
            sessionId = unitSessionId
            val role = when {
                index == 0 && !(allowFirstCueRole && activeRole != null) ->
                    DreamSourceRole.NARRATIVE
                activeRole != null -> activeRole
                else -> DreamSourceRole.NARRATIVE
            }
            ResolvedSource(unit = unit, role = role)
        }
    }

    private fun List<ResolvedSource>.toReadableText(): String {
        val groundedClauses = mutableListOf<MutableList<ResolvedSource>>()
        forEach { source ->
            val previous = groundedClauses.lastOrNull()?.lastOrNull()
            val previousSegment = previous?.unit?.segments?.lastOrNull()
            val currentSegment = source.unit.segments.first()
            val sameGroundedClause = previous != null &&
                previous.role == source.role &&
                previousSegment?.sessionId == currentSegment.sessionId &&
                previousSegment.transcriptAttempt == currentSegment.transcriptAttempt &&
                previousSegment.segmentIndex + 1 == currentSegment.segmentIndex &&
                currentSegment.sourceStartMillis >= previousSegment.sourceEndMillis
            if (sameGroundedClause) {
                groundedClauses.last() += source
            } else {
                groundedClauses += mutableListOf(source)
            }
        }
        return groundedClauses.joinToString(separator = " ") { clause ->
            clause.joinToString(separator = " ") { it.unit.text }.toReadableSentence()
        }
    }

    private fun String.toReadableSentence(): String {
        val trimmed = trim()
        val letters = trimmed.filter(Char::isLetter)
        val normalized = if (letters.isNotEmpty() && letters.none(Char::isLowerCase)) {
            trimmed.lowercase(Locale.US)
        } else {
            trimmed
        }
        val cased = buildString(normalized.length + 1) {
            var capitalizeNextLetter = true
            normalized.forEach { character ->
                if (character.isLetter()) {
                    append(if (capitalizeNextLetter) character.uppercaseChar() else character)
                    capitalizeNextLetter = false
                } else {
                    append(character)
                    if (character in SENTENCE_ENDINGS) capitalizeNextLetter = true
                }
            }
        }.replace(STANDALONE_I, "I")
        return if (cased.lastOrNull() in SENTENCE_ENDINGS) cased else "$cased."
    }

    private fun List<ResolvedSource>.toSpans(): List<EnrichedSourceSpan> {
        val resolvedSegments = flatMap { source ->
            source.unit.segments.map { segment -> ResolvedSegment(segment, source.role) }
        }
        val groups = mutableListOf<MutableList<ResolvedSegment>>()
        resolvedSegments.forEach { source ->
            val previous = groups.lastOrNull()?.lastOrNull()
            val continuesPrevious = previous != null &&
                previous.role == source.role &&
                previous.segment.sessionId == source.segment.sessionId &&
                previous.segment.segmentIndex + 1 == source.segment.segmentIndex
            if (continuesPrevious) {
                groups.last() += source
            } else {
                groups += mutableListOf(source)
            }
        }
        return groups.map { group ->
            val first = group.first()
            val last = group.last()
            EnrichedSourceSpan(
                role = first.role,
                sessionId = first.segment.sessionId,
                startSegmentIndex = first.segment.segmentIndex,
                endSegmentIndexInclusive = last.segment.segmentIndex,
                sourceStartMillis = first.segment.sourceStartMillis,
                sourceEndMillis = last.segment.sourceEndMillis,
                segmentIds = group.map { it.segment.id },
            )
        }
    }

    private data class ResolvedSource(
        val unit: EnrichmentSourceUnit,
        val role: DreamSourceRole,
    )

    private data class ResolvedSegment(
        val segment: NightTranscriptSegment,
        val role: DreamSourceRole,
    )

    private data class ModelPart(
        val dreamLabel: String,
        val startOrdinal: Int,
        val endOrdinal: Int,
        val kind: EnrichedDreamKind,
        val uncertain: Boolean,
    )

    private data class SessionDreamKey(
        val sessionOrder: Int,
        val canonicalLabel: String,
    )

    private const val MAX_OUTPUT_CHARACTERS = 1_000_000
    private val ROOT_KEYS = setOf("parts")
    private val PART_KEYS = setOf("dream", "kind", "uncertain", "start", "end")
    private val MODEL_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,63})")
    private val NUMERIC_LABEL = Regex("(?:d|dream-?)(0|[1-9][0-9]*)")
    private val LABEL_SEPARATOR = Regex("[ _-]+")
    private val REQUIRED_SEMANTIC_PART_START_CUES =
        setOf(EnrichmentCue.NEW_DREAM, EnrichmentCue.DREAM_REFERENCE)
    private val NEW_LABEL_CUES = setOf(
        EnrichmentCue.NEW_DREAM,
        EnrichmentCue.ADDITION,
        EnrichmentCue.CORRECTION,
        EnrichmentCue.UNCERTAIN_FRAGMENT,
    )
    private val CONTINUATION_CUES = setOf(EnrichmentCue.ADDITION, EnrichmentCue.CORRECTION)
    private val SENTENCE_ENDINGS = setOf('.', '!', '?')
    private val STANDALONE_I = Regex("\\bi\\b")
}

private sealed interface JsonValue

private data class JsonObject(val values: Map<String, JsonValue>) : JsonValue {
    fun requireKeys(expected: Set<String>) {
        if (values.keys != expected) {
            throw EnrichmentOutputException(EnrichmentOutputReason.WRONG_FIELDS)
        }
    }

    fun requireString(name: String): String = (values[name] as? JsonString)?.value
        ?: throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_FIELD_TYPE)

    fun requireBoolean(name: String): Boolean = (values[name] as? JsonBoolean)?.value
        ?: throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_FIELD_TYPE)

    fun requireArray(name: String): JsonArray = values[name] as? JsonArray
        ?: throw EnrichmentOutputException(EnrichmentOutputReason.INVALID_FIELD_TYPE)
}

private data class JsonArray(val values: List<JsonValue>) : JsonValue
private data class JsonString(val value: String) : JsonValue
private data class JsonNumber(val raw: String) : JsonValue
private data class JsonBoolean(val value: Boolean) : JsonValue
private data object JsonNull : JsonValue

private fun JsonValue.asObject(): JsonObject = this as? JsonObject
    ?: throw EnrichmentOutputException(EnrichmentOutputReason.EXPECTED_OBJECT)

/** Small strict parser used so the deterministic JVM validator has no Android or JSON dependency. */
private class StrictJsonParser(private val source: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (index != source.length) malformed()
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH || index >= source.length) malformed()
        return when (source[index]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            '-', in '0'..'9' -> JsonNumber(parseNumber())
            else -> malformed()
        }
    }

    private fun parseObject(depth: Int): JsonObject {
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (takeIfPresent('}')) return JsonObject(values)
        while (true) {
            if (index >= source.length || source[index] != '"') malformed()
            val name = parseString()
            if (values.containsKey(name)) malformed()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            values[name] = parseValue(depth)
            skipWhitespace()
            if (takeIfPresent('}')) break
            expect(',')
            skipWhitespace()
        }
        return JsonObject(values)
    }

    private fun parseArray(depth: Int): JsonArray {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (takeIfPresent(']')) return JsonArray(values)
        while (true) {
            values += parseValue(depth)
            skipWhitespace()
            if (takeIfPresent(']')) break
            expect(',')
            skipWhitespace()
        }
        return JsonArray(values)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    if (index >= source.length) malformed()
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(parseUnicodeEscape())
                        else -> malformed()
                    }
                }

                character.code < 0x20 -> malformed()
                else -> result.append(character)
            }
        }
        malformed()
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > source.length) malformed()
        val raw = source.substring(index, index + 4)
        if (!HEX.matches(raw)) malformed()
        index += 4
        return raw.toInt(16).toChar()
    }

    private fun parseNumber(): String {
        val start = index
        if (source[index] == '-') index += 1
        if (index >= source.length) malformed()
        if (source[index] == '0') {
            index += 1
        } else {
            if (source[index] !in '1'..'9') malformed()
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (index < source.length && source[index] == '.') {
            index += 1
            val fractionStart = index
            while (index < source.length && source[index].isDigit()) index += 1
            if (fractionStart == index) malformed()
        }
        if (index < source.length && source[index] in setOf('e', 'E')) {
            index += 1
            if (index < source.length && source[index] in setOf('+', '-')) index += 1
            val exponentStart = index
            while (index < source.length && source[index].isDigit()) index += 1
            if (exponentStart == index) malformed()
        }
        return source.substring(start, index)
    }

    private fun <T : JsonValue> parseLiteral(
        literal: String,
        value: T,
    ): T {
        if (!source.startsWith(literal, index)) malformed()
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun expect(expected: Char) {
        if (index >= source.length || source[index] != expected) malformed()
        index += 1
    }

    private fun takeIfPresent(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index += 1
        return true
    }

    private fun malformed(): Nothing =
        throw EnrichmentOutputException(EnrichmentOutputReason.MALFORMED_JSON)

    private companion object {
        const val MAX_DEPTH = 64
        val JSON_WHITESPACE = setOf(' ', '\t', '\n', '\r')
        val HEX = Regex("[0-9a-fA-F]{4}")
    }
}
