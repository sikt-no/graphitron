package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.GraphitronFieldTarget;
import no.sikt.graphitron.model.tables.GraphitronOccurrenceProducerRoutine;
import no.sikt.graphitron.model.tables.GraphitronRoutineEntry;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIMED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;

/**
 * {@link RoutineWriteEmitter}'s query: every {@code @routine}-writing mutation coordinate whose
 * re-read the store states, with every catalog name its entry point spells.
 *
 * <p>A routine write is an occurrence on the mutation root whose producer is a {@code ROUTINE}: the
 * store states the callable it calls ({@code graphitron_occurrence_producer_routine}) and what it
 * produces ({@code graphitron_occurrence_producer_result}). The write's statement takes no step
 * onward, since it holds the write's locks, so its result is re-read in a statement of its own, and
 * the result's shape says where that re-read is stated:
 *
 * <ul>
 *   <li>a result of <b>records</b> lifted onto a table ({@link RecordsResult}) is re-read along the
 *       field's own join path, from the routine's result to that table, each step stating the column
 *       pairs it joins on;</li>
 *   <li>an <b>object</b> held on the routine's result ({@link ObjectResult}) is re-read by the child
 *       occurrences under the field that take a key row, each paired by name from the routine's
 *       result to the table the key row names.</li>
 * </ul>
 *
 * <p>Admission is those facts being present and nothing else: a coordinate has a row where the store
 * states its re-read, and no row where it does not. Whether the schema is valid is the build's
 * question, answered by the store's defects, which stop the build before anything is emitted; this
 * query does not read them. A closed list of shapes the store states but the emitter does not render
 * yet is named by {@link Unrendered} and never reaches the rows.
 *
 * <p>Three statements at their grains, which is the rule and not a tuning. The occurrence statement
 * reads every routine-write occurrence with its call, its routine arguments, its join path's steps
 * and its re-reading children as correlated {@code MULTISET}s, which is what admission is decided
 * from. A step's column pairing and a re-reading child's captured pairing are their own statements,
 * paired back on keys the store declares and narrowed to the admitted coordinates, read once here
 * instead of once per coordinate. Every catalog name is captured rather than derived
 * ({@code sql_table.class_fqn}, {@code sql_schema.tables_class_fqn} and {@code keys_class_fqn},
 * {@code sql_constraint.jooq_name}), so a row is built with no live catalog.
 *
 * <p><b>Cost.</b> The {@code MULTISET}s correlate stored rows read on their key, the join path's
 * steps and the occurrences directly under the field, once per routine-write occurrence on the
 * mutation root. That population is the graph's routine-writing mutation fields, which is small by
 * construction.
 */
public final class RoutineWriteQuery {

    private RoutineWriteQuery() {}

    /**
     * A table as the generated model names it: its SQL name, its constant's field name on the
     * schema's {@code Tables} class, its generated class and that {@code Tables} class.
     */
    public record Table(String sqlName, String javaFieldName, String tableClassName,
                        String constantsClassName) {
        public Table {
            Objects.requireNonNull(sqlName, "sqlName");
            Objects.requireNonNull(javaFieldName, "javaFieldName");
            Objects.requireNonNull(tableClassName, "tableClassName");
            Objects.requireNonNull(constantsClassName, "constantsClassName");
        }
    }

