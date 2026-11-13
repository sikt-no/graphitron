package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import no.sikt.graphitron.model.test.TestRunContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which catalog table a graph type resolves to, as a captured fact rather than a derivation of one.
 *
 * <p>The relation states only the settled bindings, so its key is the settledness: a type resolving
 * to two tables is not a row. That is what lets it carry a primary key and a foreign key into
 * {@code sql_table}, neither of which the derivation it replaces can have, and it is why its
 * readers stop spelling a {@code candidates = 1} predicate they were spelling eleven times and
 * forgetting four.
 *
 * <p>The last case is the one that matters while both relations exist. It is not a claim about the
 * fixture but about the rule: the two populations agree exactly, so the readers can move over one
 * at a time and the old relation can be dissolved when none is left.
 */
class TableTypeTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Film @table(name: "film") { title: String }
        type Actor @table { firstName: String @field(name: "first_name") }
        type Query { films: [Film!]!, actors: [Actor!]! }
        """;

    @Test
    @DisplayName("a spelled table name resolves to the table the catalog declares")
    void aSpelledNameResolves() {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            assertThat(bindingOf(store.dsl(), "Film"))
                .as("@table(name: \"film\") names the film table")
                .isEqualTo("film");
        }
    }

    @Test
    @DisplayName("a bare @table binds by the type's own name")
    void aBareTableBindsByTypeName() {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            assertThat(bindingOf(store.dsl(), "Actor"))
                .as("no name: was written, so the type's own name is the spelling; the fallback is"
                    + " the resolution's rule and not something the decode recorded")
                .isEqualTo("actor");
        }
    }

    @Test
    @DisplayName("the decode records no fallback, only what the author wrote")
    void theDecodeRecordsOnlyWhatWasWritten() {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            var a = GRAPHQL_DIRECTIVE_APPLICATION;
            var t = GRAPHITRON_AST_TABLE_ENTRY;
            assertThat(store.dsl().select(t.TABLE_REF).from(a)
                    .leftJoin(t).on(t.GRAPH_NAME.eq(a.GRAPH_NAME), t.SOURCE_NAME.eq(a.SOURCE_NAME),
                        t.SOURCE_LINE.eq(a.SOURCE_LINE), t.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                    .where(a.GRAPH_NAME.eq(GRAPH), a.COORDINATE.eq("Actor"),
                        a.DIRECTIVE_NAME.eq("table"))
                    .fetchOne(t.TABLE_REF))
                .as("the entry is the input; a bare @table wrote no name and the column says so")
                .isNull();
        }
    }

    private static String bindingOf(DSLContext dsl, String typeName) {
        var t = GRAPHITRON_TABLETYPE;
        return dsl.select(t.TABLE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(GRAPH)).and(t.TYPE_NAME.eq(typeName))
            .fetchOne(t.TABLE_NAME);
    }

    private static CatalogInput jooq() {
        var ctx = TestRunContext.of();
        return CatalogInput.of(ctx);
    }
}
