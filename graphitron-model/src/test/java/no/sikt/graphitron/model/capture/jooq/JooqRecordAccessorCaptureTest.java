package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.jooq.RecordGetters;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.test.SeededStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN_RECORD_ACCESSOR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The getter a record reads a column through, against a catalog written by hand.
 *
 * <p>{@link JooqFactCaptureTest} reads the generated fixture catalog, which is the better witness for
 * everything that catalog can show. It cannot show these two: its records come from jOOQ's default
 * strategy over lower-case names, so every getter sits where the default rule looks. The
 * hand-written catalog has an upper-case Oracle-style table, whose getters the rule has to spell
 * {@code getBilde} rather than {@code getBILDE}, and a record one of whose getters was renamed.
 */
class JooqRecordAccessorCaptureTest {

    private static final String GRAPH = "jooq-record-accessor";
    private static final String PACKAGE = "no.sikt.graphitron.model.capture.jooq.handwritten";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Test
    @DisplayName("an upper-case column is read through the getter that capitalises only its first letter")
    void anUpperCaseColumnReadsThroughItsDefaultGetter() {
        try (var store = GraphitronStore.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            JooqFactCapture.capture(store.dsl(), GRAPH, PACKAGE, loader(), AT);
            var t = SQL_COLUMN_RECORD_ACCESSOR;
            assertThat(store.dsl().select(t.COLUMN_NAME, t.GETTER_NAME, t.PROPERTY_NAME).from(t)
                    .where(t.TABLE_NAME.eq("PERSONBILDE")).orderBy(t.COLUMN_NAME)
                    .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3()))
                .containsExactly("BILDE getBilde bilde", "PERSONLOPENR getPersonlopenr personlopenr");
        }
    }

    @Test
    @DisplayName("a column whose getter was renamed has no row, and its sibling keeps one")
    void aRenamedGetterHasNoRow() {
        try (var store = GraphitronStore.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            JooqFactCapture.capture(store.dsl(), GRAPH, PACKAGE, loader(), AT);
            var t = SQL_COLUMN_RECORD_ACCESSOR;
            assertThat(store.dsl().fetchCount(SQL_COLUMN, SQL_COLUMN.TABLE_NAME.eq("renamed")))
                .as("both columns are captured").isEqualTo(2);
            assertThat(store.dsl().select(t.COLUMN_NAME).from(t).where(t.TABLE_NAME.eq("renamed"))
                    .fetch(t.COLUMN_NAME))
                .containsExactly("renamed_id");
        }
    }

    @Test
    @DisplayName("the getter name is jOOQ's default, and the property decapitalises it as java.beans does")
    void theNamingRule() {
        assertThat(RecordGetters.getterName("BILDE")).isEqualTo("getBilde");
        assertThat(RecordGetters.getterName("film_id")).isEqualTo("getFilmId");
        assertThat(RecordGetters.getterName("FilmId")).isEqualTo("getFilmid");
        assertThat(RecordGetters.getterName("some-col.name x")).isEqualTo("getSomeColNameX");
        assertThat(RecordGetters.propertyName("getFilmId")).isEqualTo("filmId");
        assertThat(RecordGetters.propertyName("getAB")).isEqualTo("AB");
    }

    private static ClassLoader loader() {
        return Thread.currentThread().getContextClassLoader();
    }
}
