package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONNECTION_ELEMENT_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_NAVIGATED_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SCOPE_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_field_navigated_type} answers, and what {@code intent_connection_element_type}
 * answers underneath it: which type a field's own generated SQL should read the facts of, as against
 * the type the field's signature names. The two differ wherever a wrapper stands between the field
 * and what it delivers, and a rule reading the signature at such a coordinate reads the wrapper's
 * facts, which are none.
 *
 * <p>The cases are in two halves because the relations answer two questions. The lower half is a
 * question about a type: is this type a connection, and over what. The upper half is a question
 * about a field: which type does this coordinate navigate as. Keeping them apart is what lets the
 * shape test be pinned without a field in the fixture at all.
 *
 * <p>The case this pair exists for is {@link #anAuthorDeclaredConnectionNavigatesAsItsElement}. The
 * navigation used to be spelled as a read of {@code graphitron_field_synthesis} at five sites, which
 * answers for a connection the generator built and is silent about one the author wrote out in the
 * SDL. Silence there is not a decline: it resolved the coordinate to a type binding no table, so the
 * field had no scope table, so its arguments had no scope, so a filter on it resolved no column. The
 * end-to-end case asserts the scope table directly for that reason, a rule stated correctly here
 * being worth nothing if the relation above it still cannot answer.
 */
class FieldNavigatedTypeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== What makes a type a connection =====

    /**
     * The shape test, which is the classifier's own transcribed rather than reinvented: a field named
     * {@code edges} whose element object declares a field named {@code node}, and the element is that
     * node's named type.
     */
    @Test
    void aRelayShapedTypeNamesTheElementItPaginates() {
        withCatalog(dsl -> {
            seedConnection(dsl, "FilmsConnection", "FilmsEdge", "Film");

            assertThat(elementRows(dsl)).containsExactly("FilmsConnection Film");
        });
    }

    /**
     * A type whose {@code edges} element declares no {@code node} is not a connection. The near miss
     * worth pinning: half the shape is not the shape, and admitting it would hand every
     * {@code edges}-carrying wrapper an element name taken from nowhere.
     */
    @Test
    void anEdgeTypeWithoutANodeFieldIsNoConnection() {
        withCatalog(dsl -> {
            seedField(dsl, GRAPH, "FilmsConnection", "edges", "FilmsEdge", true);
            seedField(dsl, GRAPH, "FilmsEdge", "cursor", "String", false);

            assertThat(elementRows(dsl)).isEmpty();
        });
    }

    /**
     * A {@code node} reachable some other way than through {@code edges} is not a connection either.
     * The symmetric near miss, and the one a relay-ish type hand-rolled without an edge list falls
     * into.
     */
    @Test
    void aNodeFieldNotReachedThroughEdgesIsNoConnection() {
        withCatalog(dsl -> {
            seedField(dsl, GRAPH, "FilmsConnection", "node", "Film", false);

            assertThat(elementRows(dsl)).isEmpty();
        });
    }

    /**
     * The edge element must be an OBJECT. An interface declaring {@code node} is a shape the spec does
     * not describe, and the guard is what keeps this relation from reading one type's field list as
     * another's.
     */
    @Test
    void anInterfaceEdgeElementIsNoConnection() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "FilmsEdge", "INTERFACE");
            seedField(dsl, GRAPH, "FilmsConnection", "edges", "FilmsEdge", true);
            seedField(dsl, GRAPH, "FilmsEdge", "node", "Film", false);

            assertThat(elementRows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph reads none of these shapes. */
    @Test
    void aSiblingGraphReadsNoConnectionShape() {
        withCatalog(dsl -> {
            seedConnection(dsl, "FilmsConnection", "FilmsEdge", "Film");
            seedGraph(dsl, "other");

            assertThat(elementRowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Which type a field navigates as =====

    /**
     * The ordinary case and the fallback rung: a field that returns what it says it returns navigates
     * as that. The rung is what makes this relation total over every field, which is what lets a
     * consumer join it rather than left-join it.
     */
    @Test
    void anOrdinaryFieldNavigatesAsItsOwnNamedType() {
        withCatalog(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);

            assertThat(navigatedRows(dsl)).contains("Query.films NAMED_TYPE Film");
        });
    }

    /** Totality reaches a scalar leaf too: a field navigating to nothing useful still has a row. */
    @Test
    void aScalarFieldNavigatesAsItsScalar() {
        withCatalog(dsl -> {
            seedField(dsl, GRAPH, "Film", "title", "String", false);

            assertThat(navigatedRows(dsl)).contains("Film.title NAMED_TYPE String");
        });
    }

    /**
     * The upper rung: where a macro rewrote the field's type expression, the expression the author
     * wrote is what the coordinate navigates as, its list and non-null wrappers stripped.
     */
    @Test
    void aMacroRewrittenFieldNavigatesAsTheExpressionTheAuthorWrote() {
        withCatalog(dsl -> {
            seedConnection(dsl, "FilmConnection", "FilmEdge", "Film");
            seedField(dsl, GRAPH, "Query", "films", "FilmConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "CONNECTION", "[Film!]!");

            assertThat(navigatedRows(dsl)).contains("Query.films AUTHORED_EXPRESSION Film");
        });
    }

    /**
     * The rung this pair of relations was added for. A connection type the author declared in the SDL
     * has no synthesis row, so the upper rung is silent, and the structural shape is what answers.
     * Before this rung the coordinate resolved to the wrapper, which binds no table.
     */
    @Test
    void anAuthorDeclaredConnectionNavigatesAsItsElement() {
        withCatalog(dsl -> {
            seedConnection(dsl, "FilmsConnection", "FilmsEdge", "Film");
            seedField(dsl, GRAPH, "Query", "filmsConnection", "FilmsConnection", false);

            assertThat(navigatedRows(dsl))
                .contains("Query.filmsConnection CONNECTION_ELEMENT Film");
        });
    }

    /**
     * Where both upper rungs fire they agree, a synthesised connection's {@code edges.node} being the
     * element the authored expression named, so the precedence between them is a tie-break rather
     * than a disagreement. Pinned because a reader could otherwise take the ordering for a conflict
     * the relation resolves in one rung's favour.
     */
    @Test
    void aSynthesisedConnectionTakesTheAuthoredRungAndBothAgree() {
        withCatalog(dsl -> {
            seedConnection(dsl, "FilmConnection", "FilmEdge", "Film");
            seedField(dsl, GRAPH, "Query", "films", "FilmConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "CONNECTION", "[Film!]!");

            assertThat(navigatedRows(dsl)).contains("Query.films AUTHORED_EXPRESSION Film");
            assertThat(elementRows(dsl)).contains("FilmConnection Film");
        });
    }

    /**
     * A polymorphic container is navigated <em>as</em>, not through: a field returning a union
     * navigates as the union, and which members it holds is the membership relation's answer read
     * from that name. Stated as a case because "navigate" could be read as resolving all the way to
     * a table, which this relation deliberately does not do.
     */
    @Test
    void aUnionReturningFieldNavigatesAsTheUnionItself() {
        withCatalog(dsl -> {
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(navigatedRows(dsl)).contains("Query.documents NAMED_TYPE Document");
        });
    }

    /** One row per field and never two, which is what totality plus a precedence buys. */
    @Test
    void everyFieldHasExactlyOneRow() {
        withCatalog(dsl -> {
            seedConnection(dsl, "FilmsConnection", "FilmsEdge", "Film");
            seedField(dsl, GRAPH, "Query", "filmsConnection", "FilmsConnection", false);
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "CONNECTION", "[Film!]!");

            derive(dsl);
            long fields = dsl.fetchCount(no.sikt.graphitron.model.Tables.GRAPHQL_FIELD,
                no.sikt.graphitron.model.Tables.GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPH));
            assertThat(navigatedRows(dsl)).hasSize((int) fields);
        });
    }

    // ===== What the silence cost, end to end =====

    /**
     * The payoff, asserted where it was missing rather than where it is stated: a field returning an
     * author-declared connection over a table-bound element resolves a scope table, so its arguments
     * have a scope and a filter written at that coordinate has a column to resolve against. This is
     * the assertion that would have failed before the structural rung existed.
     */
    @Test
    void anAuthorDeclaredConnectionFieldResolvesAScopeTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedConnection(dsl, "FilmsConnection", "FilmsEdge", "Film");
            seedField(dsl, GRAPH, "Query", "filmsConnection", "FilmsConnection", false);

            assertThat(scopeRows(dsl))
                .contains("Query.filmsConnection NAMED_TYPE_TABLE film");
        });
    }

    /**
     * The generator-synthesised sibling resolves the same table, which is what hid the silence: a
     * schema exercising both reads as "connections work" until somebody looks for the ones the author
     * named. Kept as a case beside the one above so the pair states that the two paths agree.
     */
    @Test
    void aSynthesisedConnectionFieldResolvesTheSameTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedConnection(dsl, "FilmConnection", "FilmEdge", "Film");
            seedField(dsl, GRAPH, "Query", "films", "FilmConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "CONNECTION", "[Film!]!");

            assertThat(scopeRows(dsl)).contains("Query.films NAMED_TYPE_TABLE film");
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "Boolean", "SCALAR");
            body.accept(dsl);
        });
    }

    /** A Relay-shaped wrapper: the connection's edge list, and the edge's node. */
    private static void seedConnection(DSLContext dsl, String connectionType, String edgeType,
                                       String elementType) {
        seedField(dsl, GRAPH, connectionType, "edges", edgeType, true);
        seedField(dsl, GRAPH, edgeType, "node", elementType, false);
    }

    private static List<String> elementRows(DSLContext dsl) {
        return elementRowsIn(dsl, GRAPH);
    }

    private static List<String> elementRowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_CONNECTION_ELEMENT_TYPE.TYPE_NAME,
                INTENT_CONNECTION_ELEMENT_TYPE.ELEMENT_TYPE_NAME)
            .from(INTENT_CONNECTION_ELEMENT_TYPE)
            .where(INTENT_CONNECTION_ELEMENT_TYPE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_CONNECTION_ELEMENT_TYPE.TYPE_NAME)
            .fetch(r -> r.value1() + " " + r.value2());
    }

    private static List<String> navigatedRows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_NAVIGATED_TYPE.TYPE_NAME,
                INTENT_FIELD_NAVIGATED_TYPE.FIELD_NAME,
                INTENT_FIELD_NAVIGATED_TYPE.BASIS,
                INTENT_FIELD_NAVIGATED_TYPE.NAVIGATED_TYPE_NAME)
            .from(INTENT_FIELD_NAVIGATED_TYPE)
            .where(INTENT_FIELD_NAVIGATED_TYPE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_FIELD_NAVIGATED_TYPE.TYPE_NAME, INTENT_FIELD_NAVIGATED_TYPE.FIELD_NAME)
            .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3() + " " + r.value4());
    }

    /** The scope relation, rendered the way its own test renders it. */
    private static List<String> scopeRows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_SCOPE_TABLE.TYPE_NAME, INTENT_FIELD_SCOPE_TABLE.FIELD_NAME,
                INTENT_FIELD_SCOPE_TABLE.BASIS, INTENT_FIELD_SCOPE_TABLE.TABLE_NAME)
            .from(INTENT_FIELD_SCOPE_TABLE)
            .where(INTENT_FIELD_SCOPE_TABLE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_FIELD_SCOPE_TABLE.TYPE_NAME, INTENT_FIELD_SCOPE_TABLE.FIELD_NAME,
                INTENT_FIELD_SCOPE_TABLE.TABLE_NAME)
            .fetch(r -> r.value1() + "." + r.value2() + " " + r.value3() + " " + r.value4());
    }
}
