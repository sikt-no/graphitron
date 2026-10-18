package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.render.ConditionGlueRenderer;
import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.decodedKeyMaterialisations;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.declaresGuardedColumnRead;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationDecodesDescentTo;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.materialisationPrecedesFirstRead;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.projectedColumnReads;
import static no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.readsColumnByName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a projected {@code argMapping} binding emits: the node id decoded once into the target record,
 * and the named key column read off it. The claim the whole item turns on, at the tier that can see
 * the emitted shape.
 *
 * <p>Four properties are pinned rather than one, because each has its own way of going wrong. The
 * decode is <b>named</b>, so a transposed composite-key projection is unconstructable. It happens
 * <b>once</b> per node id however many parameters read columns off it, so one bad id has one failure
 * point. It happens <b>outside</b> the write transaction, because the entry point catches
 * everything inside that transaction and routes it through the field's error channel, where a
 * malformed node id has no business: it is a client error about an argument, not a database error
 * about a write. And the column read is <b>hoisted and guarded</b>, a declared local the consumer
 * splices by name, which is what lets an omitted {@code @nodeId} anywhere along the projected path
 * reach the routine or the condition as a plain null instead of crashing the request on a read off a
 * record the decode returned as null.
 *
 * <p>The store side is handed in, as in {@code KeyProjectionRelationTest}: what the view resolves is
 * the model module's to pin, and the end-to-end path from an authored {@code argMapping} through
 * capture to a running query is the execution tier's. Here the subject is the emission, asked as
 * typed questions through
 * {@link no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions} so the rendered spelling
 * lives in one place rather than being pinned as code strings here. The routine-write site is not
 * asked here: {@code GraphQLQueryTest} runs each of its binding shapes against the database.
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
        assertThat(declaresGuardedColumnRead(conditions, glue, "keyInFilmId", "FILM", "FILM_ID"))
            .as("and the read is a guarded prelude local, so an absent node id binds null")
            .isTrue();
        assertThat(materialisationPrecedesFirstRead(conditions, glue, "keyInFilmId"))
            .as("the materialisation precedes the guarded read that projects off it")
            .isTrue();
    }

    /** The authored-arm default at the {@code @condition} site. */
    private static TypeSpec conditionsClass() {
        return conditionsClass(CONDITION_SDL,
            filmProjection("Query", "films", "in.filmId.film_id"));
    }

    /**
     * Two {@code @node} types over {@code film}, one {@code @nodeId} input field naming each, both
     * projected at a key column behind a {@code @condition} on the same condition class. The two
     * coordinates land on one conditions class, so the class hosts both decodes.
     */
    private static final String SIBLING_CONDITION_SDL = """
        type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID!
            title: String
        }
        type FilmAlias implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID!
            title: String
        }
        input FilmPick { filmId: ID! @nodeId(typeName: "Film") }
        input FilmAliasPick { filmId: ID! @nodeId(typeName: "FilmAlias") }
        type Query {
            film: Film
            films(in: FilmPick!): [Film!]! @condition(condition: {
                className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                method: "filmIdKeyEquals",
                argMapping: "filmId: in.filmId.film_id"
            })
            aliasFilms(in: FilmAliasPick!): [Film!]! @condition(condition: {
                className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                method: "filmIdKeyEquals",
                argMapping: "filmId: in.filmId.film_id"
            })
        }
        """;

    /**
     * The conditions-class half of the decode identity. This class's record decode used to be keyed
     * and named from the projected table's record class, so two node types over one table collapsed
     * onto one body carrying one typeId: the same defect the fetchers host had, at the second host
     * that mints these bodies. Keyed on the node type, the class hosts one per type.
     */
    @Test
    void siblingNodeTypesOverOneTable_eachConditionGetsItsOwnDecodeOnTheConditionsClass() {
        var conditions = conditionsClass(SIBLING_CONDITION_SDL,
            filmProjection("Query", "films", "in.filmId.film_id"),
            filmAliasProjection("Query", "aliasFilms", "in.filmId.film_id"));

        // The record family only: the class also hosts the key-grain decodes the install rail lifts
        // (decode<TypeName>KeyOrThrow), which are type-name keyed already and are what the Record
        // suffix keeps this family apart from.
        assertThat(conditions.methodSpecs())
            .extracting(MethodSpec::name)
            .filteredOn(n -> n.startsWith("decode") && n.endsWith("Record"))
            .as("one decode body per node type, each named after its own type")
            .containsExactlyInAnyOrder("decodeFilmRecord", "decodeFilmAliasRecord");
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

    /** {@link #filmProjection}'s sibling: the second node type over {@code film}. */
    private static ResolvedKeyProjections.Projection filmAliasProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path,
            trailingSegmentOf(path, FILM_ID), "FilmAlias", "FilmAlias",
            TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID)),
            List.of(FILM_ID), FILM_ID);
    }

    /** The path's last segment where it names {@code column}, and null where it does not. */
    private static String trailingSegmentOf(String path, ColumnRef column) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        return last.equalsIgnoreCase(column.sqlName()) ? last : null;
    }

}
