package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.IntentFieldRoutineMethod;
import no.sikt.graphitron.model.tables.IntentMutationRoutineSeat;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Records;
import org.jooq.Select;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CARRIER_DATA_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_ROUTINE_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ROUTINE_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_ROUTINE_SEAT;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;

/**
 * {@link RoutineWriteEmitter}'s query: every {@code @routine}-writing mutation coordinate the store
 * admits, with every catalog name its entry point spells.
 *
 * <p>Three statements at three grains, which is the rule and not a tuning: a coordinate, a chain
 * hop's column pairing and a carrier's captured pairing are three sentences about three keys. The
 * coordinate statement drives from {@code intent_mutation_routine_seat}, where membership lives, and
 * carries the routine's arguments and the chain's hops as correlated {@code MULTISET}s. The two
 * pairings are their own statements paired back on keys the store declares, because each is a union
 * over two relations and reads once here instead of once per coordinate. The run reads this once;
 * the emitter then renders each coordinate from its row.
 *
 * <p>Nothing here decides anything. Membership is the seat relation's verdict, the chain's shape is
 * the walk's, a pairing is the walk's column pairs. Every catalog name is captured rather than
 * derived ({@code sql_table.class_fqn}, {@code sql_schema.tables_class_fqn} and
 * {@code keys_class_fqn}, {@code sql_constraint.jooq_name}), so a row is built with no live catalog.
 *
 * <p><b>Cost.</b> The hop {@code MULTISET} correlates the walk's steps, stored rows read on their
 * key, once per admitted coordinate. That population is the graph's routine-writing mutation
 * fields, which is small by construction.
 */
public final class RoutineWriteQuery {

    private RoutineWriteQuery() {}

