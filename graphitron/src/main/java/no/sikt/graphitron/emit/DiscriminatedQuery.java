package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * The discriminated lift's query: for every occurrence whose rows a column of one shared table tells
 * apart, that column, each participant with the value meaning a row is it, and each participant's
 * fields read off another table, with the columns the step there joins on and the column read.
 *
 * <p>A lift onto such an occurrence reads the shared table's rows by key as any lift does, and adds
 * what tells a row's type: the column itself, under the alias the type resolver reads, the rows
 * kept to the participants' values, and a participant's field on another table read through its
 * step from the shared row, for that participant's rows alone.
 *
 * <p>What this query does not state yet is refused, the reason naming it, so the lift is emitted
 * whole or not at all: a participant on a table of its own, a field the interface declares off the
 * shared table, a participant's field nesting an object, and a field more than one step away.
 */
public final class DiscriminatedQuery {

    private DiscriminatedQuery() {}

    /** A column a step joins on: the shared table's, by its jOOQ name, and the other table's. */
    public record StepColumn(String fromJavaName, String toJavaName) {}

    /**
     * A participant's field read off another table: its name, the table, the columns the step from
     * the shared row joins on, and the column read there.
     */
    public record CrossField(String fieldName, LiftQuery.Table table, List<StepColumn> on, String columnJavaName) {
        public CrossField {
            on = List.copyOf(on);
        }
    }

    /** A participant: its type, the value meaning a row is it, and its fields off the shared table. */
    public record Participant(String typeName, String value, List<CrossField> crossFields) {
        public Participant {
            crossFields = List.copyOf(crossFields);
        }
    }

    /**
     * One occurrence the column tells apart: its coordinate, the column by its SQL name, the
     * participants in name order, or why it is not lifted here.
     */
    public record Row(String coordinate, String discriminatorColumn, List<Participant> participants,
                      Optional<String> refusal) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            participants = List.copyOf(participants);
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    private record Stated(String coordinate, String type, String recognizedBy, String table, String base,
                          String column, String value) {}

    private record FieldFact(String typeName, String fieldName, String targetKind, String reaches, boolean tableBound) {}

    /**
     * The graph's discriminated occurrences, by coordinate; {@code targets} names the interface each
     * lifting occurrence answers, whose own fields are checked beside its participants'.
     */
    public static Map<String, Row> read(StoreHandle store, Map<String, String> targets) {
        var p = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var graph = store.graphName();
        var byCoordinate = new LinkedHashMap<String, List<Stated>>();
        store.dsl().select(p.COORDINATE, p.PARTICIPANT_TYPE, p.RECOGNIZED_BY, p.TABLE_NAME, p.BASE_NAME,
                p.DISCRIMINATOR_COLUMN, p.DISCRIMINATOR_VALUE)
            .from(p)
            .where(p.GRAPH_NAME.eq(graph))
            .orderBy(p.COORDINATE, p.PARTICIPANT_TYPE)
            .fetch(r -> new Stated(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7()))
            .forEach(s -> byCoordinate.computeIfAbsent(s.coordinate(), k -> new ArrayList<>()).add(s));
        // Only an occurrence a discriminator tells apart is this query's; one told apart by table is
        // the polymorphic lift's.
        byCoordinate.values().removeIf(rows -> rows.stream().noneMatch(s -> "DISCRIMINATOR".equals(s.recognizedBy())));
        var fields = fields(store);
        var out = new LinkedHashMap<String, Row>();
        byCoordinate.forEach((coordinate, stated) -> out.put(coordinate,
            row(store, coordinate, Optional.ofNullable(targets.get(coordinate)), stated, fields)));
        return out;
    }

    private static Row row(StoreHandle store, String coordinate, Optional<String> target, List<Stated> stated,
                           Map<String, List<FieldFact>> fields) {
        var first = stated.getFirst();
        String base = first.base();
        if (target.isPresent()) {
            for (var f : fields.getOrDefault(target.get(), List.of())) {
                boolean off = f.reaches() != null && !f.reaches().equals(base);
                if (off || "OBJECT".equals(f.targetKind()) && !f.tableBound()) {
                    return refused(coordinate, first, "the interface declares " + target.get() + "."
                        + f.fieldName() + " off " + base);
                }
            }
        }
        var participants = new ArrayList<Participant>();
        for (var s : stated) {
            if (!"DISCRIMINATOR".equals(s.recognizedBy()) || !s.table().equals(base)) {
                return refused(coordinate, first, s.type() + " is a participant on a table of its own");
            }
            var crossFields = new ArrayList<CrossField>();
            for (var f : fields.getOrDefault(s.type(), List.of())) {
                if ("OBJECT".equals(f.targetKind()) && !f.tableBound()) {
                    return refused(coordinate, first, s.type() + "." + f.fieldName() + " nests an object");
                }
                if (f.reaches() == null || f.reaches().equals(base)) {
                    continue;
                }
                var cross = crossField(store, s.type(), f.fieldName(), base);
                if (cross.isEmpty()) {
                    return refused(coordinate, first, s.type() + "." + f.fieldName()
                        + " is read more than one step off " + base);
                }
                crossFields.add(cross.get());
            }
            participants.add(new Participant(s.type(), s.value(), crossFields));
        }
        return new Row(coordinate, first.column(), participants, Optional.empty());
    }

