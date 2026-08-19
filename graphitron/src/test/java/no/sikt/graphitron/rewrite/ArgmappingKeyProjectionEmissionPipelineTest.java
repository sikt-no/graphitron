package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.render.ConditionGlueRenderer;
import no.sikt.graphitron.plan.KeyProjectionCommands;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;
import no.sikt.graphitron.rewrite.model.ColumnRef;
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

    /** A fixture whose unprojected sibling binds a bare slot rather than a dotted path. */
    private static final String BARE_SIBLING_SDL = SDL
        .replace("input: RentFilmInput!", "input: RentFilmInput!, customerId: Int!")
        .replace("pCustomerId: input.customerId", "pCustomerId: customerId");

    /**
     * A bare-slot binding beside a projected one renders the ordinary typed read. The case exists
     * because the sink derives a projected path's leaf by dropping its last segment, which a
     * one-segment path has no room for: asking the relation before deriving anything is what keeps an
     * ordinary binding from tripping that invariant, and every fixture above happens to bind through a
     * dotted path, so nothing else here would notice.
     */
    @Test
    void aBareSlotBindingBesideAProjectedOneStillReadsItsSlot() {
        String body = fetcherBody(BARE_SIBLING_SDL);

        assertThat(body)
            .contains("keyInputInventoryId.get(")
            .as("the bare slot reads through the typed env accessor, untouched by the sink")
            .contains("env.<java.lang.Integer>getArgument(\"customerId\")");
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
                inventoryProjection("Query", "rental", "input.inventoryId.inventory_id")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no emitter at that coordinate reads a projection")
            .hasMessageContaining("Query.rental");
    }

    /** The same projection at the {@code @condition} site, whose glue hosts its own decode body. */
    private static final String CONDITION_SDL = """
        type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID!
            title: String
        }
        input FilmPick { filmId: ID! @nodeId(typeName: "Film") }
        type Query {
            film: Film
            films(in: FilmPick!): [Film!]! @condition(condition: {
                className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                method: "filmIdKeyEquals",
                argMapping: "filmId: in.filmId.film_id"
            })
        }
        """;

    /**
     * The shape an author reaches for first: a {@code @condition} whose method parameter binds a key
     * column of a {@code @nodeId}-carrying input field. The glue reads the column off a decoded record
     * rather than handing the method the base64 wire id, and the decode body is hosted on the conditions
     * class, which is what made this site cost more than the routine one: the bodies a
     * {@code <Type>Fetchers} class hosts are unreachable from there.
     */
    @Test
    void aConditionParameterReadsTheProjectedColumnOffADecodedRecord() {
        var conditions = conditionsClass();

        assertThat(conditions.methodSpecs())
            .as("the decode body is hosted on the conditions class itself")
            .extracting(MethodSpec::name)
            .contains("decodeFilmRecord");

        String glue = conditions.methodSpecs().stream()
            .filter(m -> m.name().startsWith("filmsCondition") || m.name().contains("films"))
            .map(m -> m.code().toString())
            .findFirst().orElseThrow();
        assertThat(glue)
            .as("the wire value descends to the @nodeId leaf and the decode materialises the record")
            .contains("keyInFilmId = decodeFilmRecord(")
            .as("the column is named, not indexed")
            .containsPattern("keyInFilmId\\.get\\([\\w.]*Tables\\.FILM\\.FILM_ID\\)");
        assertThat(glue.indexOf("keyInFilmId = decodeFilmRecord("))
            .as("the materialisation precedes the binding local that reads it")
            .isLessThan(glue.indexOf("keyInFilmId.get("));
    }

    /** The one conditions class the fixture's single glue owner produces, rendered through the plan. */
    private static TypeSpec conditionsClass() {
        var bundle = TestSchemaHelper.buildBundle(CONDITION_SDL);
        var projections = new ResolvedKeyProjections.Projections(List.of(
            filmProjection("Query", "films", "in.filmId.film_id")));
        var plan = EmitPlan.produce(bundle.model(), bundle.federationLink(), bundle.usesOneOf(),
            DEFAULT_OUTPUT_PACKAGE, projections);
        assertThat(plan.conditions().rows())
            .as("the fixture's @condition mints a row, so the glue below is not empty by accident")
            .isNotEmpty();
        var classes = ConditionGlueRenderer.render(plan.conditions().rows(), DEFAULT_OUTPUT_PACKAGE,
            plan.keyProjections());
        assertThat(classes).hasSize(1);
        return classes.getFirst();
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
            inventoryProjection("Mutation", "rentFilm", "input.inventoryId.inventory_id")));
        var plan = EmitPlan.produce(schema, bundle.federationLink(), bundle.usesOneOf(),
            DEFAULT_OUTPUT_PACKAGE, projections);
        assertThat(KeyProjectionCommands.produce(projections).rows())
            .as("the fixture's projection reaches a command row, so the emission below is not passing"
                + " because nothing was carried")
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

    // ===== The store rows these fixtures stand in for =====

    /*
     * Spelled by hand because TestSchemaHelper builds a model with no store behind it, and written to
     * be exactly what StoreNodeTables would have assembled from the fixture catalog: the same generated
     * class names, the same key order, the same type id (the fixtures declare no typeId:, so it is the
     * type's own name). That the real assembly agrees is StoreNodeTablesTest's claim against a captured
     * store; what these fixtures need is a row of the right shape so the emission below is the subject.
     */

    private static final ColumnRef INVENTORY_ID =
        new ColumnRef("inventory_id", "INVENTORY_ID", "java.lang.Long");
    private static final ColumnRef FILM_ID = new ColumnRef("film_id", "FILM_ID", "java.lang.Long");

    private static ResolvedKeyProjections.Projection inventoryProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path, "Inventory",
            "Inventory",
            TestFixtures.tableRef("inventory", "INVENTORY", "Inventory", List.of(INVENTORY_ID)),
            List.of(INVENTORY_ID), INVENTORY_ID);
    }

    private static ResolvedKeyProjections.Projection filmProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path, "Film", "Film",
            TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID)),
            List.of(FILM_ID), FILM_ID);
    }

}