    /** The seat a coordinate's routine write sits in, as {@code intent_mutation_routine_seat} states it. */
    public enum Seat {
        /** The write re-reads its committed row by walking the field's own {@code @reference} chain. */
        CHAIN,
        /** The write returns a payload carrier whose data field re-reads by name-matched key. */
        CARRIER
    }

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
     * One hop of a chain re-read, after the routine node the chain departs from. {@code seq} is the
     * store's position in the chain counting the routine node as zero; {@code pairs} is never empty.
     * {@code filter} is null where the path element carried no {@code condition:}.
     */
    public record Hop(int seq, String alias, Table table, Keying keying, List<Pair> pairs,
                      Condition filter) {
        public Hop {
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * One admitted routine-write coordinate.
     *
     * @param returnTypeName the SDL type the field returns, unwrapped
     * @param listReturn     whether the fetcher delivers many: the field's own wrapper on the chain
     *                       seat, the carrier's data field's on the other
     * @param hops           the chain hops after the routine node, in chain order; empty on the
     *                       carrier seat. The first is the re-read's anchor, which the statement
     *                       selects from rather than joins
     * @param targetTable    the table the carrier's data field re-reads; null on the chain seat
     * @param capturedPairs  the carrier's name-matched pairing; empty on the chain seat
     */
    public record Row(String typeName, String fieldName, Seat seat, String returnTypeName,
                      boolean listReturn, Call call, List<Hop> hops, Table targetTable,
                      List<Pair> capturedPairs) {

        public Row {
            hops = List.copyOf(hops);
            capturedPairs = List.copyOf(capturedPairs);
            switch (seat) {
                case CHAIN -> {
                    if (hops.isEmpty()) {
                        throw new IllegalStateException(
                            "the chain-seated routine write at " + typeName + "." + fieldName
                            + " states no hop after its routine node, so its post-commit re-read"
                            + " has no table to depart from");
                    }
                }
                case CARRIER -> {
                    if (targetTable == null || capturedPairs.isEmpty()) {
                        throw new IllegalStateException(
                            "the carrier-seated routine write at " + typeName + "." + fieldName
                            + " captures no key of its data field's table; with none the data"
                            + " field's re-read has nothing to correlate on");
                    }
                }
            }
        }
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
     * Reads every routine-write coordinate the store admits. A coordinate whose seat verdict is
     * anything but {@code ADMITTED} has no row, and that is the whole of membership.
     */
    public static Rows read(StoreHandle store) {
        var hopPairs = hopPairs(store);
        var carrierPairs = carrierPairs(store);
        return new Rows(coordinateRows(store).stream()
            .map(raw -> new Row(raw.typeName(), raw.fieldName(), raw.seat(), raw.returnTypeName(),
                raw.listReturn(), raw.call(),
                raw.hops().stream()
                    .map(hop -> hop.resolve(raw.typeName(), raw.fieldName(), hopPairs))
                    .toList(),
                raw.targetTable(),
                carrierPairs.getOrDefault(new Coordinate(raw.typeName(), raw.fieldName()), List.of())))
            .toList());
    }

    /**
     * A chain hop's table alias: {@code <fieldName>_<index>}, the index counting hops from zero along
     * the whole chain rather than per {@code @reference} element. The routine node is sequence zero
     * and no hop, so the first hop's sequence is one.
     */
    static String hopAlias(String fieldName, int chainSeq) {
        return fieldName + "_" + (chainSeq - 1);
    }

    private record Coordinate(String typeName, String fieldName) {}

    private record HopKey(String typeName, String fieldName, int seq) {}

    /** A coordinate as read, its hops still carrying an unresolved pairing. */
    private record RawRow(String typeName, String fieldName, Seat seat, String returnTypeName,
                          boolean listReturn, Call call, List<RawHop> hops, Table targetTable) {}

    /** A hop as read: everything but the column pairs, which the pairing statement supplies. */
    private record RawHop(int seq, String stepVia, String alias, Table table,
                          String keysClassName, String keyConstantName, Condition filter) {

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
         * Total over how a step of the walk joins: a step crossing a foreign key, named or found
         * from a table, joins on that key; a step departing a function result joins by name against
         * the arriving primary key.
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
                    "the walk took hop " + seq + " of " + typeName + "."
                    + fieldName + " through '" + stepVia + "', which is outside the vocabulary a"
                    + " post-commit re-read can join on");
            };
        }
    }

