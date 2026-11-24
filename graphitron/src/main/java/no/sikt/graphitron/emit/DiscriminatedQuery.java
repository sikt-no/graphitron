package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CROSS_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INTERFACE_SHARED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NESTING;
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
 * <p>What this query does not state the store refuses before it is asked, so the lift is emitted
 * whole: a participant on a table of its own, {@code SERVICE_DISCRIMINATED_OWN_TABLE_UNSUPPORTED}; a
 * participant's field answering an object with no table read down a path,
 * {@code SERVICE_DISCRIMINATED_OBJECT_PATH_UNSUPPORTED}; and a field more than one step away,
 * {@code SCALAR_PATH}. Meeting one here is a broken invariant.
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
     * participants in name order, and the names of the fields whose terms land under an alias no
     * participant qualifies.
     *
     * <p>Every participant's projection lands in one select list, where an aliased field compares
     * equal on its alias alone, so each participant's selection keeps every occurrence of those
     * names: a nesting field, whose spliced fields write unqualified aliases, and a field the
     * interface declares that is read by alias rather than by column identity.
     */
    public record Row(String coordinate, String discriminatorColumn, List<Participant> participants,
                      List<String> sharedAliasFieldNames) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            participants = List.copyOf(participants);
            sharedAliasFieldNames = List.copyOf(sharedAliasFieldNames);
        }
    }

    private record Stated(String coordinate, String type, String recognizedBy, String table, String base,
                          String column, String value) {}

    private record FieldFact(String typeName, String fieldName, String targetKind, String reaches, boolean tableBound) {}

    /**
     * The lifting occurrences a discriminator tells apart, by coordinate; {@code targets} names each
     * lifting occurrence with the interface it answers, whose own fields are checked beside its
     * participants'. An occurrence that does not lift is not this query's.
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
        byCoordinate.keySet().retainAll(targets.keySet());
        var fields = fields(store);
        // The participants' fields that are nesting fields, read off their parent's row.
        var ns = GRAPHITRON_OCCURRENCE_NESTING;
        var nesting = java.util.Set.copyOf(store.dsl().select(ns.COORDINATE).from(ns)
            .where(ns.GRAPH_NAME.eq(graph)).fetch(ns.COORDINATE));
        // The fields each interface's participants answer under one alias, and the fields read one
        // step off their type's own table.
        var sf = GRAPHITRON_INTERFACE_SHARED_FIELD;
        var shared = new LinkedHashMap<String, List<String>>();
        store.dsl().select(sf.INTERFACE_TYPE, sf.FIELD_NAME).from(sf).where(sf.GRAPH_NAME.eq(graph))
            .orderBy(sf.INTERFACE_TYPE, sf.FIELD_NAME)
            .forEach(r -> shared.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));
        var cc = GRAPHITRON_FIELD_CROSS_COLUMN;
        var crossColumns = java.util.Set.copyOf(store.dsl().select(cc.TYPE_NAME.concat(".").concat(cc.FIELD_NAME))
            .from(cc).where(cc.GRAPH_NAME.eq(graph)).fetch(org.jooq.Record1::value1));
        var out = new LinkedHashMap<String, Row>();
        byCoordinate.forEach((coordinate, stated) -> out.put(coordinate,
            row(store, coordinate, Optional.ofNullable(targets.get(coordinate)), stated, fields, nesting,
                shared, crossColumns)));
        return out;
    }

    /**
     * The lift of one occurrence. Each participant's fields reach the select through its projection
     * unit: a column on the row, a nesting field spliced from the row, a table-bound child down any
     * path under an alias its owner qualifies. What the unit does not carry is a scalar off the shared
     * table, read through its one step for that participant's rows alone; one read further off is
     * refused.
     */
    private static Row row(StoreHandle store, String coordinate, Optional<String> target, List<Stated> stated,
                           Map<String, List<FieldFact>> fields, java.util.Set<String> nesting,
                           Map<String, List<String>> sharedByInterface, java.util.Set<String> crossColumns) {
        var first = stated.getFirst();
        String base = first.base();
        var participants = new ArrayList<Participant>();
        for (var s : stated) {
            if (!"DISCRIMINATOR".equals(s.recognizedBy()) || !s.table().equals(base)) {
                throw refused(coordinate, s.type() + " is a participant on a table of its own",
                    "SERVICE_DISCRIMINATED_OWN_TABLE_UNSUPPORTED");
            }
            var crossFields = new ArrayList<CrossField>();
            for (var f : fields.getOrDefault(s.type(), List.of())) {
                if (nesting.contains(s.type() + "." + f.fieldName())) {
                    continue;
                }
                if ("OBJECT".equals(f.targetKind()) && !f.tableBound()) {
                    throw refused(coordinate, s.type() + "." + f.fieldName()
                        + " reads an object with no table of its own off another row",
                        "SERVICE_DISCRIMINATED_OBJECT_PATH_UNSUPPORTED");
                }
                if (f.tableBound() || f.reaches() == null || f.reaches().equals(base)) {
                    continue;
                }
                var cross = crossColumns.contains(s.type() + "." + f.fieldName())
                    ? crossField(store, s.type(), f.fieldName())
                    : Optional.<CrossField>empty();
                if (cross.isEmpty()) {
                    throw refused(coordinate, s.type() + "." + f.fieldName()
                        + " is read more than one step off " + base, "SCALAR_PATH");
                }
                crossFields.add(cross.get());
            }
            participants.add(new Participant(s.type(), s.value(), crossFields));
        }
        return new Row(coordinate, first.column(), participants,
            target.map(t -> sharedByInterface.getOrDefault(t, List.of())).orElse(List.of()));
    }

    /** A lift the store should have refused, by the code it refuses it with. */
    private static IllegalStateException refused(String coordinate, String reason, String code) {
        return new IllegalStateException(coordinate + " is lifted, though " + reason
            + ", which the store refuses as " + code);
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
     * {@code type.field}'s one step off its own table, which graphitron_field_cross_column states it
     * is read across: the table it reaches, the column pairs and the column it reads there; empty
     * where no column is bound there.
     */
    private static Optional<CrossField> crossField(StoreHandle store, String type, String field) {
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
        if (steps.isEmpty()) {
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
