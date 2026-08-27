package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_ROUTINE_SEAT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_mutation_routine_seat}: which seat a mutation
 * root's {@code @routine} occupies, and where it occupies none, which precondition stopped it.
 *
 * <p>The relation's contract is totality, so every case here asserts the whole graph's rows rather
 * than a projection of them: a coordinate that should draw no row, or should draw a different
 * verdict than the one under test, fails the case it appears in. The one emitting verdict is a case
 * at each seat and the thirteen refusals are a case each, which is what makes the vocabulary closed
 * in fact and not only in the comment.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows, for the reason
 * {@code CarrierDataFieldTest} states: a seeded fixture is free to declare a shape capture never
 * writes, and the case then pins behaviour no build can produce. The catalog supplies the routine
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

    // ===== The two seats that emit =====

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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN ADMITTED Rental"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CARRIER ADMITTED RentFilmPayload"));
    }

    // ===== The three refusals both seats share =====

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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN MULTIPLE_ROUTINE_NODES Rental"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN CHAIN_HEAD_NOT_ROUTINE Rental"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN READ_SURFACE_ON_WRITE Rental"));
    }

    // ===== The chain seat's own refusals =====

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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN REFERENCE_ON_CARRIER_RETURN RentFilmPayload"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN CONNECTION_RETURN Rental"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN CHAIN_UNRESOLVED Rental"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN UNANCHORED_FIRST_HOP Rental"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN UNANCHORED_FIRST_HOP Rental"));
    }

    /**
     * The terminus rule, and it is the author's {@code @table} that is compared: the chain lands on
     * {@code rental} and the return type's own binding names {@code actor}.
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CHAIN TERMINUS_NOT_RETURN_TABLE Actor"));
    }

    // ===== The carrier seat's own refusals =====

    /**
     * A {@code @table} return with no path is the chain shape minus its chain: nothing says where
     * the committed row is re-read from, and there is no payload data field to hang a hop on.
     */
    @Test
    void aTableBoundReturnWithNoPathIsTheChainShapeMinusItsChain() {
        withCapturedStore(RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CARRIER TABLE_BOUND_RETURN Rental"));
    }

    /**
     * A return that is neither table-bound nor a payload declaring exactly one data channel is no
     * carrier at all. Here the payload declares two, which is the arity
     * {@code intent_carrier_data_field} counts and this verdict reports.
     */
    @Test
    void aPayloadWithTwoDataChannelsIsNoCarrier() {
        withCapturedStore(ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental other: Rental errors: [WriteError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CARRIER NO_CARRIER RentFilmPayload"));
    }

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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CARRIER CARRIER_ELEMENT_NOT_TABLE RentFilmPayload"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CARRIER CARRIER_DATA_FIELD_NON_NULL RentFilmPayload"));
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilm CARRIER CARRIER_HOP_UNRESOLVED RentFilmPayload"));
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
            """, dsl -> assertThat(seats(dsl)).isEmpty());
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
            """, dsl -> assertThat(seats(dsl)).containsExactly(
                "Mutation.rentFilmCarried CARRIER ADMITTED RentFilmPayload",
                "Mutation.rentFilmChained CHAIN ADMITTED Rental",
                "Mutation.rentFilmTwice CHAIN MULTIPLE_ROUTINE_NODES Rental"));
    }

    // ===== Helpers =====

    private static final String GRAPH = "MutationRoutineSeatTest";

    /**
     * Every seat the graph holds, one string per row: the coordinate, the seat, the verdict and the
     * type the field returns. Asserted whole so a coordinate that should draw no row, or a different
     * verdict, cannot hide behind a filter.
     */
    private static List<String> seats(DSLContext dsl) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        return dsl.select(s.fields())
            .from(s)
            .where(s.GRAPH_NAME.eq(GRAPH))
            .orderBy(s.TYPE_NAME, s.FIELD_NAME)
            .fetch()
            .map(row -> row.get(s.TYPE_NAME) + "." + row.get(s.FIELD_NAME) + " "
                + row.get(s.SEAT) + " " + row.get(s.VERDICT) + " " + row.get(s.RETURN_TYPE_NAME));
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), census())) {
            body.accept(store.dsl());
        }
    }

    /**
     * The real scan over the test classes, on {@code CarrierDataFieldTest}'s terms: the carrier
     * relations this one reduces over resolve a record-backed element through the census, so a case
     * that draws no such element still has to be judged against the census a build would see.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(MutationRoutineSeatTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