    /**
     * A column: its SQL name, its generated field name, and its bound Java type as
     * {@code Field.getType().getName()} reports it, which is the post-converter type.
     */
    public record Column(String sqlName, String javaName, String bindingType) {
        public Column {
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /** One cell of a key pairing: the departing side's column and the arriving side's. */
    public record Pair(Column source, Column target) {}

    /**
     * One IN parameter of the routine, in declaration order. {@code path} is where the value is read
     * from the request: the author's {@code argMapping} right-hand side as written, or the
     * parameter's own name where the author wrote none. {@code javaTypeName} is the source form
     * ({@code java.lang.Integer[]} for an array).
     */
    public record Argument(String parameterName, String javaTypeName, String path) {

        /** The written path split on the dot, outermost first. */
        public List<String> segments() {
            return List.of(path.split("\\."));
        }
    }

    /** The routine call: the generated {@code Routines} class and method, and its result table. */
    public record Call(String routinesClassName, String methodName, Table resultTable,
                       List<Argument> arguments) {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    /** How a hop's join is keyed. */
    public sealed interface Keying {

        /** By a foreign key the generated model publishes as {@code <keysClass>.<constant>}. */
        record ForeignKey(String keysClassName, String constantName) implements Keying {}

        /** By name against the arriving primary key: a hop departing a function result. */
        record NameMatched() implements Keying {}
    }

    /** An authored two-argument join condition method, {@code (source, target) -> Condition}. */
    public record Condition(String className, String methodName) {}

    /**
     * One step of a records result's re-read, after the routine's result the path departs from.
     * {@code seq} is the step's place in the path counting the routine's result as zero;
     * {@code pairs} is never empty. {@code filter} is null where the path element carried no
     * {@code condition:}.
     */
    public record Hop(int seq, String alias, Table table, Keying keying, List<Pair> pairs,
                      Condition filter) {
        public Hop {
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * One admitted routine-write coordinate, by the shape of what its producer produces.
     */
    public sealed interface Row permits RecordsResult, ObjectResult {

        /** The mutation root type declaring the field. */
        String typeName();

        /** The routine-writing field. */
        String fieldName();

        /** The SDL type the field returns, unwrapped. */
        String returnTypeName();

        /** Whether the field itself returns a list. */
        boolean listReturn();

        /** The routine the write calls. */
        Call call();
    }

    /**
     * A producer whose result is records lifted onto {@code liftTable}, re-read along the field's
     * join path from the routine's result to that table.
     *
     * @param hops the path's steps after the routine's result, in path order. The first is the
     *             re-read's anchor, which the statement selects from rather than joins
     */
    public record RecordsResult(String typeName, String fieldName, String returnTypeName,
                                boolean listReturn, Call call, Table liftTable, List<Hop> hops)
            implements Row {

        public RecordsResult {
            Objects.requireNonNull(liftTable, "liftTable");
            hops = List.copyOf(hops);
            if (hops.isEmpty()) {
                throw new IllegalStateException(
                    "the routine write at " + typeName + "." + fieldName + " produces records of "
                    + liftTable.sqlName() + " and states no step after its routine's result, so its"
                    + " post-commit re-read has no table to depart from");
            }
            if (hops.getFirst().filter() != null) {
                throw new IllegalStateException(
                    "the routine write at " + typeName + "." + fieldName + " carries a condition on"
                    + " the step out of its routine's result, which the re-read is keyed past rather"
                    + " than joined across, so the condition would be dropped; the store states"
                    + " CONDITION_ON_WRITE_RESULT for it");
            }
        }
    }

    /**
     * One child occurrence re-reading an object result: the field, the table its key row names, and
     * the pairing that captures that table's key out of the routine's result.
     *
     * @param listReturn    whether the re-reading field returns a list, which is how many keys the
     *                      write captures
     * @param capturedPairs the routine result's column of each name beside the table's primary-key
     *                      column of that name, in key order
     */
    public record ReRead(String fieldName, Table table, boolean listReturn, List<Pair> capturedPairs) {

        public ReRead {
            Objects.requireNonNull(table, "table");
            capturedPairs = List.copyOf(capturedPairs);
            if (capturedPairs.isEmpty()) {
                throw new IllegalStateException(
                    "the re-reading field " + fieldName + " captures no key of " + table.sqlName()
                    + "; with none its re-read has nothing to correlate on");
            }
        }
    }

    /**
     * A producer whose result is an object held on the routine's result, re-read by the fields
     * under it. The emitter renders an object re-read by one field; more is
     * {@link Unrendered#SEVERAL_RE_READING_FIELDS} and never reaches a row.
     */
    public record ObjectResult(String typeName, String fieldName, String returnTypeName,
                               boolean listReturn, Call call, List<ReRead> reReads) implements Row {

        public ObjectResult {
            reReads = List.copyOf(reReads);
            if (reReads.size() != 1) {
                throw new IllegalStateException(
                    "the routine write at " + typeName + "." + fieldName + " returns an object"
                    + " re-read by " + reReads.size() + " fields; the emitter captures the keys of"
                    + " exactly one");
            }
        }

        /** The one field re-reading the object. */
        public ReRead reRead() {
            return reReads.getFirst();
        }
    }

    /**
     * Shapes the store states a re-read for that the emitter does not render yet. A coordinate in
     * one of these has no row; {@link #unrendered} names them so a test can pin the list closed.
     */
    enum Unrendered {
        /**
         * A records result lifted onto a table with no step after the routine's result: the field
         * writes no path, and the emitter has no re-read for the lift alone.
         */
        LIFT_WITHOUT_STEP,
        /** An object result with more than one field re-reading it by key. */
        SEVERAL_RE_READING_FIELDS,
        /**
         * An object result with a field under it that binds a node id, an {@code ID} encoded from
         * the keys. An argument binding a node id is an input to the call and no such field.
         */
        ID_ENCODED_FROM_KEYS,
        /**
         * An object result re-read by a non-null single field: a re-read finding no row would null
         * the whole result. Where the result type has an errors field the store also states a
         * defect; where it has none, this is what keeps the shape out.
         */
        NON_NULL_SINGLE_RE_READ
    }

    /**
     * Every admitted coordinate, in coordinate order, keyed by the coordinate: a second row for one
     * coordinate fails here rather than emitting two methods of one name.
     */
    public record Rows(List<Row> rows) {

        public Rows {
            rows = List.copyOf(rows);
            var seen = new java.util.HashSet<String>();
            for (var r : rows) {
                if (!seen.add(r.typeName() + "." + r.fieldName())) {
                    throw new IllegalArgumentException(
                        "the routine-write rows are keyed by coordinate; " + r.typeName() + "."
                        + r.fieldName() + " appears twice");
                }
            }
        }

        /** No rows: a graph that writes through no routine, or a run with no store. */
        public static Rows empty() {
            return new Rows(List.of());
        }

        /** The row for one coordinate, empty when the coordinate writes through no routine. */
        public Optional<Row> rowFor(String typeName, String fieldName) {
            return rows.stream()
                .filter(r -> r.typeName().equals(typeName) && r.fieldName().equals(fieldName))
                .findFirst();
        }
    }

    /**
     * Reads every routine-write coordinate whose re-read the store states. A coordinate whose
     * re-read it does not state has no row, and that is the whole of membership.
     */
    public static Rows read(StoreHandle store) {
        var occurrences = occurrences(store);
        var records = occurrences.stream().filter(RoutineWriteQuery::admittedAsRecords).toList();
        var objects = occurrences.stream().filter(RoutineWriteQuery::admittedAsObject).toList();
        var hopPairs = hopPairs(store, records);
        var reReadPairs = reReadPairs(store, objects);
        var rows = new ArrayList<Row>();
        for (var o : occurrences) {
            if (records.contains(o)) {
                rows.add(new RecordsResult(o.typeName(), o.fieldName(), o.returnTypeName(),
                    o.listReturn(), o.call(), o.liftTable(),
                    o.hops().stream()
                        .map(hop -> hop.resolve(o.typeName(), o.fieldName(), hopPairs))
                        .toList()));
            } else if (objects.contains(o)) {
                rows.add(new ObjectResult(o.typeName(), o.fieldName(), o.returnTypeName(),
                    o.listReturn(), o.call(),
                    o.reReads().stream()
                        .map(r -> new ReRead(r.fieldName(), r.table(), r.listReturn(),
                            reReadPairs.getOrDefault(r.coordinate(), List.of())))
                        .toList()));
            }
        }
        return new Rows(rows);
    }

    /**
     * Every routine-write coordinate whose re-read the store states in a shape the emitter does not
     * render yet, keyed by its {@code Type.field} coordinate, in coordinate order.
     */
    static Map<String, Unrendered> unrendered(StoreHandle store) {
        var found = new LinkedHashMap<String, Unrendered>();
        for (var o : occurrences(store)) {
            unrenderedCase(o).ifPresent(c -> found.put(o.typeName() + "." + o.fieldName(), c));
        }
        return found;
    }

    /**
     * A step's table alias: {@code <fieldName>_<index>}, the index counting hops from zero along the
     * whole path rather than per {@code @reference} element. The routine's result is sequence zero
     * and no hop, so the first hop's sequence is one.
     */
    static String hopAlias(String fieldName, int chainSeq) {
        return fieldName + "_" + (chainSeq - 1);
    }

    private record HopKey(String typeName, String fieldName, int seq) {}

    // -------------------------------------------------------------------------------------
    // Admission: the shape of the result, and whether its re-read is stated.
    // -------------------------------------------------------------------------------------

    /**
     * One routine-write occurrence on the mutation root, as the store states it: the shape of its
     * producer's result and the table records are lifted onto, whether the field's join path lands
     * on that table or on the routine's own result, the path's steps, the children that re-read by
     * key row, and whether a child binds a node id.
     */
    private record Occurrence(String typeName, String fieldName, String returnTypeName,
                              boolean listReturn, String shape, Call call, Table liftTable,
                              boolean landsOnLift, boolean landsOnRoutineResult, List<RawHop> hops,
                              List<RawReRead> reReads, boolean nodeIdChild) {

        /** Records of one table: the shape the field's own join path re-reads. */
        boolean records() {
            return ("SINGLE_RECORD".equals(shape) || "RECORD_LIST".equals(shape)) && liftTable != null;
        }

        /** An object held where the routine's result is: the shape its children re-read. */
        boolean object() {
            return "OBJECT".equals(shape) && landsOnRoutineResult;
        }
    }

    /**
     * A records result whose join path lands on the lift table and whose every step states the
     * column pairs it joins on. A step stating none is a routine node or a join by method alone,
     * neither of which a post-commit re-read can take.
     */
    private static boolean admittedAsRecords(Occurrence o) {
        return o.records() && o.landsOnLift() && !o.hops().isEmpty()
            && o.hops().stream().allMatch(RawHop::paired);
    }

    /**
     * An object result re-read by one child whose key is matched by name out of the routine's
     * result, in none of the {@link Unrendered} shapes.
     */
    private static boolean admittedAsObject(Occurrence o) {
        return o.object() && unrenderedCase(o).isEmpty() && o.reReads().size() == 1
            && o.reReads().getFirst().keyMatched();
    }

    private static Optional<Unrendered> unrenderedCase(Occurrence o) {
        if (o.records()) {
            return o.hops().isEmpty() ? Optional.of(Unrendered.LIFT_WITHOUT_STEP) : Optional.empty();
        }
        if (!o.object()) {
            return Optional.empty();
        }
        if (o.nodeIdChild()) {
            return Optional.of(Unrendered.ID_ENCODED_FROM_KEYS);
        }
        if (o.reReads().size() > 1) {
            return Optional.of(Unrendered.SEVERAL_RE_READING_FIELDS);
        }
        if (o.reReads().size() == 1 && o.reReads().getFirst().nonNullSingle()) {
            return Optional.of(Unrendered.NON_NULL_SINGLE_RE_READ);
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------------------
    // Statement one: the occurrence grain.
    // -------------------------------------------------------------------------------------

    /**
     * Every occurrence on the mutation root whose producer calls a routine, with the facts its
     * admission reads, its call, and its steps and re-reading children.
     *
     * <p>The producer's routine, its result, the call surface the generated model publishes, the
     * routine's result table and its constants class are inner joins: an occurrence missing one
     * states no call to render. The lift table is a left join because only a records result has one.
     * The arguments are read from the first {@code @routine} application, which is the one the
     * producer calls.
     */
    private static List<Occurrence> occurrences(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ro = GRAPHQL_ROOT_OPERATION;
        var ft = GRAPHITRON_FIELD_TARGET;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var res = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var re = GRAPHITRON_ROUTINE_ENTRY;
        var re2 = GRAPHITRON_ROUTINE_ENTRY.as("first_routine");
        var sr = SQL_ROUTINE;
        var rt = SQL_TABLE.as("routine_result");
        var rs = SQL_SCHEMA.as("routine_schema");
        var lt = SQL_TABLE.as("lift_table");
        var ls = SQL_SCHEMA.as("lift_schema");
        var jp = GRAPHITRON_JOIN_PATH;
        var child = GRAPHITRON_OCCURRENCE.as("encoding_child");
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        Field<Boolean> landsOnLift = field(exists(selectOne().from(jp)
            .where(jp.GRAPH_NAME.eq(p.GRAPH_NAME), jp.COORDINATE.eq(p.COORDINATE),
                jp.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME), jp.TARGET_SOURCE_NAME.eq(res.TABLE_SOURCE_NAME),
                jp.TARGET_SCHEMA.eq(res.TABLE_SCHEMA), jp.TARGET_TABLE.eq(res.TABLE_NAME))));
        Field<Boolean> landsOnRoutineResult = field(exists(selectOne().from(jp)
            .where(jp.GRAPH_NAME.eq(p.GRAPH_NAME), jp.COORDINATE.eq(p.COORDINATE),
                jp.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME), jp.TARGET_SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                jp.TARGET_SCHEMA.eq(pr.TABLE_SCHEMA), jp.TARGET_TABLE.eq(pr.ROUTINE_NAME))));
        Field<Boolean> nodeIdChild = field(exists(selectOne().from(child)
            .join(b).on(b.GRAPH_NAME.eq(child.GRAPH_NAME), b.COORDINATE.eq(child.COORDINATE),
                b.KIND.eq("NODE_ID"))
            .where(child.GRAPH_NAME.eq(p.GRAPH_NAME), child.PARENT_COORDINATE.eq(p.COORDINATE),
                child.KIND.eq("FIELD"))));
        var arguments = arguments(re, pr);
        var hops = hops(p.GRAPH_NAME, f.FIELD_NAME, ft, p.COORDINATE);
        var reReads = reReads(p.GRAPH_NAME, p.COORDINATE, pr);
        return store.dsl()
            .select(f.TYPE_NAME, f.FIELD_NAME, ft.TARGET_TYPE_NAME, f.IS_LIST, res.SHAPE,
                sr.ROUTINES_CLASS_FQN, sr.ROUTINES_METHOD_NAME,
                rt.TABLE_NAME, rt.JOOQ_NAME, rt.CLASS_FQN, rs.TABLES_CLASS_FQN,
                arguments,
                lt.TABLE_NAME, lt.JOOQ_NAME, lt.CLASS_FQN, ls.TABLES_CLASS_FQN,
                landsOnLift, landsOnRoutineResult, hops, reReads, nodeIdChild)
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ro).on(ro.GRAPH_NAME.eq(f.GRAPH_NAME), ro.TYPE_NAME.eq(f.TYPE_NAME),
                ro.OPERATION.eq("MUTATION"))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .join(pr).on(pr.GRAPH_NAME.eq(p.GRAPH_NAME), pr.COORDINATE.eq(p.COORDINATE))
            .join(res).on(res.GRAPH_NAME.eq(p.GRAPH_NAME), res.COORDINATE.eq(p.COORDINATE))
            .join(re).on(re.GRAPH_NAME.eq(f.GRAPH_NAME), re.TYPE_NAME.eq(f.TYPE_NAME),
                re.FIELD_NAME.eq(f.FIELD_NAME),
                re.ORDINAL.eq(select(min(re2.ORDINAL)).from(re2).where(re2.GRAPH_NAME.eq(re.GRAPH_NAME),
                    re2.TYPE_NAME.eq(re.TYPE_NAME), re2.FIELD_NAME.eq(re.FIELD_NAME))))
            .join(sr).on(sr.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                sr.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), sr.ROUTINE_NAME.eq(pr.ROUTINE_NAME),
                sr.ROUTINES_CLASS_FQN.isNotNull())
            .join(rt).on(rt.SOURCE_NAME.eq(sr.SOURCE_NAME), rt.TABLE_SCHEMA.eq(sr.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(sr.ROUTINE_NAME))
            .join(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .leftJoin(lt).on(lt.SOURCE_NAME.eq(res.TABLE_SOURCE_NAME),
                lt.TABLE_SCHEMA.eq(res.TABLE_SCHEMA), lt.TABLE_NAME.eq(res.TABLE_NAME))
            .leftJoin(ls).on(ls.SOURCE_NAME.eq(lt.SOURCE_NAME), ls.TABLE_SCHEMA.eq(lt.TABLE_SCHEMA))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("ROUTINE"), p.START.eq("ROOT"))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME)
            .fetch(r -> new Occurrence(r.get(f.TYPE_NAME), r.get(f.FIELD_NAME),
                r.get(ft.TARGET_TYPE_NAME), Boolean.TRUE.equals(r.get(f.IS_LIST)), r.get(res.SHAPE),
                new Call(r.get(sr.ROUTINES_CLASS_FQN), r.get(sr.ROUTINES_METHOD_NAME),
                    new Table(r.get(rt.TABLE_NAME), r.get(rt.JOOQ_NAME), r.get(rt.CLASS_FQN),
                        r.get(rs.TABLES_CLASS_FQN)),
                    r.get(arguments)),
                r.get(lt.TABLE_NAME) == null ? null : new Table(r.get(lt.TABLE_NAME),
                    r.get(lt.JOOQ_NAME), r.get(lt.CLASS_FQN), r.get(ls.TABLES_CLASS_FQN)),
                Boolean.TRUE.equals(r.get(landsOnLift)), Boolean.TRUE.equals(r.get(landsOnRoutineResult)),
                r.get(hops), r.get(reReads), Boolean.TRUE.equals(r.get(nodeIdChild))));
    }

    /**
     * The routine's IN parameters in declaration order, each bound to the request value that
     * supplies it: the author's {@code argMapping} entry for the parameter, or the parameter's own
     * name where the author wrote none, a routine call identity-binding every parameter the author
     * did not spell. A parameter naming no argument either way is a validator rejection before this
     * runs.
     */
    private static Field<List<Argument>> arguments(GraphitronRoutineEntry re,
            GraphitronOccurrenceProducerRoutine pr) {
        var p = SQL_ROUTINE_PARAMETER;
        var m = GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
        var ms = GRAPHITRON_ARGMAPPING_SITE;
        // A @routine mapping sits on a field, so the site's coordinate is that field's own.
        var mc = GRAPHQL_FIELD_ELEMENT;
        return multiset(
            select(p.JOOQ_NAME, p.BINDING_TYPE,
                coalesce(
                    field(select(m.BOUND_TO)
                        .from(m)
                        .join(ms).on(ms.GRAPH_NAME.eq(m.GRAPH_NAME), ms.SOURCE_NAME.eq(m.SOURCE_NAME),
                            ms.SOURCE_LINE.eq(m.SOURCE_LINE), ms.SOURCE_COLUMN.eq(m.SOURCE_COLUMN))
                        .join(mc).on(mc.GRAPH_NAME.eq(ms.GRAPH_NAME), mc.COORDINATE.eq(ms.COORDINATE))
                        .where(m.GRAPH_NAME.eq(re.GRAPH_NAME), ms.DIRECTIVE_NAME.eq("routine"),
                            mc.TYPE_NAME.eq(re.TYPE_NAME),
                            mc.FIELD_NAME.eq(re.FIELD_NAME), ms.ORDINAL.eq(re.ORDINAL),
                            m.PARAM_NAME.eq(p.JOOQ_NAME))
                        .orderBy(m.POSITION)
                        .limit(1)),
                    p.JOOQ_NAME))
                .from(p)
                .where(p.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME), p.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA),
                    p.ROUTINE_NAME.eq(pr.ROUTINE_NAME))
                .orderBy(p.POSITION))
            .convertFrom(r -> r.map(Records.mapping(Argument::new)));
    }

