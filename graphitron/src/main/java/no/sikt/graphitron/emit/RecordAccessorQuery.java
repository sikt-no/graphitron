package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN_RECORD_ACCESSOR;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;

/**
 * The Bean Validation property each column of a generated jOOQ record is read through, for the
 * catalogs this graph reads: the one read of {@code sql_column_record_accessor}. Read once per run
 * and handed to both readers, the VALIDATION pre-step's per-column check and the lint finding naming
 * the columns it cannot check, which then partition a record through the same rows, passing
 * {@link Rows#propertyOf} as the lookup.
 *
 * <p>Cost: one statement over the graph's catalog columns, joined to their tables on the key.
 */
public final class RecordAccessorQuery {

    private RecordAccessorQuery() {}

    /** The rows, keyed by record class name, then by the column's SQL name. */
    public record Rows(Map<String, Map<String, String>> properties) {
        public Rows {
            var copy = new HashMap<String, Map<String, String>>();
            properties.forEach((record, columns) -> copy.put(record, Map.copyOf(columns)));
            properties = Map.copyOf(copy);
        }

        /** No rows: a run with no store, which therefore checks no record column. */
        public static Rows empty() {
            return new Rows(Map.of());
        }

        /**
         * The property {@code table}'s record reads {@code column} through, empty where the capture
         * found no getter for it.
         */
        public Optional<String> propertyOf(TableRef table, ColumnRef column) {
            return Optional.ofNullable(properties.getOrDefault(table.recordClassName(), Map.of())
                .get(column.sqlName()));
        }
    }

    /** The graph's record accessors, from the store. */
    public static Rows read(StoreHandle store) {
        var a = SQL_COLUMN_RECORD_ACCESSOR;
        var t = SQL_TABLE;
        var s = STORE_GRAPH_SOURCE;
        var properties = new HashMap<String, Map<String, String>>();
        store.dsl()
            .select(t.RECORD_CLASS_FQN, a.COLUMN_NAME, a.PROPERTY_NAME)
            .from(a)
            .join(t).on(t.SOURCE_NAME.eq(a.SOURCE_NAME), t.TABLE_SCHEMA.eq(a.TABLE_SCHEMA),
                t.TABLE_NAME.eq(a.TABLE_NAME))
            .join(s).on(s.SOURCE_NAME.eq(a.SOURCE_NAME))
            .where(s.GRAPH_NAME.eq(store.graphName()))
            .forEach(r -> properties.computeIfAbsent(r.value1(), k -> new HashMap<>())
                .put(r.value2(), r.value3()));
        return new Rows(properties);
    }
}
