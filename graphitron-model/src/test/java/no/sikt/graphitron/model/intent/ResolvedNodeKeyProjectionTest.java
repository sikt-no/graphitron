package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedCatalogRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_resolved_node_key_projection} returns: the {@code argMapping} bindings that
 * decode a node id and project one column out of the decoded key. The reduction the item turns on,
 * and the row an emitter reads to know which column of a decoded record a routine parameter gets.
 *
 * <p>It is a reduction over the relations beside it and holds one rule of its own, the type
 * agreement, so the cases here are mostly about where the meeting fails. Absence means this
 * pair is not a projection, and each way of arriving at absence is a query over the relations
 * beside it: nothing trailing is the bare form, two or more trailing segments is the typo, a
 * trailing segment matching no key column is the unknown column, one matching a column the parameter
 * cannot take is the type mismatch, a bare {@code @nodeId} is the missing {@code typeName:}, and no
 * leaf at all is a path the walk rejects before the store is written. None of them is this relation's
 * to report, which is what keeps it a population an emitter can trust rather than a verdict it has to
 * interpret.
 *
 * <p>The type agreement fires only where both operands are known, and the cases pin the standing
 * aside because it is where the gate deliberately does not: an unresolvable parameter type and a
 * column the catalog cannot type both leave the pair projecting as it did before the gate existed.
 * Requiring the match would have made a pair that is neither a projection nor a defect, which is the
 * silence the item exists to close.
 */
class ResolvedNodeKeyProjectionTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The projection resolves =====

    /**
     * The motivating case end to end: a {@code @nodeId} input field bound through its key column,
     * with the tier that answered carried so a reader can explain the answer.
     */
    @Test
    void aTrailingSegmentNamingAKeyColumnResolves() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.NODE_TYPE_NAME))
                .isEqualTo("Inventory");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_NAME))
                .isEqualTo("inventory_id");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.KEY_POSITION)).isZero();
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.TIER)).isEqualTo("SDL_PINNED");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.BOUND_KIND))
                .isEqualTo("INPUT_FIELD");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.USE_SITE))
                .isEqualTo("Mutation.rentFilm#0");
        });
    }

    /**
     * A projection off a {@code @nodeId} argument rather than an input field. The leaf kind is what
     * tells an emitter which slot the wire value is read out of, and both kinds reach this relation.
     */
    @Test
    void aProjectionOffANodeIdArgumentResolves() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "Inventory");
            inventoryNodeType(dsl, "inventory_id");
            pair(dsl, "pInventoryId", "inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.BOUND_KIND)).isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.BOUND_ARGUMENT_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_NAME))
                .isEqualTo("inventory_id");
        });
    }

    /**
     * Matching is case-insensitive, so a segment spelled the generated way and one spelled the SQL
     * way are one answer, and the column comes out under the tier's spelling rather than the
     * author's. That is what lines the projection up with the key list the decode returns values
     * against.
     */
    @Test
    void aSegmentSpelledTheOtherWayStillMatchesAndTakesTheTiersSpelling() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.INVENTORY_ID");

            assertThat(only(dsl).get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_NAME))
                .as("the row is the tier's own spelling, not the one the author happened to type")
                .isEqualTo("inventory_id");
        });
    }

    /**
     * A composite key opens into each of its columns, so two parameters bound from one node id are
     * two rows at two pair positions carrying two different key positions. This is what makes a
     * composite projection expressible without indexing a tuple anywhere.
     */
    @Test
    void twoParametersBoundFromOneIdAreTwoRowsOfOneKey() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "bar");
            seedColumn(dsl, PKG, PUBLIC, "bar", "bar_id", 0, "barId");
            seedColumn(dsl, PKG, PUBLIC, "bar", "foo_id", 1, "fooId");
            seedNode(dsl, GRAPH, "Bar");
            seedTableBinding(dsl, GRAPH, "Bar", "bar");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 0, "bar_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 1, "foo_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Bar");
            pair(dsl, 0, "pBarId", "input.inventoryId.bar_id");
            pair(dsl, 1, "pFooId", "input.inventoryId.foo_id");

            var rows = rows(dsl);
            assertThat(rows).hasSize(2);
            assertThat(rows.stream()
                .map(r -> List.of(r.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.POSITION),
                    r.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_NAME),
                    r.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.KEY_POSITION))))
                .containsExactlyInAnyOrder(List.of(0, "bar_id", 0), List.of(1, "foo_id", 1));
        });
    }

    /** The projection resolves off whichever tier answered, the primary key included. */
    @Test
    void aProjectionResolvesOffThePrimaryKeyTierToo() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            assertThat(only(dsl).get(INTENT_RESOLVED_NODE_KEY_PROJECTION.TIER))
                .isEqualTo("CATALOG_PRIMARY_KEY");
        });
    }

    // ===== The type gate =====

    /**
     * The gate's positive case: both types resolve and agree, and the row carries each so a reader
     * of one row does not have to know which side of the equality it is looking at.
     */
    @Test
    void aProjectionWhoseTypesAgreeResolvesAndCarriesBoth() {
        withTypedInventoryNode("java.lang.Integer", "java.lang.Integer", dsl -> {
            var row = only(dsl);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_JAVA_TYPE))
                .isEqualTo("java.lang.Integer");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.PARAM_JAVA_TYPE))
                .isEqualTo("java.lang.Integer");
        });
    }

    /**
     * The gate's whole point: a column whose Java type the parameter cannot take is not a projection
     * an emitter can see, so the disagreement is a missing row rather than a row an emitter has to
     * re-check. The candidate beside it still exists, which is what lets the detection say the column
     * name was right and the type was not.
     */
    @Test
    void aProjectionWhoseTypesDisagreeHasNoRow() {
        withTypedInventoryNode("java.lang.Integer", "java.lang.String", dsl ->
            assertThat(rows(dsl))
                .as("an Integer key column bound to a String parameter is not a projection")
                .isEmpty());
    }

    /**
     * One half of what the outer joins exist for. A parameter whose type does not resolve (no call
     * surface captured, or a name the census cannot report) leaves the gate standing aside: the pair
     * projects exactly as it did before the predicate existed, because requiring the match would turn
     * it into a pair that is neither a projection nor a defect, which is the silence this family
     * exists to close.
     */
    @Test
    void aProjectionWhoseParameterTypeIsUnresolvableStillResolves() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.PARAM_JAVA_TYPE))
                .as("nothing resolved the parameter's type, so the gate stood aside")
                .isNull();
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_JAVA_TYPE))
                .isEqualTo("java.lang.String");
        });
    }

    /**
     * The other half, and the one that would have re-broken what the candidate split fixed. A pinned
     * key column under a node type with no table binding has no catalog column to take a type from,
     * and the key-column relation admits it on purpose (its own comment: a name resolving against
     * nothing is a row there and a detection elsewhere). So the candidate stands and the gate stands
     * aside; had the reach for the type been an inner join, this pair would have been reported as a
     * column that does not exist.
     */
    @Test
    void aProjectionWhoseColumnTypeIsUnresolvableStillResolves() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedNode(dsl, GRAPH, "Inventory");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "inventory_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.TIER)).isEqualTo("SDL_PINNED");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_JAVA_TYPE))
                .as("no table binding, so no catalog column to type")
                .isNull();
        });
    }

    // ===== Where the meeting fails =====

    /**
     * A trailing segment naming no key column of the node type is not a projection. The unknown
     * column is a rejection the detection stratum reads off the binding relation, and swallowing it
     * here as a near miss is what would let a build emit a read of a column nobody has.
     */
    @Test
    void aTrailingSegmentNamingNoKeyColumnHasNoRow() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.no_such_column");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A binding with nothing unconsumed against a one-column key is a projection, and it is the same
     * row an authored segment produces: the same column at the same key position off the same tier.
     * The author did not spell the column because there was nothing else it could have been, and
     * that spelling difference is provenance the candidate relation carries, not a second shape here.
     */
    @Test
    void aBindingWithNoTrailingSegmentResolvesTheSoleKeyColumn() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_NAME))
                .isEqualTo("inventory_id");
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.KEY_POSITION)).isEqualTo(0);
            assertThat(row.get(INTENT_RESOLVED_NODE_KEY_PROJECTION.ARGUMENT_PATH))
                .as("the path is the author's own, which is the only spelling there is")
                .isEqualTo("input.inventoryId");
        });
    }

    /**
     * The same binding against a composite key has no row, which is where the inference stops. Two
     * columns and one bound parameter leave nothing to pick, so this stays the bare form the defect
     * relation reports; admitting it would have to choose a key position out of nothing.
     */
    @Test
    void aBindingWithNoTrailingSegmentHasNoRowAgainstACompositeKey() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "bar");
            seedColumn(dsl, PKG, PUBLIC, "bar", "bar_id", 0, "barId");
            seedColumn(dsl, PKG, PUBLIC, "bar", "foo_id", 1, "fooId");
            seedNode(dsl, GRAPH, "Bar");
            seedTableBinding(dsl, GRAPH, "Bar", "bar");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 0, "bar_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 1, "foo_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Bar");
            pair(dsl, "pBarId", "input.inventoryId");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * Two trailing segments do not resolve as a projection even where the first of them names a key
     * column. Exactly one, never a minimum: admitting more would let a projection resolve off a path
     * whose middle the author got wrong.
     */
    @Test
    void twoTrailingSegmentsHaveNoRowEvenWhenTheFirstNamesAKeyColumn() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id.nope");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A bare {@code @nodeId} carries no node type to resolve a key list against, so there is nothing
     * to project even where the trailing segment would have matched. The missing {@code typeName:}
     * is the rejection, and this relation stays quiet.
     */
    @Test
    void aBareNodeIdHasNoRow() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", null);
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A node type whose key columns resolve on no tier projects nothing, which is the ambiguous
     * binding and the keyless table arriving here as absence. The key-column relation is where that
     * silence is stated; this one only fails to find a match.
     */
    @Test
    void aNodeTypeWithNoResolvedKeyColumnsHasNoRow() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            assertThat(rows(dsl))
                .as("no tier answered for Inventory, so there is no key list to project out of")
                .isEmpty();
        });
    }

    /**
     * A path whose head names no slot binds nothing, so it has no leaf and reaches no projection
     * whatever the rest of it spells. The absence travels through the binding relation rather than
     * being re-decided here.
     */
    @Test
    void aPathThatBindsNothingHasNoRow() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "pInventoryId", "notAnArgument.inventory_id");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== Fixtures =====

    /** The source the graph resolves catalog names against. */
    private static void catalog(DSLContext dsl) {
        seedSource(dsl, PKG, "JOOQ_SCHEMA");
        seedGraphSource(dsl, GRAPH, PKG);
    }

    /** An {@code Inventory} node over the {@code inventory} table with its key column pinned. */
    private static void inventoryNodeType(DSLContext dsl, String pinnedColumn) {
        seedTable(dsl, PKG, PUBLIC, "inventory");
        seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
        seedNode(dsl, GRAPH, "Inventory");
        seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
        seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, pinnedColumn);
    }

    /** {@code Mutation.rentFilm(input: RentFilmInput)} and the occurrence rows under it. */
    private static void inputSurface(DSLContext dsl) {
        seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
        seedField(dsl, GRAPH, "RentFilmInput", "inventoryId");
        seedField(dsl, GRAPH, "Mutation", "rentFilm");
        seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
        seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
            new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
    }

    /** The whole fixture most cases depart from: the node, its key, and the input surface above it. */
    private static void withInventoryNode(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            inputSurface(dsl);
            body.accept(dsl);
        });
    }

    /**
     * The same fixture with both types of the gate stated: the key column's binding type and the
     * routine parameter's, plus the call surface the parameter is reached through. A case about the
     * gate states both sides rather than relying on two seed defaults happening to agree.
     */
    private static void withTypedInventoryNode(String columnJavaType, String paramJavaType,
            Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId",
                columnJavaType);
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "inventory_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            seedRoutine(dsl, GRAPH, "Mutation", "rentFilm", "rent_film");
            seedTable(dsl, PKG, PUBLIC, "rent_film");
            seedCatalogRoutine(dsl, PKG, PUBLIC, "rent_film", PKG + ".Routines", "rentFilm");
            seedRoutineParameter(dsl, PKG, PUBLIC, "rent_film", 0, "pInventoryId", paramJavaType);
            pair(dsl, "pInventoryId", "input.inventoryId.inventory_id");
            body.accept(dsl);
        });
    }

    /** A {@code @routine} pair at position zero, with its segment decomposition beside it. */
    private static void pair(DSLContext dsl, String paramName, String argumentPath) {
        pair(dsl, 0, paramName, argumentPath);
    }

    /** A {@code @routine} pair at a position the case names. */
    private static void pair(DSLContext dsl, int position, String paramName, String argumentPath) {
        seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", 0, position, paramName,
            argumentPath);
        seedArgumentPathSegments(dsl, GRAPH, "Mutation", "rentFilm", argumentPath);
    }

    // ===== Reads =====

    /** Every row of the graph under assertion. */
    private static List<Record> rows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_RESOLVED_NODE_KEY_PROJECTION.fields())
            .from(INTENT_RESOLVED_NODE_KEY_PROJECTION)
            .where(INTENT_RESOLVED_NODE_KEY_PROJECTION.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .stream()
            .map(Record.class::cast)
            .toList();
    }

    /** The one row a single-pair fixture produces. */
    private static Record only(DSLContext dsl) {
        var all = rows(dsl);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }
}
