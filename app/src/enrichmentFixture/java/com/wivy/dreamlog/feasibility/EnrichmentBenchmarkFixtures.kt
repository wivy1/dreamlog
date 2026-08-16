package com.wivy.dreamlog.feasibility

import com.wivy.dreamlog.enrichment.DreamSourceRole
import com.wivy.dreamlog.enrichment.EnrichedDreamKind
import com.wivy.dreamlog.enrichment.NightTranscriptSegment
import com.wivy.dreamlog.enrichment.OrderedNightTranscript
import com.wivy.dreamlog.enrichment.ValidatedEnrichment

internal data class EnrichmentFixtureExpectation(
    val dreamCount: Int,
    val dreamKinds: List<EnrichedDreamKind>,
    val uncertainDreams: Set<Int> = emptySet(),
    val requiredRoles: Set<DreamSourceRole> = emptySet(),
    val requiredReadingWords: Set<String> = emptySet(),
)

internal data class EnrichmentBenchmarkFixture(
    val id: String,
    val description: String,
    val input: OrderedNightTranscript,
    val expectation: EnrichmentFixtureExpectation,
) {
    fun evaluate(result: ValidatedEnrichment): List<String> {
        val failures = mutableListOf<String>()
        if (result.dreams.size != expectation.dreamCount) {
            failures += "expected ${expectation.dreamCount} dreams; got ${result.dreams.size}"
        }
        val kinds = result.dreams.map { it.kind }
        if (kinds != expectation.dreamKinds) {
            failures += "expected kinds ${expectation.dreamKinds}; got $kinds"
        }
        expectation.uncertainDreams.forEach { index ->
            if (result.dreams.getOrNull(index)?.uncertain != true) {
                failures += "dream $index did not preserve uncertainty"
            }
        }
        val roles = result.dreams.flatMap { dream -> dream.sourceSpans.map { it.role } }.toSet()
        expectation.requiredRoles.filterNotTo(mutableSetOf()) { it in roles }.forEach { role ->
            failures += "missing ${role.wireValue} source role"
        }
        val readingWords = result.dreams
            .flatMap { dream -> WORD.findAll(dream.generatedText.lowercase()).map { it.value } }
            .toSet()
        expectation.requiredReadingWords.filterNotTo(mutableSetOf()) { it in readingWords }
            .forEach { word -> failures += "reading omitted required word '$word'" }
        result.dreams.forEachIndexed { index, dream ->
            if (dream.generatedText.none(Char::isLowerCase)) {
                failures += "dream $index did not improve source casing"
            }
            if (dream.generatedText.lastOrNull() !in SENTENCE_ENDINGS) {
                failures += "dream $index did not end with readable punctuation"
            }
        }
        return failures
    }
}

