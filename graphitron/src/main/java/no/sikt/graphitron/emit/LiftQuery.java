package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Records;

import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The lift emitter's query: every producing occurrence whose call hands back records of the table
 * its target binds, with that table and its primary key, the derived source table's columns.
 *
 * <p>A producer lifts what its call returns onto a table when the target type is bound to the table
 * the result's records belong to; the target then reads its rows off that table, keyed by what the
 * call returned. A result whose records go down to a payload's data field is no lift of this
 * producer's: the target binds no table, and the data field's own producer reads them. The key is
 * the table's primary key, in key order, nested as one {@code MULTISET} per row.
 *
 * <p>Service producers only, whose path from the lifted table to the target is empty. A routine
 * write lifts its function table and walks its written path from there, which joins this query when
 * the store states those paths.
 */
public final class LiftQuery {

    private LiftQuery() {}

    /** One column of the lifted table's primary key, in key order. */
    public record KeyColumn(String sqlName, String javaName, String bindingType) {
        public KeyColumn {
            Objects.requireNonNull(sqlName, "sqlName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /**
     * The lifted table: its generated class, the constants class and field it is read through, and
     * its record class.
     */
    public record Table(String sqlName, String tableClassName, String constantsClassName,
                        String javaFieldName, String recordClassName) {
        public Table {
            Objects.requireNonNull(tableClassName, "tableClassName");
            Objects.requireNonNull(constantsClassName, "constantsClassName");
            Objects.requireNonNull(javaFieldName, "javaFieldName");
        }
    }

    /**
     * One lifting occurrence: its coordinate, the type its rows are projected as, whether it lifts
     * one record or a list, whether it starts at a root or under an object, the lifted table and
     * its key.
     */
    public record Row(String coordinate, String targetType, boolean list, boolean root, Table table,
                      List<KeyColumn> key) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(table, "table");
            key = List.copyOf(key);
        }
    }

    /** The graph's lifting occurrences, by coordinate. */
    public static List<Row> read(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var tt = GRAPHITRON_TABLETYPE;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        return store.dsl()
            .select(p.COORDINATE, ft.TARGET_TYPE_NAME, r.SHAPE, p.START, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN,
                t.JOOQ_NAME, t.RECORD_CLASS_FQN, key(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME))
            .from(p)
            .join(r).on(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE))
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            // The target binds the table the records belong to: the lift is this producer's.
            .join(tt).on(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                tt.TABLE_SOURCE_NAME.eq(r.TABLE_SOURCE_NAME), tt.TABLE_SCHEMA.eq(r.TABLE_SCHEMA),
                tt.TABLE_NAME.eq(r.TABLE_NAME))
            .join(t).on(t.SOURCE_NAME.eq(r.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(r.TABLE_SCHEMA),
                t.TABLE_NAME.eq(r.TABLE_NAME))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"),
                r.SHAPE.in("SINGLE_RECORD", "RECORD_LIST"))
            .orderBy(p.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), rec.value2(), "RECORD_LIST".equals(rec.value3()),
                "ROOT".equals(rec.value4()),
                new Table(rec.value5(), rec.value6(), rec.value7(), rec.value8(), rec.value9()),
                rec.value10()));
    }

    /** The table's primary key, in key order. */
    private static Field<List<KeyColumn>> key(Field<String> source, Field<String> schema,
                                              Field<String> table) {
        var k = SQL_CONSTRAINT;
        var kc = SQL_CONSTRAINT_COLUMN;
        var c = SQL_COLUMN;
        return multiset(
            select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE)
                .from(k)
                .join(kc).on(kc.SOURCE_NAME.eq(k.SOURCE_NAME), kc.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                    kc.TABLE_NAME.eq(k.TABLE_NAME), kc.CONSTRAINT_NAME.eq(k.CONSTRAINT_NAME))
                .join(c).on(c.SOURCE_NAME.eq(kc.SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
                .where(k.SOURCE_NAME.eq(source), k.TABLE_SCHEMA.eq(schema), k.TABLE_NAME.eq(table),
                    k.CONSTRAINT_TYPE.eq("PRIMARY KEY"))
                .orderBy(kc.POSITION))
            .convertFrom(rs -> rs.map(Records.mapping(KeyColumn::new)));
    }
}
