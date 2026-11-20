package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_TO_ONE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
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
 * <p>A service producer's path from the lifted table to the target is empty. A routine write on the
 * mutation root whose result is records lifts along the field's join path: its routine's result is
 * keyed onto the path's first table, the anchor, by the anchor's primary key matched by name, and the
 * steps after the anchor are the lift's hops, each with how it joins, the condition it filters by and
 * whether it reaches one row. The table the target is read off is the last hop's, or the anchor's
 * where there is none.
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

    /** A column a hop joins on: its SQL name, its generated field name and its bound Java type. */
    public record Column(String sqlName, String javaName, String bindingType) {
        public Column {
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /** One cell of a hop's column pairing: the departing table's column and the arriving one's. */
    public record Pair(Column from, Column to) {}

    /** How a hop's join is keyed. */
    public sealed interface Keying {

        /** By a foreign key the generated model publishes as {@code <keysClass>.<constant>}. */
        record ForeignKey(String keysClassName, String constantName) implements Keying {}

        /** By the hop's column pairs, compared side for side: a step riding no published key. */
        record NameMatched() implements Keying {}
    }

    /** An authored two-argument condition method, {@code (from, to) -> Condition}, a hop filters by. */
    public record Condition(String className, String methodName) {}

    /**
     * One step of the lift's path after its anchor, in path order: the table it arrives at and that
     * table's primary key, its alias, how it joins, its column pairs, the condition it filters by
     * ({@code null} where it has none), and whether it reaches one row of its table ({@code null}
     * where the store states no reach for it).
     */
    public record Hop(int position, String alias, Table table, List<KeyColumn> key, Keying keying,
                      List<Pair> pairs, Condition filter, Boolean toOne) {
        public Hop {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(keying, "keying");
            key = List.copyOf(key);
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * One lifting occurrence: its coordinate, the type its rows are projected as, whether it lifts
     * one record or a list, whether it starts at a root or under an object, the lifted table and
     * its key, and the hops from that table to the one the target is read off, empty for a service.
     */
    public record Row(String coordinate, String targetType, boolean list, boolean root, Table table,
                      List<KeyColumn> key, List<Hop> hops) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(table, "table");
            key = List.copyOf(key);
            hops = List.copyOf(hops);
        }

        /** A lift with no hops: the target is read off the lifted table itself. */
        public Row(String coordinate, String targetType, boolean list, boolean root, Table table,
                   List<KeyColumn> key) {
            this(coordinate, targetType, list, root, table, key, List.of());
        }

        /** The table the target is read off: the last hop's, or the lifted table's with none. */
        public Table terminal() {
            return hops.isEmpty() ? table : hops.getLast().table();
        }

        /** The primary key of {@link #terminal()}, in key order. */
        public List<KeyColumn> terminalKey() {
            return hops.isEmpty() ? key : hops.getLast().key();
        }
    }

    /**
     * The graph's lifting occurrences, by coordinate, as {@code graphitron_occurrence_lift} states
     * them: the services', then the routine writes'. Two statements, one per kind of producer, since a
     * routine write's row carries its path and a service's has none.
     */
    public static List<Row> read(StoreHandle store) {
        var rows = new ArrayList<Row>(services(store));
        rows.addAll(routineWrites(store));
        return List.copyOf(rows);
    }

    /** The service producers lifting what their call hands back, by coordinate. */
    private static List<Row> services(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var l = GRAPHITRON_OCCURRENCE_LIFT;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        return store.dsl()
            .select(p.COORDINATE, ft.TARGET_TYPE_NAME, l.SHAPE, p.START, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN,
                t.JOOQ_NAME, t.RECORD_CLASS_FQN, key(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME))
            .from(p)
            // The store states which producers lift and the table they lift from.
            .join(l).on(l.GRAPH_NAME.eq(p.GRAPH_NAME), l.COORDINATE.eq(p.COORDINATE))
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .join(t).on(t.SOURCE_NAME.eq(l.ANCHOR_SOURCE_NAME), t.TABLE_SCHEMA.eq(l.ANCHOR_SCHEMA),
                t.TABLE_NAME.eq(l.ANCHOR_TABLE))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"))
            .orderBy(p.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), rec.value2(), "RECORD_LIST".equals(rec.value3()),
                "ROOT".equals(rec.value4()),
                new Table(rec.value5(), rec.value6(), rec.value7(), rec.value8(), rec.value9()),
                rec.value10()));
    }

    /**
     * The routine writes on the mutation root whose records the store states lifted
     * ({@code graphitron_occurrence_lift}): the anchor its path's first step reaches, the hops the
     * steps after it.
     */
    private static List<Row> routineWrites(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var l = GRAPHITRON_OCCURRENCE_LIFT;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var first = GRAPHITRON_JOIN_PATH_STEP.as("anchor_step");
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        return store.dsl()
            .select(p.COORDINATE, ft.TARGET_TYPE_NAME, l.SHAPE, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN,
                t.JOOQ_NAME, t.RECORD_CLASS_FQN, key(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME), hops(first))
            .from(p)
            // The store states which routine writes lift and the anchor they lift from.
            .join(l).on(l.GRAPH_NAME.eq(p.GRAPH_NAME), l.COORDINATE.eq(p.COORDINATE))
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            // The path's first step, whose followers are the hops.
            .join(first).on(first.GRAPH_NAME.eq(p.GRAPH_NAME), first.COORDINATE.eq(p.COORDINATE),
                first.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME), first.POSITION.eq(0))
            .join(t).on(t.SOURCE_NAME.eq(l.ANCHOR_SOURCE_NAME), t.TABLE_SCHEMA.eq(l.ANCHOR_SCHEMA),
                t.TABLE_NAME.eq(l.ANCHOR_TABLE))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("ROUTINE"))
            .orderBy(p.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), rec.value2(), "RECORD_LIST".equals(rec.value3()), true,
                new Table(rec.value4(), rec.value5(), rec.value6(), rec.value7(), rec.value8()),
                rec.value9(), rec.value10()));
    }

    /**
     * The steps of {@code anchor}'s path after it, in path order, each with its table and that
     * table's key, the foreign key it rides where it rides one, its column pairs, the condition
     * method it filters by and whether it reaches one row.
     */
    private static Field<List<Hop>> hops(no.sikt.graphitron.model.tables.GraphitronJoinPathStep anchor) {
        var s = GRAPHITRON_JOIN_PATH_STEP.as("hop_step");
        var sk = GRAPHITRON_JOIN_PATH_STEP_KEY;
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        var m = GRAPHITRON_JOIN_PATH_STEP_METHOD;
        var one = GRAPHITRON_JOIN_PATH_STEP_TO_ONE;
        var t = SQL_TABLE.as("hop_table");
        var ts = SQL_SCHEMA.as("hop_table_schema");
        var k = SQL_CONSTRAINT.as("hop_key");
        var ks = SQL_SCHEMA.as("hop_key_schema");
        Field<String> keySource = coalesce(sk.CONSTRAINT_SOURCE_NAME, ik.CONSTRAINT_SOURCE_NAME);
        Field<String> keySchema = coalesce(sk.CONSTRAINT_SCHEMA, ik.CONSTRAINT_SCHEMA);
        Field<String> keyTable = coalesce(sk.CONSTRAINT_TABLE, ik.CONSTRAINT_TABLE);
        Field<String> keyName = coalesce(sk.CONSTRAINT_NAME, ik.CONSTRAINT_NAME);
        return multiset(
            select(s.POSITION, t.TABLE_NAME, t.CLASS_FQN, ts.TABLES_CLASS_FQN, t.JOOQ_NAME, t.RECORD_CLASS_FQN,
                key(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME), keyName, ks.KEYS_CLASS_FQN, k.JOOQ_NAME,
                pairs(s), m.METHOD_CLASS_NAME, m.METHOD_NAME, one.TO_ONE)
                .from(s)
                .join(t).on(t.SOURCE_NAME.eq(s.TO_SOURCE_NAME), t.TABLE_SCHEMA.eq(s.TO_SCHEMA),
                    t.TABLE_NAME.eq(s.TO_TABLE))
                .join(ts).on(ts.SOURCE_NAME.eq(t.SOURCE_NAME), ts.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .leftJoin(sk).on(sk.GRAPH_NAME.eq(s.GRAPH_NAME), sk.COORDINATE.eq(s.COORDINATE),
                    sk.TARGET_TYPE.eq(s.TARGET_TYPE), sk.POSITION.eq(s.POSITION))
                .leftJoin(ik).on(ik.GRAPH_NAME.eq(s.GRAPH_NAME), ik.COORDINATE.eq(s.COORDINATE),
                    ik.TARGET_TYPE.eq(s.TARGET_TYPE), ik.POSITION.eq(s.POSITION))
                .leftJoin(k).on(k.SOURCE_NAME.eq(keySource), k.TABLE_SCHEMA.eq(keySchema),
                    k.TABLE_NAME.eq(keyTable), k.CONSTRAINT_NAME.eq(keyName))
                .leftJoin(ks).on(ks.SOURCE_NAME.eq(k.SOURCE_NAME), ks.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
                .leftJoin(m).on(m.GRAPH_NAME.eq(s.GRAPH_NAME), m.COORDINATE.eq(s.COORDINATE),
                    m.TARGET_TYPE.eq(s.TARGET_TYPE), m.POSITION.eq(s.POSITION))
                .leftJoin(one).on(one.GRAPH_NAME.eq(s.GRAPH_NAME), one.COORDINATE.eq(s.COORDINATE),
                    one.TARGET_TYPE.eq(s.TARGET_TYPE), one.POSITION.eq(s.POSITION))
                .where(s.GRAPH_NAME.eq(anchor.GRAPH_NAME), s.COORDINATE.eq(anchor.COORDINATE),
                    s.TARGET_TYPE.eq(anchor.TARGET_TYPE), s.POSITION.gt(anchor.POSITION))
                .orderBy(s.POSITION))
            .convertFrom(rs -> rs.map(h -> new Hop(h.value1(), "hop" + h.value1(),
                new Table(h.value2(), h.value3(), h.value4(), h.value5(), h.value6()), h.value7(),
                keying(h.value2(), h.value8(), h.value9(), h.value10()), h.value11(),
                h.value12() == null || h.value13() == null ? null : new Condition(h.value12(), h.value13()),
                h.value14())));
    }

    /**
     * A step riding a foreign key joins on its published constant; one riding none joins on its
     * column pairs. A key the generated model publishes no constant for is refused: the emitted join
     * would name a constant that does not exist.
     */
    private static Keying keying(String table, String keyName, String keysClassName, String constantName) {
        if (keyName == null) {
            return new Keying.NameMatched();
        }
        if (keysClassName == null || constantName == null) {
            throw new IllegalStateException("the lift's step to " + table + " rides the foreign key "
                + keyName + ", which the generated model publishes no constant for");
        }
        return new Keying.ForeignKey(keysClassName, constantName);
    }

    /** A step's column pairs, in the order the step pairs them. */
    private static Field<List<Pair>> pairs(no.sikt.graphitron.model.tables.GraphitronJoinPathStep step) {
        var c = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var from = SQL_COLUMN.as("from_column");
        var to = SQL_COLUMN.as("to_column");
        return multiset(
            select(from.COLUMN_NAME, from.JOOQ_NAME, from.BINDING_TYPE, to.COLUMN_NAME, to.JOOQ_NAME,
                to.BINDING_TYPE)
                .from(c)
                .join(from).on(from.SOURCE_NAME.eq(c.FROM_SOURCE_NAME), from.TABLE_SCHEMA.eq(c.FROM_SCHEMA),
                    from.TABLE_NAME.eq(c.FROM_TABLE), from.COLUMN_NAME.eq(c.FROM_COLUMN))
                .join(to).on(to.SOURCE_NAME.eq(c.TO_SOURCE_NAME), to.TABLE_SCHEMA.eq(c.TO_SCHEMA),
                    to.TABLE_NAME.eq(c.TO_TABLE), to.COLUMN_NAME.eq(c.TO_COLUMN))
                .where(c.GRAPH_NAME.eq(step.GRAPH_NAME), c.COORDINATE.eq(step.COORDINATE),
                    c.TARGET_TYPE.eq(step.TARGET_TYPE), c.POSITION.eq(step.POSITION))
                .orderBy(c.ORDINAL))
            .convertFrom(rs -> rs.map(r -> new Pair(new Column(r.value1(), r.value2(), r.value3()),
                new Column(r.value4(), r.value5(), r.value6()))));
    }

    /** The table's primary key, in key order. */
    static Field<List<KeyColumn>> key(Field<String> source, Field<String> schema,
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
