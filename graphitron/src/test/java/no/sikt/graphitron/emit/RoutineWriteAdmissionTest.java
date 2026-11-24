package no.sikt.graphitron.emit;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link RoutineWriteQuery}'s admission: which mutation-root {@code @routine} writes draw a row, in
 * which result shape, and which the store admits in a shape the emitter does not render yet.
 *
 * <p>What the store refuses is its defects', each pinned by a fact document where its code is
 * stated: a path a write's result leads on by the path family's ({@code defects/routine-write-paths},
 * {@code defects/path-inference}, {@code defects/routine-step-unresolved}), a condition on the step
 * out of the result ({@code defects/condition-on-write-result}), a routine written after a
 * {@code @reference} ({@code defects/root-reference-without-routine}), a read surface, a second
 * routine and a connection on a write ({@code defects/read-surface-on-write},
 * {@code defects/entry-defect-write-rules}), and what a write's result holds
 * ({@code defects/write-result-holds-only-keys}). A defect stops the build before the emitter runs,
 * so no row is asserted absent for one here.
 */
@PipelineTier
class RoutineWriteAdmissionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void theWritesTheEmitterRendersDrawARowEachInTheirResultShape() {
        List<String> rows = FACTS.read("scenarios/routine-write",
            graph -> describe(RoutineWriteQuery.read(graph.handle())));
        assertThat(rows).as("a write returning rentals' records").contains("Mutation.rentFilm RecordsResult Rental");
        assertThat(rows).as("a write returning customers' records")
            .contains("Mutation.rentFilmCustomer RecordsResult Customer");
        assertThat(rows).as("a write returning a payload object")
            .contains("Mutation.rentFilmPayload ObjectResult RentFilmPayload");
        assertThat(rows).as("the insert beside them draws no row")
            .noneMatch(row -> row.startsWith("Mutation.createFilm "));
    }

    /**
     * A routine write's arguments are the store's fills: a node id stopped on at a routine parameter
     * is decoded into the node's key and its one column taken, as {@code scenarios/argmapping-key-column}
     * states the slot's {@code KEY_VALUE} shape, and a plain value beside it is read as it arrives.
     */
    @Test
    void aNodeIdArgumentIsFilledWithItsKeyColumn() {
        var arguments = FACTS.read("scenarios/argmapping-key-column",
            graph -> RoutineWriteQuery.read(graph.handle()).rowFor("Mutation", "rentFilmBare")
                .orElseThrow().call().arguments());
        assertThat(arguments)
            .extracting(RoutineWriteQuery.Argument::parameterName, RoutineWriteQuery.Argument::pathNames,
                RoutineWriteQuery.Argument::nodeId, RoutineWriteQuery.Argument::decode)
            .containsExactly(
                tuple("pInventoryId", List.of("inventoryId"), false, java.util.Optional.empty()),
                tuple("pCustomerId", List.of("customerId"), true, java.util.Optional.of(
                    new RoutineWriteQuery.KeyValue("Mutation.rentFilmBare(customerId:)", "public.customer",
                        "CUSTOMER_ID"))));
    }

    @Test
    void aRoutineOnTheQueryRootOrUnderATypeIsNoWrite() {
        List<RoutineWriteQuery.Row> rows = FACTS.read("scenarios/field-path-routine-inferred",
            graph -> RoutineWriteQuery.read(graph.handle()).rows());
        assertThat(rows)
            .as("a read through a routine, at the query root or under a table, draws no row")
            .isEmpty();
    }

    /**
     * The shapes the store admits and the emitter does not render yet, each kept out of the rows by
     * name and refused by the walk: a table-bound return with no step after its routine (a lift), an
     * object result with two fields re-reading the written rows, one encoding a node id from the
     * returned keys beside its re-read, and a non-null single re-read with no errors field. Each is a
     * stored deferral at the field, {@code defects/routine-write-unrendered} pinning the codes; the
     * node id also draws NODE_ID_LANDS_ON_TABLE, the node-id rule not yet taking a routine result's
     * name-matched keys as the path to the node's table.
     */
    @Test
    void theShapesTheEmitterDoesNotRenderYetAreKeptOutByName() {
        String sdl = """
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            union WriteError = DbErr
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
            """;
        FACTS.readInline("routine-write-admission", sdl, CatalogInput.of(testContext()),
                TestSchemaHelper.testClasspath(), graph -> {
            var handle = graph.handle();
            assertThat(RoutineWriteQuery.read(handle).rows()).isEmpty();
            var d = GRAPHITRON_ENTRY_DEFECT;
            assertThat(handle.dsl().select(d.CODE).from(d).where(d.GRAPH_NAME.eq(handle.graphName()))
                .fetch(d.CODE))
                .containsExactlyInAnyOrder("NODE_ID_LANDS_ON_TABLE",
                    "ROUTINE_WRITE_LIFT_WITHOUT_STEP_UNSUPPORTED", "ROUTINE_WRITE_SEVERAL_RE_READS_UNSUPPORTED",
                    "ROUTINE_WRITE_NODE_ID_FROM_KEYS_UNSUPPORTED", "ROUTINE_WRITE_NON_NULL_RE_READ_UNSUPPORTED");
            assertThat(RoutineWriteQuery.unrendered(handle)).containsOnly(
                entry("Mutation.lifted", RoutineWriteQuery.Unrendered.LIFT_WITHOUT_STEP),
                entry("Mutation.twice", RoutineWriteQuery.Unrendered.SEVERAL_RE_READING_FIELDS),
                entry("Mutation.withId", RoutineWriteQuery.Unrendered.ID_ENCODED_FROM_KEYS),
                entry("Mutation.nonNull", RoutineWriteQuery.Unrendered.NON_NULL_SINGLE_RE_READ));
            return null;
        });
        Map<FieldCoordinates, GraphitronField> classified = TestSchemaHelper.buildBundle(sdl).model().fields();
        for (String field : List.of("lifted", "twice", "withId", "nonNull")) {
            assertThat(classified.get(FieldCoordinates.coordinates("Mutation", field)))
                .as("the walk refuses Mutation." + field + ", so no generated schema states it")
                .isInstanceOf(GraphitronField.UnclassifiedField.class);
        }
    }

    /** Every row, one string per row: the coordinate, the result shape and the type it returns. */
    private static List<String> describe(RoutineWriteQuery.Rows rows) {
        return rows.rows().stream()
            .map(r -> r.typeName() + "." + r.fieldName() + " " + r.getClass().getSimpleName() + " "
                + r.returnTypeName())
            .toList();
    }
}
