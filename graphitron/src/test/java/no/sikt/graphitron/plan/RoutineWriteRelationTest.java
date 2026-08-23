package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.JoinBasis;
import no.sikt.graphitron.command.RoutineCall;
import no.sikt.graphitron.command.RoutineWriteCommand;
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
import java.util.stream.Stream;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The routine-write relation's membership enforcer and per-arm fact pins, in the fetcher edge
 * relation's shape: the relation's coordinate keys equal exactly the routine-write leaves'
 * coordinates derived from the model (never a hand tag), the keys are disjoint from the launcher
 * relation's, and each arm's witness pins the facts its renderer reads.
 *
 * <p>The relation is read from the fact store and every expectation is the classifier's own, so
 * each check that joins the two is an agreement between them: the store says what a coordinate's
 * emission reads, the walk's carriers say what it should read, and a divergence anywhere in that
 * surface fails a case here rather than surfacing as changed output several tiers down.
 *
 * <p>The fixture carries every mutation shape on one schema so membership is tested against a
 * live non-member rather than against an empty relation: a chain-re-reading routine write, a second
 * one whose chain crosses two hops (the shape that states the alias numbering and the per-hop
 * keying a one-hop chain leaves unstated), a payload-carrier routine write whose payload has an
 * {@code @error} field (so the routed dispatch arm is reached rather than only the redacting one),
 * and a DML insert.
 */
@PipelineTier
class RoutineWriteRelationTest {

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

