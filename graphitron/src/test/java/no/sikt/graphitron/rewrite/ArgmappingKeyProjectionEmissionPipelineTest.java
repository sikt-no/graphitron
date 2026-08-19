package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.plan.KeyProjectionCommands;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a projected {@code argMapping} binding emits: the node id decoded once into the target record,
 * and the named key column read off it. The claim the whole item turns on, at the tier that can see
 * the emitted shape.
 *
 * <p>Three properties are pinned rather than one, because each has its own way of going wrong. The
 * decode is <b>named</b>, so a transposed composite-key projection is unconstructable. It happens
 * <b>once</b> per node id however many parameters read columns off it, so one bad id has one failure
 * point. And it happens <b>outside</b> the write transaction, because the entry point catches
 * everything inside that transaction and routes it through the field's error channel, where a
 * malformed node id has no business: it is a client error about an argument, not a database error
 * about a write.
 *
 * <p>The store side is handed in, as in {@code KeyProjectionRelationTest}: what the view resolves is
 * the model module's to pin, and the end-to-end path from an authored {@code argMapping} through
 * capture to a running query is the execution tier's. Here the subject is the emission.
 */
@PipelineTier
class ArgmappingKeyProjectionEmissionPipelineTest {

    /** The motivating shape: a {@code @nodeId} input field opened with the node type's key column. */
    private static final String SDL = """
        type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
            id: ID!
        }
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental, inventory: Inventory }
        input RentFilmInput {
            inventoryId: ID! @nodeId(typeName: "Inventory")
            customerId: Int!
        }
        type Mutation {
          rentFilm(input: RentFilmInput!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    /** Two routine parameters bound to one node id, which is what "materialise once" is about. */
    private static final String SHARED_ID_SDL = SDL.replace(
        "pCustomerId: input.customerId", "pCustomerId: input.inventoryId.inventory_id");

    @Test
    void aProjectedParameterReadsTheNamedColumnOffADecodedRecord() {
        String body = fetcherBody(SDL);

        assertThat(body)
            .as("the wire value descends to the @nodeId leaf and the decode materialises the record")
            .contains("InventoryRecord keyInputInventoryId = "
                + "decodeInventoryRecord(argInputInventoryId(env.getArgument(\"input\")))")
            .as("the column is named, not indexed: a transposed composite projection cannot be built")
            .containsPattern("Routines\\.rentFilm\\(keyInputInventoryId\\.get\\("
                + "[\\w.]*Tables\\.INVENTORY\\.INVENTORY_ID\\)");
        assertThat(body)
            .as("the unprojected sibling parameter still reads its value straight off the wire map")
            .contains("argInputCustomerId(env.getArgument(\"input\"))");
    }

    /**
     * The descent to a projected leaf hands the raw wire value on rather than casting it to the
     * routine parameter's type. Casting there would be wrong twice: the value is a base64 string and
     * the parameter is the column's type, and the decode helper's own wire-shape guard is what turns a
     * non-string into a null decode rather than a request-time cast failure.
     */
    @Test
    void theDescentToAProjectedLeafIsUntyped() {
        var helper = helpers(SDL).stream()
            .filter(m -> m.name().equals("argInputInventoryId"))
            .findFirst().orElseThrow();

        assertThat(helper.returnType()).isEqualTo(ClassName.get(Object.class));
        assertThat(helper.parameters()).singleElement()
            .extracting(ParameterSpec::type).isEqualTo(ClassName.get(Object.class));
    }

    /**
     * One decode per node id, not one per parameter. Two identical materialisations would give one bad
     * id two identical failure points and cost a second decode for nothing; the second read finds the
     * local the first declared.
     */
    @Test
    void twoParametersOffOneNodeIdShareOneMaterialisation() {
        String body = fetcherBody(SHARED_ID_SDL);

        assertThat(count(body, "InventoryRecord keyInputInventoryId ="))
            .as("one declaration")
            .isEqualTo(1);
        assertThat(count(body, "keyInputInventoryId\\.get\\("))
            .as("both parameters read off it")
            .isEqualTo(2);
    }

    /**
     * The declaration precedes the {@code try} the write opens. Inside it, the entry point's
     * {@code catch (Exception e)} would route a malformed node id through the field's error channel,
     * redacting or re-reporting a client error about an argument as a failure of the write.
     */
    @Test
    void theDecodeHappensBeforeTheWriteTransactionIsEntered() {
        String body = fetcherBody(SDL);

        assertThat(body.indexOf("InventoryRecord keyInputInventoryId ="))
            .isGreaterThanOrEqualTo(0)
            .isLessThan(body.indexOf("try {"));
    }

    /**
     * A projection at a coordinate no wired emitter owns stops the build. The store-side deferral is
     * keyed on the directive and cannot see that one site's emitters are wired unevenly, so this is
     * the gate that keeps an unreached shape from rendering as an ordinary nested read: a base64 node
     * id handed to a routine parameter, which is the silence the family exists to close arriving
     * through the back door.
     */
    @Test
    void aProjectionNoEmitterOwnsStopsThePlan() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        assertThatThrownBy(() -> EmitPlan.produce(bundle.model(), bundle.federationLink(),
            bundle.usesOneOf(), DEFAULT_OUTPUT_PACKAGE,
            new ResolvedKeyProjections.Projections(List.of(
                new ResolvedKeyProjections.Projection("Query", "rental",
                    "input.inventoryId.inventory_id", "Inventory", "inventory_id")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no emitter at that coordinate reads a projection")
            .hasMessageContaining("Query.rental");
    }

    private static String fetcherBody(String sdl) {
        return mutationFetchers(sdl).methodSpecs().stream()
            .filter(m -> m.name().equals("rentFilm"))
            .findFirst().orElseThrow()
            .code().toString();
    }

    private static List<MethodSpec> helpers(String sdl) {
        return mutationFetchers(sdl).methodSpecs();
    }

    /**
     * The {@code MutationFetchers} class, generated through the plan so the reachability gate and the
     * relation's own routing are exercised rather than bypassed. The projection is the one input the
     * walk cannot supply, so it is spelled here as the store would have resolved it.
     */
    private static TypeSpec mutationFetchers(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var schema = bundle.model();
        var projections = new ResolvedKeyProjections.Projections(List.of(
            new ResolvedKeyProjections.Projection("Mutation", "rentFilm",
                "input.inventoryId.inventory_id", "Inventory", "inventory_id")));
        var plan = EmitPlan.produce(schema, bundle.federationLink(), bundle.usesOneOf(),
            DEFAULT_OUTPUT_PACKAGE, projections);
        assertThat(KeyProjectionCommands.produce(projections, schema).rows())
            .as("the fixture's projection resolves against the model, so the emission below is not"
                + " passing because nothing was carried")
            .hasSize(1);
        return TypeFetcherGenerator.generate(schema, bundle.assembled(), DEFAULT_OUTPUT_PACKAGE,
                plan.launchers(), plan.typeUnits().fetchers(), plan.routineWrites(),
                plan.keyProjections()).stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst().orElseThrow();
    }

    private static long count(String body, String regex) {
        return Pattern.compile(regex).matcher(body).results().count();
    }
}
