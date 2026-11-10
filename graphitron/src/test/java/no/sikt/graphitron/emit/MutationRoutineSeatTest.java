package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * {@link RoutineWriteQuery}'s admission: which mutation-root {@code @routine} coordinates draw a row,
 * in which result shape, and which the store states in a shape the emitter does not render yet.
 *
 * <p>Every case asserts the whole graph's rows rather than a projection of them: a coordinate that
 * should draw no row, or a different result shape, fails the case it appears in. Each admitted shape
 * is a case, each {@link RoutineWriteQuery.Unrendered} shape is a case, and each shape the old
 * admission refused is a case that asserts either its absence from the rows, where a missing fact
 * keeps it out, or the store defect that stops the build, where every fact admission reads is
 * present and the defect is the gate.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows: a seeded
 * fixture is free to declare a shape capture never writes, and the case then pins behaviour no
 * build can produce. The catalog supplies the routine
 * whose result the writes depart from ({@code rent_film}) and the table its name-matched keys reach
 * ({@code rental}).
 */
@PipelineTier
class MutationRoutineSeatTest {

    @TempDir
    Path tmp;

    /** The error channel every carrier fixture below declares, so no case is a payload without one. */
    private static final String ERRORS = """
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        union WriteError = DbErr
        """;

    /** The table the {@code rent_film} result's name-matched keys reach, and the chain's terminus. */
    private static final String RENTAL = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rentals: [Rental] }
        """;

    // ===== The two result shapes that emit =====

    /**
     * The chain seat: the routine call is the write, the one {@code @reference} element says where
     * the committed row is re-read from, and the field's {@code @table} return names that same
     * table.
     */
    @Test
    void aChainWhoseTerminusIsTheReturnTableIsAdmitted() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).containsExactly("Mutation.rentFilm RecordsResult Rental");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * The carrier seat: no path is written at all, the return is a payload wrapping one data field
     * beside an errors channel, and the hop out of the routine result to that data field's table is
     * the name-matched one the catalog derives.
     */
    @Test
    void aCarrierWithANameMatchedHopIsAdmitted() {
        withCapturedStore(ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental errors: [WriteError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
                assertThat(rows(dsl)).containsExactly("Mutation.rentFilm ObjectResult RentFilmPayload");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    // ===== Shapes both result shapes share =====

    /**
     * A second {@code @routine} is a shape owed the multi-lateral emit, and it precedes every other
     * verdict: the rules below are stated about the chain's one routine, and here there is no one
     * routine for them to be about.
     */
    @Test
    void aSecondRoutineApplicationRefusesAheadOfEverythingElse() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(defects(dsl)).contains("MULTIPLE_ROUTINE_NODES");
            });
    }

    /**
     * An {@code @reference} written before the routine would have the chain depart from something
     * other than the function result, which at a root there is nothing to be.
     */
    @Test
    void aReferenceWrittenBeforeTheRoutineIsNotTheChainsHead() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @reference(path: [{table: "rental"}])
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * Neither write seat carries a filter or an ordering, so a {@code @condition} on the field would
     * classify clean and then silently do nothing.
     */
    @Test
    void aConditionOnTheWriteFieldIsAReadSurfaceNeitherSeatHas() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
                @condition(condition: {
                  className: "no.sikt.graphitron.rewrite.TestConditionStub",
                  method: "lifterFieldCondition"
                })
            }
            """, dsl -> {
                assertThat(defects(dsl)).contains("READ_SURFACE_ON_WRITE");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * The same refusal at the argument site. A {@code @condition} on an argument of the write field
     * is the same read surface as one on the field, and the verdict is asked of the application at
     * the argument's coordinate, so a reading pointed at the field's coordinate would miss it.
     */
    @Test
    void aConditionOnAWriteArgumentIsAReadSurfaceToo() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(
                inventoryId: Int! @condition(condition: {
                  className: "no.sikt.graphitron.rewrite.TestConditionStub",
                  method: "lifterFieldCondition"
                }),
                customerId: Int!
              ): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(defects(dsl)).contains("READ_SURFACE_ON_WRITE");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * An ordering is a read surface on the same terms as a filter, and {@code @orderBy} has only
     * the argument site to sit on, so the verdict reads its applications at the field's arguments.
     */
    @Test
    void anOrderByOnAWriteArgumentIsAReadSurfaceToo() {
        withCapturedStore(RENTAL + """
            enum RentalSortField { RENTAL_ID @order(fields: [{name: "rental_id"}]) }
            input RentalOrder { field: RentalSortField! }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!, order: RentalOrder @orderBy): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(defects(dsl)).contains("READ_SURFACE_ON_WRITE");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    // ===== Records results the old admission refused =====

    /**
     * The fourth cell: a path written on a field that returns the payload carrier is the right fact
     * at the wrong grain, its seat being the payload's data field, which is the coordinate whose
     * rows it would fetch.
     */
    @Test
    void aPathOnACarrierReturnSitsAtTheWrongGrain() {
        withCapturedStore(ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental errors: [WriteError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * The write's re-read is keyed by the captured routine columns rather than paginated, so the
     * connection macro's rewrite of the field's type expression refuses the seat.
     */
    @Test
    void aConnectionReturnRefusesTheChainSeat() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @asConnection
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * A routine name no function result answers leaves the chain with no start, no nodes and no
     * terminus, which is one of the three ways a chain fails to land on exactly one table.
     */
    @Test
    void aRoutineNameNothingAnswersLeavesTheChainUnresolved() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "no_such_function")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * A first hop carrying an authored condition has no derivable re-read anchor: the predicate
     * names the routine alias, which must not appear in the follow-up query.
     */
    @Test
    void anAuthoredConditionOnTheFirstHopLeavesTheReReadUnanchored() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental", condition: {
                  className: "no.sikt.graphitron.rewrite.TestConditionStub",
                  method: "join"
                }}])
            }
            """, dsl -> {
                assertThat(defects(dsl)).contains("CONDITION_ON_WRITE_RESULT");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * The same verdict at the shape that used to read as {@code CHAIN_UNRESOLVED}: a first hop that
     * joins by an authored condition alone. Such a hop names no key and no table, so it resolved to
     * no hop row and the chain reached no node, which conflated a refusal with a shape the
     * classification walk calls owed an emitter. It now routes off the condition method's signature,
     * the chain reaches seq 1, and the verdict is the one this relation's own CASE already assigns
     * the shape. The sibling case above writes {@code {table:, condition:}}, which is the table arm's
     * row and reached this verdict all along, so this case is the population that moved.
     */
    @Test
    void aBareConditionFirstHopReachesTheChainAndReadsAsUnanchored() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{condition: {
                  className: "no.sikt.graphitron.rewrite.TestConditionRoutes",
                  method: "routineResultToRental"
                }}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * A chain ending on a table its return type is not bound to: the path written ends on
     * {@code rental} and the return type's own binding names {@code actor}, so no join path reaches
     * {@code Actor} and the chain is unresolved. The terminus rule compares a join path's landing
     * with the return's table, and a join path only exists where it reaches the type's table.
     */
    @Test
    void aReturnTableTheChainDoesNotLandOnRefuses() {
        withCapturedStore("""
            type Actor @table(name: "actor") { actorId: Int! @field(name: "actor_id") }
            type Query { actors: [Actor] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Actor!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    // ===== Object results the old admission refused =====

    /**
     * A routine write re-reads its committed row from a catalog table, so a data channel the
     * backing closure reaches a class for is a shape this family has no re-read for. The closure
     * reaches {@code LanguageDto} because a service mutation elsewhere in the graph produces it,
     * which is what makes the element a record rather than nothing at all.
     */
    @Test
    void aRecordElementDataFieldHasNoTableToReReadFrom() {
        withCapturedStore(ERRORS + RENTAL + """
            type LanguageDto { name: String }
            type FilmPayload { language: LanguageDto errors: [WriteError] }
            type RentFilmPayload { language: LanguageDto errors: [WriteError] }
            type Mutation {
              createFilm: FilmPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.derive.TestBackingService", method: "films"})
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * The data channel is a non-null single, so a re-read a read policy legitimately returns no row
     * for would null the whole payload through non-null propagation and destroy the errors list
     * beside it.
     */
    @Test
    void aNonNullDataFieldWouldDestroyTheErrorsList() {
        withCapturedStore(ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental! errors: [WriteError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).containsExactly(
                    entry("Mutation.rentFilm", RoutineWriteQuery.Unrendered.NON_NULL_SINGLE_RE_READ));
            });
    }

    /**
     * The data field's table is one no name-matched hop out of this field's own routine result
     * reaches, so the post-commit re-read has no keys to run on.
     */
    @Test
    void aDataFieldTableNoHopReachesRefuses() {
        withCapturedStore(ERRORS + """
            type Actor @table(name: "actor") { actorId: Int! @field(name: "actor_id") }
            type Query { actors: [Actor] }
            type RentFilmPayload { actor: Actor errors: [WriteError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    // ===== The population's edges =====

    /**
     * The population is the mutation root's, bound by {@code graphql_root_operation} rather than by
     * the literal name Mutation: a {@code @routine} on a Query field is a read and draws no row, and
     * neither does one on a child field.
     */
    @Test
    void onlyTheMutationRootsRoutinesAreInThePopulation() {
        withCapturedStore("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query {
              rentals(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            type Mutation { touch: Boolean }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * Totality, asserted over a graph holding one coordinate of each seat: every mutation-root
     * {@code @routine} draws exactly one row, and the chain-defining application is the last one
     * written, so a coordinate carrying two of them still draws one.
     */
    @Test
    void everyMutationRoutineCoordinateDrawsExactlyOneRow() {
        withCapturedStore(ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental errors: [WriteError] }
            type Mutation {
              rentFilmChained(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
              rentFilmCarried(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
              rentFilmTwice(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
                assertThat(rows(dsl)).containsExactly(
                    "Mutation.rentFilmCarried ObjectResult RentFilmPayload",
                    "Mutation.rentFilmChained RecordsResult Rental");
                assertThat(unrendered(dsl)).isEmpty();
            });
    }

    /**
     * The shapes the store admits and the emitter does not render yet, each kept out of the rows by
     * name: a table-bound return with no step after its routine (a lift), an object result with two
     * fields re-reading the written rows, one encoding a node id from the returned keys beside its
     * re-read, and a non-null single re-read with no errors field. Three draw no defect, so the named
     * case is all that keeps them out; the node id draws NODE_ID_LANDS_ON_TABLE, the node-id rule
     * not yet taking a routine result's name-matched keys as the path to the node's table.
     */
    @Test
    void theShapesTheEmitterDoesNotRenderYetAreKeptOutByName() {
        withCapturedStore(ERRORS + """
            interface Node { id: ID! }
            type Rental implements Node @node @table(name: "rental") {
              id: ID! @nodeId
              rentalId: Int! @field(name: "rental_id")
            }
            type Query { rentals: [Rental] node(id: ID!): Node }
            type RentalTwice { rental: Rental other: Rental errors: [WriteError] }
            type RentalWithId { rental: Rental rentalId: ID @nodeId(typeName: "Rental") errors: [WriteError] }
            type RentalNonNull { rental: Rental! }
            type Mutation {
              lifted(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
              twice(inventoryId: Int!, customerId: Int!): RentalTwice
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
              withId(inventoryId: Int!, customerId: Int!): RentalWithId
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
              nonNull(inventoryId: Int!, customerId: Int!): RentalNonNull
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
                assertThat(rows(dsl)).isEmpty();
                assertThat(defects(dsl)).containsExactly("NODE_ID_LANDS_ON_TABLE");
                assertThat(unrendered(dsl)).containsOnly(
                    entry("Mutation.lifted", RoutineWriteQuery.Unrendered.LIFT_WITHOUT_STEP),
                    entry("Mutation.twice", RoutineWriteQuery.Unrendered.SEVERAL_RE_READING_FIELDS),
                    entry("Mutation.withId", RoutineWriteQuery.Unrendered.ID_ENCODED_FROM_KEYS),
                    entry("Mutation.nonNull", RoutineWriteQuery.Unrendered.NON_NULL_SINGLE_RE_READ));
            });
    }

    // ===== Helpers =====

    private static final String GRAPH = "MutationRoutineSeatTest";

    /**
     * Every row the graph admits, one string per row: the coordinate, the result shape and the type
     * the field returns. Asserted whole so a coordinate that should draw no row, or a different
     * shape, cannot hide behind a filter.
     */
    private static List<String> rows(DSLContext dsl) {
        return RoutineWriteQuery.read(new StoreHandle(dsl, GRAPH)).rows().stream()
            .map(r -> r.typeName() + "." + r.fieldName() + " " + r.getClass().getSimpleName() + " "
                + r.returnTypeName())
            .toList();
    }

    /** Every coordinate the store states a re-read for in a shape the emitter does not render. */
    private static Map<String, RoutineWriteQuery.Unrendered> unrendered(DSLContext dsl) {
        return RoutineWriteQuery.unrendered(new StoreHandle(dsl, GRAPH));
    }

    /** The codes of the defects the store states for the graph, which are what stop the build. */
    private static List<String> defects(DSLContext dsl) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        return dsl.select(d.CODE).from(d).where(d.GRAPH_NAME.eq(GRAPH)).fetch(d.CODE);
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl,
                CatalogInput.of(ctx),
                TestSchemaHelper.testClasspath())) {
            body.accept(store.dsl());
        }
    }
}
