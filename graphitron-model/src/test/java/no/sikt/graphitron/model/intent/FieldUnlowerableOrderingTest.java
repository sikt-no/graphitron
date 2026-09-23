package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_UNLOWERABLE_ORDERING;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedDefaultOrder;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedImplements;
import static no.sikt.graphitron.model.test.SeededStore.seedOrderBy;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_field_unlowerable_ordering} returns: the coordinates where an ordering is
 * available and the coordinate's own read shape cannot deliver it, one row per coordinate and
 * availability route.
 *
 * <p>The comparison is availability against delivery, not sortedness, and the two most surprising
 * cases below are what that keying buys. A multitable child read carrying a declaration is a row,
 * because the read is one statement per participant and no branch carries the declaration; a
 * list-shaped root that reaches the classifier's multitable arm lowers its declaration onto every
 * branch and is not. A multitable read carrying <em>no</em> declaration is quiet, because what is
 * available there (the participants' own primary keys, combined on a synthetic sort key) is exactly
 * what is delivered. Every other absence
 * in this file is a shape some other surface owns.
 *
 * <p>The route is part of the grain and the cases pin it as one: a coordinate carrying two
 * declarations is two rows, and two {@code @orderBy} arguments on one coordinate are two rows as
 * well, because each remedy names one route and a relation keyed on the coordinate alone would have
 * had to word one remedy for two different things the author did.
 *
 * <p>One arm of the relation is deliberately not exercised here, and where it is exercised says
 * why. The {@code KEY_CAPTURE_SCATTER} verdict reads {@code intent_mutation_routine_seat} at its one
 * emitting verdict, which no seeded store can reach: that verdict turns on a chain landing on a
 * catalog routine's own result, so the fixture that states it captures real SDL against a real
 * catalog. That case is {@code no.sikt.graphitron.rewrite.derive.UnlowerableOrderingsTest}, which is
 * the same tier {@code MutationRoutineSeatTest} states the seat relation itself at, for the same
 * reason. The {@code PRIMARY_KEY_FALLBACK} route rides that arm alone, and its inertness on this
 * one is asserted below as the property it is.
 */
class FieldUnlowerableOrderingTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The fan-out verdict, one case per availability route =====

    /**
     * The arm's primary case since the roots were lowered: a child field returning a union whose
     * members each bind their own table, carrying a field-level {@code @defaultOrder}. The
     * multitable read is one statement per participant, and at a child coordinate no branch carries
     * the declaration, so it is a row, and the location is the directive's own rather than the
     * field's, which is what lets an editor underline what the author wrote. Pinned originally as the
     * guard against over-narrowing: a rule narrowed to roots would have left the child silent, and
     * the lowering is root-only.
     */
    @Test
    void aMultitableChildFieldCarriesTheSameRow() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedTableBinding(dsl, GRAPH, "Store", "store");
            seedField(dsl, GRAPH, "Store", "documents", "Document", true);
            seedDefaultOrder(dsl, GRAPH, "Store", "documents");

            assertThat(rendered(dsl))
                .containsExactly("Store.documents PARTICIPANT_FAN_OUT DEFAULT_ORDER -");
            var row = rows(dsl).getFirst();
            assertThat(row.get(INTENT_FIELD_UNLOWERABLE_ORDERING.SOURCE_LINE))
                .as("the declaring directive's own line, not the field's")
                .isEqualTo(7);
            assertThat(row.get(INTENT_FIELD_UNLOWERABLE_ORDERING.SOURCE_COLUMN)).isEqualTo(11);
        });
    }

    /**
     * The client-supplied half of the same shape: an {@code @orderBy} argument asks for an ordering
     * per request, and the child fan-out delivers participant primary-key order whichever value
     * arrives. The declaring argument is carried, because the remedy names it.
     */
    @Test
    void anOrderByArgumentOnAMultitableChildIsUnlowerable() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedTableBinding(dsl, GRAPH, "Store", "store");
            seedField(dsl, GRAPH, "Store", "documents", "Document", true);
            seedArgument(dsl, GRAPH, "Store", "documents", "order", "DocumentOrderBy");
            seedOrderBy(dsl, GRAPH, "Store", "documents", "order");

            assertThat(rendered(dsl))
                .containsExactly("Store.documents PARTICIPANT_FAN_OUT ORDER_BY_ARGUMENT order");
        });
    }

    /**
     * The reason the route is in the grain: each half is unlowered on its own and each has its own
     * remedy, so the arity is the answer rather than one row wording both. Seated on the child
     * coordinate, where the rows survive the root lowering.
     */
    @Test
    void bothDeclarationsAtOneCoordinateAreTwoRows() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedTableBinding(dsl, GRAPH, "Store", "store");
            seedField(dsl, GRAPH, "Store", "documents", "Document", true);
            seedDefaultOrder(dsl, GRAPH, "Store", "documents");
            seedArgument(dsl, GRAPH, "Store", "documents", "order", "DocumentOrderBy");
            seedOrderBy(dsl, GRAPH, "Store", "documents", "order");

            assertThat(rendered(dsl)).containsExactly(
                "Store.documents PARTICIPANT_FAN_OUT DEFAULT_ORDER -",
                "Store.documents PARTICIPANT_FAN_OUT ORDER_BY_ARGUMENT order");
        });
    }

    /**
     * Why the argument name is in the grain rather than beside it: the {@code @orderBy} population
     * is keyed at argument grain, so two of them on one coordinate are two rows here. Unreachable
     * through today's ordering resolution, which takes the first such argument it finds, but a
     * relation's grain is not the place to inherit a consumer's first-match precedence. Seated on the
     * child coordinate, as above.
     */
    @Test
    void twoOrderByArgumentsOnOneCoordinateAreTwoRows() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedTableBinding(dsl, GRAPH, "Store", "store");
            seedField(dsl, GRAPH, "Store", "documents", "Document", true);
            seedArgument(dsl, GRAPH, "Store", "documents", "order", "DocumentOrderBy");
            seedArgument(dsl, GRAPH, "Store", "documents", "thenBy", "DocumentOrderBy");
            seedOrderBy(dsl, GRAPH, "Store", "documents", "order");
            seedOrderBy(dsl, GRAPH, "Store", "documents", "thenBy");

            assertThat(rendered(dsl)).containsExactly(
                "Store.documents PARTICIPANT_FAN_OUT ORDER_BY_ARGUMENT order",
                "Store.documents PARTICIPANT_FAN_OUT ORDER_BY_ARGUMENT thenBy");
        });
    }

    /**
     * The interface arm answers the same way as the union arm, the container axis being what makes
     * the polymorphic membership one relation; nothing here restates that recognition.
     */
    @Test
    void anUnboundInterfaceContainerAnswersTheSameWay() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Searchable", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedImplements(dsl, GRAPH, "Film", "Searchable");
            seedImplements(dsl, GRAPH, "Actor", "Searchable");
            seedTableBinding(dsl, GRAPH, "Store", "store");
            seedField(dsl, GRAPH, "Store", "search", "Searchable", true);
            seedDefaultOrder(dsl, GRAPH, "Store", "search");

            assertThat(rendered(dsl))
                .containsExactly("Store.search PARTICIPANT_FAN_OUT DEFAULT_ORDER -");
        });
    }

    // ===== The roots the classifier lowers, and the neighbours it does not =====
    //
    // The fan-out arm excludes exactly the coordinates the classifier lowers an ordering at: a field
    // on the QUERY root, list-shaped, that reaches the multitable arm. Each form of list-shaped read
    // is a case, because the macro rewrites a connection's type expression and a spelling keyed on
    // is_list alone would keep rejecting it; each neighbour that shares two of the three conjuncts is
    // a kept-row case, because a spelling missing the third would accept and discard its declaration.

    /** A plain list root, with both routes declared: lowered, so neither route is a row. */
    @Test
    void aDeclaredListRootIsLowered() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);
            seedDefaultOrder(dsl, GRAPH, "Query", "documents");
            seedArgument(dsl, GRAPH, "Query", "documents", "order", "DocumentOrderBy");
            seedOrderBy(dsl, GRAPH, "Query", "documents", "order");

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /** A root returning a connection type the author declared: list-shaped through the navigation rung. */
    @Test
    void aDeclaredAuthoredConnectionRootIsLowered() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            connectionOver(dsl, "DocumentConnection", "DocumentEdge", "Document");
            seedField(dsl, GRAPH, "Query", "documents", "DocumentConnection", false);
            seedDefaultOrder(dsl, GRAPH, "Query", "documents");

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /**
     * The reported form: an {@code @asConnection} root, whose type expression the macro rewrote to
     * the minted connection, so the rewritten field reads {@code is_list = FALSE}. Lowered.
     */
    @Test
    void aDeclaredAsConnectionRootIsLowered() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            connectionOver(dsl, "DocumentConnection", "DocumentEdge", "Document");
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);
            seedFieldSynthesis(dsl, GRAPH, "Query", "documents", "DocumentConnection");
            seedDefaultOrder(dsl, GRAPH, "Query", "documents");

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /**
     * {@code [Node] @asConnection}: the classifier's {@code Node} route tests the named type of the
     * rewritten expression, which is the connection's, so this root reaches the multitable arm and is
     * lowered. The pin on reading {@code graphitron_field.named_type} rather than the navigated type.
     */
    @Test
    void aNodeAsConnectionRootIsLowered() {
        withCatalog(dsl -> {
            nodeInterface(dsl);
            connectionOver(dsl, "NodeConnection", "NodeEdge", "Node");
            seedField(dsl, GRAPH, "Query", "nodes", "Node", true);
            seedFieldSynthesis(dsl, GRAPH, "Query", "nodes", "NodeConnection");
            seedDefaultOrder(dsl, GRAPH, "Query", "nodes");

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /** A single-valued root: the ordering resolver returns nothing to lower, so it keeps its row. */
    @Test
    void aSingleValuedRootKeepsItsRow() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedField(dsl, GRAPH, "Query", "document", "Document", false);
            seedDefaultOrder(dsl, GRAPH, "Query", "document");

            assertThat(rendered(dsl))
                .containsExactly("Query.document PARTICIPANT_FAN_OUT DEFAULT_ORDER -");
        });
    }

    /**
     * A root {@code @service} returning a multitable list classifies ahead of the multitable arm and
     * delivers the service's order, so its declaration is still unlowered.
     */
    @Test
    void aServiceRootKeepsItsRow() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);
            seedService(dsl, GRAPH, "Query", "documents", "com.example.DocumentService", "documents");
            seedDefaultOrder(dsl, GRAPH, "Query", "documents");

            assertThat(rendered(dsl))
                .containsExactly("Query.documents PARTICIPANT_FAN_OUT DEFAULT_ORDER -");
        });
    }

    /** A plain {@code [Node]} root takes the node route, which lowers no ordering. */
    @Test
    void aNodeListRootKeepsItsRow() {
        withCatalog(dsl -> {
            nodeInterface(dsl);
            seedField(dsl, GRAPH, "Query", "nodes", "Node", true);
            seedDefaultOrder(dsl, GRAPH, "Query", "nodes");

            assertThat(rendered(dsl))
                .containsExactly("Query.nodes PARTICIPANT_FAN_OUT DEFAULT_ORDER -");
        });
    }

    /**
     * The two earlier routes that reject a multitable return on their own today, named anyway
     * because the exclusion states "reaches the multitable arm" and not "fails elsewhere".
     */
    @Test
    void aLookupKeyOrRoutineRootKeepsItsRow() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedField(dsl, GRAPH, "Query", "byKey", "Document", true);
            seedArgument(dsl, GRAPH, "Query", "byKey", "id", "ID");
            seedArgumentLookupKey(dsl, GRAPH, "Query", "byKey", "id");
            seedDefaultOrder(dsl, GRAPH, "Query", "byKey");
            seedField(dsl, GRAPH, "Query", "viaRoutine", "Document", true);
            seedRoutine(dsl, GRAPH, "Query", "viaRoutine", "public.documents");
            seedDefaultOrder(dsl, GRAPH, "Query", "viaRoutine");

            assertThat(rendered(dsl)).containsExactly(
                "Query.byKey PARTICIPANT_FAN_OUT DEFAULT_ORDER -",
                "Query.viaRoutine PARTICIPANT_FAN_OUT DEFAULT_ORDER -");
        });
    }

    // ===== The boundaries, each an absence some other surface owns =====

    /**
     * The property the whole keying rests on, and the row a sortedness rule would have minted here.
     * A multitable read with nothing declared is quiet: the emitter orders the combined result on a
     * synthetic key built from each participant's primary key, so what is available is exactly what
     * is delivered and the never-unsorted invariant holds.
     *
     * <p>This is also where the fallback route's inertness on this arm is asserted rather than
     * assumed. Both participant tables have primary keys, so a rule keyed on "the read's target
     * table has a primary key" would fire; the route joins the binding of the type the field
     * navigates as, and the container binding no table is this shape's own precondition.
     */
    @Test
    void aMultitableRootWithNoDeclarationIsQuiet() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /**
     * A single-{@code @table} field is one statement, and one statement carries its ordering, so a
     * declaration there is honoured and not a row. The negative that keeps this relation from being
     * a census of every ordering ever written.
     */
    @Test
    void aSingleTableFieldWithADeclaredOrderingIsQuiet() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedDefaultOrder(dsl, GRAPH, "Query", "films");
            seedArgument(dsl, GRAPH, "Query", "films", "order", "FilmOrderBy");
            seedOrderBy(dsl, GRAPH, "Query", "films", "order");

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /**
     * The single-table discriminated interface, one join away from the shape above it and a
     * different read entirely: the container binds its own table, so its implementors share one
     * statement and one filter surface, and the ordering lowers onto that statement today. Quiet,
     * and the two shapes are told apart by the container's binding alone.
     */
    @Test
    void aContainerThatBindsItsOwnTableIsQuiet() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Content", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "Content", "content");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedImplements(dsl, GRAPH, "Film", "Content");
            seedImplements(dsl, GRAPH, "Actor", "Content");
            seedField(dsl, GRAPH, "Query", "content", "Content", true);
            seedDefaultOrder(dsl, GRAPH, "Query", "content");

            assertThat(rendered(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's coordinates draw none of these rows. */
    @Test
    void aSiblingGraphReadsNoneOfTheseRows() {
        withCatalog(dsl -> {
            multitableUnion(dsl);
            seedTableBinding(dsl, GRAPH, "Store", "store");
            seedField(dsl, GRAPH, "Store", "documents", "Document", true);
            seedDefaultOrder(dsl, GRAPH, "Store", "documents");
            seedGraph(dsl, "other");

            assertThat(rendered(dsl)).hasSize(1);
            assertThat(rows(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * The reported shape's own container: a union of three table-bound members, each with a primary
     * key, and no binding of its own. Three rather than two because the message a consumer meets
     * names the participants, and a list of one or two reads the same whether the join is right or
     * transposed.
     */
    private static void multitableUnion(DSLContext dsl) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedTableBinding(dsl, GRAPH, "Actor", "actor");
        seedTableBinding(dsl, GRAPH, "Language", "language");
        seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
        seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
        seedUnionMember(dsl, GRAPH, "Document", "Language", 3);
    }

    /** A connection type over an element, by the structural shape: {@code edges} whose type has {@code node}. */
    private static void connectionOver(DSLContext dsl, String connectionType, String edgeType,
                                       String elementType) {
        seedField(dsl, GRAPH, connectionType, "edges", edgeType, true);
        seedField(dsl, GRAPH, edgeType, "node", elementType, false);
    }

    /** {@code Node} as a multitable interface over two table-bound implementations. */
    private static void nodeInterface(DSLContext dsl) {
        seedType(dsl, GRAPH, "Node", "INTERFACE");
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedTableBinding(dsl, GRAPH, "Actor", "actor");
        seedImplements(dsl, GRAPH, "Film", "Node");
        seedImplements(dsl, GRAPH, "Actor", "Node");
    }

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "language", "content", "store")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedColumn(dsl, PKG, PUBLIC, table, table + "_id", 0, table.toUpperCase() + "_ID");
                seedPrimaryKey(dsl, PKG, PUBLIC, table, table + "_pkey", table + "_id");
            }
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            body.accept(dsl);
        });
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rows(dsl, GRAPH);
    }

    private static Result<Record> rows(DSLContext dsl, String graphName) {
        derive(dsl);
        var v = INTENT_FIELD_UNLOWERABLE_ORDERING;
        return dsl.select(v.fields())
            .from(v)
            .where(v.GRAPH_NAME.eq(graphName))
            .orderBy(v.TYPE_NAME, v.FIELD_NAME, v.AVAILABLE_VIA, v.ARGUMENT_NAME)
            .fetch();
    }

    /** {@code Type.field verdict available_via argument_name}, the absent argument as a dash. */
    private static List<String> rendered(DSLContext dsl) {
        var v = INTENT_FIELD_UNLOWERABLE_ORDERING;
        return rows(dsl).map(row -> row.get(v.TYPE_NAME) + "." + row.get(v.FIELD_NAME)
            + " " + row.get(v.VERDICT) + " " + row.get(v.AVAILABLE_VIA)
            + " " + (row.get(v.ARGUMENT_NAME) == null ? "-" : row.get(v.ARGUMENT_NAME)));
    }
}
