package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Records;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The frame emitter's query: every producing occurrence's frame, what a fetcher does around its
 * call. Whether it starts at a root or under an object, the type declaring its field, the outcome
 * its errors are claimed into where it has one, and under an object the key it takes off its
 * parent: the table the declaring type binds, whose row the parent is, and that table's primary
 * key, in key order. The parent's table is the declaring type's and not the occurrence's source: a
 * service under an object reads off its own result, and its parent is what it was reached from.
 *
 * <p>Service producers only, and under an object only those whose declaring type binds a table. A
 * parent holding a plain object hands its key over through an accessor the store does not state for
 * a service yet, so such a producer has no frame here.
 */
public final class FrameQuery {

    private FrameQuery() {}

    /** One column of the key a child takes off its parent, in key order. */
    public record KeyColumn(String sqlName, String javaName, String bindingType) {
        public KeyColumn {
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /** The parent's table, as the generated code reads it: its constants class and field. */
    public record ParentTable(String sqlName, String constantsClassName, String javaFieldName,
                              List<KeyColumn> key) {
        public ParentTable {
            Objects.requireNonNull(constantsClassName, "constantsClassName");
            Objects.requireNonNull(javaFieldName, "javaFieldName");
            key = List.copyOf(key);
        }
    }

    /** The outcome a producer's errors are claimed into: its type, and the field they arrive at. */
    public record Outcome(String typeName, String errorsFieldName) {
        public Outcome {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(errorsFieldName, "errorsFieldName");
        }
    }

    /**
     * One frame: the producing occurrence, the type declaring its field, its outcome if any, and
     * under an object the parent's table it takes its key off; at a root there is none.
     */
    public record Row(String coordinate, String declaringType, Optional<Outcome> outcome,
                      Optional<ParentTable> parent) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(declaringType, "declaringType");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(parent, "parent");
        }

        public boolean root() {
            return parent.isEmpty();
        }
    }

    /** The graph's frames, by coordinate. */
    public static List<Row> read(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var po = GRAPHITRON_PRODUCER_OUTCOME;
        var tt = GRAPHITRON_TABLETYPE;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        return store.dsl()
            .select(p.COORDINATE, f.TYPE_NAME, p.START, po.OUTCOME_TYPE_NAME, po.ERRORS_FIELD_NAME,
                t.TABLE_NAME, s.TABLES_CLASS_FQN, t.JOOQ_NAME,
                key(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME))
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(po).on(po.GRAPH_NAME.eq(f.GRAPH_NAME), po.TYPE_NAME.eq(f.TYPE_NAME),
                po.FIELD_NAME.eq(f.FIELD_NAME))
            // Under an object, the parent is a row of the table the declaring type binds.
            .leftJoin(tt).on(tt.GRAPH_NAME.eq(f.GRAPH_NAME), tt.TYPE_NAME.eq(f.TYPE_NAME),
                p.START.eq("CHILD"))
            .leftJoin(t).on(t.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                t.TABLE_NAME.eq(tt.TABLE_NAME))
            .leftJoin(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"),
                p.START.eq("ROOT").or(tt.TABLE_NAME.isNotNull()))
            .orderBy(p.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), rec.value2(),
                Optional.ofNullable(rec.value4()).map(type -> new Outcome(type, rec.value5())),
                "ROOT".equals(rec.value3())
                    ? Optional.empty()
                    : Optional.of(new ParentTable(rec.value6(), rec.value7(), rec.value8(), rec.value9()))));
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
