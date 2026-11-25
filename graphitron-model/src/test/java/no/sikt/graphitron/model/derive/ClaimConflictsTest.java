package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.Rejection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build's report of the directive mutual-exclusivity rule: the conflict fixtures capture into a
 * fact store and assert the violations {@link ClaimConflicts} reads from the claim defects and the
 * claims under them, message-identical to what a build has always reported. Every expectation is a
 * hand-written message the store does not hold, the message being the reader's aggregation of the
 * directives that wrote the claims under the defect.
 * The conflicts the {@code defects/claims-conflict} document holds are read from its graph; a
 * fixture no document produces stays inline: a {@code @service} beside a {@code @nodeId}, the
 * {@code @service} and {@code @routine} pair at the root positions, three claims at one field, and
 * beside them the build-error consumer's population pin, the sibling-graph scoping guard and the
 * undecoded applications.
 *
 * <p>What the claims and the defects hold is not asked here. That is the relations' own rule, and it
 * lives in the module whose DDL declares them, in the {@code producer-claim}, {@code binding-claim},
 * {@code type-claim} and {@code claims-conflict} documents under graphitron-model's
 * {@code src/test/resources/facts}. What
 * stands here is that an author's schema reaches those relations and that the report a build meets
 * is composed from what they hold.
 */
class ClaimConflictsTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String EXTERNAL_FIELD_STUB = "no.sikt.graphitron.rewrite.TestExternalFieldStub";

    @TempDir
    Path tmp;

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** The document whose conflicts the read cases are about. */
    private static final String CONFLICTS = "defects/claims-conflict";

    // ===== The migrated conflict fixtures =====

    @Test
    void tableAndErrorMintOneTypeViolation() {
        var v = conflictAt("Failure");
        assertThat(v.message()).isEqualTo("Type 'Failure': @table, @error are mutually exclusive");
        assertThat(v.location()).isNotNull();
        assertThat(v.location().getSourceName()).endsWith("claims-conflict.graphqls");
    }

    @Test
    void serviceAndExternalFieldMintOneFieldViolation() {
        var v = conflictAt("Film.serviceAndReader");
        assertThat(v.message())
            .isEqualTo("Field 'Film.serviceAndReader': @service, @externalField are mutually exclusive");
        assertThat(v.location()).isNotNull();
        assertThat(v.location().getSourceName()).endsWith("claims-conflict.graphqls");
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
        assertThat(conflictAt("Query.serviceAndKey").message())
            .isEqualTo("Field 'Query.serviceAndKey': @service, @lookupKey are mutually exclusive");
    }

    @Test
    void serviceAndMutationMintOneFieldViolation() {
        var v = conflictAt("Mutation.writeAndService");
        assertThat(v.message())
            .isEqualTo("Field 'Mutation.writeAndService': @service, @mutation are mutually exclusive");
        // The typed directives list survives the coordinate prefix wrap (the near-miss consumers
        // group and count rejections per directive without re-parsing prose).
        assertThat(v.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(((Rejection.InvalidSchema.DirectiveConflict) v.rejection()).directives())
            .containsExactly("service", "mutation");
    }

    @Test
    void serviceAndRoutineMintOneFieldViolationAtEveryPosition() {
        // The child field, the root single-node field, and the root multi-node chain: the conflict
        // surfaces from the claims at all three positions.
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
        var v = conflictAt("Query.routineAndKey");
        assertThat(v.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(v.message()).isEqualTo("Field 'Query.routineAndKey': @routine with @lookupKey on one"
            + " field classifies but does not emit yet");
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
     * A claim defect fails the build wherever it is written: a conflicted coordinate on a type no
     * field reaches is a violation as much as one on a reached type.
     */
    @Test
    void everyClaimDefectFailsTheBuild() {
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
            assertThat(dsl.selectDistinct(GRAPHITRON_ENTRY_DEFECT_SITE.TYPE_NAME)
                .from(GRAPHITRON_ENTRY_DEFECT_SITE)
                .where(GRAPHITRON_ENTRY_DEFECT_SITE.GRAPH_NAME.eq(GRAPH),
                    GRAPHITRON_ENTRY_DEFECT_SITE.CODE.eq(ClaimConflicts.CONFLICT))
                .fetchSet(0, String.class))
                .as("the defects are total over the authored claims, population or not")
                .containsExactlyInAnyOrder("Film", "Unreached");
            assertThat(ClaimConflicts.detect(dsl, GRAPH).violations())
                .extracting(ValidationError::coordinate)
                .containsExactly("Film.id", "Unreached.id");
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
            assertThat(ClaimConflicts.detect(store.dsl(), "own").violations())
                .as("the sibling graph's conflict must not surface in this graph's run, the two "
                    + "graphs holding the same type name")
                .isEmpty();
            assertThat(ClaimConflicts.detect(store.dsl(), "sibling").violations())
                .hasSize(1);
        }
    }

    // ===== The undecoded applications =====

    /**
     * That a schema too broken to decode still reports its conflicts. {@code @mutation} without its
     * required verb and {@code @routine} without its required name never assemble, so capture writes
     * no semantic row; the claims are the applications, so the coordinates still claim and the
     * violations arrive as they would from a decoded pair. The @mutation's missing verb is a defect
     * of its own beside the conflict.
     *
     * <p>What stands here is that a real capture of a real broken schema reaches that state and that
     * the report is minted from it.
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
            assertThat(ClaimConflicts.detect(dsl, GRAPH).violations())
                .extracting(ValidationError::message)
                .containsExactly(
                    "Field 'Mutation.createFilm': @service, @mutation are mutually exclusive",
                    "Field 'Query.broken': @service, @routine are mutually exclusive");
            assertThat(dsl.select(GRAPHITRON_ENTRY_DEFECT_SITE.COORDINATE)
                .from(GRAPHITRON_ENTRY_DEFECT_SITE)
                .where(GRAPHITRON_ENTRY_DEFECT_SITE.GRAPH_NAME.eq(GRAPH),
                    GRAPHITRON_ENTRY_DEFECT_SITE.CODE.eq("MUTATION_WITHOUT_VERB"))
                .fetch(GRAPHITRON_ENTRY_DEFECT_SITE.COORDINATE))
                .as("a @mutation with no verb is a defect of its own, at the @mutation")
                .containsExactly("Mutation.createFilm");
        });
    }


    // ===== Helpers =====

    /** The one violation the claims-conflict document's graph reports at {@code coordinate}. */
    private static ValidationError conflictAt(String coordinate) {
        List<ValidationError> at = FACTS.read(CONFLICTS, graph ->
            ClaimConflicts.detect(graph.handle().dsl(), graph.handle().graphName()).violations()
                .stream().filter(v -> coordinate.equals(v.coordinate())).toList());
        assertThat(at).as("violations at %s", coordinate).hasSize(1);
        return at.getFirst();
    }

    /** Captures {@code sdl} and runs the detection over what the capture wrote. */
    private List<ValidationError> detect(String sdl) {
        return detection(sdl).violations();
    }

    /** {@link #detect}, keeping the whole typed {@code Detection} product. */
    private ClaimConflicts.Detection detection(String sdl) {
        try (var store = CapturedStore.of(tmp, sdl)) {
            return ClaimConflicts.detect(store.dsl(), GRAPH);
        }
    }

}
