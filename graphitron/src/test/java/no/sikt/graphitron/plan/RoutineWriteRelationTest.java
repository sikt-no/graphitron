package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.RoutineCall;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The routine-write relation's membership enforcer and per-arm fact pins, in the fetcher edge
 * relation's shape: the relation's coordinate keys equal exactly the two routine-write leaves'
 * coordinates derived from the model (never a hand tag), the keys are disjoint from the launcher
 * relation's, and each arm's witness pins the facts its renderer reads.
 *
 * <p>The fixture carries all three mutation shapes on one schema so membership is tested against a
 * live non-member rather than against an empty relation: a chain-re-reading routine write, a
 * payload-carrier routine write whose payload has an {@code @error} field (so the routed dispatch
 * arm is reached rather than only the redacting one), and a DML insert.
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
        input FilmInput { title: String }

        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            @reference(path: [{table: "rental"}])
          rentFilmPayload(inventoryId: Int!, customerId: Int!): RentFilmPayload
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    private static final GeneratedUnits UNITS = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);

    private static GraphitronSchema model;
    private static EmitPlan plan;

    @BeforeAll
    static void producePlan() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        model = bundle.model();
        plan = EmitPlan.produceWithoutStore(model, bundle.federationLink(), bundle.usesOneOf(),
            DEFAULT_OUTPUT_PACKAGE);
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
            .hasSize(2);
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
        var row = directArm();
        var hops = ((MutationField.MutationRoutineWriteField)
            model.fields().get(FieldCoordinates.coordinates("Mutation", "rentFilm"))).chain().hops();
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
                + " anchor itself")
            .isEqualTo(((JoinStep.Hop) hops.getLast()).alias());
        var hop0Slots = ((no.sikt.graphitron.rewrite.model.On.ColumnPairs) hop0.on()).slots();
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
        return (RoutineWriteCommand.ChainReread) plan.routineWrites()
            .rowFor("Mutation", "rentFilm").orElseThrow();
    }

    private static RoutineWriteCommand.CarrierKeys carrierArm() {
        return (RoutineWriteCommand.CarrierKeys) plan.routineWrites()
            .rowFor("Mutation", "rentFilmPayload").orElseThrow();
    }
}
