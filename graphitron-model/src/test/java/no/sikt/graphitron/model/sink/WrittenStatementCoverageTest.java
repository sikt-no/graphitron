package no.sikt.graphitron.model.sink;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The gate over capture's written insert statements: a relation whose write is stated rather than
 * assembled has to state <em>all</em> of it.
 *
 * <p>Two properties, and neither needs a list of relations or of columns to check against, which is
 * the point: a roster would be the thing that rots. Every relation the schema computes a column for
 * is writable, whether it has a written statement or goes through the generic arm, because an
 * insert naming a column the database computes is rejected outright and that rejection is what this
 * gate moves from a run to the build. And every write function names every column of its relation
 * the database does not compute, checked by writing a row whose every writable column carries a
 * distinct value and reading it back: a column left out of the statement comes back null, and a
 * pair bound in the wrong order comes back swapped.
 *
 * <p>The generic arm used to be the reason a computed column needed a written statement at all: it
 * named every column a relation declared, so a relation with one could not be inserted through it.
 * It now names the columns a row actually touched, and nothing touches a computed one, so a
 * relation is free to have no statement of its own. That is what the first property checks rather
 * than assumes; requiring a statement instead would be asserting the old constraint against a
 * writer that no longer has it.
 *
 * <p>The store is bent for the probe, referential integrity off and the check constraints dropped,
 * because the subject here is a statement's column list and not a relation's domains. Nothing else
 * about the schema is touched, and the rows never leave this store.
 */
class WrittenStatementCoverageTest {

    @Test
    @DisplayName("A relation carrying a computed column is writable through whichever arm takes it")
    void everyRelationWithAGeneratedColumnIsWritable() {
        try (var store = FactStores.inMemory()) {
            DSLContext dsl = store.dsl();
            relax(dsl);
            var covered = new ArrayList<String>();
            for (Table<?> table : Public.PUBLIC.getTables()) {
                if (generatedColumns(dsl, table).isEmpty()) {
                    continue;
                }
                covered.add(table.getName());
                var writer = FactWrites.of(table);
                probe(dsl, table, writer != null ? writer : throughTheSink());
            }
            assertThat(covered)
                .describedAs("the schema computes columns somewhere, or this gate is vacuous")
                .isNotEmpty();
        }
    }

    @Test
    @DisplayName("A written statement names every column its relation does not compute")
    void everyWrittenStatementCoversItsWritableColumns() {
        try (var store = FactStores.inMemory()) {
            DSLContext dsl = store.dsl();
            relax(dsl);
            var covered = new ArrayList<String>();
            for (Table<?> table : Public.PUBLIC.getTables()) {
                var writer = FactWrites.of(table);
                if (writer == null) {
                    continue;
                }
                covered.add(table.getName());
                probe(dsl, table, writer);
            }
            assertThat(covered)
                .describedAs("the registry FactSink dispatches through")
                .isNotEmpty();
        }
    }

    /**
     * The generic arm, addressed as a writer so the probe can drive it the same way. The graph name
     * is taken off the row rather than fixed, because the sink stamps it and the probe is checking
     * that every value it wrote survives, that column included.
     */
    private static FactWrites.RelationWriter throughTheSink() {
        return (dsl, rows) -> {
            var graph = rows.get(0).getTable().field("GRAPH_NAME", String.class);
            var sink = new FactSink(dsl, graph == null ? "probe" : rows.get(0).get(graph));
            rows.forEach(sink::add);
            sink.flush();
        };
    }

    /** Writes one fully-populated row through {@code writer} and asserts every value survived. */
    @SuppressWarnings("unchecked")
    private static void probe(DSLContext dsl, Table<?> table, FactWrites.RelationWriter writer) {
        Set<String> computed = generatedColumns(dsl, table);
        var writable = new ArrayList<Field<?>>();
        for (Field<?> field : table.fields()) {
            if (!computed.contains(field.getName().toUpperCase(Locale.ROOT))) {
                writable.add(field);
            }
        }
        TableRecord<?> row = (TableRecord<?>) dsl.newRecord(table);
        var expected = new ArrayList<Object>(writable.size());
        for (int i = 0; i < writable.size(); i++) {
            Object value = probeValue(table, writable.get(i), i);
            expected.add(value);
            row.set((Field<Object>) writable.get(i), value);
        }

        writer.write(dsl, List.of(row));

        List<? extends Record> stored = dsl.selectFrom(table).fetch();
        assertThat(stored)
            .describedAs("%s's written statement inserted no row", table.getName())
            .hasSize(1);
        for (int i = 0; i < writable.size(); i++) {
            Field<?> field = writable.get(i);
            assertThat(stored.get(0).get(field))
                .describedAs("%s.%s: the written statement either does not name this column or "
                    + "binds it out of order", table.getName(), field.getName())
                .isEqualTo(expected.get(i));
        }
        dsl.deleteFrom(table).execute();
    }

    /**
     * A value distinct within its row, so a bind order that disagrees with the column order shows
     * up as a swap rather than passing. Booleans are the one type with too few values for that,
     * and they are pinned TRUE so a relation's own check constraints stay satisfiable in the
     * shapes that survive dropping the named ones.
     */
    private static Object probeValue(Table<?> table, Field<?> field, int index) {
        Class<?> type = field.getType();
        if (type == String.class) {
            return "probe-" + index;
        }
        if (type == Integer.class) {
            return 100 + index;
        }
        if (type == Boolean.class) {
            return Boolean.TRUE;
        }
        return fail(("%s.%s has type %s, which this probe has no value for; add one rather than "
            + "skipping the column, since a skipped column is a column this gate stops covering")
            .formatted(table.getName(), field.getName(), type.getName()));
    }

    /** The columns h2 computes for {@code table}, upper-cased as the catalog spells them. */
    private static Set<String> generatedColumns(DSLContext dsl, Table<?> table) {
        return new LinkedHashSet<>(dsl.fetch(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'PUBLIC' AND table_name = ? AND is_generated <> 'NEVER'",
            table.getName().toUpperCase(Locale.ROOT))
            .getValues(0, String.class));
    }

    /** Drops what a synthetic row would trip over: foreign keys, and every named check. */
    private static void relax(DSLContext dsl) {
        dsl.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (Record constraint : dsl.fetch(
            "SELECT table_name, constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = 'PUBLIC' AND constraint_type = 'CHECK'")) {
            dsl.execute("ALTER TABLE \"%s\" DROP CONSTRAINT \"%s\""
                .formatted(constraint.get(0, String.class), constraint.get(1, String.class)));
        }
    }
}
