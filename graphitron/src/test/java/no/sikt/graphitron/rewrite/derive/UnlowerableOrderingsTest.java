package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.derive.UnlowerableOrderings;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store-backed home of the never-unsorted invariant's honesty half: real SDL captured into a
 * fact store, and the violations {@link UnlowerableOrderings} projects from
 * {@code intent_field_unlowerable_ordering}. This is the tier that says an author's schema reaches
 * the relation in the shape the rule reads, and that the report a consumer meets is minted from what
 * it finds.
 *
 * <p>What the view returns given rows is not asked here. That is the relation's own algebra, its two
 * verdicts, its three availability routes and the absences between them, and it lives in the module
 * whose DDL declares it, in
 * {@code no.sikt.graphitron.model.intent.FieldUnlowerableOrderingTest}, against a store seeded row by
 * row. What stands here is the decode: which {@link Rejection} arm each verdict becomes, the prose it
 * carries, the location it points at, and the one arm whose rejection is deliberately held.
 *
 * <p>A declared ordering on a child multitable read is refused rather than accepted and dropped:
 * the read is one statement per participant, and no branch carries the declaration. A list-shaped
 * root that reaches the classifier's multitable arm lowers the same declaration onto every branch
 * and draws no row.
 */
@PipelineTier
class UnlowerableOrderingsTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    /**
     * The three-implementation multitable interface the report arrived on: each participant binds a
     * table of its own, the interface binds none, so the read is three statements combined on a
     * synthetic key.
     */
    private static final String DOCUMENTS = """
        interface Document { rowId: Int }
        type Film implements Document @table(name: "film") { rowId: Int @field(name: "film_id") }
        type Actor implements Document @table(name: "actor") { rowId: Int @field(name: "actor_id") }
        type Language implements Document @table(name: "language") {
            rowId: Int @field(name: "language_id")
        }
        """;

    // ===== The fan-out verdict, which fails a build at a child multitable coordinate =====
    //
    // Seated on a child field: a list-shaped root that reaches the multitable arm lowers its
    // declaration onto the participant branches and draws no row (the last case of this section).

    /**
     * The reported shape at the coordinate it still fails at. The message states what was
     * declared, why the read cannot carry it, what the rows do instead, and both remedies; an author
     * who meets it needs to know that removing the declaration loses them nothing they currently
     * have.
     */
    @Test
    void aDefaultOrderOnAMultitableChildIsRejectedNamingTheParticipants() {
        var violations = detect(DOCUMENTS + """
            type Store @table(name: "store") {
                documents: [Document!]! @defaultOrder(fields: [{name: "rowId"}])
            }
            type Query { stores: [Store] }
            """);

        assertThat(messages(violations)).containsExactly(
            "Field 'Store.documents': @defaultOrder declares an ordering this field cannot honour."
            + " A field returning the multitable interface 'Document' is read as one statement per"
            + " participant (Actor, Film, Language) and the results are combined on a synthetic key,"
            + " so the declared columns are not applied and rows arrive in participant primary-key"
            + " order. Remove the declaration, or return a single @table type.");
        assertThat(violations).singleElement()
            .satisfies(v -> assertThat(v.kind()).isEqualTo(RejectionKind.DEFERRED));
    }

    /**
     * The location is the declaring directive's own and not the field's, which is what lets an
     * editor underline what the author wrote rather than the line the field happens to sit on.
     */
    @Test
    void theRejectionLocatesOnTheDeclaringDirective() {
        var violations = detect(DOCUMENTS + """
            type Store @table(name: "store") {
                documents: [Document!]!
                    @defaultOrder(fields: [{name: "rowId"}])
            }
            type Query { stores: [Store] }
            """);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isNotNull();
            assertThat(v.location().getLine())
                .as("the @defaultOrder application's own line, one below the field's")
                .isEqualTo(9);
        });
    }

    /**
     * The client-supplied half: an {@code @orderBy} argument is accepted at this coordinate and
     * discarded, so the remedy names the argument rather than a field-level directive.
     */
    @Test
    void anOrderByArgumentOnAMultitableChildIsRejectedNamingTheArgument() {
        var violations = detect(DOCUMENTS + """
            enum DocumentSort { ROW_ID @order(primaryKey: true) }
            input DocumentOrderBy { field: DocumentSort!  direction: SortDirection }
            type Store @table(name: "store") {
                documents(order: [DocumentOrderBy] @orderBy): [Document!]!
            }
            type Query { stores: [Store] }
            """);

        assertThat(messages(violations)).containsExactly(
            "Field 'Store.documents': argument 'order' carries @orderBy, which asks for an ordering"
            + " this field cannot honour. A field returning the multitable interface 'Document' is"
            + " read as one statement per participant (Actor, Film, Language) and the results are"
            + " combined on a synthetic key, so a client-supplied order is not applied and rows"
            + " arrive in participant primary-key order. Remove the argument's @orderBy, or return a"
            + " single @table type.");
    }

    /**
     * The combination the report carried, which is the reason the route is part of the relation's
     * grain: two declarations, two rejections, each naming its own remedy. Neither half depends on
     * the other, and the pagination the field also asked for changes nothing here: a child
     * multitable read lowers no ordering onto its participant branches.
     */
    @Test
    void bothDeclarationsAtOneCoordinateAreTwoRejections() {
        var violations = detect(DOCUMENTS + """
            enum DocumentSort { ROW_ID @order(primaryKey: true) }
            input DocumentOrderBy { field: DocumentSort!  direction: SortDirection }
            type Store @table(name: "store") {
                documents(order: [DocumentOrderBy] @orderBy): [Document!]!
                    @asConnection @defaultOrder(fields: [{name: "rowId"}])
            }
            type Query { stores: [Store] }
            """);

        assertThat(violations).extracting(ValidationError::coordinate)
            .containsExactly("Store.documents", "Store.documents");
        assertThat(messages(violations))
            .anySatisfy(m -> assertThat(m).contains("@defaultOrder declares an ordering"))
            .anySatisfy(m -> assertThat(m).contains("argument 'order' carries @orderBy"));
    }

    /**
     * A union container reads the same way and the prose says union, because an author looking for
     * the declaration they wrote needs the word they wrote it under.
     */
    @Test
    void aUnionContainerIsNamedAsAUnion() {
        var violations = detect("""
            type Film @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Actor @table(name: "actor") { rowId: Int @field(name: "actor_id") }
            union Document = Film | Actor
            type Store @table(name: "store") {
                documents: [Document!]! @defaultOrder(fields: [{name: "rowId"}])
            }
            type Query { stores: [Store] }
            """);

        assertThat(messages(violations)).singleElement()
            .satisfies(m -> assertThat(m).contains("the multitable union 'Document'"));
    }

    /**
     * The same declarations on a list-shaped root, the coordinate the classifier lowers them at:
     * both routes, on the {@code @asConnection} form the report arrived on, and no rejection.
     */
    @Test
    void theSameDeclarationsOnAMultitableRootAreLowered() {
        assertThat(detect(DOCUMENTS + """
            enum DocumentSort { ROW_ID @order(primaryKey: true) }
            input DocumentOrderBy { field: DocumentSort!  direction: SortDirection }
            type Query {
                documents(order: DocumentOrderBy @orderBy): [Document!]!
                    @asConnection @defaultOrder(fields: [{name: "rowId"}])
            }
            """)).isEmpty();
    }

    // ===== The write arm, whose row is minted and whose rejection is held =====

    /**
     * The list-returning {@code @routine} write: the routine's returned keys are captured and the
     * rows re-read by key, and neither step sorts, so the target table's primary key orders nothing
     * here even though it is available. The view says so, and the decode deliberately mints no
     * error: the only live instance of the shape is in graphitron's own example schema, so wording
     * the rejection reddens this reactor's verification build until that write's second step carries
     * an order to deliver.
     *
     * <p>This case is the pair of assertions the lowering flips: the row exists, so a lowering
     * landing upstream shows up here as a failing test rather than as a quietly empty population,
     * and no violation is minted, which is the half that becomes a message.
     */
    @Test
    void aListReturningRoutineWriteMintsARowAndHoldsItsRejection() {
        String sdl = """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rentals: [Rental] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """;

        assertThat(population(sdl))
            .as("the coordinate sits in a cell that can reject it, on the fallback route")
            .containsExactly("Mutation.rentFilm KEY_CAPTURE_SCATTER PRIMARY_KEY_FALLBACK");
        assertThat(detect(sdl))
            .as("and the rejection is held until the write has an order to deliver")
            .isEmpty();
    }

    // ===== The population the build-error consumer asks about =====

    /**
     * The domain narrowing, which is this consumer's own and not the relation's: a violating
     * coordinate on a type the generator's traversal never reaches costs no emitted source, so it
     * fails no build. The view keeps its row, because the editor's diagnostic arm reads these rows
     * ungated and a type no field reaches is where an author most needs the signal.
     */
    @Test
    void aViolationOutsideTheClassificationDomainMintsNoBuildError() {
        String sdl = DOCUMENTS + """
            type Unreached {
                documents: [Document!]! @defaultOrder(fields: [{name: "rowId"}])
            }
            type Query { films: [Film!]! @defaultOrder(primaryKey: true) }
            """;

        assertThat(population(sdl))
            .containsExactly("Unreached.documents PARTICIPANT_FAN_OUT DEFAULT_ORDER");
        assertThat(detect(sdl)).isEmpty();
    }

    /**
     * The negative that keeps the rule from being a census of every ordering ever written: a
     * single-{@code @table} field is one statement, and one statement carries its ordering.
     */
    @Test
    void aSingleTableFieldWithADeclaredOrderingIsNotRejected() {
        assertThat(detect("""
            type Film @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Query { films: [Film!]! @defaultOrder(primaryKey: true) }
            """)).isEmpty();
    }

    // ===== Helpers =====

    /** The messages an author actually meets, coordinate prefix included. */
    private static List<String> messages(List<ValidationError> violations) {
        return violations.stream().map(ValidationError::message).toList();
    }

    /** {@code Type.field verdict available_via}, the shape a case about the population reads. */
    private static String render(UnlowerableOrderings.Unlowered row) {
        return row.coordinate() + " " + row.verdict() + " " + row.availableVia();
    }

    /** Captures {@code sdl} against the catalog and runs the build-error consumer's detection. */
    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            return UnlowerableOrderings.detect(store.dsl(), GRAPH).violations();
        }
    }

    /**
     * The whole view over the captured store, ungated and rendered: what a case about the
     * population reads, where {@link #detect} reads what a build fails on.
     */
    private List<String> population(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            return UnlowerableOrderings.rows(store.dsl(), GRAPH).stream()
                .map(UnlowerableOrderingsTest::render)
                .toList();
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
