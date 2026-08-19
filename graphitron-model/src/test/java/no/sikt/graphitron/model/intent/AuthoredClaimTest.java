package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedError;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalField;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldDirective;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedInputFieldLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedTypeDirective;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_authored_field_claim} and {@code intent_authored_type_claim} return: which
 * classification each coordinate's author has claimed, one row per claim, in a vocabulary the
 * reading side decodes. Every claiming directive contributes a pair of arms, and the pair is the
 * shape worth naming first. The decoded arm reads the semantic relation capture wrote when the
 * directive's arguments made sense; the presence arm reads the raw application and fires only where
 * no semantic row stands beside it, so an application whose decode declined still claims. A
 * coordinate carrying both rows is one claim, not two, and that is an anti-join rather than a
 * grouping.
 *
 * <p>Beside the arms sit the position masks, which are the walk's per-position gates transcribed:
 * {@code @service} claims anywhere, {@code @externalField} and {@code @nodeId} nowhere on a root,
 * {@code @routine} anywhere but Mutation and Subscription, {@code @lookupKey} on Query alone,
 * {@code @mutation} on Mutation alone, and neither type-grain claim on a root name at all. Each is
 * stated here as the same coordinate seeded at several positions, which is the only arrangement
 * that tells a mask apart from an empty fixture.
 *
 * <p>Two of the rules answer with a position rather than with a row, and those cases assert the
 * line. A repeatable directive applied twice collapses to its first application, and a field's
 * several lookup-marked arguments collapse to the first argument, both by ordinal rather than by
 * anything a row's own contents carry; seeding the later ordinal at the earlier line is what makes
 * the two distinguishable. The {@code @lookupKey} arm answers with no position at all where the
 * claim came through the input closure, the marked application then sitting on a remote input field
 * rather than on this coordinate, and that null is asserted for what it is.
 *
 * <p>The closure itself is the one recursive thing here: an argument claims when its named type
 * carries a lookup marker, or names an input object that reaches one. Three of its edges only show
 * up under inputs a fixture has to state deliberately, an input object cycle, which must terminate
 * rather than answer, and an object type in the middle of a chain, which must stop it.
 *
 * <p>What the walk makes of these claims, and which pairs of them are mutually exclusive, is a
 * different question with a different home: {@code intent_authored_claim_conflict} reads these
 * relations and is pinned by {@code no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest},
 * whose expectations are the report messages a consumer meets.
 */
class AuthoredClaimTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";

    private static final String SERVICE_CLASS = "app.Service";
    private static final String EXTERNAL_CLASS = "app.External";

    /** The three names the masks read as roots, plus the child type everything else hangs on. */
    private static final List<String> ROOTS = List.of("Query", "Mutation", "Subscription");

    // ===== The arm pairs =====

    @Test
    void everyClaimingDirectiveAnswersWithItsOwnClassifierAndTrigger() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "byService");
            seedService(dsl, GRAPH, "Film", "byService", SERVICE_CLASS, "get");
            seedField(dsl, GRAPH, "Film", "byExternal");
            seedExternalField(dsl, GRAPH, "Film", "byExternal", EXTERNAL_CLASS, "rating");
            seedField(dsl, GRAPH, "Film", "byNodeId");
            seedNodeId(dsl, GRAPH, "Film", "byNodeId");
            seedField(dsl, GRAPH, "Query", "byRoutine");
            seedRoutine(dsl, GRAPH, "Query", "byRoutine", "film_fn");
            seedField(dsl, GRAPH, "Query", "byLookup");
            seedArgument(dsl, GRAPH, "Query", "byLookup", "id", "ID");
            seedArgumentLookupKey(dsl, GRAPH, "Query", "byLookup", "id");
            seedField(dsl, GRAPH, "Mutation", "byMutation");
            seedMutation(dsl, GRAPH, "Mutation", "byMutation", "INSERT");

            assertThat(claims(dsl, "Film", "byService")).containsExactly("SERVICE service true");
            assertThat(claims(dsl, "Film", "byExternal")).containsExactly("EXTERNAL_FIELD externalField true");
            assertThat(claims(dsl, "Film", "byNodeId")).containsExactly("NODE_ID nodeId true");
            assertThat(claims(dsl, "Query", "byRoutine")).containsExactly("ROUTINE routine true");
            assertThat(claims(dsl, "Query", "byLookup")).containsExactly("LOOKUP_KEY lookupKey true");
            assertThat(claims(dsl, "Mutation", "byMutation")).containsExactly("MUTATION mutation true");
        });
    }

    /**
     * The state a schema too broken to decode reaches: {@code @mutation} without its verb and
     * {@code @routine} without its name never assemble, so capture writes the raw application and no
     * semantic row. The coordinate keeps claiming, and says through {@code decoded} that its payload
     * is absent rather than empty.
     */
    @Test
    void anApplicationWhoseDecodeDeclinedStillClaimsThroughItsPresenceArm() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "brokenService");
            seedFieldDirective(dsl, GRAPH, "Film", "brokenService", "service");
            seedField(dsl, GRAPH, "Film", "brokenExternal");
            seedFieldDirective(dsl, GRAPH, "Film", "brokenExternal", "externalField");
            seedField(dsl, GRAPH, "Film", "brokenNodeId");
            seedFieldDirective(dsl, GRAPH, "Film", "brokenNodeId", "nodeId");
            seedField(dsl, GRAPH, "Query", "brokenRoutine");
            seedFieldDirective(dsl, GRAPH, "Query", "brokenRoutine", "routine");
            seedField(dsl, GRAPH, "Mutation", "brokenMutation");
            seedFieldDirective(dsl, GRAPH, "Mutation", "brokenMutation", "mutation");

            assertThat(claims(dsl, "Film", "brokenService")).containsExactly("SERVICE service false");
            assertThat(claims(dsl, "Film", "brokenExternal")).containsExactly("EXTERNAL_FIELD externalField false");
            assertThat(claims(dsl, "Film", "brokenNodeId")).containsExactly("NODE_ID nodeId false");
            assertThat(claims(dsl, "Query", "brokenRoutine")).containsExactly("ROUTINE routine false");
            assertThat(claims(dsl, "Mutation", "brokenMutation")).containsExactly("MUTATION mutation false");
        });
    }

    /**
     * The anti-join, and the key it stands on. Every arm pair is stated twice on one type: once with
     * both rows, where the decoded arm answers alone, and once with the raw row only, where the
     * presence arm still answers. The second coordinate is what an anti-join keyed on less than the
     * whole coordinate would silence.
     */
    @Test
    void aDecodedApplicationSilencesItsOwnPresenceArmAndNoOtherCoordinates() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "both");
            seedService(dsl, GRAPH, "Film", "both", SERVICE_CLASS, "get");
            seedFieldDirective(dsl, GRAPH, "Film", "both", "service");
            seedExternalField(dsl, GRAPH, "Film", "both", EXTERNAL_CLASS, "rating");
            seedFieldDirective(dsl, GRAPH, "Film", "both", "externalField");
            seedNodeId(dsl, GRAPH, "Film", "both");
            seedFieldDirective(dsl, GRAPH, "Film", "both", "nodeId");

            seedField(dsl, GRAPH, "Film", "rawOnly");
            seedFieldDirective(dsl, GRAPH, "Film", "rawOnly", "service");
            seedFieldDirective(dsl, GRAPH, "Film", "rawOnly", "externalField");
            seedFieldDirective(dsl, GRAPH, "Film", "rawOnly", "nodeId");

            seedField(dsl, GRAPH, "Query", "both");
            seedRoutine(dsl, GRAPH, "Query", "both", "film_fn");
            seedFieldDirective(dsl, GRAPH, "Query", "both", "routine");
            seedField(dsl, GRAPH, "Query", "rawOnly");
            seedFieldDirective(dsl, GRAPH, "Query", "rawOnly", "routine");

            seedField(dsl, GRAPH, "Mutation", "both");
            seedMutation(dsl, GRAPH, "Mutation", "both", "INSERT");
            seedFieldDirective(dsl, GRAPH, "Mutation", "both", "mutation");
            seedField(dsl, GRAPH, "Mutation", "rawOnly");
            seedFieldDirective(dsl, GRAPH, "Mutation", "rawOnly", "mutation");

            assertThat(claims(dsl, "Film", "both")).containsExactlyInAnyOrder(
                "SERVICE service true", "EXTERNAL_FIELD externalField true", "NODE_ID nodeId true");
            assertThat(claims(dsl, "Film", "rawOnly")).containsExactlyInAnyOrder(
                "SERVICE service false", "EXTERNAL_FIELD externalField false", "NODE_ID nodeId false");
            assertThat(claims(dsl, "Query", "both")).containsExactly("ROUTINE routine true");
            assertThat(claims(dsl, "Query", "rawOnly")).containsExactly("ROUTINE routine false");
            assertThat(claims(dsl, "Mutation", "both")).containsExactly("MUTATION mutation true");
            assertThat(claims(dsl, "Mutation", "rawOnly")).containsExactly("MUTATION mutation false");
        });
    }

    /**
     * The one directive with no presence arm. {@code @lookupKey} claims from the argument surface
     * rather than from an application on the field, so a raw application on the field itself is not
     * a fallback the arm has: there is no field-grain application for it to fall back from.
     */
    @Test
    void lookupKeyHasNoPresenceArmToFallBackTo() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "raw");
            seedFieldDirective(dsl, GRAPH, "Query", "raw", "lookupKey");

            assertThat(claims(dsl, "Query", "raw")).isEmpty();
        });
    }

    // ===== The position masks =====

    /**
     * The two masked arms beside the one that is not: {@code @service} claims wherever it is
     * written, so a root coordinate carrying all three applications answers with exactly one claim
     * and a child coordinate carrying the same three answers with all of them. Both arms are stated
     * on coordinates of their own, a decoded row otherwise silencing the presence arm before the
     * mask is reached.
     */
    @Test
    void externalFieldAndNodeIdNeverClaimOnARootType() {
        withSeededStore(GRAPH, dsl -> {
            for (var typeName : List.of("Query", "Mutation", "Subscription", "Film")) {
                seedField(dsl, GRAPH, typeName, "decoded");
                seedExternalField(dsl, GRAPH, typeName, "decoded", EXTERNAL_CLASS, "rating");
                seedNodeId(dsl, GRAPH, typeName, "decoded");
                seedService(dsl, GRAPH, typeName, "decoded", SERVICE_CLASS, "get");
                seedField(dsl, GRAPH, typeName, "raw");
                seedFieldDirective(dsl, GRAPH, typeName, "raw", "externalField");
                seedFieldDirective(dsl, GRAPH, typeName, "raw", "nodeId");
                seedFieldDirective(dsl, GRAPH, typeName, "raw", "service");
            }

            for (var root : ROOTS) {
                assertThat(claims(dsl, root, "decoded")).as("%s.decoded", root)
                    .containsExactly("SERVICE service true");
                assertThat(claims(dsl, root, "raw")).as("%s.raw", root)
                    .containsExactly("SERVICE service false");
            }
            assertThat(claims(dsl, "Film", "decoded")).containsExactlyInAnyOrder(
                "SERVICE service true", "EXTERNAL_FIELD externalField true", "NODE_ID nodeId true");
            assertThat(claims(dsl, "Film", "raw")).containsExactlyInAnyOrder(
                "SERVICE service false", "EXTERNAL_FIELD externalField false", "NODE_ID nodeId false");
        });
    }

    @Test
    void routineClaimsEverywhereButOnMutationAndSubscription() {
        withSeededStore(GRAPH, dsl -> {
            for (var typeName : List.of("Query", "Mutation", "Subscription", "Film")) {
                seedField(dsl, GRAPH, typeName, "decoded");
                seedRoutine(dsl, GRAPH, typeName, "decoded", "film_fn");
                seedField(dsl, GRAPH, typeName, "raw");
                seedFieldDirective(dsl, GRAPH, typeName, "raw", "routine");
            }

            assertThat(claims(dsl, "Query", "decoded")).containsExactly("ROUTINE routine true");
            assertThat(claims(dsl, "Film", "decoded")).containsExactly("ROUTINE routine true");
            assertThat(claims(dsl, "Mutation", "decoded")).isEmpty();
            assertThat(claims(dsl, "Subscription", "decoded")).isEmpty();
            assertThat(claims(dsl, "Query", "raw")).containsExactly("ROUTINE routine false");
            assertThat(claims(dsl, "Film", "raw")).containsExactly("ROUTINE routine false");
            assertThat(claims(dsl, "Mutation", "raw")).isEmpty();
            assertThat(claims(dsl, "Subscription", "raw")).isEmpty();
        });
    }

    @Test
    void mutationClaimsOnTheMutationTypeAlone() {
        withSeededStore(GRAPH, dsl -> {
            for (var typeName : List.of("Query", "Mutation", "Subscription", "Film")) {
                seedField(dsl, GRAPH, typeName, "decoded");
                seedMutation(dsl, GRAPH, typeName, "decoded", "INSERT");
                seedField(dsl, GRAPH, typeName, "raw");
                seedFieldDirective(dsl, GRAPH, typeName, "raw", "mutation");
            }

            assertThat(claims(dsl, "Mutation", "decoded")).containsExactly("MUTATION mutation true");
            assertThat(claims(dsl, "Mutation", "raw")).containsExactly("MUTATION mutation false");
            for (var typeName : List.of("Query", "Subscription", "Film")) {
                assertThat(claims(dsl, typeName, "decoded")).as("%s.decoded", typeName).isEmpty();
                assertThat(claims(dsl, typeName, "raw")).as("%s.raw", typeName).isEmpty();
            }
        });
    }

    @Test
    void lookupKeyClaimsOnQueryAlone() {
        withSeededStore(GRAPH, dsl -> {
            for (var typeName : List.of("Query", "Mutation", "Subscription", "Film")) {
                seedField(dsl, GRAPH, typeName, "marked");
                seedArgument(dsl, GRAPH, typeName, "marked", "id", "ID");
                seedArgumentLookupKey(dsl, GRAPH, typeName, "marked", "id");
            }

            assertThat(claims(dsl, "Query", "marked")).containsExactly("LOOKUP_KEY lookupKey true");
            for (var typeName : List.of("Mutation", "Subscription", "Film")) {
                assertThat(claims(dsl, typeName, "marked")).as("%s.marked", typeName).isEmpty();
            }
        });
    }

    // ===== The collapses, which answer with a position =====

    /**
     * A repeatable directive applied twice is one claim carrying the first application's position,
     * on both arms. The later ordinal is seeded at the earlier line, so a rule collapsing on
     * anything but the ordinal answers with a line this fixture can name.
     */
    @Test
    void aRepeatedRoutineIsOneClaimAtItsFirstApplicationsPosition() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "decoded");
            seedRoutine(dsl, GRAPH, "Query", "decoded", 1, "second_fn", 10);
            seedRoutine(dsl, GRAPH, "Query", "decoded", 0, "first_fn", 20);
            seedField(dsl, GRAPH, "Query", "raw");
            seedFieldDirective(dsl, GRAPH, "Query", "raw", "routine", 1, 10);
            seedFieldDirective(dsl, GRAPH, "Query", "raw", "routine", 0, 20);

            assertThat(lines(dsl, "Query", "decoded")).containsExactly(20);
            assertThat(lines(dsl, "Query", "raw")).containsExactly(20);
        });
    }

    /**
     * The same collapse on a field's arguments: several marked ones are one claim, at the first
     * argument's own position rather than at the field's. The marker's line follows the argument it
     * sits on, so the ordinal is what the answer names.
     */
    @Test
    void severalMarkedArgumentsAreOneClaimAtTheFirstArgumentsPosition() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "marked");
            seedArgument(dsl, GRAPH, "Query", "marked", "second", "ID", 1, 10);
            seedArgumentLookupKey(dsl, GRAPH, "Query", "marked", "second", 10);
            seedArgument(dsl, GRAPH, "Query", "marked", "first", "ID", 0, 20);
            seedArgumentLookupKey(dsl, GRAPH, "Query", "marked", "first", 20);

            assertThat(claims(dsl, "Query", "marked")).containsExactly("LOOKUP_KEY lookupKey true");
            assertThat(lines(dsl, "Query", "marked")).containsExactly(20);
        });
    }

    // ===== The lookup closure =====

    /**
     * A field whose argument merely names an input carrying the marker claims too, and the claim
     * carries no position: the application it stands on sits on that remote input field, not here.
     * A field whose arguments name nothing lookup-bearing does not claim at all, which is what
     * separates the closure from a rule that fires on having arguments.
     */
    @Test
    void anArgumentNamingALookupBearingInputClaimsWithoutAPositionOfItsOwn() {
        withSeededStore(GRAPH, dsl -> {
            seedLookupBearingInput(dsl, "LookupInput");
            seedField(dsl, GRAPH, "Query", "byInput");
            seedArgument(dsl, GRAPH, "Query", "byInput", "in", "LookupInput");
            seedField(dsl, GRAPH, "Query", "untouched");
            seedArgument(dsl, GRAPH, "Query", "untouched", "in", "ID");

            assertThat(claims(dsl, "Query", "byInput")).containsExactly("LOOKUP_KEY lookupKey true");
            assertThat(dsl.selectFrom(INTENT_AUTHORED_FIELD_CLAIM)
                .where(INTENT_AUTHORED_FIELD_CLAIM.FIELD_NAME.eq("byInput"))
                .fetchSingle().getSourceName())
                .as("the marked application is on the input's field, so this coordinate has no position to give")
                .isNull();
            assertThat(claims(dsl, "Query", "untouched")).isEmpty();
        });
    }

    /**
     * The closure walks input objects and only input objects. {@code Outer} names the marked input
     * and is one itself, so it carries the marker outward; {@code Wrapper} names it too but is an
     * object, and the chain stops at it. Nothing a compiler emits puts an object type in an
     * argument's position, which is why the negative has to be stated as rows.
     */
    @Test
    void theClosureIsTransitiveThroughInputObjectsAndStopsAtAnythingElse() {
        withSeededStore(GRAPH, dsl -> {
            seedLookupBearingInput(dsl, "LookupInput");

            seedDeclaredType(dsl, GRAPH, "Outer", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Outer", "nested", "LookupInput", false);
            seedDeclaredType(dsl, GRAPH, "Wrapper", "OBJECT");
            seedField(dsl, GRAPH, "Wrapper", "nested", "LookupInput", false);

            seedField(dsl, GRAPH, "Query", "byOuter");
            seedArgument(dsl, GRAPH, "Query", "byOuter", "in", "Outer");
            seedField(dsl, GRAPH, "Query", "byWrapper");
            seedArgument(dsl, GRAPH, "Query", "byWrapper", "in", "Wrapper");

            assertThat(claims(dsl, "Query", "byOuter")).containsExactly("LOOKUP_KEY lookupKey true");
            assertThat(claims(dsl, "Query", "byWrapper")).isEmpty();
        });
    }

    /**
     * Two inputs naming each other, one of them marked. The closure has to answer rather than
     * recurse forever, and the answer is that both are lookup-bearing.
     */
    @Test
    void aCyclicInputPairTerminatesAndBothEndsClaim() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "CycleA", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "CycleB", "INPUT_OBJECT");
            seedType(dsl, GRAPH, "Int", "SCALAR");
            seedField(dsl, GRAPH, "CycleA", "key", "Int", false);
            seedInputFieldLookupKey(dsl, GRAPH, "CycleA", "key");
            seedField(dsl, GRAPH, "CycleA", "partner", "CycleB", false);
            seedField(dsl, GRAPH, "CycleB", "back", "CycleA", false);

            seedField(dsl, GRAPH, "Query", "byA");
            seedArgument(dsl, GRAPH, "Query", "byA", "in", "CycleA");
            seedField(dsl, GRAPH, "Query", "byB");
            seedArgument(dsl, GRAPH, "Query", "byB", "in", "CycleB");

            assertThat(claims(dsl, "Query", "byA")).containsExactly("LOOKUP_KEY lookupKey true");
            assertThat(claims(dsl, "Query", "byB")).containsExactly("LOOKUP_KEY lookupKey true");
        });
    }

    // ===== The type grain =====

    @Test
    void aTypeClaimsTableAndErrorThroughTheSameArmPair() {
        withSeededStore(GRAPH, dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTypeDirective(dsl, GRAPH, "Film", "table");
            seedError(dsl, GRAPH, "Film");
            seedTypeDirective(dsl, GRAPH, "Film", "error");

            seedTypeDirective(dsl, GRAPH, "Ghost", "table");
            seedTypeDirective(dsl, GRAPH, "Ghost", "error");

            assertThat(typeClaims(dsl, "Film")).containsExactlyInAnyOrder(
                "TABLE table true", "ERROR error true");
            assertThat(typeClaims(dsl, "Ghost")).containsExactlyInAnyOrder(
                "TABLE table false", "ERROR error false");
        });
    }

    /**
     * Two stores rather than one, because a decoded row on a root would silence that root's
     * presence arm through the anti-join and the mask would never be reached.
     */
    @Test
    void noTypeClaimLandsOnARootName() {
        withSeededStore(GRAPH, dsl -> {
            for (var root : ROOTS) {
                seedTableBinding(dsl, GRAPH, root, "film");
                seedError(dsl, GRAPH, root);
            }
            assertThat(allTypeClaims(dsl)).as("the decoded arms").isEmpty();
        });
        withSeededStore(GRAPH, dsl -> {
            for (var root : ROOTS) {
                seedTypeDirective(dsl, GRAPH, root, "table");
                seedTypeDirective(dsl, GRAPH, root, "error");
            }
            assertThat(allTypeClaims(dsl)).as("the presence arms, with nothing to silence them").isEmpty();
        });
    }

    /**
     * One directive applied at two sites, which is what a base declaration extended by a second one
     * produces. The claim sits at the type grain either way, and the presence arm collapses the pair
     * to the first application, the later ordinal again seeded at the earlier line.
     */
    @Test
    void aTypeApplicationAtTwoSitesCollapsesToTheFirst() {
        withSeededStore(GRAPH, dsl -> {
            seedTypeDirective(dsl, GRAPH, "Ghost", "table", 1, 10);
            seedTypeDirective(dsl, GRAPH, "Ghost", "table", 0, 20);

            assertThat(typeClaims(dsl, "Ghost")).containsExactly("TABLE table false");
            assertThat(dsl.selectFrom(INTENT_AUTHORED_TYPE_CLAIM)
                .where(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME.eq("Ghost"))
                .fetchSingle().getSourceLine()).isEqualTo(20);
        });
    }

    // ===== The partition =====

    /**
     * The partition and the anti-join's own key in one arrangement: one graph carries the decoded
     * row and its sibling carries the raw application at the same coordinate and the same type. An
     * anti-join blind to the graph would read the first graph's decode as a reason to silence the
     * second graph's claim, which is the direction a partition case cannot state from one graph.
     */
    @Test
    void aGraphClaimsNothingOnItsSiblingsBehalf() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            seedField(dsl, GRAPH, "Film", "x");
            seedService(dsl, GRAPH, "Film", "x", SERVICE_CLASS, "get");
            seedError(dsl, GRAPH, "Film");
            seedField(dsl, OTHER_GRAPH, "Film", "x");
            seedFieldDirective(dsl, OTHER_GRAPH, "Film", "x", "service");
            seedTypeDirective(dsl, OTHER_GRAPH, "Film", "error");

            assertThat(allFieldClaims(dsl))
                .containsExactlyInAnyOrder(GRAPH + " SERVICE true", OTHER_GRAPH + " SERVICE false");
            assertThat(allTypeClaims(dsl))
                .containsExactlyInAnyOrder(GRAPH + " ERROR true", OTHER_GRAPH + " ERROR false");
        });
    }

    // ===== Helpers =====

    /** An input object one of whose fields carries the retired marker: the closure's own seed. */
    private static void seedLookupBearingInput(DSLContext dsl, String typeName) {
        seedDeclaredType(dsl, GRAPH, typeName, "INPUT_OBJECT");
        seedType(dsl, GRAPH, "Int", "SCALAR");
        seedField(dsl, GRAPH, typeName, "key", "Int", false);
        seedInputFieldLookupKey(dsl, GRAPH, typeName, "key");
    }

    /** One coordinate's claims in {@link #GRAPH}: what each says it is and where it came from. */
    private static List<String> claims(DSLContext dsl, String typeName, String fieldName) {
        return dsl.selectFrom(INTENT_AUTHORED_FIELD_CLAIM)
            .where(INTENT_AUTHORED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_AUTHORED_FIELD_CLAIM.TYPE_NAME.eq(typeName))
            .and(INTENT_AUTHORED_FIELD_CLAIM.FIELD_NAME.eq(fieldName))
            .fetch(r -> r.getClassifier() + " " + r.getTrigger() + " " + r.getDecoded());
    }

    /** The same rows' lines alone, for the cases whose answer is which application won. */
    private static List<Integer> lines(DSLContext dsl, String typeName, String fieldName) {
        return dsl.selectFrom(INTENT_AUTHORED_FIELD_CLAIM)
            .where(INTENT_AUTHORED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_AUTHORED_FIELD_CLAIM.TYPE_NAME.eq(typeName))
            .and(INTENT_AUTHORED_FIELD_CLAIM.FIELD_NAME.eq(fieldName))
            .fetch(INTENT_AUTHORED_FIELD_CLAIM.SOURCE_LINE);
    }

    /** {@link #claims} at the type grain. */
    private static List<String> typeClaims(DSLContext dsl, String typeName) {
        return dsl.selectFrom(INTENT_AUTHORED_TYPE_CLAIM)
            .where(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME.eq(typeName))
            .fetch(r -> r.getClassifier() + " " + r.getTrigger() + " " + r.getDecoded());
    }

    /** Every field claim in the store, whichever graph it belongs to. */
    private static List<String> allFieldClaims(DSLContext dsl) {
        return dsl.selectFrom(INTENT_AUTHORED_FIELD_CLAIM)
            .fetch(r -> r.getGraphName() + " " + r.getClassifier() + " " + r.getDecoded());
    }

    /** {@link #allFieldClaims} at the type grain. */
    private static List<String> allTypeClaims(DSLContext dsl) {
        return dsl.selectFrom(INTENT_AUTHORED_TYPE_CLAIM)
            .fetch(r -> r.getGraphName() + " " + r.getClassifier() + " " + r.getDecoded());
    }
}
