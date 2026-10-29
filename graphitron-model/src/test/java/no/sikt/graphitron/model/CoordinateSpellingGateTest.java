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
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds eight relations to their coordinate being their key spelled out:
 * {@code graphql_field_element} and {@code graphitron_field} at {@code Type.field},
 * {@code graphql_argument_element} and {@code graphitron_argument} at {@code Type.field(arg:)}, and
 * the type, enum value, directive and directive argument anchors at {@code Type},
 * {@code Type.value}, {@code @directive} and {@code @directive(arg:)}.
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
 * again, so the unique holds; the only thing the row breaks is the check. The anchors' cases add a row
 * instead, at a coordinate the store holds for another kind of element, which the unique leaves free.
 *
 * <p>The unique key is what a reference to an anchor's coordinate needs. For the type and directive
 * anchors the check already makes the coordinate a function of a one-part key, so no row can collide
 * on the coordinate without failing the check first; an enum value's two parts can, which is the
 * one unique refusal here.
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
        enum Rating { G PG }
        directive @paged(size: Int, from: Int) on FIELD_DEFINITION
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

    @Test
    @DisplayName("a captured type element's coordinate is its type name")
    void aCapturedTypeElementSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_TYPE_ELEMENT;
            assertRefused(() -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH)
                .set(t.TYPE_NAME, "Movie")
                .set(t.COORDINATE, "Film.title")
                .set(t.TOUCHED_AT, LocalDateTime.now())
                .execute());
        });
    }

    @Test
    @DisplayName("a captured enum value element's coordinate is its type and value name")
    void aCapturedEnumValueElementSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_ENUM_VALUE_ELEMENT;
            assertRefused(() -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH)
                .set(t.TYPE_NAME, "Rating")
                .set(t.VALUE_NAME, "R")
                .set(t.COORDINATE, "Film.title")
                .set(t.TOUCHED_AT, LocalDateTime.now())
                .execute());
        });
    }

    @Test
    @DisplayName("a captured directive element's coordinate is the at sign and its name")
    void aCapturedDirectiveElementSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_DIRECTIVE_ELEMENT;
            assertRefused(() -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH)
                .set(t.DIRECTIVE_NAME, "limited")
                .set(t.COORDINATE, "Film.title")
                .set(t.TOUCHED_AT, LocalDateTime.now())
                .execute());
        });
    }

    @Test
    @DisplayName("a captured directive argument element's coordinate is its directive and argument name")
    void aCapturedDirectiveArgumentElementSpellsItsKey(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var t = GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT;
            assertRefused(() -> dsl.insertInto(t)
                .set(t.GRAPH_NAME, GRAPH)
                .set(t.DIRECTIVE_NAME, "paged")
                .set(t.ARGUMENT_NAME, "after")
                .set(t.COORDINATE, "Film.title")
                .set(t.TOUCHED_AT, LocalDateTime.now())
                .execute());
        });
    }

    @Test
    @DisplayName("two enum value elements spelling one coordinate under two keys are refused")
    void anEnumValueCoordinateIsUnique(@TempDir Path directory) {
        withCapturedStore(directory, SDL, dsl -> {
            var now = LocalDateTime.now();
            var e = GRAPHQL_ELEMENT;
            dsl.insertInto(e, e.GRAPH_NAME, e.COORDINATE, e.ELEMENT_KIND, e.TOUCHED_AT)
                .values(GRAPH, "A", "NAMED_TYPE", now)
                .values(GRAPH, "A.b", "NAMED_TYPE", now)
                .values(GRAPH, "A.b.c", "ENUM_VALUE", now)
                .execute();
            var te = GRAPHQL_TYPE_ELEMENT;
            dsl.insertInto(te, te.GRAPH_NAME, te.TYPE_NAME, te.COORDINATE, te.TOUCHED_AT)
                .values(GRAPH, "A", "A", now)
                .values(GRAPH, "A.b", "A.b", now)
                .execute();
            var t = GRAPHQL_ENUM_VALUE_ELEMENT;
            dsl.insertInto(t, t.GRAPH_NAME, t.TYPE_NAME, t.VALUE_NAME, t.COORDINATE, t.TOUCHED_AT)
                .values(GRAPH, "A", "b.c", "A.b.c", now)
                .execute();
            assertThatThrownBy(() -> dsl.insertInto(t,
                        t.GRAPH_NAME, t.TYPE_NAME, t.VALUE_NAME, t.COORDINATE, t.TOUCHED_AT)
                    .values(GRAPH, "A.b", "c", "A.b.c", now)
                    .execute())
                .as("both rows spell their own key, so only the unique coordinate can refuse the"
                    + " second")
                .isInstanceOf(IntegrityConstraintViolationException.class)
                .hasMessageContaining("Unique index or primary key violation");
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