    private static Row refused(String coordinate, Stated first, String reason) {
        return new Row(coordinate, first.column(), List.of(), Optional.of(reason));
    }

    /**
     * Each participant-candidate type's fields: what kind of type each answers, the table its path
     * reaches, and whether that type is bound to a table.
     */
    private static Map<String, List<FieldFact>> fields(StoreHandle store) {
        var f = GRAPHITRON_FIELD;
        var t = GRAPHITRON_TYPE;
        var jp = GRAPHITRON_JOIN_PATH;
        var tt = GRAPHITRON_TABLETYPE;
        var out = new LinkedHashMap<String, List<FieldFact>>();
        store.dsl().select(f.TYPE_NAME, f.FIELD_NAME, t.KIND, jp.TARGET_TABLE, tt.TYPE_NAME)
            .from(f)
            .join(t).on(t.GRAPH_NAME.eq(f.GRAPH_NAME), t.TYPE_NAME.eq(f.NAMED_TYPE))
            .leftJoin(jp).on(jp.GRAPH_NAME.eq(f.GRAPH_NAME), jp.COORDINATE.eq(f.COORDINATE), jp.KIND.eq("JOIN"))
            .leftJoin(tt).on(tt.GRAPH_NAME.eq(f.GRAPH_NAME), tt.TYPE_NAME.eq(f.NAMED_TYPE))
            .where(f.GRAPH_NAME.eq(store.graphName()))
            .orderBy(f.TYPE_NAME, f.ORDINAL)
            .fetch(r -> new FieldFact(r.value1(), r.value2(), r.value3(), r.value4(), r.value5() != null))
            .forEach(fact -> out.computeIfAbsent(fact.typeName(), k -> new ArrayList<>()).add(fact));
        return out;
    }

    /**
     * {@code type.field}'s one step off {@code base}: the table it reaches, the column pairs and the
     * column it reads there; empty where its path takes more than one step or does not start at
     * {@code base}.
     */
    private static Optional<CrossField> crossField(StoreHandle store, String type, String field, String base) {
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var fc = SQL_COLUMN.as("fc");
        var tc = SQL_COLUMN.as("tc");
        var tb = SQL_TABLE;
        var s = SQL_SCHEMA;
        var graph = store.graphName();
        String coordinate = type + "." + field;
        var steps = store.dsl().select(sc.POSITION, sc.FROM_TABLE, fc.JOOQ_NAME, tc.JOOQ_NAME, tb.TABLE_NAME,
                tb.CLASS_FQN, s.TABLES_CLASS_FQN, tb.JOOQ_NAME, tb.RECORD_CLASS_FQN, tb.SOURCE_NAME, tb.TABLE_SCHEMA)
            .from(sc)
            .join(fc).on(fc.SOURCE_NAME.eq(sc.FROM_SOURCE_NAME), fc.TABLE_SCHEMA.eq(sc.FROM_SCHEMA),
                fc.TABLE_NAME.eq(sc.FROM_TABLE), fc.COLUMN_NAME.eq(sc.FROM_COLUMN))
            .join(tc).on(tc.SOURCE_NAME.eq(sc.TO_SOURCE_NAME), tc.TABLE_SCHEMA.eq(sc.TO_SCHEMA),
                tc.TABLE_NAME.eq(sc.TO_TABLE), tc.COLUMN_NAME.eq(sc.TO_COLUMN))
            .join(tb).on(tb.SOURCE_NAME.eq(sc.TO_SOURCE_NAME), tb.TABLE_SCHEMA.eq(sc.TO_SCHEMA),
                tb.TABLE_NAME.eq(sc.TO_TABLE))
            .join(s).on(s.SOURCE_NAME.eq(tb.SOURCE_NAME), s.TABLE_SCHEMA.eq(tb.TABLE_SCHEMA))
            .where(sc.GRAPH_NAME.eq(graph), sc.COORDINATE.eq(coordinate))
            .orderBy(sc.POSITION, sc.ORDINAL)
            .fetch();
        if (steps.isEmpty() || steps.stream().anyMatch(r -> r.value1() != 0)
                || !steps.getFirst().value2().equals(base)) {
            return Optional.empty();
        }
        var head = steps.getFirst();
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var column = store.dsl().select(c.JOOQ_NAME)
            .from(oc)
            .join(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
            .where(oc.GRAPH_NAME.eq(graph), oc.COORDINATE.eq(coordinate),
                oc.TABLE_SOURCE_NAME.eq(head.value10()), oc.TABLE_SCHEMA.eq(head.value11()),
                oc.TABLE_NAME.eq(head.value5()))
            .fetchOptional(c.JOOQ_NAME);
        if (column.isEmpty()) {
            return Optional.empty();
        }
        var table = new LiftQuery.Table(head.value5(), head.value6(), head.value7(), head.value8(), head.value9());
        var on = steps.stream().map(r -> new StepColumn(r.value3(), r.value4())).toList();
        return Optional.of(new CrossField(field, table, on, column.get()));
    }
}