    /**
     * A step as read: everything but the column pairs, which the pairing statement supplies, and
     * whether the store states any for it.
     */
    private record RawHop(int seq, String stepVia, String alias, Table table,
                          String keysClassName, String keyConstantName, Condition filter,
                          boolean paired) {

        /**
         * The hop with its pairing joined in. A hop the store states no pairing for is refused: a
         * join on no pair re-reads the whole table rather than the row the write committed.
         */
        Hop resolve(String typeName, String fieldName, Map<HopKey, List<Pair>> pairs) {
            var found = pairs.get(new HopKey(typeName, fieldName, seq));
            if (found == null || found.isEmpty()) {
                throw new IllegalStateException(
                    "the routine-write re-read for " + typeName + "." + fieldName + " reaches hop "
                    + seq + " at alias '" + alias + "', resolved through " + stepVia + ", and the store"
                    + " states no column pairing for it; joining on no pair would re-read the whole"
                    + " of " + table.sqlName() + " rather than the row the write committed");
            }
            return new Hop(seq, alias, table, keying(typeName, fieldName), found, filter);
        }

        /**
         * Total over how a step of the join path joins: a step crossing a foreign key, named or
         * found from a table, joins on that key; a step departing a function result joins by name
         * against the arriving primary key.
         */
        private Keying keying(String typeName, String fieldName) {
            return switch (stepVia) {
                case "KEY" -> {
                    if (keysClassName == null || keyConstantName == null) {
                        throw new IllegalStateException(
                            "hop " + seq + " of " + typeName + "." + fieldName + " joins on a"
                            + " foreign key the generated model publishes no constant for, so the"
                            + " emitted join would name a constant that does not exist");
                    }
                    yield new Keying.ForeignKey(keysClassName, keyConstantName);
                }
                case "NAME_MATCH" -> new Keying.NameMatched();
                default -> throw new IllegalStateException(
                    "the join path took hop " + seq + " of " + typeName + "."
                    + fieldName + " through '" + stepVia + "', which is outside the vocabulary a"
                    + " post-commit re-read can join on");
            };
        }
    }