    // -------------------------------------------------------------------------------------
    // Statement one: the coordinate grain.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted coordinate with its call, its hops and, on the carrier seat, the table its data
     * field re-reads.
     *
     * <p>The call surface, the routine's result table and its constants class are inner joins: an
     * admitted seat resolves a unique call surface by the verdict's own construction, so a missing
     * row means the verdict and the resolution disagree, and dropping the coordinate silently is
     * worse. The carrier joins are left joins because a chain-seated coordinate has no carrier hop.
     */
    private static List<RawRow> coordinateRows(StoreHandle store) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        var rm = INTENT_FIELD_ROUTINE_METHOD;
        var rt = SQL_TABLE.as("routine_result");
        var rs = SQL_SCHEMA.as("routine_schema");
        var ch = INTENT_CARRIER_ROUTINE_HOP;
        var ct = SQL_TABLE.as("carrier_target");
        var cs = SQL_SCHEMA.as("carrier_schema");
        var cd = GRAPHITRON_CARRIER_DATA_FIELD;
        var df = GRAPHQL_FIELD.as("carrier_data_field");
        var mf = GRAPHQL_FIELD.as("mutation_field");
        return store.dsl()
            .select(s.TYPE_NAME, s.FIELD_NAME, s.SEAT, s.RETURN_TYPE_NAME,
                mf.IS_LIST, df.IS_LIST,
                rm.CLASS_NAME, rm.METHOD_NAME,
                rt.TABLE_NAME, rt.JOOQ_NAME, rt.CLASS_FQN, rs.TABLES_CLASS_FQN,
                arguments(s, rm),
                hops(s),
                ct.TABLE_NAME, ct.JOOQ_NAME, ct.CLASS_FQN, cs.TABLES_CLASS_FQN)
            .from(s)
            .join(mf).on(mf.GRAPH_NAME.eq(s.GRAPH_NAME), mf.TYPE_NAME.eq(s.TYPE_NAME),
                mf.FIELD_NAME.eq(s.FIELD_NAME))
            .join(rm).on(rm.GRAPH_NAME.eq(s.GRAPH_NAME), rm.TYPE_NAME.eq(s.TYPE_NAME),
                rm.FIELD_NAME.eq(s.FIELD_NAME), rm.ORDINAL.eq(s.ORDINAL), rm.CANDIDATES.eq(1))
            .join(rt).on(rt.SOURCE_NAME.eq(rm.SOURCE_NAME), rt.TABLE_SCHEMA.eq(rm.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(rm.ROUTINE_NAME))
            .join(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .leftJoin(cd).on(cd.GRAPH_NAME.eq(s.GRAPH_NAME), cd.TYPE_NAME.eq(s.RETURN_TYPE_NAME),
                cd.FAMILY.eq("ROUTINE"), cd.DATA_FIELDS.eq(1))
            .leftJoin(df).on(df.GRAPH_NAME.eq(cd.GRAPH_NAME), df.TYPE_NAME.eq(cd.TYPE_NAME),
                df.FIELD_NAME.eq(cd.FIELD_NAME))
            .leftJoin(ch).on(ch.GRAPH_NAME.eq(cd.GRAPH_NAME), ch.TYPE_NAME.eq(cd.TYPE_NAME),
                ch.FIELD_NAME.eq(cd.FIELD_NAME), ch.CANDIDATES.eq(1))
            .leftJoin(ct).on(ct.SOURCE_NAME.eq(ch.TO_SOURCE_NAME),
                ct.TABLE_SCHEMA.eq(ch.TO_SCHEMA), ct.TABLE_NAME.eq(ch.TO_TABLE))
            .leftJoin(cs).on(cs.SOURCE_NAME.eq(ct.SOURCE_NAME), cs.TABLE_SCHEMA.eq(ct.TABLE_SCHEMA))
            .where(s.GRAPH_NAME.eq(store.graphName()), s.VERDICT.eq("ADMITTED"))
            .orderBy(s.TYPE_NAME, s.FIELD_NAME)
            .fetch(RoutineWriteQuery::rawRow);
    }

    private static RawRow rawRow(org.jooq.Record18<String, String, String, String, Boolean, Boolean,
            String, String, String, String, String, String, List<Argument>,
            List<RawHop>, String, String, String, String> row) {
        var seat = Seat.valueOf(row.value3());
        boolean chain = seat == Seat.CHAIN;
        return new RawRow(row.value1(), row.value2(), seat, row.value4(),
            Boolean.TRUE.equals(chain ? row.value5() : row.value6()),
            new Call(row.value7(), row.value8(),
                new Table(row.value9(), row.value10(), row.value11(), row.value12()),
                row.value13()),
            chain ? row.value14() : List.of(),
            chain ? null : new Table(row.value15(), row.value16(), row.value17(), row.value18()));
    }

    /**
     * The routine's IN parameters in declaration order, each bound to the request value that
     * supplies it: the author's {@code argMapping} entry for the parameter, or the parameter's own
     * name where the author wrote none, a routine call identity-binding every parameter the author
     * did not spell. A parameter naming no argument either way is a validator rejection before this
     * runs.
     */
    private static Field<List<Argument>> arguments(IntentMutationRoutineSeat s,
            IntentFieldRoutineMethod rm) {
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
                        .where(m.GRAPH_NAME.eq(s.GRAPH_NAME), ms.DIRECTIVE_NAME.eq("routine"),
                            mc.TYPE_NAME.eq(s.TYPE_NAME),
                            mc.FIELD_NAME.eq(s.FIELD_NAME), ms.ORDINAL.eq(s.ORDINAL),
                            m.PARAM_NAME.eq(p.JOOQ_NAME))
                        .orderBy(m.POSITION)
                        .limit(1)),
                    p.JOOQ_NAME))
                .from(p)
                .where(p.SOURCE_NAME.eq(rm.SOURCE_NAME), p.TABLE_SCHEMA.eq(rm.TABLE_SCHEMA),
                    p.ROUTINE_NAME.eq(rm.ROUTINE_NAME))
                .orderBy(p.POSITION))
            .convertFrom(r -> r.map(Records.mapping(Argument::new)));
    }

    /**
     * The chain's hops: the steps the walk from the routine's result took, in path order. The
     * routine is the field's first written element, so the first step is at place one and the
     * routine is no hop: re-invoking it after the commit would re-execute the write.
     *
     * <p>A step crossing a key names its constraint, and the {@code Keys} class is the generated
     * model's for the table that declares it. A step the generated model publishes no constant for
     * arrives with nulls and is refused where the row is built.
     */
    private static Field<List<RawHop>> hops(IntentMutationRoutineSeat s) {
        var p = GRAPHITRON_FIELD_PATH;
        var sr = GRAPHITRON_FIELD_PATH_START_ROUTINE;
        var pk = GRAPHITRON_FIELD_PATH_KEY;
        var c = GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
        var pc = GRAPHITRON_FIELD_PATH_COLUMN;
        var t = SQL_TABLE.as("hop_target");
        var ts = SQL_SCHEMA.as("hop_target_schema");
        var k = SQL_CONSTRAINT.as("hop_key");
        var ks = SQL_SCHEMA.as("hop_key_schema");
        Field<String> via = when(pk.CONSTRAINT_NAME.isNotNull(), inline("KEY"))
            .when(exists(selectOne().from(pc).where(onStep(pc.GRAPH_NAME, pc.TYPE_NAME,
                pc.FIELD_NAME, pc.START_SOURCE_NAME, pc.START_SCHEMA, pc.START_TABLE,
                pc.POSITION))), inline("NAME_MATCH"))
            .otherwise(inline("CONDITION"));
        return multiset(
            select(p.POSITION, via, t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN,
                ts.TABLES_CLASS_FQN, ks.KEYS_CLASS_FQN, k.JOOQ_NAME, c.CLASS_NAME, c.METHOD,
                p.FIELD_NAME)
                .from(p)
                .join(sr).on(sr.GRAPH_NAME.eq(p.GRAPH_NAME), sr.TYPE_NAME.eq(p.TYPE_NAME),
                    sr.FIELD_NAME.eq(p.FIELD_NAME), sr.START_SOURCE_NAME.eq(p.START_SOURCE_NAME),
                    sr.START_SCHEMA.eq(p.START_SCHEMA), sr.START_TABLE.eq(p.START_TABLE))
                .join(t).on(t.SOURCE_NAME.eq(p.TO_SOURCE_NAME), t.TABLE_SCHEMA.eq(p.TO_SCHEMA),
                    t.TABLE_NAME.eq(p.TO_TABLE))
                .join(ts).on(ts.SOURCE_NAME.eq(t.SOURCE_NAME), ts.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .leftJoin(pk).on(onStep(pk.GRAPH_NAME, pk.TYPE_NAME, pk.FIELD_NAME,
                    pk.START_SOURCE_NAME, pk.START_SCHEMA, pk.START_TABLE, pk.POSITION))
                .leftJoin(k).on(k.SOURCE_NAME.eq(pk.CONSTRAINT_SOURCE_NAME),
                    k.TABLE_SCHEMA.eq(pk.CONSTRAINT_SCHEMA), k.TABLE_NAME.eq(pk.CONSTRAINT_TABLE),
                    k.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
                .leftJoin(ks).on(ks.SOURCE_NAME.eq(k.SOURCE_NAME),
                    ks.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
                .leftJoin(c).on(c.GRAPH_NAME.eq(p.GRAPH_NAME),
                    c.SOURCE_NAME.eq(p.ELEMENT_SOURCE_NAME),
                    c.SOURCE_LINE.eq(p.ELEMENT_SOURCE_LINE),
                    c.SOURCE_COLUMN.eq(p.ELEMENT_SOURCE_COLUMN))
                .where(p.GRAPH_NAME.eq(s.GRAPH_NAME), p.TYPE_NAME.eq(s.TYPE_NAME),
                    p.FIELD_NAME.eq(s.FIELD_NAME))
                .orderBy(p.POSITION))
            .convertFrom(r -> r.map(RoutineWriteQuery::rawHop));
    }

    /** A child of the walk's step {@code GRAPHITRON_FIELD_PATH}, on the step's whole key. */
    private static org.jooq.Condition onStep(Field<String> graph, Field<String> type, Field<String> field,
                                    Field<String> startSource, Field<String> startSchema,
                                    Field<String> startTable, Field<Integer> position) {
        var p = GRAPHITRON_FIELD_PATH;
        return graph.eq(p.GRAPH_NAME).and(type.eq(p.TYPE_NAME)).and(field.eq(p.FIELD_NAME))
            .and(startSource.eq(p.START_SOURCE_NAME)).and(startSchema.eq(p.START_SCHEMA))
            .and(startTable.eq(p.START_TABLE)).and(position.eq(p.POSITION));
    }

    private static RawHop rawHop(Record row) {
        int seq = row.get(0, Integer.class);
        return new RawHop(seq, row.get(1, String.class),
            hopAlias(row.get(10, String.class), seq),
            new Table(row.get(2, String.class), row.get(3, String.class),
                row.get(4, String.class), row.get(5, String.class)),
            row.get(6, String.class), row.get(7, String.class),
            condition(row.get(8, String.class), row.get(9, String.class)));
    }

    // -------------------------------------------------------------------------------------
    // Statement two: the chain hop's column pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted chain hop's ordered column pairing, as the walk's step states it: the departing
     * table's column and the arriving one's, whether a foreign key paired them or a function
     * result's names matched the arriving key.
     */
    private static Map<HopKey, List<Pair>> hopPairs(StoreHandle store) {
        var pc = GRAPHITRON_FIELD_PATH_COLUMN;
        var sr = GRAPHITRON_FIELD_PATH_START_ROUTINE;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var byKey = new LinkedHashMap<HopKey, List<Pair>>();
        store.dsl()
            .select(pc.TYPE_NAME, pc.FIELD_NAME, pc.POSITION, pc.ORDINAL,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(pc)
            .join(sr).on(sr.GRAPH_NAME.eq(pc.GRAPH_NAME), sr.TYPE_NAME.eq(pc.TYPE_NAME),
                sr.FIELD_NAME.eq(pc.FIELD_NAME), sr.START_SOURCE_NAME.eq(pc.START_SOURCE_NAME),
                sr.START_SCHEMA.eq(pc.START_SCHEMA), sr.START_TABLE.eq(pc.START_TABLE))
            .join(src).on(src.SOURCE_NAME.eq(pc.FROM_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(pc.FROM_SCHEMA), src.TABLE_NAME.eq(pc.FROM_TABLE),
                src.COLUMN_NAME.eq(pc.FROM_COLUMN))
            .join(tgt).on(tgt.SOURCE_NAME.eq(pc.TO_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(pc.TO_SCHEMA), tgt.TABLE_NAME.eq(pc.TO_TABLE),
                tgt.COLUMN_NAME.eq(pc.TO_COLUMN))
            .where(pc.GRAPH_NAME.eq(store.graphName()),
                row(pc.TYPE_NAME, pc.FIELD_NAME).in(admittedCoordinates(store)))
            .orderBy(pc.TYPE_NAME, pc.FIELD_NAME, pc.POSITION, pc.ORDINAL)
            .forEach(r -> byKey
                .computeIfAbsent(new HopKey(r.value1(), r.value2(), r.value3()),
                    key -> new ArrayList<>())
                .add(pair(r.value5(), r.value6(), r.value7(),
                    r.value8(), r.value9(), r.value10())));
        return byKey;
    }

    /**
     * The coordinates the seat relation admits, as a semi-join: the pairing reads are narrowed to
     * what this run emits, and the seat relation is keyed by coordinate, so this filters and never
     * multiplies.
     */
    private static Select<Record2<String, String>> admittedCoordinates(StoreHandle store) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        return select(s.TYPE_NAME, s.FIELD_NAME)
            .from(s)
            .where(s.GRAPH_NAME.eq(store.graphName()), s.VERDICT.eq("ADMITTED"));
    }

    // -------------------------------------------------------------------------------------
    // Statement three: the carrier's captured pairing.
    // -------------------------------------------------------------------------------------

    /**
     * The carrier seat's captured key pairing: the routine result's column of each name beside the
     * arriving table's primary-key column of that name, in key order. The relation holds only total
     * pairings, since a capture missing one key column filters on a partial key and re-reads a row
     * the write did not commit.
     */
    private static Map<Coordinate, List<Pair>> carrierPairs(StoreHandle store) {
        var s = INTENT_MUTATION_ROUTINE_SEAT;
        var cd = GRAPHITRON_CARRIER_DATA_FIELD;
        var ch = INTENT_CARRIER_ROUTINE_HOP;
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var byKey = new LinkedHashMap<Coordinate, List<Pair>>();
        store.dsl()
            .select(s.TYPE_NAME, s.FIELD_NAME,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(s)
            .join(cd).on(cd.GRAPH_NAME.eq(s.GRAPH_NAME), cd.TYPE_NAME.eq(s.RETURN_TYPE_NAME),
                cd.FAMILY.eq("ROUTINE"), cd.DATA_FIELDS.eq(1))
            .join(ch).on(ch.GRAPH_NAME.eq(cd.GRAPH_NAME), ch.TYPE_NAME.eq(cd.TYPE_NAME),
                ch.FIELD_NAME.eq(cd.FIELD_NAME), ch.CANDIDATES.eq(1))
            .join(nm).on(nm.SOURCE_NAME.eq(ch.FROM_SOURCE_NAME),
                nm.TABLE_SCHEMA.eq(ch.FROM_SCHEMA), nm.TABLE_NAME.eq(ch.FROM_TABLE),
                nm.TO_SOURCE_NAME.eq(ch.TO_SOURCE_NAME), nm.TO_SCHEMA.eq(ch.TO_SCHEMA),
                nm.TO_TABLE.eq(ch.TO_TABLE))
            .join(src).on(src.SOURCE_NAME.eq(ch.FROM_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(ch.FROM_SCHEMA), src.TABLE_NAME.eq(ch.FROM_TABLE),
                src.COLUMN_NAME.eq(nm.COLUMN_NAME))
            .join(tgt).on(tgt.SOURCE_NAME.eq(ch.TO_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(ch.TO_SCHEMA), tgt.TABLE_NAME.eq(ch.TO_TABLE),
                tgt.COLUMN_NAME.eq(nm.TO_COLUMN))
            .where(s.GRAPH_NAME.eq(store.graphName()), s.VERDICT.eq("ADMITTED"),
                s.SEAT.eq("CARRIER"))
            .orderBy(s.TYPE_NAME, s.FIELD_NAME, nm.POSITION)
            .forEach(r -> byKey
                .computeIfAbsent(new Coordinate(r.value1(), r.value2()), key -> new ArrayList<>())
                .add(pair(r.value3(), r.value4(), r.value5(),
                    r.value6(), r.value7(), r.value8())));
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
