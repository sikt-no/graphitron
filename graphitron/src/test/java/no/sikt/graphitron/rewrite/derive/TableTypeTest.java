package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_BINDING;
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
@PipelineTier
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
            assertThat(store.dsl().select(GRAPHITRON_TABLE.TABLE_REF).from(GRAPHITRON_TABLE)
                    .where(GRAPHITRON_TABLE.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHITRON_TABLE.TYPE_NAME.eq("Actor"))
                    .fetchOne(GRAPHITRON_TABLE.TABLE_REF))
                .as("the entry is the input; a bare @table wrote no name and the column says so")
                .isNull();
        }
    }

    @Test
    @DisplayName("the settled bindings are exactly the derivation's unambiguous rows")
    void theSettledBindingsAgreeWithTheDerivation() {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            var dsl = store.dsl();
            var b = INTENT_RESOLVED_TYPE_BINDING;
            List<String> derived = dsl
                .select(b.TYPE_NAME, b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME).from(b)
                .where(b.GRAPH_NAME.eq(GRAPH)).and(b.CANDIDATES.eq(1))
                .fetch(r -> r.value1() + "|" + r.value2() + "|" + r.value3() + "|" + r.value4());
            var t = GRAPHITRON_TABLETYPE;
            List<String> captured = dsl
                .select(t.TYPE_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME).from(t)
                .where(t.GRAPH_NAME.eq(GRAPH))
                .fetch(r -> r.value1() + "|" + r.value2() + "|" + r.value3() + "|" + r.value4());

            assertThat(captured)
                .as("the fixture has to bind something, or this compares two empty sets")
                .isNotEmpty();
            assertThat(captured)
                .as("every settled binding the derivation reports, and no others")
                .containsExactlyInAnyOrderElementsOf(derived);
        }
    }

    private static String bindingOf(DSLContext dsl, String typeName) {
        var t = GRAPHITRON_TABLETYPE;
        return dsl.select(t.TABLE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(GRAPH)).and(t.TYPE_NAME.eq(typeName))
            .fetchOne(t.TABLE_NAME);
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
