package no.sikt.graphitron.model;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds four relations to their coordinate being their key spelled out:
 * {@code graphql_field_element} and {@code graphitron_field} at {@code Type.field}, and
 * {@code graphql_argument_element} and {@code graphitron_argument} at {@code Type.field(arg:)}.
 *
 * <p>The check is what lets an upsert into these relations match on the key alone. Each also carries
 * a unique coordinate, and with the coordinate free to disagree with the key, a row colliding on the
 * coordinate under a different key would be a second way to match; with it bound to the key, that
 * second match can only ever be the first one.
 *
 * <p>A capture table may carry this check where it may not carry an invariant an author can reach.
 * The writer computes the coordinate from the strings it computes the key from, in the same call, so
 * no document produces a row that fails it, and a failure here is a writer defect rather than a
 * schema the store has to be able to record. The capture below is the legal half of every case: it
 * writes all four relations under the check, and a check refusing the writers' own spelling fails
 * it before any refusal is tried.
 *
 * <p>Each refusal takes one captured row out and writes it back under another key. The coordinate's
 * element row stays, so the reference into the element relation holds, and the coordinate is free
 * again, so the unique holds; the only thing the row breaks is the check.
 */
class CoordinateSpellingGateTest {

    private static final String SDL = """
        type Query {
          films(first: Int, last: Int): [Film]
        }
        type Film {
          title: String
          rating: String
        }
        """;

    @Test
    @DisplayName("a captured field element's coordinate is its type and field name")
    void aCapturedFieldElementSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_FIELD_ELEMENT;
            assertThat(dsl.deleteFrom(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.COORDINATE.eq("Film.rating")).execute()).isOne();
            assertRefused(() -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH)
                .set(t.TYPE_NAME, "Film")
                .set(t.FIELD_NAME, "year")
                .set(t.COORDINATE, "Film.rating")
                .set(t.TOUCHED_AT, LocalDateTime.now())
                .execute());
        });
    }

    @Test
    @DisplayName("a captured argument element's coordinate is its field and argument name")
    void aCapturedArgumentElementSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_ARGUMENT_ELEMENT;
            assertThat(dsl.deleteFrom(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.COORDINATE.eq("Query.films(last:)")).execute())
                .isOne();
            assertRefused(() -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH)
                .set(t.TYPE_NAME, "Query")
                .set(t.FIELD_NAME, "films")
                .set(t.ARGUMENT_NAME, "after")
                .set(t.COORDINATE, "Query.films(last:)")
                .set(t.TOUCHED_AT, LocalDateTime.now())
                .execute());
        });
    }

    @Test
    @DisplayName("an emitted field's coordinate is its type and field name")
    void anEmittedFieldSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHITRON_FIELD;
            var row = dsl.selectFrom(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.COORDINATE.eq("Film.rating")).fetchOne();
            assertThat(row).isNotNull();
            row.delete();
            row.setFieldName("year");
            assertRefused(() -> dsl.insertInto(t).set(row.intoMap()).execute());
        });
    }

    @Test
    @DisplayName("an emitted argument's coordinate is its field and argument name")
    void anEmittedArgumentSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHITRON_ARGUMENT;
            var row = dsl.selectFrom(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.COORDINATE.eq("Query.films(last:)")).fetchOne();
            assertThat(row).isNotNull();
            row.delete();
            row.setArgumentName("after");
            assertRefused(() -> dsl.insertInto(t).set(row.intoMap()).execute());
        });
    }

    private static void assertRefused(ThrowingCallable insert) {
        assertThatThrownBy(insert)
            .as("the coordinate names an element that exists and no other row holds, so the "
                + "reference and the unique both hold and only the check can refuse a coordinate "
                + "that does not spell the row's key")
            .isInstanceOf(IntegrityConstraintViolationException.class)
            .hasMessageContaining("Check constraint");
    }
}
