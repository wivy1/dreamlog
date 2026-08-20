package com.wivy.dreamlog.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentDomainAndParserTest {
    @Test
    fun canonicalInputFingerprintAndLabeledPartPromptAreDeterministicAndRequestLocal() {
        val later = segment(
            sessionId = "550e8400-e29b-41d4-a716-446655440001",
            sessionOrder = 1,
            segmentIndex = 0,
            startMillis = 0L,
            endMillis = 500L,
            text = "Later \"quoted\" text",
        )
        val earlier = segment(
            sessionId = "550e8400-e29b-41d4-a716-446655440000",
            sessionOrder = 0,
            segmentIndex = 0,
            startMillis = 10L,
            endMillis = 600L,
            text = "Earlier text",
        )
        val first = OrderedNightTranscript.create(NIGHT_ID, listOf(later, earlier))
        val second = OrderedNightTranscript.create(NIGHT_ID, listOf(earlier, later))

        assertEquals(
            listOf(
                "550e8400-e29b-41d4-a716-446655440000:0",
                "550e8400-e29b-41d4-a716-446655440001:0",
            ),
            first.segments.map { it.id.encoded },
        )
        assertEquals(first.fingerprintSha256, second.fingerprintSha256)
        assertEquals(64, first.fingerprintSha256.length)
        assertNotEquals(
            first.fingerprintSha256,
            OrderedNightTranscript.create(
                NIGHT_ID,
                listOf(earlier.copy(transcriptAttempt = 2), later),
            ).fingerprintSha256,
        )
        assertNotEquals(
            first.fingerprintSha256,
            OrderedNightTranscript.create(
                NIGHT_ID,
                listOf(
                    earlier.copy(narrationStartedAtEpochMillis = 1_000_001L),
                    later,
                ),
            ).fingerprintSha256,
        )

        val request = EnrichmentPromptBuilder.build(first, attempt = 3)
        assertEquals(6, ENRICHMENT_SCHEMA_VERSION)
        assertEquals("18", ENRICHMENT_PROMPT_VERSION)
        assertTrue(EnrichmentPromptBuilder.responseContractDescription.contains("\"parts\""))
        assertTrue(EnrichmentPromptBuilder.responseContractDescription.contains("\"dream\""))
        assertTrue(EnrichmentPromptBuilder.responseContractDescription.contains("JSON boolean"))
        assertFalse(EnrichmentPromptBuilder.responseContractDescription.contains("s0"))
        assertFalse(EnrichmentPromptBuilder.responseContractDescription.contains("s1"))
        assertEquals(3, request.attempt)
        assertEquals(first.fingerprintSha256, request.inputFingerprintSha256)
        assertTrue(
            request.systemInstruction.contains(
                EnrichmentPromptBuilder.responseContractDescription,
            ),
        )
        assertTrue(request.systemInstruction.contains("contiguous, nonoverlapping parts"))
        assertTrue(request.systemInstruction.contains("Do not copy transcript text"))
        assertTrue(request.systemInstruction.contains("Labels follow first mention, not event chronology"))
        assertTrue(request.systemInstruction.contains("Both sides of an explicit new-dream boundary"))
        assertTrue(request.systemInstruction.contains("uncertainty alone does not make a Dream a fragment"))
        assertEquals(
            "Allowed aliases in exact order: s0, s1. Use no other alias. Cover this entire " +
                "list exactly once.\n" +
                "The first part must start at s0 and the final part must end at s1.\n" +
                "Required semantic part starts: none.\n" +
                "Captures and segments in chronological order:\n" +
                "c0 first=s0 narration-start-epoch-ms=1000000 utc-offset-seconds=-18000 " +
                "start-gap-ms=first:\n" +
                "s0 cue=none \"Earlier text\"\n" +
                "c1 first=s1 narration-start-epoch-ms=1060000 utc-offset-seconds=-18000 " +
                "start-gap-ms=60000:\n" +
                "s1 cue=none \"Later \\\"quoted\\\" text\"\n" +
                "End captures. Return one bare JSON object.",
            request.userContent,
        )
        assertEquals(request.userContent, EnrichmentPromptBuilder.build(second, 3).userContent)
        assertFalse(request.userContent.contains(first.fingerprintSha256))
        assertFalse(request.userContent.contains(NIGHT_ID))
        assertFalse(request.userContent.contains("550e8400"))
    }

    @Test
    fun promptExposesMandatoryStartsAndSemanticCuesWithoutPersistedIds() {
        val source = input(
            segment("persisted-session-a", 0, 0, 0L, 100L, "one"),
            segment("persisted-session-a", 0, 1, 100L, 200L, "CORRECTION two"),
            segment(
                "persisted-session-b",
                1,
                0,
                0L,
                100L,
                "I REMEMBER MORE ABOUT THE SAME DREAM three",
            ),
        )

        val content = EnrichmentPromptBuilder.build(source, 1).userContent
        assertTrue(content.contains("Required semantic part starts: none."))
        assertTrue(content.contains("c0 first=s0"))
        assertTrue(content.contains("c1 first=s2"))
        assertTrue(content.contains("s0 cue=none"))
        assertTrue(content.contains("s1 cue=correction"))
        assertTrue(content.contains("s2 cue=addition"))
        assertFalse(content.contains("persisted-session"))
    }

    @Test
    fun explicitNewDreamCueIsAMandatoryPartStart() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "I walked through rain"),
            segment("session-a", 0, 1, 100L, 200L, "MY NEXT DREAM was in a library"),
        )

        val content = EnrichmentPromptBuilder.build(source, 1).userContent
        assertTrue(
            content.contains(
                "Required semantic part starts: s1. Each of these aliases must be the start " +
                    "of a separate part.",
            ),
        )
        assertTrue(content.contains("s1 cue=new-dream"))
    }

    @Test
    fun backwardDreamIntroductionIsMandatoryAndRepairsAMergedModelPart() {
        val source = input(
            segment(
                "session-a",
                0,
                0,
                0L,
                100L,
                "I WALKED THROUGH A GLASS GREENHOUSE",
            ),
            segment(
                "session-a",
                0,
                1,
                100L,
                200L,
                "I HAD ANOTHER DREAM BEFORE THIS I SAILED PAST AN ISLAND",
            ),
        )

        val request = EnrichmentPromptBuilder.build(source, 1)
        assertTrue(
            request.userContent.contains(
                "Required semantic part starts: s1. Each of these aliases must be the start " +
                    "of a separate part.",
            ),
        )
        assertTrue(request.userContent.contains("s1 cue=new-dream"))

        val result = parse(
            source,
            dream(
                label = "d0",
                kind = EnrichedDreamKind.FRAGMENT,
                uncertain = true,
                start = "s0",
                end = "s1",
            ),
        )

        assertEquals(1, source.capturePartitions().size)
        assertEquals(2, result.dreams.size)
        assertEquals(
            listOf(EnrichedDreamKind.DREAM, EnrichedDreamKind.DREAM),
            result.dreams.map(EnrichedDreamDraft::kind),
        )
        assertEquals(listOf(true, true), result.dreams.map(EnrichedDreamDraft::uncertain))
        assertEquals(
            listOf(listOf(0), listOf(1)),
            result.dreams.map { dream ->
                dream.sourceSpans.single().segmentIds.map(SourceSegmentId::segmentIndex)
            },
        )
        val observedIds = result.dreams
            .flatMap(EnrichedDreamDraft::sourceSpans)
            .flatMap(EnrichedSourceSpan::segmentIds)
        assertEquals(source.segments.map(NightTranscriptSegment::id).toSet(), observedIds.toSet())
        assertEquals(source.segments.size, observedIds.size)
        assertEquals(source.segments.size, observedIds.distinct().size)
        assertEquals(
            source.segments.flatMap { supportedWords(it.text) }.sorted(),
            result.dreams.flatMap { supportedWords(it.generatedText) }.sorted(),
        )
    }

    @Test
    fun explicitDreamBoundaryDoesNotPromoteIndependentlyIncompleteRecall() {
        val source = input(
            segment(
                "session-a",
                0,
                0,
                0L,
                100L,
                "I CANNOT REMEMBER THE REST",
            ),
            segment(
                "session-a",
                0,
                1,
                100L,
                200L,
                "MY NEXT DREAM WAS BESIDE A HARBOR",
            ),
        )

        val result = parse(
            source,
            dream(
                label = "d0",
                kind = EnrichedDreamKind.FRAGMENT,
                uncertain = false,
                start = "s0",
                end = "s1",
            ),
        )

        assertEquals(
            listOf(EnrichedDreamKind.FRAGMENT, EnrichedDreamKind.DREAM),
            result.dreams.map(EnrichedDreamDraft::kind),
        )
        assertEquals(listOf(true, false), result.dreams.map(EnrichedDreamDraft::uncertain))
        assertEquals(
            source.segments.map(NightTranscriptSegment::id),
            result.dreams.flatMap(EnrichedDreamDraft::sourceSpans)
                .flatMap(EnrichedSourceSpan::segmentIds),
        )
    }

    @Test
    fun requestEnumeratesOnlyAvailableAliasesAndExactSemanticStarts() {
        val oneUnit = input(
            segment("session-a", 0, 0, 0L, 100L, "one continuous dream"),
        )
        val oneUnitContent = EnrichmentPromptBuilder.build(oneUnit, 1).userContent
        assertTrue(
            oneUnitContent.contains(
                "Allowed aliases in exact order: s0. Use no other alias. Cover this entire " +
                    "list exactly once.",
            ),
        )
        assertTrue(
            oneUnitContent.contains(
                "The first part must start at s0 and the final part must end at s0.",
            ),
        )
        assertFalse(Regex("\\bs1\\b").containsMatchIn(oneUnitContent))

        val returnNarrationWords = listOf(
            "before",
            "the", "second", "dream", "was", "on", "a", "train",
            "back", "in", "the", "first", "dream", "the", "door", "was", "blue",
        )
        val returnNarration = input(
            *returnNarrationWords.mapIndexed { index, word ->
                segment(
                    sessionId = "session-a",
                    sessionOrder = 0,
                    segmentIndex = index,
                    startMillis = index * 100L,
                    endMillis = (index + 1) * 100L,
                    text = word,
                )
            }.toTypedArray(),
        )
        val returnContent = EnrichmentPromptBuilder.build(returnNarration, 1).userContent
        assertTrue(returnContent.contains("Allowed aliases in exact order: s0, s1, s2."))
        assertTrue(
            returnContent.contains(
                "Required semantic part starts: s1, s2. Each of these aliases must be the " +
                    "start of a separate part.",
            ),
        )
        assertTrue(returnContent.contains("s1 cue=new-dream"))
        assertTrue(returnContent.contains("s2 cue=dream-reference"))
    }

    @Test
    fun emptyPromptAndOutputUseTheExactPlainJsonShape() {
        val empty = input()
        assertEquals(
            "Allowed aliases in exact order: none.\n" +
                "Required semantic part starts: none.\n" +
                "Captures and segments in chronological order:\n(none)\n" +
                "End captures. Return one bare JSON object.",
            EnrichmentPromptBuilder.build(empty, attempt = 1).userContent,
        )
        val parsed = EnrichmentOutputParser.parse("{\"parts\":[]}", empty, 7)
        assertEquals(ENRICHMENT_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals(7, parsed.attempt)
        assertEquals(empty.fingerprintSha256, parsed.inputFingerprintSha256)
        assertTrue(parsed.dreams.isEmpty())
    }

    @Test
    fun canonicalInputRejectsDuplicateSessionsAndOverlappingRanges() {
        val sharedOrder = runCatching {
            OrderedNightTranscript.create(
                NIGHT_ID,
                listOf(
                    segment("session-a", 0, 0, 0L, 100L, "one"),
                    segment("session-b", 0, 0, 0L, 100L, "two"),
                ),
            )
        }.exceptionOrNull()
        assertTrue(sharedOrder is IllegalArgumentException)

        val overlap = runCatching {
            OrderedNightTranscript.create(
                NIGHT_ID,
                listOf(
                    segment("session-a", 0, 0, 0L, 200L, "one"),
                    segment("session-a", 0, 1, 100L, 300L, "two"),
                ),
            )
        }.exceptionOrNull()
        assertTrue(overlap is IllegalArgumentException)
    }

    @Test
    fun labeledPartGroupingBuildsReadableTextFromEveryRawSegmentWithoutChangingRawInput() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "I WALKED THROUGH RAIN"),
            segment("session-a", 0, 1, 100L, 220L, "THEN I SAW A RED DOOR!"),
            segment("session-a", 0, 2, 220L, 350L, "A DIFFERENT DREAM BEGAN"),
        )
        val result = parse(
            source,
            dream(label = "d0", start = "s0", end = "s0"),
            dream(label = "d1", start = "s1", end = "s1"),
        )

        assertEquals(listOf(0, 1), result.dreams.map(EnrichedDreamDraft::order))
        assertEquals("I walked through rain then I saw a red door!", result.dreams[0].generatedText)
        assertEquals("A different dream began.", result.dreams[1].generatedText)
        assertEquals(
            source.segments.flatMap { supportedWords(it.text) },
            result.dreams.flatMap { supportedWords(it.generatedText) },
        )
        assertEquals("I WALKED THROUGH RAIN", source.segments.first().text)
        assertNull(result.dreams.first().generatedTitle)
        assertEquals(
            listOf(SourceSegmentId("session-a", 0), SourceSegmentId("session-a", 1)),
            result.dreams.first().sourceSpans.single().segmentIds,
        )
    }

    @Test
    fun wordLevelM04SegmentsIgnorePromptSizingBoundariesInGroundedReadingText() {
        val words = listOf(
            "I", "WAS", "WALKING", "THROUGH", "A", "RED", "FOREST", "WHEN", "A",
            "SILVER", "BIRD", "LANDED", "BESIDE", "ME", "AND", "THEN", "THE", "TREES",
            "BEGAN", "TO", "SING",
        )
        val source = input(
            *words.mapIndexed { index, word ->
                segment(
                    "session-a",
                    0,
                    index,
                    index * 100L,
                    (index + 1L) * 100L,
                    word,
                )
            }.toTypedArray(),
        )

        val result = parse(source, dream(start = "s0", end = "s1"))

        assertEquals(
            "I was walking through a red forest when a silver bird landed beside me and then " +
                "the trees began to sing.",
            result.dreams.single().generatedText,
        )
        assertEquals(
            source.segments.map(NightTranscriptSegment::id),
            result.dreams.single().sourceSpans.single().segmentIds,
        )
        assertEquals(
            source.segments.flatMap { supportedWords(it.text) },
            supportedWords(result.dreams.single().generatedText),
        )
    }

    @Test
    fun sourceRolesAreDerivedWithinOneCaptureAndLaterCaptureIsIsolated() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "THE CAR WAS RED"),
            segment("session-a", 0, 1, 100L, 200L, "ANOTHER DETAIL IT WAS RAINING"),
            segment("session-a", 0, 2, 200L, 300L, "CORRECTION THE CAR WAS BLUE"),
            segment("session-a", 0, 3, 300L, 400L, "I PARKED BESIDE A LAKE"),
            segment("session-b", 1, 0, 0L, 100L, "THE LAKE HAD SILVER WAVES"),
        )
        val result = parse(source, dream(start = "s0", end = "s3"))
        assertEquals(2, result.dreams.size)
        val spans = result.dreams.first().sourceSpans

        assertEquals(
            listOf(
                DreamSourceRole.NARRATIVE,
                DreamSourceRole.ADDITION,
                DreamSourceRole.CORRECTION,
            ),
            spans.map(EnrichedSourceSpan::role),
        )
        assertEquals(0L, spans.first().sourceStartMillis)
        assertEquals(100L, spans.first().sourceEndMillis)
        assertEquals(
            SourceSegmentId("session-b", 0),
            result.dreams.last().sourceSpans.single().segmentIds.single(),
        )
        assertEquals(DreamSourceRole.NARRATIVE, result.dreams.last().sourceSpans.single().role)
    }

    @Test
    fun continuationCuesInLaterCapturesRemainSeparateUncertainFragments() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "THE CAR WAS RED"),
            segment("session-b", 1, 0, 0L, 100L, "CORRECTION THE CAR WAS BLUE"),
            segment(
                "session-c",
                2,
                0,
                0L,
                100L,
                "I REMEMBER MORE ABOUT THE SAME CAR IT WAS BESIDE A LAKE",
            ),
        )

        val result = parse(
            source,
            dream(start = "s0", end = "s0"),
            dream(
                kind = EnrichedDreamKind.FRAGMENT,
                uncertain = true,
                start = "s1",
                end = "s1",
            ),
            dream(
                kind = EnrichedDreamKind.FRAGMENT,
                uncertain = true,
                start = "s2",
                end = "s2",
            ),
        )

        assertEquals(3, result.dreams.size)
        assertEquals(
            listOf(
                DreamSourceRole.NARRATIVE,
                DreamSourceRole.CORRECTION,
                DreamSourceRole.ADDITION,
            ),
            result.dreams.map { it.sourceSpans.single().role },
        )
        assertEquals(
            listOf(
                EnrichedDreamKind.DREAM,
                EnrichedDreamKind.FRAGMENT,
                EnrichedDreamKind.FRAGMENT,
            ),
            result.dreams.map(EnrichedDreamDraft::kind),
        )
        assertEquals(listOf(false, true, true), result.dreams.map(EnrichedDreamDraft::uncertain))
    }

    @Test
    fun correctionAndLaterNarrativeCapturesRemainThreeSeparateDreams() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "THE CAR WAS RED"),
            segment("session-b", 1, 0, 0L, 100L, "CORRECTION THE CAR WAS BLUE"),
            segment("session-c", 2, 0, 0L, 100L, "I FLOATED INSIDE A GLASS ELEVATOR"),
        )

        val result = parse(
            source,
            dream(start = "s0", end = "s0"),
            dream(start = "s1", end = "s1"),
            dream(start = "s2", end = "s2"),
        )

        assertEquals(3, result.dreams.size)
        assertEquals(
            listOf(
                DreamSourceRole.NARRATIVE,
                DreamSourceRole.CORRECTION,
                DreamSourceRole.NARRATIVE,
            ),
            result.dreams.map { it.sourceSpans.single().role },
        )
        assertTrue(result.dreams[1].uncertain)
        assertEquals("I floated inside a glass elevator.", result.dreams[2].generatedText)
    }

    @Test
    fun laterAdditionAndNewDreamCapturesRemainIsolatedWithSafetySignals() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "I WALKED BESIDE A RIVER"),
            segment("session-b", 1, 0, 0L, 100L, "I REMEMBER MORE ABOUT THE SAME RIVER"),
            segment("session-c", 2, 0, 0L, 100L, "MY NEXT DREAM BEGAN IN A TOWER"),
        )

        val result = parse(
            source,
            dream(start = "s0", end = "s0"),
            dream(
                kind = EnrichedDreamKind.FRAGMENT,
                uncertain = true,
                start = "s1",
                end = "s2",
            ),
        )

        assertEquals(3, result.dreams.size)
        assertEquals(EnrichedDreamKind.DREAM, result.dreams[0].kind)
        assertFalse(result.dreams[0].uncertain)
        assertEquals(EnrichedDreamKind.FRAGMENT, result.dreams[1].kind)
        assertTrue(result.dreams[1].uncertain)
        assertEquals(EnrichedDreamKind.DREAM, result.dreams[2].kind)
        assertTrue(result.dreams[2].uncertain)
    }

    @Test
    fun leadingCorrectionWithoutAPriorTargetIsAnUncertainFragment() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "CORRECTION THE DOOR WAS BLUE"),
        )

        val result = parse(source, dream(start = "s0", end = "s0"))

        val fragment = result.dreams.single()
        assertEquals(EnrichedDreamKind.FRAGMENT, fragment.kind)
        assertTrue(fragment.uncertain)
        assertEquals(DreamSourceRole.CORRECTION, fragment.sourceSpans.single().role)
    }

    @Test
    fun mixedCaseSourcePreservesSupportedProperNameCasing() {
        val source = input(
            segment(
                "session-a",
                0,
                0,
                0L,
                100L,
                "I saw DreamLog near Lake Michigan",
            ),
        )

        val result = parse(source, dream(start = "s0", end = "s0"))

        assertEquals("I saw DreamLog near Lake Michigan.", result.dreams.single().generatedText)
    }

    @Test
    fun correctionWithMultipleEarlierDreamsBecomesAnUncertainFragment() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "I WAS RIDING A TRAIN"),
            segment(
                "session-b",
                1,
                0,
                0L,
                100L,
                "MY NEXT DREAM WAS ABOUT A HOUSE",
            ),
            segment(
                "session-c",
                2,
                0,
                0L,
                100L,
                "CORRECTION THE DOOR WAS BLUE",
            ),
        )

        val result = parse(
            source,
            dream(start = "s0", end = "s0"),
            dream(start = "s1", end = "s2"),
        )

        assertEquals(3, result.dreams.size)
        val ambiguous = result.dreams.last()
        assertEquals(EnrichedDreamKind.FRAGMENT, ambiguous.kind)
        assertTrue(ambiguous.uncertain)
        assertEquals(DreamSourceRole.CORRECTION, ambiguous.sourceSpans.single().role)
        assertEquals("Correction the door was blue.", ambiguous.generatedText)
    }

    @Test
    fun cueFreeModelBoundaryRemainsAuthoritative() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "I WALKED THROUGH RAIN"),
            segment("session-b", 1, 0, 0L, 100L, "I STOOD IN A LIBRARY"),
        )

        val result = parse(
            source,
            dream(start = "s0", end = "s0"),
            dream(start = "s1", end = "s1"),
        )

        assertEquals(2, result.dreams.size)
        assertEquals("I walked through rain.", result.dreams[0].generatedText)
        assertEquals("I stood in a library.", result.dreams[1].generatedText)
    }

    @Test
    fun ramblingScenePartsWithOneLabelRemainOneDreamAndCueFreeNewLabelIsRejected() {
        val words = (0 until 40).map { index -> "scene$index" }
        val source = input(
            *words.mapIndexed { index, word ->
                segment(
                    "session-a",
                    0,
                    index,
                    index * 100L,
                    (index + 1L) * 100L,
                    word,
                )
            }.toTypedArray(),
        )

        val result = parse(
            source,
            dream(label = "d0", start = "s0", end = "s0"),
            dream(label = "d0", start = "s1", end = "s2"),
        )

        assertEquals(1, result.dreams.size)
        assertEquals(source.segments.map(NightTranscriptSegment::id), result.dreams.single()
            .sourceSpans.single().segmentIds)
        assertOutputRejected(
            source,
            output(
                dream(label = "d0", start = "s0", end = "s0"),
                dream(label = "d1", start = "s1", end = "s2"),
            ),
        )
    }

    @Test
    fun recurringD0D1D0ProducesTwoDreamsAndOrderedNoncontiguousSpans() {
        val source = input(
            segment(
                "session-a",
                0,
                0,
                0L,
                100L,
                "THE FIRST DREAM WAS ON A TRAIN",
            ),
            segment(
                "session-a",
                0,
                1,
                100L,
                200L,
                "THE SECOND DREAM WAS IN A LIBRARY",
            ),
            segment(
                "session-a",
                0,
                2,
                200L,
                300L,
                "BACK IN THE FIRST DREAM THE TRAIN ENTERED A TUNNEL",
            ),
        )

        val result = parse(
            source,
            dream(label = "d0", start = "s0", end = "s0"),
            dream(label = "d1", start = "s1", end = "s1"),
            dream(label = "d0", start = "s2", end = "s2"),
        )

        assertEquals(2, result.dreams.size)
        assertEquals(
            listOf(listOf(0), listOf(2)),
            result.dreams[0].sourceSpans.map { span ->
                span.segmentIds.map(SourceSegmentId::segmentIndex)
            },
        )
        assertEquals(listOf(1), result.dreams[1].sourceSpans.single().segmentIds.map {
            it.segmentIndex
        })
        assertEquals(
            "The first dream was on a train. Back in the first dream the train entered a tunnel.",
            result.dreams[0].generatedText,
        )
    }

    @Test
    fun explicitNewDreamCueForcesBoundaryWhenModelMissesIt() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "I WAS SWIMMING UNDER ICE"),
            segment(
                "session-a",
                0,
                1,
                100L,
                200L,
                "SUDDENLY A DIFFERENT DREAM I WAS IN A BRIGHT KITCHEN",
            ),
        )

        val result = parse(source, dream(label = "d0", start = "s0", end = "s1"))

        assertEquals(2, result.dreams.size)
        assertEquals(
            listOf("I was swimming under ice.", "Suddenly a different dream I was in a bright kitchen."),
            result.dreams.map(EnrichedDreamDraft::generatedText),
        )
    }

    @Test
    fun forcedNewDreamBoundaryStillAllowsReturnToFirstDream() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "THE FIRST DREAM WAS ON A TRAIN"),
            segment("session-a", 0, 1, 100L, 200L, "THE SECOND DREAM WAS IN A LIBRARY"),
            segment(
                "session-a",
                0,
                2,
                200L,
                300L,
                "BACK IN THE FIRST DREAM THE TRAIN ENTERED A TUNNEL",
            ),
        )

        val result = parse(
            source,
            dream(label = "d0", start = "s0", end = "s1"),
            dream(label = "d0", start = "s2", end = "s2"),
        )

        assertEquals(2, result.dreams.size)
        assertEquals(
            listOf(listOf(0), listOf(2)),
            result.dreams.first().sourceSpans.map { span ->
                span.segmentIds.map(SourceSegmentId::segmentIndex)
            },
        )
        assertEquals("The second dream was in a library.", result.dreams.last().generatedText)
    }

    @Test
    fun recurringDreamRemainsADreamWhenOnlyItsReturnedPartIsFragmentary() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "THE FIRST DREAM WAS ON A TRAIN"),
            segment("session-a", 0, 1, 100L, 200L, "THE SECOND DREAM WAS IN A LIBRARY"),
            segment(
                "session-a",
                0,
                2,
                200L,
                300L,
                "BACK IN THE FIRST DREAM THE TRAIN ENTERED A TUNNEL",
            ),
        )

        val result = parse(
            source,
            dream(label = "d0", start = "s0", end = "s0"),
            dream(label = "d1", start = "s1", end = "s1"),
            dream(
                label = "d0",
                kind = EnrichedDreamKind.FRAGMENT,
                uncertain = true,
                start = "s2",
                end = "s2",
            ),
        )

        assertEquals(EnrichedDreamKind.DREAM, result.dreams.first().kind)
        assertTrue(result.dreams.first().uncertain)
        assertEquals(EnrichedDreamKind.DREAM, result.dreams.last().kind)
    }

    @Test
    fun oneModelPartCrossingTwoSessionsAlwaysBecomesTwoIsolatedDreams() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "FIRST NARRATION"),
            segment("session-b", 1, 0, 0L, 100L, "SECOND NARRATION"),
        )

        val result = parse(source, dream(label = "d0", start = "s0", end = "s1"))

        assertEquals(2, result.dreams.size)
        assertEquals(
            listOf(setOf("session-a"), setOf("session-b")),
            result.dreams.map { dream -> dream.sourceSpans.map { it.sessionId }.toSet() },
        )
    }

    @Test
    fun explicitUncertaintyAndIncompleteRecallAreDeterministicSafetyFloors() {
        val uncertainSource = input(
            segment("session-a", 0, 0, 0L, 100L, "MAYBE I SAW A GREEN DOOR"),
        )
        val uncertainResult = parse(
            uncertainSource,
            dream(kind = EnrichedDreamKind.DREAM, uncertain = false, start = "s0", end = "s0"),
        )
        assertEquals(EnrichedDreamKind.DREAM, uncertainResult.dreams.single().kind)
        assertTrue(uncertainResult.dreams.single().uncertain)

        val incompleteSource = input(
            segment(
                "session-a",
                0,
                0,
                0L,
                100L,
                "A DARK HALLWAY I CANNOT REMEMBER THE REST",
            ),
        )
        val incompleteResult = parse(
            incompleteSource,
            dream(kind = EnrichedDreamKind.DREAM, uncertain = false, start = "s0", end = "s0"),
        )
        assertEquals(EnrichedDreamKind.FRAGMENT, incompleteResult.dreams.single().kind)
        assertTrue(incompleteResult.dreams.single().uncertain)
        assertEquals(
            "A dark hallway I cannot remember the rest.",
            incompleteResult.dreams.single().generatedText,
        )
    }

    @Test
    fun parserRequiresOneBareObjectWithExactRootAndPartKeys() {
        val empty = input()
        listOf(
            "{",
            "text {\"parts\":[]}",
            "```json\n{\"parts\":[]}\n```",
            "{\"parts\":[]} trailing",
            "[]",
            "{\"dreams\":[]}",
            "{\"parts\":[],\"attempt\":1}",
            "{\"schema_version\":6,\"parts\":[]}",
        ).forEach { assertOutputRejected(empty, it) }

        val source = input(segment("session-a", 0, 0, 0L, 100L, "some words"))
        listOf(
            "{\"parts\":[{\"dream\":\"d0\",\"kind\":\"dream\",\"uncertain\":false," +
                "\"start\":\"s0\",\"end\":\"s0\",\"text\":\"some words\"}]}",
            "{\"parts\":[{\"dream\":\"d0\",\"kind\":\"dream\",\"uncertain\":false," +
                "\"start\":\"s0\"}]}",
            "{\"parts\":[{\"dream\":\"d0\",\"kind\":\"dream\",\"uncertain\":\"false\"," +
                "\"start\":\"s0\",\"end\":\"s0\"}]}",
            "{\"parts\":[{\"dream\":\"d0\",\"kind\":\"memory\",\"uncertain\":false," +
                "\"start\":\"s0\",\"end\":\"s0\"}]}",
            "{\"parts\":[{\"kind\":\"dream\",\"uncertain\":false," +
                "\"start\":\"s0\",\"end\":\"s0\"}]}",
        ).forEach { assertOutputRejected(source, it) }
    }

    @Test
    fun parserReportsOnlyClosedContentFreeFailureReasons() {
        val source = input(segment("session-a", 0, 0, 0L, 100L, "private words"))

        assertEquals(
            EnrichmentOutputReason.MALFORMED_JSON,
            outputFailure(source, "{").reason,
        )
        assertEquals(
            EnrichmentOutputReason.WRONG_FIELDS,
            outputFailure(source, "{}").reason,
        )
        val alternateLabel = EnrichmentOutputParser.parse(
            outputJson = "{\"parts\":[{\"dream\":\"Dream 0\",\"kind\":\"dream\"," +
                "\"uncertain\":false,\"start\":\"s0\",\"end\":\"s0\"}]}",
            input = source,
            expectedAttempt = 1,
        )
        assertEquals(1, alternateLabel.dreams.size)
        assertEquals(
            EnrichmentOutputReason.INVALID_DREAM_LABEL,
            outputFailure(
                source,
                "{\"parts\":[{\"dream\":\"   \",\"kind\":\"dream\"," +
                    "\"uncertain\":false,\"start\":\"s0\",\"end\":\"s0\"}]}",
            ).reason,
        )
        assertTrue(EnrichmentOutputReason.entries.none { it.safeDetail.contains("private words") })
    }

    @Test
    fun partsMustBeKnownContiguousNonoverlappingChronologicalAndComplete() {
        val source = input(
            segment("session-a", 0, 0, 0L, 100L, "one"),
            segment("session-b", 1, 0, 0L, 100L, "two"),
            segment("session-c", 2, 0, 0L, 100L, "three"),
        )
        val accepted = parse(
            source,
            dream(start = "s0", end = "s1"),
            dream(start = "s2", end = "s2"),
        )
        assertEquals(3, accepted.dreams.size)
        val repairedSharedEndpoint = parse(
            source,
            dream(start = "s0", end = "s1"),
            dream(start = "s1", end = "s2"),
        )
        assertEquals(3, repairedSharedEndpoint.dreams.size)

        listOf(
            output(dream(start = "s0", end = "s1")),
            output(dream(start = "s1", end = "s2")),
            output(dream(start = "s0", end = "s2"), dream(start = "s1", end = "s2")),
            output(dream(start = "s0", end = "s0"), dream(start = "s2", end = "s2")),
            output(dream(start = "s1", end = "s0"), dream(start = "s1", end = "s2")),
            output(dream(start = "s0", end = "s9")),
            "{\"parts\":[]}",
        ).forEach { assertOutputRejected(source, it) }

        listOf("s00", "S0", "s-1", "session-a:0", "narrative|s0").forEach { alias ->
            assertOutputRejected(source, output(dream(start = alias, end = "s2")))
        }
    }

    private fun parse(
        input: OrderedNightTranscript,
        vararg dreams: DreamPartJson,
    ): ValidatedEnrichment = EnrichmentOutputParser.parse(output(*dreams), input, 1)

    private fun assertOutputRejected(input: OrderedNightTranscript, json: String) {
        outputFailure(input, json)
    }

    private fun outputFailure(
        input: OrderedNightTranscript,
        json: String,
    ): EnrichmentOutputException {
        val failure = runCatching {
            EnrichmentOutputParser.parse(json, input, 1)
        }.exceptionOrNull()
        assertTrue(failure is EnrichmentOutputException)
        return failure as EnrichmentOutputException
    }

    private fun input(vararg segments: NightTranscriptSegment): OrderedNightTranscript =
        OrderedNightTranscript.create(NIGHT_ID, segments.toList())

    private fun segment(
        sessionId: String,
        sessionOrder: Int,
        segmentIndex: Int,
        startMillis: Long,
        endMillis: Long,
        text: String,
    ) = NightTranscriptSegment(
        nightId = NIGHT_ID,
        sessionId = sessionId,
        sessionOrder = sessionOrder,
        transcriptAttempt = 1,
        segmentIndex = segmentIndex,
        sourceStartMillis = startMillis,
        sourceEndMillis = endMillis,
        text = text,
        narrationStartedAtEpochMillis = 1_000_000L + sessionOrder * 60_000L,
        narrationStartedUtcOffsetSeconds = -18_000,
    )

    private fun dream(
        label: String? = null,
        kind: EnrichedDreamKind = EnrichedDreamKind.DREAM,
        uncertain: Boolean = false,
        start: String,
        end: String,
    ) = DreamPartJson(label, kind, uncertain, start, end)

    private fun output(vararg dreams: DreamPartJson): String = buildString {
        append("{\"parts\":[")
        dreams.forEachIndexed { index, dream ->
            if (index > 0) append(',')
            append("{\"dream\":").appendJsonString(dream.label ?: "d$index")
            append(",\"kind\":").appendJsonString(dream.kind.wireValue)
            append(",\"uncertain\":").append(dream.uncertain)
            append(",\"start\":").appendJsonString(dream.start)
            append(",\"end\":").appendJsonString(dream.end)
            append('}')
        }
        append("]}")
    }

    private data class DreamPartJson(
        val label: String?,
        val kind: EnrichedDreamKind,
        val uncertain: Boolean,
        val start: String,
        val end: String,
    )

    private companion object {
        const val NIGHT_ID = "night-1"
    }
}
