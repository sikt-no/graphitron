package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ELEMENT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field's scope, its column table and the connection shapes it navigates are a graph's own, the
 * one thing a fact document cannot state, a document being one graph. Two graphs share a store and
 * one catalog and declare the same fields, with {@code Language} bound to different tables, so a
 * rule that lost its graph predicate would answer each coordinate from both bindings.
 */
class FieldGraphScopeTest {

    private static final String SDL = """
        type Film @table(name: "film") { title: String, languages: [Language] }
        type Language @table(name: "%s") { name: String }
        type LanguageConnection { edges: [LanguageEdge] }
        type LanguageEdge { node: Language }
        type Query { films: [Film], languageConnection: LanguageConnection }
        """;

    @TempDir
    Path tmp;

    @Test
    void eachGraphResolvesItsOwnBinding() {
        try (var store = twoGraphs()) {
            assertThat(columnTables(store.dsl(), "language")).containsExactly("language");
            assertThat(columnTables(store.dsl(), "actor")).containsExactly("actor");
            assertThat(scopeTables(store.dsl(), "language")).containsExactly("film", "language");
            assertThat(scopeTables(store.dsl(), "actor")).containsExactly("actor", "film");
        }
    }

    @Test
    void aConnectionShapeIsReadInTheGraphThatDeclaresIt() {
        try (var store = twoGraphs()) {
            assertThat(connections(store.dsl(), "language")).containsExactly("LanguageConnection");
            assertThat(connections(store.dsl(), "other")).isEmpty();
        }
    }

    private CapturedStore twoGraphs() {
        var jooq = CatalogInput.of(TestRunContext.of());
        return CapturedStore.ownStoreOfCatalog(tmp, "language", SDL.formatted("language"), jooq)
            .andCatalogGraph("actor", SDL.formatted("actor"), jooq)
            .andGraph("other", "type Query { ping: String }");
    }

    private static List<String> columnTables(DSLContext dsl, String graph) {
        var t = INTENT_FIELD_COLUMN_TABLE;
        return dsl.select(t.TABLE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(graph), t.TYPE_NAME.eq("Film"), t.FIELD_NAME.eq("languages"))
            .fetch(t.TABLE_NAME);
    }

    private static List<String> scopeTables(DSLContext dsl, String graph) {
        var t = GRAPHITRON_FIELD_SCOPE_TABLE;
        return dsl.selectDistinct(t.TABLE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(graph))
            .orderBy(t.TABLE_NAME)
            .fetch(t.TABLE_NAME);
    }

    private static List<String> connections(DSLContext dsl, String graph) {
        var t = GRAPHITRON_CONNECTION_ELEMENT_TYPE;
        return dsl.select(t.TYPE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(graph))
            .fetch(t.TYPE_NAME);
    }
}