    /**
     * The steps the field's join path takes from the routine's result, in path order. The routine
     * is the producer's call and no step: re-invoking it after the commit would re-execute the
     * write. A step's sequence counts the routine's result as zero, so the first step is one.
     *
     * <p>A step crossing a key names its constraint, and the {@code Keys} class is the generated
     * model's for the table that declares it. A step the generated model publishes no constant for
     * arrives with nulls and is refused where the row is built.
     */
    private static Field<List<RawHop>> hops(Field<String> graph, Field<String> fieldName,
                                            GraphitronFieldTarget ft, Field<String> occurrence) {
        var s = GRAPHITRON_JOIN_PATH_STEP;
        var sk = GRAPHITRON_JOIN_PATH_STEP_KEY;
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        var ce = GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var t = SQL_TABLE.as("hop_target");
        var ts = SQL_SCHEMA.as("hop_target_schema");
        var k = SQL_CONSTRAINT.as("hop_key");
        var ks = SQL_SCHEMA.as("hop_key_schema");
        Field<String> keySource = coalesce(sk.CONSTRAINT_SOURCE_NAME, ik.CONSTRAINT_SOURCE_NAME);
        Field<String> keySchema = coalesce(sk.CONSTRAINT_SCHEMA, ik.CONSTRAINT_SCHEMA);
        Field<String> keyTable = coalesce(sk.CONSTRAINT_TABLE, ik.CONSTRAINT_TABLE);
        Field<String> keyName = coalesce(sk.CONSTRAINT_NAME, ik.CONSTRAINT_NAME);
        var paired = exists(selectOne().from(sc).where(sc.GRAPH_NAME.eq(s.GRAPH_NAME),
            sc.COORDINATE.eq(s.COORDINATE), sc.TARGET_TYPE.eq(s.TARGET_TYPE),
            sc.POSITION.eq(s.POSITION)));
        Field<String> via = when(keyName.isNotNull(), inline("KEY"))
            .when(paired, inline("NAME_MATCH"))
            .otherwise(inline("CONDITION"));
        return multiset(
            select(s.POSITION.plus(1), via, t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN,
                ts.TABLES_CLASS_FQN, ks.KEYS_CLASS_FQN, k.JOOQ_NAME, ce.CLASS_NAME, ce.METHOD,
                fieldName, field(paired))
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
                .leftJoin(ks).on(ks.SOURCE_NAME.eq(k.SOURCE_NAME),
                    ks.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
                .leftJoin(ce).on(writtenAt(ce, s, graph))
                .where(s.GRAPH_NAME.eq(graph), s.COORDINATE.eq(occurrence),
                    s.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME))
                .orderBy(s.POSITION))
            .convertFrom(r -> r.map(RoutineWriteQuery::rawHop));
    }

