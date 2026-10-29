package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_LEGACY_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * The legacy key shapes the generated {@code NodeIdEncoder} converts old ids through, assembled from
 * {@code graphitron_node_legacy_keycolumn}: per wire type id, the legacy columns as
 * {@link ColumnRef}s on the {@link TableRef} whose constants they are read off, and where each
 * current key value sits among them.
 *
 * <p>An emission fact rather than a detection, read for the encoder to emit from on
 * {@link ResolvedKeyProjections}' terms. A shape whose table's schema publishes no {@code Tables}
 * class is left out, as {@link StoreNodeTables} leaves out such a node, since the conversion cannot
 * name its columns.
 */
public final class StoreLegacyKeyShapes {

    private StoreLegacyKeyShapes() {}

    /** Every relation this read names. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_NODE_LEGACY_KEYCOLUMN, SQL_COLUMN,
        SQL_SCHEMA, SQL_TABLE, SQL_PRIMARY_KEY, SQL_CONSTRAINT_COLUMN);

    /**
     * One type id's legacy key shape. {@code columns} is the shape in the order old ids carry their
     * values; {@code keptAt.get(j)} is the position in {@code columns} the current key's
     * {@code j}th value is read from, so {@code keptAt.size()} is the current key's length.
     */
    public record LegacyKeyShape(String typeId, TableRef table, List<ColumnRef> columns,
                                 List<Integer> keptAt) {

        public LegacyKeyShape {
            columns = List.copyOf(columns);
            keptAt = List.copyOf(keptAt);
        }
    }

    /** The read's typed product, in type id order. */
    public record Shapes(List<LegacyKeyShape> shapes) {

        public Shapes {
            shapes = List.copyOf(shapes);
        }

        /** No shapes, for callers producing a plan with no store behind it. */
        public static Shapes empty() {
            return new Shapes(List.of());
        }
    }

    /** Every legacy key shape {@code graphName} anchored. */
    public static Shapes read(DSLContext dsl, String graphName) {
        var l = GRAPHITRON_NODE_LEGACY_KEYCOLUMN;
        var c = SQL_COLUMN;
        var rows = dsl.select(l.TYPE_ID, l.POSITION, l.CURRENT_POSITION, l.TABLE_SOURCE_NAME,
                l.TABLE_SCHEMA, l.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE,
                c.DATABASE_TYPE)
            .from(l)
            .join(c).on(c.SOURCE_NAME.eq(l.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(l.TABLE_SCHEMA),
                c.TABLE_NAME.eq(l.TABLE_NAME), c.COLUMN_NAME.eq(l.COLUMN_NAME))
            .where(l.GRAPH_NAME.eq(graphName))
            .orderBy(l.TYPE_ID, l.POSITION)
            .fetch();
        var grouped = new LinkedHashMap<String, List<org.jooq.Record>>();
        for (var r : rows) {
            grouped.computeIfAbsent(r.get(l.TYPE_ID), k -> new ArrayList<>()).add(r);
        }
        var out = new ArrayList<LegacyKeyShape>();
        for (var entry : grouped.entrySet()) {
            var first = entry.getValue().getFirst();
            var table = StoreNodeTables.tableRef(dsl, first.get(l.TABLE_SOURCE_NAME),
                first.get(l.TABLE_SCHEMA), first.get(l.TABLE_NAME));
            if (table.isEmpty()) {
                continue;
            }
            var columns = new ArrayList<ColumnRef>();
            int kept = 0;
            for (var r : entry.getValue()) {
                columns.add(new ColumnRef(r.get(c.COLUMN_NAME), r.get(c.JOOQ_NAME),
                    r.get(c.BINDING_TYPE), r.get(c.DATABASE_TYPE)));
                if (r.get(l.CURRENT_POSITION) != null) {
                    kept++;
                }
            }
            var keptAt = new ArrayList<Integer>(Collections.nCopies(kept, -1));
            for (var r : entry.getValue()) {
                var at = r.get(l.CURRENT_POSITION);
                if (at != null) {
                    keptAt.set(at, r.get(l.POSITION));
                }
            }
            out.add(new LegacyKeyShape(entry.getKey(), table.get(), columns, keptAt));
        }
        return new Shapes(out);
    }
}