internal object EnrichmentBenchmarkFixtures {
    val behaviorCases: List<EnrichmentBenchmarkFixture> = listOf(
        fixture(
            id = "one-session-one-dream",
            description = "One session containing one continuous dream.",
            sessions = listOf(
                listOf(
                    "I WAS WALKING THROUGH A RED FOREST",
                    "THE TREES WERE GLOWING ABOVE ME",
                ),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 1,
                dreamKinds = listOf(EnrichedDreamKind.DREAM),
                requiredReadingWords = setOf("forest", "glowing"),
            ),
        ),
        fixture(
            id = "one-session-multiple-dreams",
            description = "One recording explicitly switches to a second dream.",
            sessions = listOf(
                listOf(
                    "I WAS RIDING A TRAIN WITH MY SISTER",
                    "THAT DREAM ENDED AND MY NEXT DREAM STARTED I WAS STANDING BY A BLUE HOUSE BESIDE THE WATER",
                ),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 2,
                dreamKinds = listOf(EnrichedDreamKind.DREAM, EnrichedDreamKind.DREAM),
                requiredReadingWords = setOf("train", "house"),
            ),
        ),
        fixture(
            id = "multiple-sessions-one-dream",
            description = "A later wakeword capture remains a separate logical fragment.",
            sessions = listOf(
                listOf("I WAS CLIMBING A TALL HILL"),
                listOf("I REMEMBER MORE ABOUT THE SAME HILL IT HAD A STONE TOWER"),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 2,
                dreamKinds = listOf(EnrichedDreamKind.DREAM, EnrichedDreamKind.FRAGMENT),
                uncertainDreams = setOf(1),
                requiredRoles = setOf(DreamSourceRole.ADDITION),
                requiredReadingWords = setOf("hill", "tower"),
            ),
        ),
        fixture(
            id = "later-correction",
            description = "A correction in a later capture remains separate and uncertain.",
            sessions = listOf(
                listOf("I WAS DRIVING A RED CAR TOWARD THE LAKE"),
                listOf("CORRECTION THE CAR WAS BLUE NOT RED"),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 2,
                dreamKinds = listOf(EnrichedDreamKind.DREAM, EnrichedDreamKind.FRAGMENT),
                uncertainDreams = setOf(1),
                requiredRoles = setOf(DreamSourceRole.CORRECTION),
                requiredReadingWords = setOf("blue", "lake"),
            ),
        ),
        fixture(
            id = "rambling-one-session",
            description = "Scene changes inside one rambling narration remain one dream.",
            sessions = listOf(
                listOf(
                    "I WALKED FROM A FOREST INTO A GLASS STATION",
                    "THEN THE LIGHT CHANGED AND MY FRIEND BECAME A WHITE BIRD",
                    "LATER WE CROSSED A CITY AND ENTERED A QUIET KITCHEN",
                    "AT SUNRISE THE SAME JOURNEY ENDED BESIDE THE WATER",
                ),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 1,
                dreamKinds = listOf(EnrichedDreamKind.DREAM),
                requiredReadingWords = setOf("forest", "water"),
            ),
        ),
        fixture(
            id = "one-session-dream-return",
            description = "One narration returns to its first of two explicitly numbered dreams.",
            sessions = listOf(
                listOf(
                    "THE FIRST DREAM WAS ON A TRAIN BESIDE THE WATER",
                    "THE SECOND DREAM WAS IN A LIBRARY WITH A BLUE DOOR",
                    "BACK IN THE FIRST DREAM THE TRAIN ENTERED A TUNNEL",
                ),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 2,
                dreamKinds = listOf(EnrichedDreamKind.DREAM, EnrichedDreamKind.DREAM),
                requiredReadingWords = setOf("train", "library", "tunnel"),
            ),
        ),
        fixture(
            id = "abrupt-context-switch",
            description = "An explicit abrupt switch separates two dreams.",
            sessions = listOf(
                listOf(
                    "I WAS SWIMMING UNDER ICE WITH MY FRIEND",
                    "SUDDENLY A DIFFERENT DREAM I WAS IN A BRIGHT KITCHEN",
                ),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 2,
                dreamKinds = listOf(EnrichedDreamKind.DREAM, EnrichedDreamKind.DREAM),
                requiredReadingWords = setOf("ice", "kitchen"),
            ),
        ),
        fixture(
            id = "fragment-uncertainty",
            description = "An uncertain incomplete memory remains a fragment.",
            sessions = listOf(
                listOf("MAYBE A DARK HALLWAY I CANNOT REMEMBER THE REST"),
            ),
            expectation = EnrichmentFixtureExpectation(
                dreamCount = 1,
                dreamKinds = listOf(EnrichedDreamKind.FRAGMENT),
                uncertainDreams = setOf(0),
                requiredReadingWords = setOf("maybe", "hallway"),
            ),
        ),
    )