    /**
     * The condition written on the element a step resolved: the element is found under the claim the
     * step's path follows, at the step's place in that claim's written list.
     */
    private static org.jooq.Condition writtenAt(
            no.sikt.graphitron.model.tables.GraphitronAstReferenceConditionStepEntry condition,
            no.sikt.graphitron.model.tables.GraphitronJoinPathStep step, Field<String> graph) {
        var cl = GRAPHITRON_JOIN_PATH_CLAIMED;
        var c = GRAPHITRON_JOIN_PATH_CLAIM;
        var ap = GRAPHITRON_AST_PATH_ENTRY;
        return exists(selectOne().from(cl)
            .join(c).on(c.GRAPH_NAME.eq(cl.GRAPH_NAME), c.ELEMENT_COORDINATE.eq(cl.CLAIM_COORDINATE),
                c.TARGET_TYPE.eq(cl.CLAIM_TARGET_TYPE))
            .join(ap).on(ap.GRAPH_NAME.eq(c.GRAPH_NAME), ap.SOURCE_NAME.eq(c.SOURCE_NAME),
                ap.ANCHOR_LINE.eq(c.SOURCE_LINE), ap.ANCHOR_COLUMN.eq(c.SOURCE_COLUMN),
                ap.PLACE.eq(step.ELEMENT_PLACE))
            .where(cl.GRAPH_NAME.eq(graph), cl.COORDINATE.eq(step.COORDINATE),
                cl.TARGET_TYPE.eq(step.TARGET_TYPE),
                condition.GRAPH_NAME.eq(ap.GRAPH_NAME), condition.SOURCE_NAME.eq(ap.SOURCE_NAME),
                condition.SOURCE_LINE.eq(ap.ELEMENT_LINE), condition.SOURCE_COLUMN.eq(ap.ELEMENT_COLUMN)));
    }

