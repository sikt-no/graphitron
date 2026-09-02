package no.sikt.graphitron.plan;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What producing the routine-write relation costs the store, counted rather than reasoned about.
 * Three statements at three grains, and three however many coordinates the graph holds, however
 * many hops their chains cross and however many arguments their routines take.
 *
 * <p>This is an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail for
 * being slow. It exists because the shape it pins is invisible from every other gate this producer
 * has. A fan-out into a statement per coordinate returns exactly the rows one statement does, so
 * {@link RoutineWriteRelationTest}'s agreement with the classifier stays green, the emitted output
 * is identical byte for byte, and the compile and execution tiers see nothing at all. A future
 * reader adding a fact to a row will reach for another query keyed by the coordinate, which is the
 * natural move and the one this test refuses.
 *
 * <p>The count is asserted at the producer's grain rather than at
 * {@link RoutineWriteFacts#read}'s, so it also covers the producer growing a read of its own beside
 * the facts pass: the two folds that stay on the schema (the error channel's minted constant and
 * the run's tenant binding) are reads of a classified model and must cost the store nothing.
 */
@PipelineTier
class RoutineWriteProducerStatementCountTest {

    /** The narrow corpus: one coordinate, one seat, a chain of one hop, two routine arguments. */
    private static final String ONE_COORDINATE = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    /**
     * The wide corpus: both seats, three admitted coordinates, one of them a chain crossing two
     * hops, plus a DML write the seat relation refuses. Every axis the coordinate statement's
     * correlated {@code MULTISET}s fan out over is populated more than once here, which is what
     * makes the equality against the narrow corpus mean something.
     */
    private static final String MANY_COORDINATES = """
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union RentFilmError = DbErr
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type RentFilmPayload {
          rental: Rental
          errors: [RentFilmError!]
        }
        type Film @table(name: "film") { title: String }
        type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
        input FilmInput { title: String }

        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            @reference(path: [{table: "rental"}])
          rentFilmCustomer(inventoryId: Int!, customerId: Int!): [Customer!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            @reference(path: [{table: "rental"}, {table: "customer"}])
          rentFilmPayload(inventoryId: Int!, customerId: Int!): RentFilmPayload
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    /** A graph that writes through no routine at all. */
    private static final String NO_ROUTINE_WRITE = """
        type Film @table(name: "film") { title: String }
        input FilmInput { title: String }
        type Query { film: Film }
        type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
        """;

    /** One per grain: the coordinate, the chain hop's pairing, the carrier's captured pairing. */
    private static final int GRAINS = 3;

    @TempDir
    Path tmp;

    @Test
    void oneCoordinateCostsOneStatementPerGrain() {
        assertThat(statementsToProduce(ONE_COORDINATE)).isEqualTo(GRAINS);
    }

    @Test
    void aWiderCorpusCostsTheSame() {
        // The assertion the pin exists for. Three admitted coordinates instead of one, both seats
        // instead of one, a two-hop chain beside a one-hop chain: every one of those is a row
        // count, and none of them is a statement count.
        var counted = new AtomicInteger();
        var model = TestSchemaHelper.buildBundle(MANY_COORDINATES).model();
        RoutineWriteRelation relation;
        try (var store = capture(MANY_COORDINATES)) {
            relation = RoutineWriteCommands.produce(counting(store, counted), model,
                DEFAULT_OUTPUT_PACKAGE);
        }
        assertThat(relation.rows())
            .as("the wide corpus really is wider; an equality between two empty reads would hold"
                + " for the wrong reason")
            .hasSize(3);
        assertThat(counted.get()).isEqualTo(GRAINS);
    }

    @Test
    void aGraphWithNoRoutineWriteCostsTheSame() {
        // Absence is an answer, and it is the same three statements: nothing here may probe a
        // relation at a time to discover that none of them holds a row, and nothing may skip a
        // grain because an earlier one came back empty. Either shape makes the count a function of
        // the corpus in the direction the pin is blind to reading as a saving.
        assertThat(statementsToProduce(NO_ROUTINE_WRITE)).isEqualTo(GRAINS);
    }

    /** The statements one {@link RoutineWriteCommands#produce} executes for {@code sdl}. */
    private int statementsToProduce(String sdl) {
        var counted = new AtomicInteger();
        var model = TestSchemaHelper.buildBundle(sdl).model();
        try (var store = capture(sdl)) {
            RoutineWriteCommands.produce(counting(store, counted), model, DEFAULT_OUTPUT_PACKAGE);
        }
        return counted.get();
    }

    /** The fixture's own store, seen through a handle that counts the statements it executes. */
    private static StoreHandle counting(CapturedStore store, AtomicInteger counted) {
        var configuration = store.dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    counted.incrementAndGet();
                }
            }));
        return new StoreHandle(DSL.using(configuration), CapturedStore.GRAPH);
    }

    private CapturedStore capture(String sdl) {
        var ctx = no.sikt.graphitron.common.configuration.TestConfiguration.testContext();
        return CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, sdl,
            new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()),
            TestSchemaHelper.classpathCensus(ctx));
    }
}
