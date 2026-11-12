package no.sikt.graphitron.model;

import no.sikt.graphitron.model.catalog.GeneratedColumns;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A directive and its formal arguments hang off their element anchors, as a field and its arguments
 * do: a row needs its coordinate's anchor. That an anchor that goes takes the row with it is pinned
 * where it happens, by the sweep after the corpus stops declaring one, in
 * {@code scenarios/removed-directive-definition}.
 */
class DirectiveAnchorKeyTest {

    private static final String SDL = """
        directive @paged(size: Int, from: Int) on FIELD_DEFINITION
        type Query {
          films: [String] @paged(size: 10)
        }
        """;

    @Test
    @DisplayName("a directive whose element anchor is missing is refused")
    void aDirectiveNeedsItsElement(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_DIRECTIVE;
            var row = dsl.selectFrom(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.DIRECTIVE_NAME.eq("paged")).fetchOne();
            assertThat(row).isNotNull();
            row.setDirectiveName("unanchored");
            assertRefused(() -> dsl.insertInto(t).set(row.intoMap()).execute());
        });
    }

    @Test
    @DisplayName("a formal argument whose element anchor is missing is refused")
    void aFormalArgumentNeedsItsElement(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_DIRECTIVE_ARGUMENT;
            var row = dsl.selectFrom(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.DIRECTIVE_NAME.eq("paged"),
                    t.ARGUMENT_NAME.eq("size")).fetchOne();
            assertThat(row).isNotNull();
            row.setArgumentName("unanchored");
            // The row as written, without what the store generates from it, which no insert names.
            var written = row.intoMap();
            GeneratedColumns.of(dsl, t).forEach(field -> written.remove(field.getName()));
            assertRefused(() -> dsl.insertInto(t).set(written).execute());
        });
    }

    private static void assertRefused(ThrowingCallable insert) {
        assertThatThrownBy(insert)
            .as("the row's own key is free and its directive stands, so only the reference into the"
                + " element anchor can refuse it")
            .isInstanceOf(IntegrityConstraintViolationException.class)
            .hasMessageContaining("Referential integrity");
    }
}