    private static RawHop rawHop(Record row) {
        int seq = row.get(0, Integer.class);
        return new RawHop(seq, row.get(1, String.class),
            hopAlias(row.get(10, String.class), seq),
            new Table(row.get(2, String.class), row.get(3, String.class),
                row.get(4, String.class), row.get(5, String.class)),
            row.get(6, String.class), row.get(7, String.class),
            condition(row.get(8, String.class), row.get(9, String.class)),
            Boolean.TRUE.equals(row.get(11, Boolean.class)));
    }

    /**
     * A child occurrence that takes a key row, as read: its coordinate, which the pairing statement
     * is keyed by, its field, the table the key row names, its list-ness, whether it is a non-null
     * single, and whether the key is matched by name out of the routine's result.
     */
    private record RawReRead(String coordinate, String fieldName, Table table, boolean listReturn,
                             boolean nonNullSingle, boolean keyMatched) {}

    /**
     * The occurrences directly under the field that start on a key row of columns: each re-reads
     * the table the key row names, by the keys the write captured. The key is matched out of the
     * function's own table, the routine the producer calls.
     */
    private static Field<List<RawReRead>> reReads(Field<String> graph, Field<String> occurrence,
                                                  GraphitronOccurrenceProducerRoutine pr) {
        var co = GRAPHITRON_OCCURRENCE.as("re_reading");
        var kr = GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW;
        var cf = GRAPHITRON_FIELD.as("re_reading_field");
        var kt = SQL_TABLE.as("re_read_table");
        var ks = SQL_SCHEMA.as("re_read_schema");
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        return multiset(
            select(co.COORDINATE, co.NAME, kt.TABLE_NAME, kt.JOOQ_NAME, kt.CLASS_FQN,
                ks.TABLES_CLASS_FQN, cf.IS_LIST,
                field(cf.IS_LIST.isFalse().and(cf.NON_NULL.isTrue())),
                field(exists(selectOne().from(nm).where(nm.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                    nm.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), nm.TABLE_NAME.eq(pr.ROUTINE_NAME),
                    nm.TO_SOURCE_NAME.eq(kr.TABLE_SOURCE_NAME), nm.TO_SCHEMA.eq(kr.TABLE_SCHEMA),
                    nm.TO_TABLE.eq(kr.TABLE_NAME)))))
                .from(co)
                .join(kr).on(kr.GRAPH_NAME.eq(co.GRAPH_NAME), kr.COORDINATE.eq(co.COORDINATE),
                    kr.KEY_KIND.eq("COLUMNS"))
                .join(cf).on(cf.GRAPH_NAME.eq(co.GRAPH_NAME), cf.COORDINATE.eq(co.ELEMENT_COORDINATE))
                .join(kt).on(kt.SOURCE_NAME.eq(kr.TABLE_SOURCE_NAME),
                    kt.TABLE_SCHEMA.eq(kr.TABLE_SCHEMA), kt.TABLE_NAME.eq(kr.TABLE_NAME))
                .join(ks).on(ks.SOURCE_NAME.eq(kt.SOURCE_NAME), ks.TABLE_SCHEMA.eq(kt.TABLE_SCHEMA))
                .where(co.GRAPH_NAME.eq(graph), co.PARENT_COORDINATE.eq(occurrence),
                    co.KIND.eq("FIELD"))
                .orderBy(co.COORDINATE))
            .convertFrom(r -> r.map(row -> new RawReRead(row.value1(), row.value2(),
                new Table(row.value3(), row.value4(), row.value5(), row.value6()),
                Boolean.TRUE.equals(row.value7()), Boolean.TRUE.equals(row.value8()),
                Boolean.TRUE.equals(row.value9()))));
    }

