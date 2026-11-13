package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_COMPONENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_MEMBER;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;

/**
 * The voucher fields as their occurrences bind them: each field whose binding is {@code VOUCHER}, and
 * the columns of its issuing row the voucher is signed over. The store owns such a field whole: its
 * refusals are the store's defects, its fetcher is the store's ({@link VoucherIssueEmitter}), and what
 * the classified tiers still need of it before they read the store, the walk's verdict standing down
 * and the projection's columns, is read here, from the fact the flipped tiers will read.
 *
 * <p>One statement at the grain of a voucher field's component: the binding names the field, its
 * members' components name the columns. Small by construction.
 */
public final class VoucherBindingQuery {

    private VoucherBindingQuery() {}

    /**
     * The voucher fields by type and field name, each with the columns it is signed over in
     * component order.
     */
    public record Rows(Map<String, Map<String, List<ColumnRef>>> byType) {
        public Rows {
            var copy = new TreeMap<String, Map<String, List<ColumnRef>>>();
            byType.forEach((type, fields) -> {
                var inner = new TreeMap<String, List<ColumnRef>>();
                fields.forEach((field, columns) -> inner.put(field, List.copyOf(columns)));
                copy.put(type, java.util.Collections.unmodifiableMap(inner));
            });
            byType = java.util.Collections.unmodifiableMap(copy);
        }

        public static Rows empty() {
            return new Rows(Map.of());
        }

        /** Every voucher field's coordinate, {@code Type.field}. */
        public Set<String> coordinates() {
            var coordinates = new TreeSet<String>();
            byType.forEach((type, fields) -> fields.keySet().forEach(field -> coordinates.add(type + "." + field)));
            return coordinates;
        }

        /** The voucher fields of {@code typeName} and their columns; empty where it issues none. */
        public Map<String, List<ColumnRef>> of(String typeName) {
            return byType.getOrDefault(typeName, Map.of());
        }
    }

    public static Rows read(StoreHandle store) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var o = GRAPHITRON_OCCURRENCE;
        var m = GRAPHITRON_VOUCHER_MEMBER;
        var c = GRAPHITRON_VOUCHER_COMPONENT;
        var col = SQL_COLUMN;
        var rows = store.dsl().selectDistinct(m.TYPE_NAME, m.ISSUING_FIELD, c.FIELD_NAME, col.COLUMN_NAME,
                col.JOOQ_NAME, col.BINDING_TYPE, col.DATABASE_TYPE)
            .from(b)
            .join(o).on(o.GRAPH_NAME.eq(b.GRAPH_NAME), o.COORDINATE.eq(b.COORDINATE))
            .join(m).on(m.GRAPH_NAME.eq(o.GRAPH_NAME),
                org.jooq.impl.DSL.concat(m.TYPE_NAME, org.jooq.impl.DSL.inline("."), m.ISSUING_FIELD)
                    .eq(o.ELEMENT_COORDINATE))
            .join(c).on(c.GRAPH_NAME.eq(m.GRAPH_NAME), c.TYPE_NAME.eq(m.TYPE_NAME), c.FIELD_NAME.eq(m.FIELD_NAME))
            .join(col).on(col.SOURCE_NAME.eq(c.TABLE_SOURCE_NAME), col.TABLE_SCHEMA.eq(c.TABLE_SCHEMA),
                col.TABLE_NAME.eq(c.TABLE_NAME), col.COLUMN_NAME.eq(c.COLUMN_NAME))
            .where(b.GRAPH_NAME.eq(store.graphName()), b.KIND.eq("VOUCHER"))
            .orderBy(m.TYPE_NAME, m.ISSUING_FIELD, c.FIELD_NAME)
            .fetch();
        var byType = new LinkedHashMap<String, Map<String, List<ColumnRef>>>();
        for (var r : rows) {
            byType.computeIfAbsent(r.get(m.TYPE_NAME), k -> new LinkedHashMap<>())
                .computeIfAbsent(r.get(m.ISSUING_FIELD), k -> new java.util.ArrayList<>())
                .add(new ColumnRef(r.get(col.COLUMN_NAME), r.get(col.JOOQ_NAME), r.get(col.BINDING_TYPE),
                    r.get(col.DATABASE_TYPE)));
        }
        return new Rows(byType);
    }
}
