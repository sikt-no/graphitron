package no.sikt.graphitron.model;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.test.SeededStore.SEEDED_READING;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * schema the store has to be able to record.
 *
 * <p>Each refusal names a coordinate an element row does exist for, so the reference into the
 * element relation holds and the only thing a refused row breaks is the check. The legal row beside
 * each is the seeders' own spelling, which is the case to look at first if the class fails whole.
 */
class CoordinateSpellingGateTest {

    private static final String GRAPH = "spelling";

    @Test
    @DisplayName("a captured field element's coordinate is its type and field name")
    void aCapturedFieldElementSpellsItsKey() {
        withSeededStore(GRAPH, dsl -> {
            assertThatCode(() -> seedField(dsl, GRAPH, "Film", "title"))
                .doesNotThrowAnyException();
            anchor(dsl, "Film.rating", "FIELD");
            assertRefused(() -> dsl.insertInto(GRAPHQL_FIELD_ELEMENT)
                .set(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME, GRAPH)
                .set(GRAPHQL_FIELD_ELEMENT.TYPE_NAME, "Film")
                .set(GRAPHQL_FIELD_ELEMENT.FIELD_NAME, "year")
                .set(GRAPHQL_FIELD_ELEMENT.COORDINATE, "Film.rating")
                .set(GRAPHQL_FIELD_ELEMENT.TOUCHED_AT, SEEDED_READING)
                .execute());
        });
    }

    @Test
    @DisplayName("a captured argument element's coordinate is its field and argument name")
    void aCapturedArgumentElementSpellsItsKey() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "films");
            assertThatCode(() -> seedArgument(dsl, GRAPH, "Query", "films", "first", "Int"))
                .doesNotThrowAnyException();
            anchor(dsl, "Query.films(last:)", "FIELD_ARGUMENT");
            assertRefused(() -> dsl.insertInto(GRAPHQL_ARGUMENT_ELEMENT)
                .set(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME, GRAPH)
                .set(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME, "Query")
                .set(GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME, "films")
                .set(GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME, "after")
                .set(GRAPHQL_ARGUMENT_ELEMENT.COORDINATE, "Query.films(last:)")
                .set(GRAPHQL_ARGUMENT_ELEMENT.TOUCHED_AT, SEEDED_READING)
                .execute());
        });
    }

    @Test
    @DisplayName("an emitted field's coordinate is its type and field name")
    void anEmittedFieldSpellsItsKey() {
        withSeededStore(GRAPH, dsl -> {
            emittedType(dsl, "Film");
            emittedElement(dsl, "Film.rating", "FIELD");
            emittedElement(dsl, "Film.title", "FIELD");
            assertThatCode(() -> emittedField(dsl, "Film", "title", "Film.title"))
                .doesNotThrowAnyException();
            assertRefused(() -> emittedField(dsl, "Film", "year", "Film.rating"));
        });
    }

    @Test
    @DisplayName("an emitted argument's coordinate is its field and argument name")
    void anEmittedArgumentSpellsItsKey() {
        withSeededStore(GRAPH, dsl -> {
            emittedType(dsl, "Query");
            emittedElement(dsl, "Query.films", "FIELD");
            emittedField(dsl, "Query", "films", "Query.films");
            emittedElement(dsl, "Query.films(first:)", "FIELD_ARGUMENT");
            emittedElement(dsl, "Query.films(last:)", "FIELD_ARGUMENT");
            assertThatCode(() -> emittedArgument(dsl, "first", "Query.films(first:)"))
                .doesNotThrowAnyException();
            assertRefused(() -> emittedArgument(dsl, "after", "Query.films(last:)"));
        });
    }

    private static void assertRefused(ThrowingCallable insert) {
        assertThatThrownBy(insert)
            .as("the coordinate names an element that exists, so the reference holds and only the "
                + "check can refuse a coordinate that does not spell the row's key")
            .isInstanceOf(IntegrityConstraintViolationException.class)
            .hasMessageContaining("Check constraint");
    }

    /** A captured element at {@code coordinate}, so a reference into the element relation holds. */
    private static void anchor(DSLContext dsl, String coordinate, String kind) {
        seedDeclaredType(dsl, GRAPH, coordinate.substring(0, coordinate.indexOf('.')), "OBJECT");
        dsl.insertInto(GRAPHQL_ELEMENT)
            .set(GRAPHQL_ELEMENT.GRAPH_NAME, GRAPH)
            .set(GRAPHQL_ELEMENT.COORDINATE, coordinate)
            .set(GRAPHQL_ELEMENT.ELEMENT_KIND, kind)
            .set(GRAPHQL_ELEMENT.TOUCHED_AT, SEEDED_READING)
            .execute();
    }

    private static void emittedElement(DSLContext dsl, String coordinate, String kind) {
        dsl.insertInto(GRAPHITRON_ELEMENT)
            .set(GRAPHITRON_ELEMENT.GRAPH_NAME, GRAPH)
            .set(GRAPHITRON_ELEMENT.COORDINATE, coordinate)
            .set(GRAPHITRON_ELEMENT.ELEMENT_KIND, kind)
            .set(GRAPHITRON_ELEMENT.TOUCHED_AT, SEEDED_READING)
            .execute();
    }

    private static void emittedType(DSLContext dsl, String typeName) {
        emittedElement(dsl, typeName, "NAMED_TYPE");
        dsl.insertInto(GRAPHITRON_TYPE)
            .set(GRAPHITRON_TYPE.GRAPH_NAME, GRAPH)
            .set(GRAPHITRON_TYPE.TYPE_NAME, typeName)
            .set(GRAPHITRON_TYPE.COORDINATE, typeName)
            .set(GRAPHITRON_TYPE.KIND, "OBJECT")
            .execute();
    }

    private static int emittedField(DSLContext dsl, String typeName, String fieldName,
                                    String coordinate) {
        return dsl.insertInto(GRAPHITRON_FIELD)
            .set(GRAPHITRON_FIELD.GRAPH_NAME, GRAPH)
            .set(GRAPHITRON_FIELD.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD.COORDINATE, coordinate)
            .set(GRAPHITRON_FIELD.ORDINAL, 0)
            .set(GRAPHITRON_FIELD.TYPE_SDL, "String")
            .set(GRAPHITRON_FIELD.NAMED_TYPE, "String")
            .set(GRAPHITRON_FIELD.NON_NULL, false)
            .set(GRAPHITRON_FIELD.IS_LIST, false)
            .execute();
    }

    private static int emittedArgument(DSLContext dsl, String argumentName, String coordinate) {
        return dsl.insertInto(GRAPHITRON_ARGUMENT)
            .set(GRAPHITRON_ARGUMENT.GRAPH_NAME, GRAPH)
            .set(GRAPHITRON_ARGUMENT.TYPE_NAME, "Query")
            .set(GRAPHITRON_ARGUMENT.FIELD_NAME, "films")
            .set(GRAPHITRON_ARGUMENT.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT.COORDINATE, coordinate)
            .set(GRAPHITRON_ARGUMENT.ORDINAL, 0)
            .set(GRAPHITRON_ARGUMENT.TYPE_SDL, "Int")
            .set(GRAPHITRON_ARGUMENT.NAMED_TYPE, "Int")
            .set(GRAPHITRON_ARGUMENT.NON_NULL, false)
            .set(GRAPHITRON_ARGUMENT.IS_LIST, false)
            .execute();
    }
}
