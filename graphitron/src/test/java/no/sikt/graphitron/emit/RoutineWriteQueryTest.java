package no.sikt.graphitron.emit;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.model.test.Scenario;
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
 * one-hop chain write, a two-hop chain write (which states the lift's hop and its keying a one-hop
 * chain leaves unstated), an object-result write, and a DML insert. It is the
 * {@code scenarios/routine-write} fact document's, read as text: the comparison is between the store
 * and the classified model, which the shared fact graphs do not build, so it goes with the walk.
 */
@PipelineTier
class RoutineWriteQueryTest {

    /** The schema the {@code scenarios/routine-write} fact document composes. */
    private static final String SDL = Scenario.load("scenarios/routine-write").composed();

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
            .isNotEmpty();
        assertThat(plan.routineWrites().rows().stream().map(r -> r.typeName() + "." + r.fieldName()))
            .as("the store admits exactly the classifier's routine-write leaves")
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

        assertThat(row.call().methodName())
            .as("the write is the declared routine call")
            .isEqualTo("rentFilm");
        assertThat(row.call().arguments())
            .as("the routine's IN parameters in declaration order, each filled with the argument the"
                + " author's argMapping lands on, a plain value with no decode")
            .extracting(RoutineWriteQuery.Argument::parameterName, RoutineWriteQuery.Argument::pathNames,
                RoutineWriteQuery.Argument::nodeId)
            .containsExactly(tuple("pInventoryId", List.of("inventoryId"), false),
                tuple("pCustomerId", List.of("customerId"), false));
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
     * The write captures the anchor's key and the lift reads on from there: the classified chain's
     * second hop is the lift's one hop, and the one-hop chain's lift has none.
     */
    @Test
    void aTwoHopChainLiftsAlongTheHopAfterItsAnchor() {
        assertMatchesItsClassifiedChain("rentFilmCustomer");
        var hop1 = (JoinStep.Hop) hopsOf("rentFilmCustomer").get(1);
        assertThat(liftOf("rentFilmCustomer").hops())
            .extracting(h -> h.table().tableClassName())
            .as("one hop after the anchor, to the classified second hop's table")
            .containsExactly(CatalogRefs.tableClass(hop1.targetTable()).canonicalName());
        assertThat(liftOf("rentFilmCustomer").terminal().sqlName())
            .as("the lift reads its target off the last hop's table")
            .isEqualTo("customer");
        assertThat(liftOf("rentFilm").hops())
            .as("a one-hop chain lifts off its anchor itself")
            .isEmpty();
    }

    /**
     * A hop crossing a real foreign key names that key's generated constant, which is what lets the
     * emitter spell the join with no live catalog.
     */
    @Test
    void aHopAfterTheAnchorNamesTheForeignKeyItCrosses() {
        var hop = liftOf("rentFilmCustomer").hops().getFirst();
        var fk = (On.Keying.ForeignKey) ((On.ColumnPairs)
            ((JoinStep.Hop) hopsOf("rentFilmCustomer").get(1)).on()).keying();

        assertThat(hop.keying())
            .as("the hop is keyed by the classified foreign key, named as its generated constant")
            .isEqualTo(new LiftQuery.Keying.ForeignKey(
                fk.fk().keysClassName(), fk.fk().constantName()));
        assertThat(hop.pairs())
            .extracting(p -> p.from().javaName(), p -> p.to().javaName())
            .as("and pairs the two ends of that key, side for side")
            .containsExactly(tuple("CUSTOMER_ID", "CUSTOMER_ID"));
        assertThat(hop.toOne())
            .as("a rental reaches one customer along the key it declares")
            .isTrue();
    }

    @Test
    void theCarrierRowCarriesWhatItsCaptureNeeds() {
        var row = (RoutineWriteQuery.ObjectResult) plan.routineWrites()
            .rowFor("Mutation", "rentFilmPayload").orElseThrow();

        assertThat(row.call().methodName()).isEqualTo("rentFilm");
        assertThat(row.reRead().fieldName()).isEqualTo("rental");
        assertThat(row.reRead().table().sqlName())
            .as("the captured keys are typed under the re-read table's own key fields")
            .isEqualTo("rental");
        assertThat(row.reRead().capturedPairs().stream().map(p -> p.target().javaName()))
            .as("the re-reading field correlates on the re-read table's key")
            .containsExactly("RENTAL_ID");
        assertThat(row.reRead().listReturn())
            .as("the re-reading field is single, this shape's only cardinality claim")
            .isFalse();
    }

    private static void assertMatchesItsClassifiedChain(String fieldName) {
        var row = chainRow(fieldName);
        var hops = hopsOf(fieldName);
        var hop0 = (JoinStep.Hop) hops.get(0);

        assertThat(row.anchor().tableClassName())
            .as("the write captures the key of hop 0's table, carried as its captured class name")
            .isEqualTo(CatalogRefs.tableClass(hop0.targetTable()).canonicalName());
        assertThat(liftOf(fieldName).table().tableClassName())
            .as("and the lift departs from that table")
            .isEqualTo(row.anchor().tableClassName());
        assertThat(liftOf(fieldName).hops())
            .as("one lift hop per classified hop after the anchor")
            .hasSize(hops.size() - 1);
        var hop0Slots = ((On.ColumnPairs) hop0.on()).slots();
        assertThat(row.capturedPairs())
            .as("the captured pairing is hop 0's own, cell for cell and side for side")
            .extracting(p -> p.source().javaName(), p -> p.target().javaName())
            .containsExactlyElementsOf(hop0Slots.stream()
                .map(s -> tuple(s.sourceSide().javaName(), s.targetSide().javaName())).toList());
        assertThat(row.capturedPairs().stream().map(p -> p.source().javaName()))
            .as("the routine's result rows carry the rental key")
            .containsExactly("RENTAL_ID");
        assertThat(row.capturedPairs().stream().map(p -> p.source().bindingType()))
            .as("each captured column carries its bound Java type as a name, which is what lets"
                + " the emitter declare the key record with no live catalog")
            .containsExactly("java.lang.Integer");
    }

    private static RoutineWriteQuery.RecordsResult chainRow(String fieldName) {
        return (RoutineWriteQuery.RecordsResult) plan.routineWrites().rowFor("Mutation", fieldName)
            .orElseThrow();
    }

    /** The lift read beside a records result's row. */
    private static LiftQuery.Row liftOf(String fieldName) {
        return plan.routineWrites().liftFor(chainRow(fieldName)).orElseThrow();
    }

    /** The leaf's own classified chain, which every chain check above joins back to. */
    private static List<JoinStep> hopsOf(String fieldName) {
        return ((MutationField.MutationRoutineWriteField) model.fields()
            .get(FieldCoordinates.coordinates("Mutation", fieldName))).chain().hops();
    }
}
