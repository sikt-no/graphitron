package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field's scope, its column table and the connection shapes it navigates are a graph's own, the
 * one thing a fact document cannot state, a document being one graph. Two graphs share a store and
 * one catalog and declare the same fields, with {@code Language} bound to different tables, so a
 * rule that lost its graph predicate would answer each coordinate from both bindings; a third graph
 * declares no connection at all. Each graph is a fact document, which claims its own rows; what is
 * asked here is only that the graphs keep them apart.
 */
class FieldGraphScopeTest {

    @TempDir
    static Path tmp;

    private static CapturedStore store;

    @BeforeAll
    static void threeGraphs() {
        var jooq = CatalogInput.of(TestRunContext.of());
        // No classpath: nothing these graphs ask is read off one, and the capture is cheaper without.
        var classpath = List.<ClasspathEntry>of();
        store = CapturedStore.ownStoreOfDocument(tmp, "scenarios/field-scope-binding-language", "language",
            jooq, classpath);
        store.andDocument("scenarios/field-scope-binding-actor", "actor", jooq, classpath);
        store.andDocument("layouts/anchor", "other", jooq, classpath);
    }

    @AfterAll
    static void close() {
        store.close();
    }

    @Test
    void eachGraphResolvesItsOwnBinding() {
        assertThat(columnTables(store.dsl(), "language")).containsExactly("language");
        assertThat(columnTables(store.dsl(), "actor")).containsExactly("actor");
        assertThat(scopeTables(store.dsl(), "language")).containsExactly("film", "language");
        assertThat(scopeTables(store.dsl(), "actor")).containsExactly("actor", "film");
    }

    @Test
    void aConnectionShapeIsReadInTheGraphThatDeclaresIt() {
        assertThat(connections(store.dsl(), "language")).containsExactly("LanguageConnection");
        assertThat(connections(store.dsl(), "actor")).containsExactly("LanguageConnection");
        assertThat(connections(store.dsl(), "other")).isEmpty();
    }

    private static List<String> columnTables(DSLContext dsl, String graph) {
        var t = GRAPHITRON_PATH_NEED_TABLE;
        return dsl.select(t.LANDING_TABLE).from(t)
            .where(t.GRAPH_NAME.eq(graph), t.COORDINATE.eq("Film.languages"))
            .fetch(t.LANDING_TABLE);
    }

    private static List<String> scopeTables(DSLContext dsl, String graph) {
        var t = GRAPHITRON_FIELD_SCOPE_TABLE;
        return dsl.selectDistinct(t.TABLE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(graph))
            .orderBy(t.TABLE_NAME)
            .fetch(t.TABLE_NAME);
    }

    private static List<String> connections(DSLContext dsl, String graph) {
        var t = GRAPHITRON_CONNECTION;
        return dsl.select(t.TYPE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(graph))
            .fetch(t.TYPE_NAME);
    }
}
