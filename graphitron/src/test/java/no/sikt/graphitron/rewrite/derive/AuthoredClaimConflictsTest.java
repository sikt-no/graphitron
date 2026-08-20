package no.sikt.graphitron.rewrite.derive;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.rewrite.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store-backed home of the directive mutual-exclusivity rule: the conflict fixtures that
 * used to assert builder tombstones (three conflict enums in {@code GraphitronSchemaBuilderTest})
 * now capture into a fact store and assert the violations {@link AuthoredClaimConflicts}
 * projects from the {@code intent_authored_claim_conflict} view, message-identical to what the
 * deleted detector sites produced. Since the cutover this class is also the view's registered
 * {@code DERIVED} agreement anchor: every fixture's expectation is a hand-written message the
 * view does not produce, so the anchor never collapses into the view compared against a
 * projection of itself (the shadow that proved the flip retired with the Java reduction it
 * shadowed). Beside the migrated fixtures sit the anchor that keeps the claim views' lookup arm
 * honest against its walk-side twin ({@code LookupFacts.triggersFor}), the sibling-graph scoping
 * guard, the build-error consumer's population pin, the undecoded presence-arm fallbacks, and the
 * classifier vocabulary round trip.
 *
 * <p>What the two claim views return given rows is not asked here. That is the relations' own
 * algebra, their arm pairs, their per-position masks and their ordinal collapses, and it lives in
 * the module whose DDL declares them, in
 * {@code no.sikt.graphitron.model.intent.AuthoredClaimTest}, against a store seeded row by row.
 * Every fixture below is a real capture of real SDL for the same reason the split was worth making:
 * what stands here is that an author's schema reaches those relations in the shape the rule reads,
 * and that the report a consumer meets is minted from what it finds.
 */
