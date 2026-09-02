package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.render.ConditionGlueRenderer;
import no.sikt.graphitron.plan.KeyProjectionCommands;
import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.decodedKeyMaterialisations;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.descendsWireValue;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.invocationTakesProjectedRead;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationDecodesDescentTo;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationDecodesWireDescent;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationDecodesWireSlot;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationPrecedesFirstRead;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationPrecedesWriteTransaction;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.projectedColumnReads;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.readsColumnByName;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.readsSlotThroughTypedAccessor;
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
 * capture to a running query is the execution tier's. Here the subject is the emission, asked as
 * typed questions through
 * {@link no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions} so the rendered spelling
 * lives in one place rather than being pinned as code strings here.
 */
@PipelineTier
class ArgmappingKeyProjectionEmissionPipelineTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;

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
        var fetchers = mutationFetchers(SDL);

        assertThat(materialisationDecodesWireDescent(fetchers, "rentFilm", "keyInputInventoryId",
                "decodeInventoryRecord", "argInputInventoryId", "input"))
            .as("the wire value descends to the @nodeId leaf and the decode materialises the record")
            .isTrue();
        assertThat(invocationTakesProjectedRead(fetchers, "rentFilm", "Routines.rentFilm",
                "keyInputInventoryId", "INVENTORY", "INVENTORY_ID"))
            .as("the column is named, not indexed: a transposed composite projection cannot be built")
            .isTrue();
        assertThat(descendsWireValue(fetchers, "rentFilm", "argInputCustomerId", "input"))
            .as("the unprojected sibling parameter still reads its value straight off the wire map")
            .isTrue();
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
        var fetchers = mutationFetchers(BARE_SIBLING_SDL);

        assertThat(projectedColumnReads(fetchers, "rentFilm", "keyInputInventoryId"))
            .as("the projected sibling still reads its column off the decoded record")
            .isPositive();
        assertThat(readsSlotThroughTypedAccessor(fetchers, "rentFilm", "customerId",
                "java.lang.Integer"))
            .as("the bare slot reads through the typed env accessor, untouched by the sink")
            .isTrue();
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
        var fetchers = mutationFetchers(SHARED_ID_SDL);

        assertThat(decodedKeyMaterialisations(fetchers, "rentFilm", "keyInputInventoryId"))
            .as("one declaration")
            .isEqualTo(1);
        assertThat(projectedColumnReads(fetchers, "rentFilm", "keyInputInventoryId"))
            .as("both parameters read off it")
            .isEqualTo(2);
    }

    /**
     * The declaration precedes the {@code try} the write opens. Inside it, the entry point's
     * {@code catch (Exception e)} would route a malformed node id through the field's error channel,
     * redacting or re-reporting a client error about an argument as a failure of the write. The
     * behavioral half lives at the execution tier: {@code GraphQLQueryTest} pins that a bad id
     * surfaces as a request error and commits nothing.
     */
    @Test
    void theDecodeHappensBeforeTheWriteTransactionIsEntered() {
        assertThat(materialisationPrecedesWriteTransaction(mutationFetchers(SDL), "rentFilm",
                "keyInputInventoryId"))
            .isTrue();
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
        assertThatThrownBy(() -> TestSchemaHelper.storeBackedPlan(tmp, SDL,
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext(),
            new ResolvedKeyProjections.Projections(List.of(
                inventoryProjection("Query", "rental", "input.inventoryId.inventory_id")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no emitter at that coordinate reads a projection")
            .hasMessageContaining("Query.rental");
    }

    // ===== The inferred arm: the author stopped on the node id and the key's arity named the column =====

    /**
     * A dotted binding that stops on the {@code @nodeId} input field. Nothing follows the node id, so
     * the wire id sits at the whole written path, and the emission is the authored form's exactly: one
     * decode of the value descended to {@code input.inventoryId}, and the sole key column read off it.
     * Before the row carried its provenance the leaf was taken to be one segment short of the path's
     * end unconditionally, so this shape decoded {@code env.getArgument("input")}, the whole input
     * object, which the helper's wire-shape guard turns into a null record and the next line into an
     * NPE on every request.
     */
    @Test
    void anInferredDottedBindingDecodesTheNodeIdsOwnSlot() {
        var fetchers = mutationFetchers(INFERRED_SDL,
            inventoryProjection("Mutation", "rentFilm", "input.inventoryId"));

        assertThat(materialisationDecodesWireDescent(fetchers, "rentFilm", "keyInputInventoryId",
                "decodeInventoryRecord", "argInputInventoryId", "input"))
            .as("the decode's argument is the node id's own slot, not the input object above it")
            .isTrue();
        assertThat(invocationTakesProjectedRead(fetchers, "rentFilm", "Routines.rentFilm",
                "keyInputInventoryId", "INVENTORY", "INVENTORY_ID"))
            .as("the inferred column is read by name, as the spelled-out form's is")
            .isTrue();
    }

    /** The motivating fixture with the column left unspelled: {@code input.inventoryId} and no more. */
    private static final String INFERRED_SDL =
        SDL.replace("pInventoryId: input.inventoryId.inventory_id", "pInventoryId: input.inventoryId");

    /** A {@code @nodeId} argument bound bare, the shape the sink used to decline outright. */
    private static final String BARE_NODE_ID_SDL = SDL
        .replace("rentFilm(input: RentFilmInput!)",
            "rentFilm(inventoryId: ID! @nodeId(typeName: \"Inventory\"), customerId: Int!)")
        .replace("pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId",
            "pInventoryId: inventoryId, pCustomerId: customerId");

    /**
     * A bare binding to a {@code @nodeId} argument at a {@code @routine}. The projection resolves and
     * is emitted: the encoded id is the slot's own value, so the decode takes the slot read directly.
     * The sink used to decline every single-segment path before consulting the relation, so this
     * coordinate read the base64 string off the wire and handed it to a parameter typed for the
     * column, which is the undecoded-wire-value escape the whole family exists to close.
     */
    @Test
    void aBareBindingToANodeIdArgumentDecodesTheSlot() {
        var fetchers = mutationFetchers(BARE_NODE_ID_SDL,
            inventoryProjection("Mutation", "rentFilm", "inventoryId"));

        assertThat(materialisationDecodesWireSlot(fetchers, "rentFilm", "keyInventoryId",
                "decodeInventoryRecord", "inventoryId"))
            .as("the slot read is the decode's argument, with no descent to compose")
            .isTrue();
        assertThat(invocationTakesProjectedRead(fetchers, "rentFilm", "Routines.rentFilm",
                "keyInventoryId", "INVENTORY", "INVENTORY_ID"))
            .as("the routine gets the column, never the base64 string")
            .isTrue();
        assertThat(readsSlotThroughTypedAccessor(fetchers, "rentFilm", "inventoryId",
                "java.lang.Long"))
            .as("and the raw typed read of that slot is gone, not merely joined by a decode")
            .isFalse();
    }

    /** Two node ids in one input, one opened with its column and one left closed. */
    private static final String MIXED_ARMS_SDL = """
        type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
            id: ID!
        }
        type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
            id: ID!
        }
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental, inventory: Inventory, customer: Customer }
        input RentFilmInput {
            inventoryId: ID! @nodeId(typeName: "Inventory")
            customerId: ID! @nodeId(typeName: "Customer")
        }
        type Mutation {
          rentFilm(input: RentFilmInput!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    /**
     * An authored projection and an inferred one at the same coordinate each read their own leaf. The
     * derivation is per row and not per method: one written path is one segment longer than its node
     * id and the other is exactly its node id, and a method-wide arithmetic would aim one of the two
     * decodes at the wrong slot whichever way it went.
     */
    @Test
    void anAuthoredAndAnInferredArmAtOneCoordinateEachReadTheirOwnLeaf() {
        var fetchers = mutationFetchers(MIXED_ARMS_SDL,
            inventoryProjection("Mutation", "rentFilm", "input.inventoryId.inventory_id"),
            customerProjection("Mutation", "rentFilm", "input.customerId"));

        assertThat(materialisationDecodesWireDescent(fetchers, "rentFilm", "keyInputInventoryId",
                "decodeInventoryRecord", "argInputInventoryId", "input"))
            .as("the authored arm's leaf is its path minus the column it spelled")
            .isTrue();
        assertThat(materialisationDecodesWireDescent(fetchers, "rentFilm", "keyInputCustomerId",
                "decodeCustomerRecord", "argInputCustomerId", "input"))
            .as("the inferred arm's leaf is its whole path")
            .isTrue();
        assertThat(invocationTakesProjectedRead(fetchers, "rentFilm", "Routines.rentFilm",
                "keyInputInventoryId", "INVENTORY", "INVENTORY_ID")).isTrue();
        assertThat(invocationTakesProjectedRead(fetchers, "rentFilm", "Routines.rentFilm",
                "keyInputCustomerId", "CUSTOMER", "CUSTOMER_ID")).isTrue();
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

        String glue = glueMethodOf(conditions);
        assertThat(materialisationDecodesDescentTo(conditions, glue, "keyInFilmId",
                "decodeFilmRecord", "filmId"))
            .as("the decode's argument descends to the @nodeId input field, not the argument above it")
            .isTrue();
        assertThat(decodedKeyMaterialisations(conditions, glue, "keyInFilmId"))
            .as("the wire value descends to the @nodeId leaf and the decode materialises the record")
            .isEqualTo(1);
        assertThat(readsColumnByName(conditions, glue, "keyInFilmId", "FILM", "FILM_ID"))
            .as("the column is named, not indexed")
            .isTrue();
        assertThat(materialisationPrecedesFirstRead(conditions, glue, "keyInFilmId"))
            .as("the materialisation precedes the binding local that reads it")
            .isTrue();
    }

    /** The authored-arm default at the {@code @condition} site. */
    private static TypeSpec conditionsClass() {
        return conditionsClass(CONDITION_SDL,
            filmProjection("Query", "films", "in.filmId.film_id"));
    }

    /** The same {@code @condition} fixture with the column left unspelled. */
    private static final String INFERRED_CONDITION_SDL =
        CONDITION_SDL.replace("filmId: in.filmId.film_id", "filmId: in.filmId");

    /**
     * The field-level {@code @condition} whose {@code argMapping} stops on the {@code @nodeId} input
     * field. Nothing follows the node id, so the decode's argument is the descent to that field, and
     * the glue is the spelled-out form's exactly. The old arithmetic took the node id to sit one
     * segment short of the path's end here too, so this shape decoded the whole {@code in} object and
     * every request against the coordinate failed; and because the projection is looked up by the
     * written path, the input field's <em>own</em> implicit predicate spells the same path and broke
     * with it.
     */
    @Test
    void anInferredConditionBindingDecodesTheNodeIdInputField() {
        var conditions = conditionsClass(INFERRED_CONDITION_SDL,
            filmProjection("Query", "films", "in.filmId"));
        String glue = glueMethodOf(conditions);

        assertThat(materialisationDecodesDescentTo(conditions, glue, "keyInFilmId",
                "decodeFilmRecord", "filmId"))
            .as("the decode's argument is the node id's own field, not the input object above it")
            .isTrue();
        assertThat(decodedKeyMaterialisations(conditions, glue, "keyInFilmId"))
            .as("once, as the spelled-out form does")
            .isEqualTo(1);
        assertThat(readsColumnByName(conditions, glue, "keyInFilmId", "FILM", "FILM_ID"))
            .as("the inferred column is read by name")
            .isTrue();
    }

    /**
     * A {@code @condition} on the {@code @nodeId} input field itself, with a projection deliberately
     * spelled at the key the glue looks up by. The install rail wins: the parameter gets the
     * whole-slot decode stated at the slot, and no projected record is materialised beside it.
     *
     * <p>The row is spelled to construct the race rather than to mirror the store, and that is the
     * point of the case. The store keys an input-field {@code @condition}'s projection at the input
     * type's own coordinate, while the glue this rewrap produces looks up by the consuming field's, so
     * the two do not meet on any SDL today. What used to keep them apart at every other coordinate was
     * the sink refusing single-segment paths outright, which was also what kept every inferred
     * projection from being emitted; removing that refusal leaves the precedence as the only thing
     * standing between two mechanisms and one parameter, so it is asserted where it can be made to
     * fail rather than where it currently cannot.
     */
    @Test
    void aWholeSlotBindingKeepsItsInstalledDecodeWhenAProjectionAlsoResolves() {
        var conditions = conditionsClass(WHOLE_SLOT_CONDITION_SDL,
            filmProjection("Query", "films", "in.filmId"));
        String glue = glueMethodOf(conditions);

        assertThat(conditions.methodSpecs())
            .as("the install rail's decode is the one the class hosts; no record decode is minted")
            .extracting(MethodSpec::name)
            .contains("decodeFilmKeyOrThrow")
            .doesNotContain("decodeFilmRecord");
        assertThat(decodedKeyMaterialisations(conditions, glue, "keyInFilmId"))
            .as("the sink stood aside: nothing was materialised for it to project off")
            .isZero();
        assertThat(projectedColumnReads(conditions, glue, "keyInFilmId"))
            .as("and no column read fired")
            .isZero();
    }

    /** The whole-slot shape: the {@code @condition} sits on the {@code @nodeId} field itself. */
    private static final String WHOLE_SLOT_CONDITION_SDL = """
        type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID!
            title: String
        }
        input FilmPick {
            filmId: ID! @nodeId(typeName: "Film") @condition(condition: {
                className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                method: "filmIdKeyEquals"
            })
        }
        type Query {
            film: Film
            films(in: FilmPick!): [Film!]!
        }
        """;

    /** The one conditions class the fixture's single glue owner produces, rendered through the plan. */
    private static TypeSpec conditionsClass(String sdl,
            ResolvedKeyProjections.Projection... rows) {
        var projections = new ResolvedKeyProjections.Projections(List.of(rows));
        var plan = TestSchemaHelper.storeBackedPlan(tmp, sdl,
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext(), projections);
        assertThat(plan.conditions().rows())
            .as("the fixture's @condition mints a row, so the glue below is not empty by accident")
            .isNotEmpty();
        var classes = ConditionGlueRenderer.render(plan.conditions().rows(), DEFAULT_OUTPUT_PACKAGE,
            plan.keyProjections());
        assertThat(classes).hasSize(1);
        return classes.getFirst();
    }

    /** The one glue method a conditions class holds: its name is the command row's to mint. */
    private static String glueMethodOf(TypeSpec conditions) {
        return conditions.methodSpecs().stream()
            .filter(m -> m.returnType().toString().equals("org.jooq.Condition"))
            .map(MethodSpec::name)
            .findFirst().orElseThrow();
    }

    private static List<MethodSpec> helpers(String sdl) {
        return mutationFetchers(sdl).methodSpecs();
    }

    /** The authored-arm default: every fixture derived from {@link #SDL} spells the column. */
    private static TypeSpec mutationFetchers(String sdl) {
        return mutationFetchers(sdl,
            inventoryProjection("Mutation", "rentFilm", "input.inventoryId.inventory_id"));
    }

    /**
     * The {@code MutationFetchers} class, generated through the plan so the reachability gate and the
     * relation's own routing are exercised rather than bypassed. The projections are the one input the
     * walk cannot supply, so they are spelled here as the store would have resolved them.
     */
    private static TypeSpec mutationFetchers(String sdl,
            ResolvedKeyProjections.Projection... rows) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var schema = bundle.model();
        var projections = new ResolvedKeyProjections.Projections(List.of(rows));
        var plan = TestSchemaHelper.storeBackedPlan(tmp, sdl, no.sikt.graphitron.common.configuration.TestConfiguration.testContext(),
            projections);
        assertThat(KeyProjectionCommands.produce(projections).rows())
            .as("the fixture's projections reach command rows, so the emission below is not passing"
                + " because nothing was carried")
            .hasSize(rows.length);
        return TypeFetcherGenerator.generate(schema, bundle.assembled(), DEFAULT_OUTPUT_PACKAGE,
                plan.launchers(), plan.typeUnits().fetchers(), plan.typeUnits().errorFetchers(),
                plan.routineWrites(), plan.keyProjections()).stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst().orElseThrow();
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
    private static final ColumnRef CUSTOMER_ID =
        new ColumnRef("customer_id", "CUSTOMER_ID", "java.lang.Long");

    /*
     * The trailing segment is the store's record of which resolution answered, and the fixtures
     * derive it the way the view does rather than taking it as a parameter: the authored arm's is the
     * path's own last segment, and the inferred arm has none. Deriving it here is what keeps a fixture
     * from spelling a combination the store cannot produce, an inferred row against a dotted-to-the-
     * column path being exactly the off-by-one these tests exist to catch.
     */
    private static ResolvedKeyProjections.Projection inventoryProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path,
            trailingSegmentOf(path, INVENTORY_ID), "Inventory", "Inventory",
            TestFixtures.tableRef("inventory", "INVENTORY", "Inventory", List.of(INVENTORY_ID)),
            List.of(INVENTORY_ID), INVENTORY_ID);
    }

    private static ResolvedKeyProjections.Projection filmProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path,
            trailingSegmentOf(path, FILM_ID), "Film", "Film",
            TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID)),
            List.of(FILM_ID), FILM_ID);
    }

    private static ResolvedKeyProjections.Projection customerProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path,
            trailingSegmentOf(path, CUSTOMER_ID), "Customer", "Customer",
            TestFixtures.tableRef("customer", "CUSTOMER", "Customer", List.of(CUSTOMER_ID)),
            List.of(CUSTOMER_ID), CUSTOMER_ID);
    }

    /** The path's last segment where it names {@code column}, and null where it does not. */
    private static String trailingSegmentOf(String path, ColumnRef column) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        return last.equalsIgnoreCase(column.sqlName()) ? last : null;
    }

}