    private static final GeneratedUnits UNITS = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);

    @TempDir
    static Path tmp;

    private static GraphitronSchema model;
    private static EmitPlan plan;

    @BeforeAll
    static void producePlan() {
        model = TestSchemaHelper.buildBundle(SDL).model();
        plan = TestSchemaHelper.storeBackedPlan(tmp, SDL);
    }

    @Test
    void theKeysAreExactlyTheRoutineWritingCoordinates() {
        var declared = model.fields().entrySet().stream()
            .filter(e -> e.getValue() instanceof MutationField.MutationRoutineWriteField
                || e.getValue() instanceof MutationField.MutationRoutineWriteRecordField)
            .map(java.util.Map.Entry::getKey)
            .collect(Collectors.toSet());

        assertThat(declared)
            .as("the fixture declares both routine-write shapes; a membership test against an"
                + " empty derived set would pass vacuously")
            .hasSize(3);
        assertThat(plan.routineWrites().rows().stream().map(RoutineWriteCommand::coordinate))
            .as("the relation's keys are the two routine-write leaves' coordinates, derived from"
                + " the model rather than restated")
            .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void aDmlMutationIsNoMemberOfThisRelation() {
        assertThat(plan.routineWrites().rowFor("Mutation", "createFilm"))
            .as("a DML write emits no routine call, so it holds no routine-write row")
            .isEmpty();
    }

    @Test
    void theKeysAreDisjointFromTheLauncherRelations() {
        var launcherKeys = plan.launchers().rows().stream()
            .map(no.sikt.graphitron.command.LauncherCommand::coordinate)
            .collect(Collectors.toSet());
        assertThat(plan.routineWrites().rows().stream().map(RoutineWriteCommand::coordinate))
            .as("one coordinate, one owning relation: a routine write's entry point is this"
                + " relation's row and never also a launcher's")
            .noneMatch(launcherKeys::contains);
    }

    @Test
    void theChainRereadArmCarriesWhatItsFollowUpQueryReads() {
        var row = directArm();

        assertThat(row.unit().owner()).isEqualTo(UNITS.fetchers("Mutation"));
        assertThat(row.unit().methodName())
            .as("the emitted entry point takes the field's own name")
            .isEqualTo("rentFilm");
        assertThat(row.call().methodName())
            .as("the write is the declared routine call")
            .isEqualTo("rentFilm");
        assertThat(row.call().arguments())
            .as("the routine's IN parameters ride the row in declaration order, each bound to the"
                + " argument the author's argMapping named")
            .extracting(RoutineCall.RoutineArgument::path)
            .containsExactly("inventoryId", "customerId");
        assertThat(row.terminusProjection())
            .as("the follow-up query projects the terminus type through its projection unit")
            .isEqualTo(UNITS.typeClass("Rental"));
        assertThat(row.arity())
            .as("the field returns a list, so the fetcher delivers a result")
            .isEqualTo(Arity.LIST);
        assertThat(row.errors())
            .as("a chain-re-reading routine write carries no payload carrier to route into, so"
                + " the throw takes the router's privacy disposition")
            .isInstanceOf(ErrorDispatch.Redacting.class);
    }

    /**
     * The narrowing the row performs on the classified chain, joined back to the chain itself
     * rather than restated: the anchor is hop 0 with its column pairing, the tail is what
     * follows it, and the terminus derives from the two. Reading the leaf's own chain here is
     * what makes this a join and not a self-comparison, the row no longer carrying that carrier.
     */
    @Test
    void theChainRereadArmsShapeIsTheClassifiedChainNarrowed() {
        assertNarrowsItsClassifiedChain("rentFilm");
    }

    /**
     * The same join on a chain of two hops, which states the facts a one-hop chain cannot. The
     * aliases run continuously across the chain rather than restarting per {@code @reference}
     * element, the tail is non-empty, and the terminus is the chain's last hop rather than the
     * anchor doubling as one.
     */
    @Test
    void aTwoHopChainNarrowsTheSameWay() {
        var row = chainArm("rentFilmCustomer");
        assertNarrowsItsClassifiedChain("rentFilmCustomer");

        assertThat(Stream.concat(Stream.of(row.anchor().alias()),
                row.hops().stream().map(RoutineWriteCommand.RereadHop::alias)))
            .as("the alias index counts hops along the whole chain from zero, so a second"
                + " @reference element continues the numbering rather than restarting it")
            .containsExactly("rentFilmCustomer_0", "rentFilmCustomer_1");
    }

    /**
     * The keying a one-hop chain never states. A re-read departs from its anchor rather than
     * joining it, so only a hop after the anchor declares a basis; a chain crossing a real foreign
     * key names that key's generated constant, which is what lets the renderer spell the join
     * without a live catalog.
     */
    @Test
    void aHopAfterTheAnchorNamesTheForeignKeyItCrosses() {
        var hop = chainArm("rentFilmCustomer").hops().getFirst();
        var fk = (On.Keying.ForeignKey) ((On.ColumnPairs)
            ((JoinStep.Hop) hopsOf("rentFilmCustomer").get(1)).on()).keying();

        assertThat(((JoinBasis.ColumnPairs) hop.on()).keying())
            .as("the hop is keyed by the classified foreign key, named as its generated constant")
            .isEqualTo(new JoinBasis.Keying.ForeignKey(
                fk.fk().keysClass().canonicalName(), fk.fk().constantName()));
        assertThat(((JoinBasis.ColumnPairs) hop.on()).pairs())
            .extracting(p -> p.sourceSide().javaName(), p -> p.targetSide().javaName())
            .as("and pairs the two ends of that key, side for side")
            .containsExactly(tuple("CUSTOMER_ID", "CUSTOMER_ID"));
    }

    private void assertNarrowsItsClassifiedChain(String fieldName) {
        var row = chainArm(fieldName);
        var hops = hopsOf(fieldName);
        var hop0 = (JoinStep.Hop) hops.get(0);

        assertThat(row.anchor().alias())
            .as("the follow-up query departs from hop 0")
            .isEqualTo(hop0.alias());
        assertThat(row.anchor().table().tableClassName())
            .as("and from hop 0's table, carried as the captured class name the renderer lifts")
            .isEqualTo(hop0.targetTable().tableClass().canonicalName());
        assertThat(row.hops().stream().map(RoutineWriteCommand.RereadHop::alias))
            .as("the tail is every hop after the anchor, in authored order")
            .containsExactlyElementsOf(hops.stream().skip(1)
                .map(h -> ((JoinStep.Hop) h).alias()).toList());
        assertThat(row.terminalAlias())
            .as("the projection reads the chain's terminus, which on a one-hop chain is the"
                + " anchor itself and on a longer one the last hop")
            .isEqualTo(((JoinStep.Hop) hops.getLast()).alias());
        var hop0Slots = ((On.ColumnPairs) hop0.on()).slots();
        assertThat(row.anchor().capturedPairs())
            .as("the captured pairing is hop 0's own, cell for cell and side for side")
            .extracting(p -> p.sourceSide().javaName(), p -> p.targetSide().javaName())
            .containsExactlyElementsOf(hop0Slots.stream()
                .map(s -> tuple(s.sourceSide().javaName(), s.targetSide().javaName())).toList());
        assertThat(row.anchor().capturedPairs().stream().map(p -> p.sourceSide().javaName()))
            .as("the routine's result rows carry the rental key")
            .containsExactly("RENTAL_ID");
        assertThat(row.anchor().capturedPairs().stream().map(p -> p.sourceSide().javaTypeName()))
            .as("each captured column carries its bound Java type as a name, which is what lets"
                + " the renderer declare the key record without a live catalog")
            .containsExactly("java.lang.Integer");
    }

    /**
     * The pairing the anchor exists for, stated on the carrier: a re-read whose anchor captures
     * nothing would filter on no key and re-read the whole table. This is the one construction
     * check the narrowing keeps, and it is the same one the sibling carrier arm makes about its
     * own captured pairs.
     */
    @Test
    void anAnchorCapturingNoKeyIsRefused() {
        var anchor = directArm().anchor();
        assertThatThrownBy(() -> new RoutineWriteCommand.RereadAnchor(
                anchor.table(), anchor.alias(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one key column");
    }

    @Test
    void theCarrierArmCarriesWhatItsCaptureNeeds() {
        var row = carrierArm();

        assertThat(row.unit().methodName()).isEqualTo("rentFilmPayload");
        assertThat(row.call().methodName()).isEqualTo("rentFilm");
        assertThat(row.targetTable().sqlName())
            .as("the captured keys are projected under the target table's own key fields")
            .isEqualTo("rental");
        assertThat(row.capturedPairs().stream().map(s -> s.targetSide().javaName()))
            .as("the payload data field correlates on the target table's key")
            .containsExactly("RENTAL_ID");
        assertThat(row.arity())
            .as("the payload's data field is single, which is this shape's only cardinality claim")
            .isEqualTo(Arity.SINGLE);
        assertThat(row.errors())
            .as("the payload carries an errors field, so the throw is routed back through"
                + " localContext with its mappings constant")
            .isInstanceOf(ErrorDispatch.LocalContextRouted.class);
        assertThat(((ErrorDispatch.LocalContextRouted) row.errors()).mappingsConstantName())
            .isNotBlank();
        assertThat(((ErrorDispatch.LocalContextRouted) row.errors()).errorMappings())
            .isEqualTo(UNITS.errorMappings());
    }

    @Test
    void theRelationIsKeyedByCoordinate() {
        var row = directArm();
        assertThatThrownBy(() -> RoutineWriteRelation.unrouted(List.of(row, row)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyed by coordinate");
    }

    /**
     * The reason this relation carries no method-name census, pinned rather than asserted in prose:
     * the entry point's name is the field's own, so two coordinates that differ only in a letter's
     * case mint two distinct and perfectly legal Java methods. A case-folded census, which the
     * sibling relations need because their upper-camelling formula is not injective, would reject
     * this pair.
     */
    @Test
    void coordinatesDifferingOnlyInCaseMintDistinctMethodsAndAreBothAdmitted() {
        var row = directArm();
        var twin = new RoutineWriteCommand.ChainReread(
            UNITS.fetcherEntryMethod("Mutation", "rentfilm"),
            FieldCoordinates.coordinates("Mutation", "rentfilm"), row.call(),
            row.anchor(), row.hops(),
            row.terminusProjection(), row.arity(), row.errors());

        assertThat(RoutineWriteRelation.unrouted(List.of(row, twin)).rows())
            .as("case-distinct field names are two rows, not a collision")
            .hasSize(2);
        assertThat(twin.unit().methodName()).isNotEqualTo(row.unit().methodName());
    }

    private static RoutineWriteCommand.ChainReread directArm() {
        return chainArm("rentFilm");
    }

    private static RoutineWriteCommand.ChainReread chainArm(String fieldName) {
        return (RoutineWriteCommand.ChainReread) plan.routineWrites()
            .rowFor("Mutation", fieldName).orElseThrow();
    }

    /** The leaf's own classified chain, which every narrowing check above joins back to. */
    private static List<JoinStep> hopsOf(String fieldName) {
        return ((MutationField.MutationRoutineWriteField) model.fields()
            .get(FieldCoordinates.coordinates("Mutation", fieldName))).chain().hops();
    }

    private static RoutineWriteCommand.CarrierKeys carrierArm() {
        return (RoutineWriteCommand.CarrierKeys) plan.routineWrites()
            .rowFor("Mutation", "rentFilmPayload").orElseThrow();
    }
}