@PipelineTier
class AuthoredClaimConflictsTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String EXTERNAL_FIELD_STUB = "no.sikt.graphitron.rewrite.TestExternalFieldStub";

    @TempDir
    Path tmp;

    // ===== The migrated conflict fixtures =====

    @Test
    void tableAndErrorMintOneTypeViolation() {
        var sdl = """
            type Film @table(name: "film") @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) { title: String }
            type Query { film: Film }
            """;
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        var v = violations.getFirst();
        assertThat(v.coordinate()).isEqualTo("Film");
        assertThat(v.message()).isEqualTo("Type 'Film': @table, @error are mutually exclusive");
        assertThat(v.location()).isNotNull();
        assertThat(v.location().getSourceName()).endsWith("fixture.graphqls");
    }

    @Test
    void serviceAndExternalFieldMintOneFieldViolation() {
        var sdl = """
            type Film @table(name: "film") {
                title: String
                    @service(service: {className: "%s", method: "get"})
                    @externalField(reference: {className: "%s", method: "rating"})
            }
            type Query { film: Film }
            """.formatted(SERVICE_STUB, EXTERNAL_FIELD_STUB);
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        var v = violations.getFirst();
        assertThat(v.coordinate()).isEqualTo("Film.title");
        assertThat(v.message()).isEqualTo("Field 'Film.title': @service, @externalField are mutually exclusive");
        assertThat(v.location()).isNotNull();
        assertThat(v.location().getSourceName()).endsWith("fixture.graphqls");
    }

    @Test
    void serviceAndNodeIdMintOneFieldViolation() {
        var sdl = """
            type Film @table(name: "film") {
                id: String @service(service: {className: "%s", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """.formatted(SERVICE_STUB);
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().message())
            .isEqualTo("Field 'Film.id': @service, @nodeId are mutually exclusive");
    }

    @Test
    void serviceAndLookupKeyMintOneFieldViolation() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film
                    @service(service: {className: "%s", method: "get"})
            }
            """.formatted(SERVICE_STUB);
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().message())
            .isEqualTo("Field 'Query.film': @service, @lookupKey are mutually exclusive");
    }

    @Test
    void serviceAndMutationMintOneFieldViolation() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilm: Film
                    @service(service: {className: "%s", method: "run"})
                    @mutation(typeName: INSERT)
            }
            """.formatted(SERVICE_STUB);
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        var v = violations.getFirst();
        assertThat(v.message())
            .isEqualTo("Field 'Mutation.createFilm': @service, @mutation are mutually exclusive");
        // The typed directives list survives the coordinate prefix wrap (the near-miss consumers
        // group and count rejections per directive without re-parsing prose).
        assertThat(v.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(((Rejection.InvalidSchema.DirectiveConflict) v.rejection()).directives())
            .containsExactly("service", "mutation");
    }

    @Test
    void serviceAndRoutineMintOneFieldViolationAtEveryPosition() {
        // The child field, the root single-node field, and the root multi-node chain: the chain
        // interception now classifies the routine shapes for real, and the conflict surfaces from
        // the claim views at all three positions instead of a per-position detector tombstone.
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") {
                firstName: String
                films(minLength: Int!): [Film!]
                    @service(service: {className: "%s", method: "getFilms"})
                    @routine(name: "films_for_actor", argMapping: "pMinLength: minLength")
            }
            type Query {
                actor: Actor
                films(actorId: Int!): [Film!]!
                    @service(service: {className: "%s", method: "getFilms"})
                    @routine(name: "films_for_actor", argMapping: "pActorId: actorId")
                chained(actorId: Int!): [Film!]!
                    @service(service: {className: "%s", method: "getFilms"})
                    @routine(name: "films_for_actor", argMapping: "pActorId: actorId")
                    @reference(path: [{table: "film"}])
            }
            """.formatted(SERVICE_STUB, SERVICE_STUB, SERVICE_STUB);
        var violations = detect(sdl);
        assertThat(violations)
            .extracting(ValidationError::message)
            .containsExactly(
                "Field 'Actor.films': @service, @routine are mutually exclusive",
                "Field 'Query.chained': @service, @routine are mutually exclusive",
                "Field 'Query.films': @service, @routine are mutually exclusive");
    }

    @Test
    void routineWithLookupKeyMintsThePinnedDeferral() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film @routine(name: "film_fn")
            }
            """;
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        var v = violations.getFirst();
        assertThat(v.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(v.message()).isEqualTo(
            "Field 'Query.film': @routine with @lookupKey on a root field classifies but does not emit yet");
    }

    @Test
    void threeClaimsNameEveryClaimInFixedOrder() {
        // The routine and lookup pair alone defers; a third claim beside them conflicts, and the
        // violation names all three (the pair's deferral never shadows a conflict).
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film
                    @service(service: {className: "%s", method: "get"})
                    @routine(name: "film_fn")
            }
            """.formatted(SERVICE_STUB);
        var violations = detect(sdl);
        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().message())
            .isEqualTo("Field 'Query.film': @service, @lookupKey, @routine are mutually exclusive");
    }

    // ===== The build-error consumer's population =====

    /**
     * Where the two consumers of one total relation disagree. The view holds every authored
     * contradiction; this consumer mints only inside the classification domain, because only the
     * emitted surface can fail a build. So a conflicted coordinate on a type no field reaches is a
     * row (the editor's arm reads it, which is where an author most needs the signal) and not a
     * violation.
     */
    @Test
    void theBuildErrorPopulationIsTheClassificationDomain() {
        var sdl = """
            type Film @table(name: "film") {
                id: String @service(service: {className: "%s", method: "get"}) @nodeId
            }
            type Unreached @table(name: "actor") {
                id: String @service(service: {className: "%s", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """.formatted(SERVICE_STUB, SERVICE_STUB);
        withCapturedStore(tmp, sdl, dsl -> {
            assertThat(dsl.selectDistinct(INTENT_AUTHORED_CLAIM_CONFLICT.TYPE_NAME)
                .from(INTENT_AUTHORED_CLAIM_CONFLICT)
                .where(INTENT_AUTHORED_CLAIM_CONFLICT.GRAPH_NAME.eq(GRAPH))
                .fetchSet(0, String.class))
                .as("the relation is total over the authored claims, population or not")
                .containsExactlyInAnyOrder("Film", "Unreached");
            assertThat(dsl.fetchCount(INTENT_TYPE_DOMAIN,
                INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(GRAPH).and(INTENT_TYPE_DOMAIN.TYPE_NAME.eq("Unreached"))))
                .as("the unreached type is outside the classification domain")
                .isZero();
            assertThat(AuthoredClaimConflicts.detect(dsl, GRAPH).violations())
                .extracting(ValidationError::coordinate)
                .as("only the domain member's conflict can fail a build")
                .containsExactly("Film.id");
        });
    }

    @Test
    void siblingGraphConflictsDoNotLeak() {
        var conflicted = """
            type Film @table(name: "film") {
                id: String @service(service: {className: "%s", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """.formatted(SERVICE_STUB);
        var clean = """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """;
        try (var store = CapturedStore.of(tmp, "own", clean).andGraph("sibling", conflicted)) {
            assertThat(AuthoredClaimConflicts.detect(store.dsl(), "own").violations())
                .as("the sibling graph's conflict must not surface in this graph's run, the two "
                    + "graphs' domains holding the same type name")
                .isEmpty();
            assertThat(AuthoredClaimConflicts.detect(store.dsl(), "sibling").violations())
                .hasSize(1);
        }
    }

    // ===== The undecoded presence arms =====

    /**
     * That a schema too broken to decode still reports its conflicts. {@code @mutation} without its
     * required verb and {@code @routine} without its required name never assemble, so capture reads
     * the raw registry and writes no semantic row; the claim views' presence arms keep the
     * coordinates claiming and the violations arrive as they would from a decoded pair. Both
     * coordinates sit on root operation types, so the domain the consumer joins holds them without
     * any walked model having to be built from a schema this broken.
     *
     * <p>What those arms return given such rows is the claim views' own question and is asked in
     * {@code no.sikt.graphitron.model.intent.AuthoredClaimTest}; what stands here is that a real
     * capture of a real broken schema reaches that state and that the report is minted from it.
     */
    @Test
    void declinedDecodesStillConflictThroughThePresenceArms() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                broken: String @service(service: {className: "%s", method: "get"}) @routine
            }
            type Mutation {
                createFilm: Film @service(service: {className: "%s", method: "run"}) @mutation
            }
            """.formatted(SERVICE_STUB, SERVICE_STUB);
        withCapturedStore(tmp, sdl, dsl -> {
            assertThat(AuthoredClaimConflicts.detect(dsl, GRAPH).violations())
                .extracting(ValidationError::message)
                .containsExactly(
                    "Field 'Mutation.createFilm': @service, @mutation are mutually exclusive",
                    "Field 'Query.broken': @service, @routine are mutually exclusive");
        });
    }

    // ===== The claim payload =====

    @Test
    void conflictClaimsCarryTheirDecodedSlotFacts() {
        // The conflicted-projection contract at the verdict grain: the claims survive the
        // conflict with their own decoded slot facts, so a broken DELETE mutation still reports
        // its verb and intended table. Claims arrive in AuthoredClaim declaration order.
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                deleteFilm(filmId: Int): ID
                    @service(service: {className: "%s", method: "run"})
                    @mutation(typeName: DELETE, table: "film")
            }
            """.formatted(SERVICE_STUB);
        var detection = detection(sdl);
        assertThat(detection.fieldConflicts()).hasSize(1);
        var conflict = detection.fieldConflicts().getFirst();
        assertThat(conflict.coordinate()).isEqualTo("Mutation.deleteFilm");
        assertThat(conflict.rejection().message()).isEqualTo("@service, @mutation are mutually exclusive");
        assertThat(conflict.claims()).hasSize(2);

        var service = (FieldClaim.Service) conflict.claims().get(0);
        assertThat(service.className()).isEqualTo(SERVICE_STUB);
        assertThat(service.method()).isEqualTo("run");
        assertThat(service.trigger()).isEqualTo("service");
        assertThat(service.decoded()).isTrue();
        assertThat(service.location()).isNotNull();

        var mutation = (FieldClaim.Mutation) conflict.claims().get(1);
        assertThat(mutation.operation()).isEqualTo("DELETE");
        assertThat(mutation.tableRef()).isEqualTo("film");
        assertThat(mutation.trigger()).isEqualTo("mutation");
        assertThat(mutation.decoded()).isTrue();
        assertThat(mutation.location()).isNotNull();
    }

    @Test
    void mutationTableSlotIsTheDirectivesOwnArgumentOnly() {
        // The table slot is graphitron_mutation.table_ref and nothing more: the write-target
        // precedence keeps its single producer in the classification walk
        // (MutationInputResolver.resolveDmlWriteTableRef), so the claim never asserts a table
        // resolved through the input argument's @table binding or the return type.
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmKey @table(name: "film") { filmId: Int }
            type Query { x: String }
            type Mutation {
                deleteFilm(input: FilmKey!): ID
                    @service(service: {className: "%s", method: "run"})
                    @mutation(typeName: DELETE)
            }
            """.formatted(SERVICE_STUB);
        var detection = detection(sdl);
        assertThat(detection.fieldConflicts()).hasSize(1);
        var mutation = (FieldClaim.Mutation) detection.fieldConflicts().getFirst().claims().get(1);
        assertThat(mutation.operation()).isEqualTo("DELETE");
        assertThat(mutation.tableRef())
            .as("no invented resolution rung: the input's @table binding must not surface as the claim's table")
            .isNull();
    }

    @Test
    void deferredPairIsAVerdictButNeverAFieldConflict() {
        // The recognised routine-plus-lookup pair reduces to the typed deferral; the verdict arm
        // is chosen by the reduction's own output type, and only Conflict arms reach the
        // projection overlay through fieldConflicts().
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film @routine(name: "film_fn")
            }
            """;
        var detection = detection(sdl);
        assertThat(detection.fieldVerdicts()).hasSize(1);
        assertThat(detection.fieldVerdicts().getFirst())
            .isInstanceOf(AuthoredClaimConflicts.FieldVerdict.Deferred.class);
        assertThat(detection.fieldConflicts())
            .as("a deferral is not a conflict; the projection overlay must not see it")
            .isEmpty();
    }

    @Test
    void routineChainIsOneClaimCarryingEveryStep() {
        // The repeatable directive's whole chain is one claim: the claims list's cardinality is
        // the conflict signal, so two applications must never read as
        // routine-conflicting-with-routine. The steps survive as the claim's slot facts, in
        // application-ordinal order.
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film]
                    @service(service: {className: "%s", method: "run"})
                    @routine(name: "first_fn")
                    @routine(name: "second_fn")
            }
            """.formatted(SERVICE_STUB);
        var detection = detection(sdl);
        assertThat(detection.fieldConflicts()).hasSize(1);
        var conflict = detection.fieldConflicts().getFirst();
        assertThat(conflict.coordinate()).isEqualTo("Query.films");
        assertThat(conflict.rejection().message()).isEqualTo("@service, @routine are mutually exclusive");
        assertThat(conflict.claims()).hasSize(2);
        var routine = (FieldClaim.Routine) conflict.claims().get(1);
        assertThat(routine.routineRefs()).containsExactly("first_fn", "second_fn");
        assertThat(routine.decoded()).isTrue();
    }

    @Test
    void interleavedReferenceNeverClaimsAndNeverSplitsTheChain() {
        // @reference contributes hops to the field's travel chain but has no claim-view arm, so
        // @routine @reference @routine is still one ROUTINE claim carrying both steps: the hop
        // can neither appear as a conflict party nor split the chain into rival-looking claims.
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film]
                    @service(service: {className: "%s", method: "run"})
                    @routine(name: "first_fn")
                    @reference(path: [{key: "film_language_id_fkey"}])
                    @routine(name: "second_fn")
            }
            """.formatted(SERVICE_STUB);
        var detection = detection(sdl);
        assertThat(detection.fieldConflicts()).hasSize(1);
        var conflict = detection.fieldConflicts().getFirst();
        assertThat(conflict.rejection().message()).isEqualTo("@service, @routine are mutually exclusive");
        assertThat(conflict.claims()).hasSize(2);
        var routine = (FieldClaim.Routine) conflict.claims().get(1);
        assertThat(routine.routineRefs()).containsExactly("first_fn", "second_fn");
    }

    @Test
    void presenceArmClaimsCarryNoSlotFacts() {
        // A declined decode still claims (the presence arm), but its slot facts are absent and
        // the claim says so through decoded=false; consumers render the honest gap instead of
        // an invented payload.
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilm: Film @service(service: {className: "%s", method: "run"}) @mutation
            }
            """.formatted(SERVICE_STUB);
        withCapturedStore(tmp, sdl, dsl -> {
            var conflicts = AuthoredClaimConflicts.detect(dsl, GRAPH).fieldConflicts();
            assertThat(conflicts).hasSize(1);
            var mutation = (FieldClaim.Mutation) conflicts.getFirst().claims().get(1);
            assertThat(mutation.decoded()).isFalse();
            assertThat(mutation.operation()).isNull();
            assertThat(mutation.tableRef()).isNull();
        });
    }

    // ===== The agreement anchor =====

    @Test
    void lookupArmAgreesWithTheWalkedTriggerPredicate() {
        // The trigger predicate has two homes until the arm-by-arm migration: the walk's
        // LookupFacts.triggersFor and the view's lookup arm. This anchor binds them over the
        // trigger populations (direct argument, input field, transitive closure, a cyclic input
        // pair the path guard must terminate on, and an untouched control).
        var sdl = """
            type Language @table(name: "language") { name: String }
            input LookupInput { language_id: Int @lookupKey }
            input LookupOuter { nested: LookupInput }
            input CycleA { partner: CycleB, key: Int @lookupKey }
            input CycleB { back: CycleA }
            type Query {
                direct(language_id: [Int] @lookupKey): [Language!]!
                byInput(in: LookupInput): [Language!]!
                byOuter(in: LookupOuter): [Language!]!
                byCycle(in: CycleB): [Language!]!
                untouched: [Language!]!
            }
            """;
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var nodes = TestSchemaHelper.nodeDeclaration();
        var facts = GatheredFacts.gather(bundle.assembled(), (s, v) -> SchemaReachability.walk(s, nodes, v));
        var triggered = bundle.assembled().getQueryType().getFieldDefinitions().stream()
            .filter(f -> facts.lookup().triggersFor(f))
            .map(GraphQLFieldDefinition::getName)
            .collect(Collectors.toSet());
        assertThat(triggered).containsExactlyInAnyOrder("direct", "byInput", "byOuter", "byCycle");

        withCapturedStore(tmp, sdl, dsl -> {
            var claimed = dsl.selectDistinct(INTENT_AUTHORED_FIELD_CLAIM.FIELD_NAME)
                .from(INTENT_AUTHORED_FIELD_CLAIM)
                .where(INTENT_AUTHORED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH),
                    INTENT_AUTHORED_FIELD_CLAIM.TYPE_NAME.eq("Query"),
                    INTENT_AUTHORED_FIELD_CLAIM.CLASSIFIER.eq("LOOKUP_KEY"))
                .fetchSet(INTENT_AUTHORED_FIELD_CLAIM.FIELD_NAME);
            assertThat(claimed).isEqualTo(triggered);
        });
    }

    // ===== The classifier vocabulary =====

    @Test
    void everyClaimKindRoundTripsThroughTheVocabulary() {
        // One fixture exercising every claim arm once, at non-conflicting coordinates. Every
        // classifier literal the views produce decodes, and together they cover the whole enum,
        // which is the two-directional vocabulary agreement: a view arm added without an enum
        // value fails the decode, an enum value added without an arm fails the coverage.
        var sdl = """
            type Film @table(name: "film") {
                title: String @externalField(reference: {className: "%s", method: "rating"})
                id: String @nodeId
                other: String @service(service: {className: "%s", method: "get"})
            }
            type Failure @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) { message: String }
            type Query {
                film(id: ID @lookupKey): Film
                routed: [Film] @routine(name: "film_fn")
            }
            type Mutation {
                createFilm: Film @mutation(typeName: INSERT)
            }
            """.formatted(EXTERNAL_FIELD_STUB, SERVICE_STUB);
        withCapturedStore(tmp, sdl, dsl -> {
            var decoded = EnumSet.noneOf(AuthoredClaim.class);
            dsl.selectDistinct(INTENT_AUTHORED_FIELD_CLAIM.CLASSIFIER).from(INTENT_AUTHORED_FIELD_CLAIM)
                .where(INTENT_AUTHORED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH))
                .fetch(r -> decoded.add(AuthoredClaim.fromClassifier(r.value1())));
            dsl.selectDistinct(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER).from(INTENT_AUTHORED_TYPE_CLAIM)
                .where(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME.eq(GRAPH))
                .fetch(r -> decoded.add(AuthoredClaim.fromClassifier(r.value1())));
            assertThat(decoded).isEqualTo(EnumSet.allOf(AuthoredClaim.class));
        });
    }

    // ===== Helpers =====

    /** Captures {@code sdl} and runs the detection over the domain the capture derived. */
    private List<ValidationError> detect(String sdl) {
        return detection(sdl).violations();
    }

    /** {@link #detect}, keeping the whole typed {@code Detection} product. */
    private AuthoredClaimConflicts.Detection detection(String sdl) {
        try (var store = CapturedStore.of(tmp, sdl)) {
            return AuthoredClaimConflicts.detect(store.dsl(), GRAPH);
        }
    }

}