    private val CONTEXT_SEGMENTS = listOf(
        "I BEGAN THE JOURNEY BESIDE A COPPER RAILWAY WHERE PURPLE WEEDS BRUSHED MY BOOTS UNDER A PALE MORNING SKY",
        "THE PATH CURVED BENEATH GLASS ARCHES WHILE SMALL GREEN MOTHS CIRCLED A CRACKED LANTERN ABOVE MY SHOULDER",
        "A SILENT CONDUCTOR OFFERED ME A PAPER COMPASS MARKED WITH RIVERS THAT MOVED WHENEVER I BLINKED",
        "BEYOND THE PLATFORM THREE MARBLE FOXES WATCHED A CLOCKWORK ORCHARD TURN SLOWLY IN THE MIST",
        "I CROSSED A NARROW BRIDGE OF BLUE ROPE AND HEARD BRASS BELLS RINGING FROM THE VALLEY BELOW",
        "THE ROAD ENTERED A CEDAR MARKET WHERE MASKED VENDORS TRADED FEATHERS FOR JARS OF WARM RAIN",
        "AN OLD BICYCLE ROLLED BESIDE ME WITHOUT A RIDER ITS SILVER WHEELS LEAVING SPARKS ON THE STONES",
        "WE PASSED A FLOODED LIBRARY AND EACH FLOATING BOOK OPENED TO A DIFFERENT MAP OF THE MOON",
        "NEAR SUNSET A WHITE DEER LED ME THROUGH TALL FERNS TOWARD A DOOR CARVED INTO THE HILLSIDE",
        "INSIDE THE HILL I FOUND A ROUND CHAMBER FILLED WITH RED SAND AND SLEEPING WOODEN BIRDS",
        "I REMEMBER MORE ABOUT THE SAME JOURNEY AFTER WAKING THE CHAMBER ALSO HELD A STAIRCASE MADE OF ICE",
        "I CLIMBED UNTIL THE WALLS BECAME TRANSPARENT AND A THUNDERSTORM APPEARED FROZEN OUTSIDE THE GLASS",
        "ON THE UPPER LANDING A CHILD IN A YELLOW COAT DREW CONSTELLATIONS ACROSS THE FLOOR WITH CHALK",
        "THE DRAWN STARS ROSE LIKE FIREFLIES AND FORMED A QUIET TUNNEL LEADING AWAY FROM THE STAIRCASE",
        "I FOLLOWED THEM INTO A FIELD OF BLACK TULIPS WHERE EVERY FLOWER WHISPERED A DIFFERENT NAME",
        "A DISTANT WINDMILL TURNED BACKWARD BESIDE A POND COVERED WITH ORANGE LEAVES AND TINY MIRRORS",
        "MY SHOES FILLED WITH SAND SO I LEFT THEM UNDER A WILLOW TREE THAT HAD PORCELAIN ROOTS",
        "AT MIDNIGHT THE POND OPENED AND REVEALED A STONE LADDER UNDER THE WATER",
        "I DESCENDED PAST SILVER FISH CARRYING CANDLES IN THEIR TRANSPARENT FINS",
        "THE LADDER ENDED IN A QUIET STATION BUILT FROM GREEN TILES AND SHELLS",
        "I REMEMBER MORE ABOUT THE SAME JOURNEY A SECOND MAP APPEARED ON THE WALL",
        "THE MAP SHOWED A DESERT ISLAND SHAPED LIKE A SLEEPING WHALE",
        "A RED BOAT WAITED AT THE PLATFORM WITH ITS SAIL FOLDED LIKE PAPER",
        "I BOARDED AND THE TILED STATION DRIFTED AWAY INTO A FIELD OF STARS",
        "THE BOAT PASSED TWO MOONS AND A CLOUD FILLED WITH TINY GOLDEN WINDOWS",
        "A BLACK CAT AT THE BOW POINTED TOWARD A DISTANT CIRCULAR HARBOR",
        "WE ENTERED THE HARBOR AS BELLS ECHOED FROM TOWERS MADE OF SALT",
        "THE CONDUCTOR RETURNED AND PLACED THE PAPER COMPASS IN MY HAND",
        "ITS MOVING RIVERS NOW FORMED A PATH BACK TO THE COPPER RAILWAY",
        "I STEPPED ONTO THE MORNING PLATFORM AND THE MARBLE FOXES BOWED",
    )

    val contextCase: EnrichmentBenchmarkFixture = fixture(
        id = "context-stress",
        description = "Four hundred forty source-timed M04 word segments across three sessions exercise the selected 2,048-token context near the production input ceiling.",
        sessions = CONTEXT_SEGMENTS.chunked(10),
        expectation = EnrichmentFixtureExpectation(
            dreamCount = 3,
            dreamKinds = listOf(
                EnrichedDreamKind.DREAM,
                EnrichedDreamKind.FRAGMENT,
                EnrichedDreamKind.FRAGMENT,
            ),
            uncertainDreams = setOf(1, 2),
            requiredRoles = setOf(DreamSourceRole.ADDITION),
            requiredReadingWords = setOf("journey", "roots"),
        ),
    )

    private fun fixture(
        id: String,
        description: String,
        sessions: List<List<String>>,
        expectation: EnrichmentFixtureExpectation,
    ): EnrichmentBenchmarkFixture {
        val nightId = "fixture_${id.replace('-', '_')}"
        val segments = sessions.flatMapIndexed { sessionOrder, session ->
            val words = session.flatMap { sentence ->
                SOURCE_WORD.findAll(sentence).map { match -> match.value }.toList()
            }
            words.mapIndexed { segmentIndex, word ->
                val start = segmentIndex * WORD_DURATION_MILLIS
                NightTranscriptSegment(
                    nightId = nightId,
                    sessionId = "session_${sessionOrder + 1}",
                    sessionOrder = sessionOrder,
                    transcriptAttempt = 1,
                    segmentIndex = segmentIndex,
                    sourceStartMillis = start,
                    sourceEndMillis = start + WORD_DURATION_MILLIS,
                    text = word,
                    narrationStartedAtEpochMillis = 1_000_000L + sessionOrder * 60_000L,
                    narrationStartedUtcOffsetSeconds = -18_000,
                )
            }
        }
        return EnrichmentBenchmarkFixture(
            id = id,
            description = description,
            input = OrderedNightTranscript.create(nightId, segments),
            expectation = expectation,
        )
    }

}

private val WORD = Regex("[a-z0-9]+(?:'[a-z0-9]+)?")
private val SOURCE_WORD = Regex("[A-Za-z0-9]+(?:'[A-Za-z0-9]+)?")
private val SENTENCE_ENDINGS = setOf('.', '!', '?')
private const val WORD_DURATION_MILLIS = 400L