    // -------------------------------------------------------------------------------------
    // Statement two: a records result's step pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted records result's ordered column pairing per step, as the join path's step
     * states it: the departing table's column and the arriving one's, whether a foreign key paired
     * them or a function result's names matched the arriving key.
     */
    private static Map<HopKey, List<Pair>> hopPairs(StoreHandle store, List<Occurrence> admitted) {
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var byOccurrence = new LinkedHashMap<String, Occurrence>();
        admitted.forEach(o -> byOccurrence.put(o.typeName() + "." + o.fieldName(), o));
        var needs = admitted.stream()
            .map(o -> row(o.typeName() + "." + o.fieldName(), o.returnTypeName()))
            .toList();
        var byKey = new LinkedHashMap<HopKey, List<Pair>>();
        store.dsl()
            .select(sc.COORDINATE, sc.POSITION, sc.ORDINAL,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(sc)
            .join(src).on(src.SOURCE_NAME.eq(sc.FROM_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(sc.FROM_SCHEMA), src.TABLE_NAME.eq(sc.FROM_TABLE),
                src.COLUMN_NAME.eq(sc.FROM_COLUMN))
            .join(tgt).on(tgt.SOURCE_NAME.eq(sc.TO_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(sc.TO_SCHEMA), tgt.TABLE_NAME.eq(sc.TO_TABLE),
                tgt.COLUMN_NAME.eq(sc.TO_COLUMN))
            .where(sc.GRAPH_NAME.eq(store.graphName()),
                row(sc.COORDINATE, sc.TARGET_TYPE).in(needs))
            .orderBy(sc.COORDINATE, sc.POSITION, sc.ORDINAL)
            .forEach(r -> {
                var o = byOccurrence.get(r.value1());
                byKey.computeIfAbsent(new HopKey(o.typeName(), o.fieldName(), r.value2() + 1),
                        key -> new ArrayList<>())
                    .add(pair(r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9()));
            });
        return byKey;
    }

    // -------------------------------------------------------------------------------------
    // Statement three: an object result's captured pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Each admitted re-reading child's captured key pairing, keyed by the child's coordinate: the
     * routine result's column of each name beside the primary-key column of that name on the table
     * the child's key row names, in key order. The relation holds only total pairings, since a
     * capture missing one key column filters on a partial key and re-reads a row the write did not
     * commit. The routine result is the function's own table, the routine the parent's producer
     * calls.
     */
    private static Map<String, List<Pair>> reReadPairs(StoreHandle store, List<Occurrence> admitted) {
        var co = GRAPHITRON_OCCURRENCE.as("re_reading");
        var kr = GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var children = admitted.stream()
            .flatMap(o -> o.reReads().stream().map(RawReRead::coordinate))
            .toList();
        var byKey = new LinkedHashMap<String, List<Pair>>();
        store.dsl()
            .select(kr.COORDINATE,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(kr)
            .join(co).on(co.GRAPH_NAME.eq(kr.GRAPH_NAME), co.COORDINATE.eq(kr.COORDINATE))
            .join(pr).on(pr.GRAPH_NAME.eq(co.GRAPH_NAME), pr.COORDINATE.eq(co.PARENT_COORDINATE))
            .join(nm).on(nm.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                nm.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), nm.TABLE_NAME.eq(pr.ROUTINE_NAME),
                nm.TO_SOURCE_NAME.eq(kr.TABLE_SOURCE_NAME), nm.TO_SCHEMA.eq(kr.TABLE_SCHEMA),
                nm.TO_TABLE.eq(kr.TABLE_NAME))
            .join(src).on(src.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), src.TABLE_NAME.eq(pr.ROUTINE_NAME),
                src.COLUMN_NAME.eq(nm.COLUMN_NAME))
            .join(tgt).on(tgt.SOURCE_NAME.eq(kr.TABLE_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(kr.TABLE_SCHEMA), tgt.TABLE_NAME.eq(kr.TABLE_NAME),
                tgt.COLUMN_NAME.eq(nm.TO_COLUMN))
            .where(kr.GRAPH_NAME.eq(store.graphName()), kr.KEY_KIND.eq("COLUMNS"),
                kr.COORDINATE.in(children))
            .orderBy(kr.COORDINATE, nm.POSITION)
            .forEach(r -> byKey
                .computeIfAbsent(r.value1(), key -> new ArrayList<>())
                .add(pair(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7())));
        return byKey;
    }

    private static Pair pair(String sourceSql, String sourceJava, String sourceType,
                             String targetSql, String targetJava, String targetType) {
        return new Pair(new Column(sourceSql, sourceJava, sourceType),
            new Column(targetSql, targetJava, targetType));
    }

    /** Null in, null out: an absent {@code condition:} is an absent filter, never a blank one. */
    private static Condition condition(String className, String methodName) {
        return className == null || methodName == null ? null : new Condition(className, methodName);
    }
}
