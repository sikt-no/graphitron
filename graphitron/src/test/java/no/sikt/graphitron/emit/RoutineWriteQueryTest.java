package no.sikt.graphitron.emit;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link RoutineWriteQuery}'s rows against the classifier: membership equals the routine-write
 * leaves' coordinates derived from the model, and each row's catalog facts join back to the leaf's
 * own classified chain. The store states what a coordinate's emission reads, the walk what it
 * should read, so a divergence fails here rather than as changed output several tiers down.
 *
 * <p>One schema carries every mutation shape so membership is tested against a live non-member: a
 * one-hop chain write, a two-hop chain write (which states the alias numbering and the per-hop
 * keying a one-hop chain leaves unstated), a payload-carrier write, and a DML insert.
 */
@PipelineTier
class RoutineWriteQueryTest {

    private static final String SDL = """
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

    @TempDir
    static Path tmp;

    private static GraphitronSchema model;
    private static EmitPlan plan;

    @BeforeAll
    static void readRows() {
        model = TestSchemaHelper.buildBundle(SDL).model();
        plan = TestSchemaHelper.storeBackedPlan(tmp, SDL);
    }

    @Test
    void theRowsAreExactlyTheRoutineWritingCoordinates() {
        var declared = model.fields().entrySet().stream()
            .filter(e -> e.getValue() instanceof MutationField.MutationRoutineWriteField
                || e.getValue() instanceof MutationField.MutationRoutineWriteRecordField)
            .map(e -> e.getKey().getTypeName() + "." + e.getKey().getFieldName())
            .collect(Collectors.toSet());

        assertThat(declared)
            .as("the fixture declares both routine-write shapes; a membership test against an"
                + " empty derived set would pass vacuously")
            .hasSize(3);
        assertThat(plan.routineWrites().rows().stream().map(r -> r.typeName() + "." + r.fieldName()))
            .as("the store admits exactly the classifier's two routine-write leaves")
            .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void aDmlMutationHasNoRow() {
        assertThat(plan.routineWrites().rowFor("Mutation", "createFilm"))
            .as("a DML write emits no routine call")
            .isEmpty();
    }

    @Test
    void theRowsAreDisjointFromTheLauncherRelation() {
        var launcherKeys = plan.launchers().rows().stream()
            .map(no.sikt.graphitron.command.LauncherCommand::coordinate)
            .collect(Collectors.toSet());
        assertThat(plan.routineWrites().rows().stream()
                .map(r -> FieldCoordinates.coordinates(r.typeName(), r.fieldName())))
            .as("one coordinate, one owning emitter: a routine write's entry point is never also a"
                + " launcher's")
            .noneMatch(launcherKeys::contains);
    }

    @Test
    void theRowsAreKeyedByCoordinate() {
        var row = chainRow("rentFilm");
        assertThatThrownBy(() -> new RoutineWriteQuery.Rows(List.of(row, row)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyed by coordinate");
    }

    @Test
    void theChainRowCarriesTheCallAndItsArguments() {
        var row = chainRow("rentFilm");

        assertThat(row.seat()).isEqualTo(RoutineWriteQuery.Seat.CHAIN);
        assertThat(row.call().methodName())
            .as("the write is the declared routine call")
            .isEqualTo("rentFilm");
        assertThat(row.call().arguments())
            .as("the routine's IN parameters in declaration order, each bound to the argument the"
                + " author's argMapping named")
            .extracting(RoutineWriteQuery.Argument::path)
            .containsExactly("inventoryId", "customerId");
        assertThat(row.returnTypeName()).isEqualTo("Rental");
        assertThat(row.listReturn())
            .as("the field returns a list")
            .isTrue();
    }

    @Test
    void aOneHopChainIsItsClassifiedChain() {
        assertMatchesItsClassifiedChain("rentFilm");
    }

    /**
     * The aliases run continuously across the chain rather than restarting per {@code @reference}
     * element, which a one-hop chain cannot show.
     */
    @Test
    void aTwoHopChainNumbersItsAliasesAlongTheWholeChain() {
        assertMatchesItsClassifiedChain("rentFilmCustomer");
        assertThat(chainRow("rentFilmCustomer").hops().stream().map(RoutineWriteQuery.Hop::alias))
            .containsExactly("rentFilmCustomer_0", "rentFilmCustomer_1");
    }

    /**
     * A hop crossing a real foreign key names that key's generated constant, which is what lets the
     * emitter spell the join with no live catalog.
     */
    @Test
    void aHopAfterTheAnchorNamesTheForeignKeyItCrosses() {
        var hop = chainRow("rentFilmCustomer").hops().get(1);
        var fk = (On.Keying.ForeignKey) ((On.ColumnPairs)
            ((JoinStep.Hop) hopsOf("rentFilmCustomer").get(1)).on()).keying();

        assertThat(hop.keying())
            .as("the hop is keyed by the classified foreign key, named as its generated constant")
            .isEqualTo(new RoutineWriteQuery.Keying.ForeignKey(
                fk.fk().keysClassName(), fk.fk().constantName()));
        assertThat(hop.pairs())
            .extracting(p -> p.source().javaName(), p -> p.target().javaName())
            .as("and pairs the two ends of that key, side for side")
            .containsExactly(tuple("CUSTOMER_ID", "CUSTOMER_ID"));
    }

    @Test
    void theCarrierRowCarriesWhatItsCaptureNeeds() {
        var row = plan.routineWrites().rowFor("Mutation", "rentFilmPayload").orElseThrow();

        assertThat(row.seat()).isEqualTo(RoutineWriteQuery.Seat.CARRIER);
        assertThat(row.call().methodName()).isEqualTo("rentFilm");
        assertThat(row.hops()).isEmpty();
        assertThat(row.targetTable().sqlName())
            .as("the captured keys are typed under the target table's own key fields")
            .isEqualTo("rental");
        assertThat(row.capturedPairs().stream().map(p -> p.target().javaName()))
            .as("the payload data field correlates on the target table's key")
            .containsExactly("RENTAL_ID");
        assertThat(row.listReturn())
            .as("the payload's data field is single, this shape's only cardinality claim")
            .isFalse();
    }

    private static void assertMatchesItsClassifiedChain(String fieldName) {
        var row = chainRow(fieldName);
        var hops = hopsOf(fieldName);
        var hop0 = (JoinStep.Hop) hops.get(0);

        assertThat(row.hops().stream().map(RoutineWriteQuery.Hop::alias))
            .as("one hop per classified hop, aliased as the walk aliases it")
            .containsExactlyElementsOf(hops.stream().map(h -> ((JoinStep.Hop) h).alias()).toList());
        assertThat(row.hops().getFirst().table().tableClassName())
            .as("the re-read departs from hop 0's table, carried as its captured class name")
            .isEqualTo(CatalogRefs.tableClass(hop0.targetTable()).canonicalName());
        var hop0Slots = ((On.ColumnPairs) hop0.on()).slots();
        assertThat(row.hops().getFirst().pairs())
            .as("the captured pairing is hop 0's own, cell for cell and side for side")
            .extracting(p -> p.source().javaName(), p -> p.target().javaName())
            .containsExactlyElementsOf(hop0Slots.stream()
                .map(s -> tuple(s.sourceSide().javaName(), s.targetSide().javaName())).toList());
        assertThat(row.hops().getFirst().pairs().stream().map(p -> p.source().javaName()))
            .as("the routine's result rows carry the rental key")
            .containsExactly("RENTAL_ID");
        assertThat(row.hops().getFirst().pairs().stream().map(p -> p.source().bindingType()))
            .as("each captured column carries its bound Java type as a name, which is what lets"
                + " the emitter declare the key record with no live catalog")
            .containsExactly("java.lang.Integer");
    }

    private static RoutineWriteQuery.Row chainRow(String fieldName) {
        return plan.routineWrites().rowFor("Mutation", fieldName).orElseThrow();
    }

    /** The leaf's own classified chain, which every chain check above joins back to. */
    private static List<JoinStep> hopsOf(String fieldName) {
        return ((MutationField.MutationRoutineWriteField) model.fields()
            .get(FieldCoordinates.coordinates("Mutation", fieldName))).chain().hops();
    }
}
