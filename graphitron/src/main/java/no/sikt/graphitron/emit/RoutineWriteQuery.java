package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.GraphitronFieldTarget;
import no.sikt.graphitron.model.tables.GraphitronOccurrenceProducerRoutine;
import no.sikt.graphitron.model.tables.GraphitronRoutineEntry;
import no.sikt.graphitron.model.tables.GraphqlField;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.Row2;
import org.jooq.Select;

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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ELEMENT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIMED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
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
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;

/**
 * {@link RoutineWriteEmitter}'s query: every {@code @routine}-writing mutation coordinate the store
 * admits, with every catalog name its entry point spells.
 *
 * <p>Four statements at their grains, which is the rule and not a tuning. Admission reads, per
 * mutation field writing through {@code @routine}, the facts its seat holds or fails on, and decides
 * membership: which coordinates have a row is this query's own question, and nothing else asks it.
 * The coordinate statement then carries each admitted field's routine arguments and its chain's hops
 * as correlated {@code MULTISET}s. A chain hop's column pairing and a carrier's captured pairing are
 * their own statements paired back on keys the store declares, read once here instead of once per
 * coordinate. The run reads this once; the emitter then renders each coordinate from its row.
 *
 * <p>The chain's shape is the field's join path's: its steps after the producer's call, their keys
 * and their column pairs, and where it lands. Every catalog name is captured rather than derived
 * ({@code sql_table.class_fqn}, {@code sql_schema.tables_class_fqn} and {@code keys_class_fqn},
 * {@code sql_constraint.jooq_name}), so a row is built with no live catalog.
 *
 * <p><b>Cost.</b> The hop {@code MULTISET} correlates the join path's steps, stored rows read on
 * their key, once per admitted coordinate. That population is the graph's routine-writing mutation
 * fields, which is small by construction.
 */
public final class RoutineWriteQuery {

    private RoutineWriteQuery() {}

    /** The seat a coordinate's routine write sits in. */
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
     * hop's place in the chain counting the routine node as zero; {@code pairs} is never empty.
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
     * Reads every routine-write coordinate the store admits. A coordinate whose seat does not hold
     * has no row, and that is the whole of membership.
     */
    public static Rows read(StoreHandle store) {
        var admitted = admitted(store);
        var hopPairs = hopPairs(store, admitted);
        var carrierPairs = carrierPairs(store, admitted);
        return new Rows(coordinateRows(store, admitted).stream()
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

    // -------------------------------------------------------------------------------------
    // Statement zero: which coordinates are admitted, and in which seat.
    // -------------------------------------------------------------------------------------

    /**
     * Whether a coordinate's seat holds, and where it does not, which precondition stopped it. Read
     * by nothing but membership and its tests: a refused coordinate simply has no row.
     */
    enum Verdict {
        /** The seat's whole predicate holds; the coordinate has a row. */
        ADMITTED,
        /** More than one {@code @routine} is written, a shape owed the multi-lateral emit. */
        MULTIPLE_ROUTINE_NODES,
        /** An {@code @reference} is written before the routine, so the chain departs from nothing. */
        CHAIN_HEAD_NOT_ROUTINE,
        /** A {@code @condition} or {@code @orderBy} is written, which neither seat can resolve. */
        READ_SURFACE_ON_WRITE,
        /** A path is written on a payload carrier, whose data field is the re-read's seat. */
        REFERENCE_ON_CARRIER_RETURN,
        /** The return is a connection, and the re-read is keyed by the routine's columns. */
        CONNECTION_RETURN,
        /** The field's join path was not walked to its end. */
        CHAIN_UNRESOLVED,
        /** The first step out of the routine's result is joined or filtered by a method. */
        UNANCHORED_FIRST_HOP,
        /** The return writes {@code @table} and the path does not land on that one table. */
        TERMINUS_NOT_RETURN_TABLE,
        /** The return writes {@code @table} with no path to walk to it. */
        TABLE_BOUND_RETURN,
        /** The return holds no one data field beside any errors field. */
        NO_CARRIER,
        /** The data field is no {@code LOOKUP}. */
        CARRIER_ELEMENT_NOT_TABLE,
        /** The data field is a non-null single, which a re-read finding no row would null. */
        CARRIER_DATA_FIELD_NON_NULL,
        /** The data field's key is not matched by name out of the routine's result. */
        CARRIER_HOP_UNRESOLVED
    }

    /** One mutation field writing through {@code @routine}: its seat, its verdict and its return. */
    record Seating(String typeName, String fieldName, Seat seat, Verdict verdict, String returnTypeName) {}

    /** An admitted coordinate, its seat and the type its field returns. */
    private record Seated(Coordinate coordinate, Seat seat, String returnTypeName) {

        String occurrence() {
            return coordinate.typeName() + "." + coordinate.fieldName();
        }
    }

    /**
     * The facts admission reads about one mutation field writing through {@code @routine}, at its
     * last application: how many it carries, whether an {@code @reference} is written and whether
     * one comes before the routine, whether a read surface is written, the type it returns and
     * whether that type writes {@code @table} and to which one table it resolves, its carrier's data
     * field, whether it returns a connection, whether its join path was walked or stopped, whether the
     * first step out of the routine's result carries a written condition, where the path lands, and on the
     * carrier side what the data field produces and whether its key is matched out of the result.
     */
    private record Facts(String typeName, String fieldName, int applications, boolean hasReference,
                         boolean referenceBeforeRoutine, boolean readSurface, String returnTypeName,
                         boolean returnWritesTable, String returnTable, String dataField,
                         boolean connectionReturn, boolean walked, boolean stopped,
                         boolean firstStepFiltered, String landing, boolean dataFieldLookup,
                         boolean dataFieldNonNullSingle, boolean carrierHop) {}

    /**
     * Which seat each mutation field writing through {@code @routine} occupies, and whether it holds:
     * the CHAIN seat where an {@code @reference} is written, the call being the write and the chain's
     * landing where the post-commit re-read runs, and the CARRIER seat where none is, the return being
     * a payload whose data field re-reads by name-matched key. A coordinate whose seat does not hold
     * draws no row, and that is the whole of membership.
     */
    private static List<Seated> admitted(StoreHandle store) {
        return seatings(store).stream()
            .filter(s -> s.verdict() == Verdict.ADMITTED)
            .map(s -> new Seated(new Coordinate(s.typeName(), s.fieldName()), s.seat(), s.returnTypeName()))
            .toList();
    }

    /** Every mutation field writing through {@code @routine}, in coordinate order, with its verdict. */
    static List<Seating> seatings(StoreHandle store) {
        return facts(store).stream()
            .map(f -> new Seating(f.typeName(), f.fieldName(), f.hasReference() ? Seat.CHAIN : Seat.CARRIER,
                verdict(f), f.returnTypeName()))
            .toList();
    }

    /**
     * Whether a seat holds, in the order the preconditions are asked. Shared by both seats: one
     * {@code @routine} only, a shape with several
     * being owed the multi-lateral emit; and no {@code @condition} or {@code @orderBy}, which neither
     * seat has a filter or an ordering to resolve against. The chain seat's chain departs from the
     * routine's result, so nothing is written before the routine; its return is no payload carrier,
     * whose data field is the right seat for the re-read, and no connection, the re-read being keyed
     * by the routine's columns rather than paginated; its path is walked to its end; its first step
     * carries no method, whose predicate names the routine's alias and so cannot appear in the
     * follow-up query; and where the return writes {@code @table} the path lands on that one table.
     * The carrier seat's return writes no {@code @table}, holds one data field beside any errors
     * field, that field is a {@code LOOKUP}, it is not a non-null single, which a re-read finding no
     * row would null with the errors beside it, and its key is matched by name out of the routine's
     * result.
     */
    private static Verdict verdict(Facts f) {
        if (f.applications() > 1) return Verdict.MULTIPLE_ROUTINE_NODES;
        if (f.hasReference() && f.referenceBeforeRoutine()) return Verdict.CHAIN_HEAD_NOT_ROUTINE;
        if (f.readSurface()) return Verdict.READ_SURFACE_ON_WRITE;
        if (f.hasReference()) {
            if (!f.returnWritesTable() && f.dataField() != null) return Verdict.REFERENCE_ON_CARRIER_RETURN;
            if (f.connectionReturn()) return Verdict.CONNECTION_RETURN;
            if (!f.walked() || f.stopped()) return Verdict.CHAIN_UNRESOLVED;
            if (f.firstStepFiltered()) return Verdict.UNANCHORED_FIRST_HOP;
            if (f.returnWritesTable() && (f.returnTable() == null || !f.returnTable().equals(f.landing()))) {
                return Verdict.TERMINUS_NOT_RETURN_TABLE;
            }
            return Verdict.ADMITTED;
        }
        if (f.returnWritesTable()) return Verdict.TABLE_BOUND_RETURN;
        if (f.dataField() == null) return Verdict.NO_CARRIER;
        if (!f.dataFieldLookup()) return Verdict.CARRIER_ELEMENT_NOT_TABLE;
        if (f.dataFieldNonNullSingle()) return Verdict.CARRIER_DATA_FIELD_NON_NULL;
        if (!f.carrierHop()) return Verdict.CARRIER_HOP_UNRESOLVED;
        return Verdict.ADMITTED;
    }

    private static List<Facts> facts(StoreHandle store) {
        var r = GRAPHITRON_ROUTINE_ENTRY;
        var r2 = GRAPHITRON_ROUTINE_ENTRY.as("other_routine");
        var ro = GRAPHQL_ROOT_OPERATION;
        var ft = GRAPHITRON_FIELD_TARGET;
        var fr = GRAPHITRON_FIELD_REFERENCE_ENTRY;
        var fc = GRAPHQL_FIELD_ELEMENT;
        var ac = GRAPHQL_ARGUMENT_ELEMENT;
        var dc = GRAPHQL_DIRECTIVE_APPLICATION;
        var te = GRAPHQL_DIRECTIVE_APPLICATION.as("table_application");
        var tt = GRAPHITRON_TABLETYPE;
        var cf = GRAPHITRON_FIELD;
        var ce = GRAPHITRON_CONNECTION_ELEMENT_TYPE;
        var jp = GRAPHITRON_JOIN_PATH;
        var st = GRAPHITRON_JOIN_PATH_STOP;
        var fs = GRAPHITRON_JOIN_PATH_STEP.as("first_step");
        var fe = GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY.as("first_condition");
        Field<String> occurrence = concat(r.TYPE_NAME, inline("."), r.FIELD_NAME);
        Field<String> dataField = field(dataFieldOf(ft.GRAPH_NAME, ft.TARGET_TYPE_NAME, r.TYPE_NAME, r.FIELD_NAME));
        return store.dsl()
            .select(r.TYPE_NAME, r.FIELD_NAME,
                field(selectCount().from(r2).where(r2.GRAPH_NAME.eq(r.GRAPH_NAME),
                    r2.TYPE_NAME.eq(r.TYPE_NAME), r2.FIELD_NAME.eq(r.FIELD_NAME))),
                field(exists(selectOne().from(fr).where(fr.GRAPH_NAME.eq(r.GRAPH_NAME),
                    fr.TYPE_NAME.eq(r.TYPE_NAME), fr.FIELD_NAME.eq(r.FIELD_NAME)))),
                field(exists(selectOne().from(fr).where(fr.GRAPH_NAME.eq(r.GRAPH_NAME),
                    fr.TYPE_NAME.eq(r.TYPE_NAME), fr.FIELD_NAME.eq(r.FIELD_NAME),
                    fr.SOURCE_NAME.eq(r.SOURCE_NAME),
                    fr.SOURCE_LINE.lt(r.SOURCE_LINE)
                        .or(fr.SOURCE_LINE.eq(r.SOURCE_LINE).and(fr.SOURCE_COLUMN.lt(r.SOURCE_COLUMN)))))),
                field(exists(selectOne().from(fc).join(dc).on(dc.GRAPH_NAME.eq(fc.GRAPH_NAME),
                        dc.COORDINATE.eq(fc.COORDINATE), dc.DIRECTIVE_NAME.eq("condition"))
                    .where(fc.GRAPH_NAME.eq(r.GRAPH_NAME), fc.TYPE_NAME.eq(r.TYPE_NAME),
                        fc.FIELD_NAME.eq(r.FIELD_NAME)))
                    .or(exists(selectOne().from(ac).join(dc).on(dc.GRAPH_NAME.eq(ac.GRAPH_NAME),
                            dc.COORDINATE.eq(ac.COORDINATE), dc.DIRECTIVE_NAME.eq("condition"))
                        .where(ac.GRAPH_NAME.eq(r.GRAPH_NAME), ac.TYPE_NAME.eq(r.TYPE_NAME),
                            ac.FIELD_NAME.eq(r.FIELD_NAME))))
                    .or(exists(selectOne().from(ac).join(dc).on(dc.GRAPH_NAME.eq(ac.GRAPH_NAME),
                            dc.COORDINATE.eq(ac.COORDINATE), dc.DIRECTIVE_NAME.eq("orderBy"))
                        .where(ac.GRAPH_NAME.eq(r.GRAPH_NAME), ac.TYPE_NAME.eq(r.TYPE_NAME),
                            ac.FIELD_NAME.eq(r.FIELD_NAME))))),
                ft.TARGET_TYPE_NAME,
                field(exists(selectOne().from(te).where(te.GRAPH_NAME.eq(ft.GRAPH_NAME),
                    te.COORDINATE.eq(ft.TARGET_TYPE_NAME), te.DIRECTIVE_NAME.eq("table")))),
                field(select(concat(tt.TABLE_SOURCE_NAME, inline("."), tt.TABLE_SCHEMA, inline("."), tt.TABLE_NAME))
                    .from(tt).where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))),
                dataField,
                field(exists(selectOne().from(cf).join(ce).on(ce.GRAPH_NAME.eq(cf.GRAPH_NAME),
                        ce.TYPE_NAME.eq(cf.NAMED_TYPE))
                    .where(cf.GRAPH_NAME.eq(r.GRAPH_NAME), cf.TYPE_NAME.eq(r.TYPE_NAME),
                        cf.FIELD_NAME.eq(r.FIELD_NAME)))),
                field(exists(selectOne().from(jp).where(jp.GRAPH_NAME.eq(r.GRAPH_NAME),
                    jp.COORDINATE.eq(occurrence), jp.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME)))),
                field(exists(selectOne().from(st).where(st.GRAPH_NAME.eq(r.GRAPH_NAME),
                    st.COORDINATE.eq(occurrence)))),
                field(exists(selectOne().from(fs)
                    .join(fe).on(writtenAt(fe, fs, r.GRAPH_NAME))
                    .where(fs.GRAPH_NAME.eq(r.GRAPH_NAME), fs.COORDINATE.eq(occurrence),
                        fs.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME), fs.POSITION.eq(0),
                        fe.CLASS_NAME.isNotNull()))),
                field(select(concat(jp.TARGET_SOURCE_NAME, inline("."), jp.TARGET_SCHEMA, inline("."), jp.TARGET_TABLE))
                    .from(jp).where(jp.GRAPH_NAME.eq(r.GRAPH_NAME), jp.COORDINATE.eq(occurrence),
                        jp.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME))),
                dataFieldLookup(ft, dataField),
                dataFieldNonNullSingle(ft, dataField),
                carrierHop(ft, dataField, occurrence))
            .from(r)
            .join(ro).on(ro.GRAPH_NAME.eq(r.GRAPH_NAME), ro.TYPE_NAME.eq(r.TYPE_NAME), ro.OPERATION.eq("MUTATION"))
            .leftJoin(ft).on(ft.GRAPH_NAME.eq(r.GRAPH_NAME), ft.TYPE_NAME.eq(r.TYPE_NAME),
                ft.FIELD_NAME.eq(r.FIELD_NAME))
            .where(r.GRAPH_NAME.eq(store.graphName()))
            // The last application defines the chain.
            .and(r.ORDINAL.eq(select(max(r2.ORDINAL)).from(r2).where(r2.GRAPH_NAME.eq(r.GRAPH_NAME),
                r2.TYPE_NAME.eq(r.TYPE_NAME), r2.FIELD_NAME.eq(r.FIELD_NAME))))
            .orderBy(r.TYPE_NAME, r.FIELD_NAME)
            .fetch(row -> new Facts(row.value1(), row.value2(), row.value3(),
                Boolean.TRUE.equals(row.value4()), Boolean.TRUE.equals(row.value5()),
                Boolean.TRUE.equals(row.value6()), row.value7(), Boolean.TRUE.equals(row.value8()),
                row.value9(), row.value10(), Boolean.TRUE.equals(row.value11()),
                Boolean.TRUE.equals(row.value12()), Boolean.TRUE.equals(row.value13()),
                Boolean.TRUE.equals(row.value14()), row.value15(), Boolean.TRUE.equals(row.value16()),
                Boolean.TRUE.equals(row.value17()), Boolean.TRUE.equals(row.value18())));
    }

    /**
     * The payload's data field: the one field of the type the routine field returns beside any
     * errors field, where the field runs its routine.
     */
    private static Select<Record1<String>> dataFieldOf(Field<String> graph, Field<String> returnType,
                                                       Field<String> typeName, Field<String> fieldName) {
        var df = GRAPHQL_FIELD.as("data_field");
        var d2 = GRAPHQL_FIELD.as("other_field");
        var e = GRAPHITRON_ERRORS_FIELD;
        var p = GRAPHITRON_PRODUCER;
        return select(df.FIELD_NAME).from(df)
            .where(df.GRAPH_NAME.eq(graph), df.TYPE_NAME.eq(returnType))
            .and(exists(selectOne().from(p).where(p.GRAPH_NAME.eq(graph), p.TYPE_NAME.eq(typeName),
                p.FIELD_NAME.eq(fieldName), p.KIND.eq("ROUTINE"))))
            .and(notExists(selectOne().from(e).where(e.GRAPH_NAME.eq(df.GRAPH_NAME),
                e.TYPE_NAME.eq(df.TYPE_NAME), e.FIELD_NAME.eq(df.FIELD_NAME))))
            .and(notExists(selectOne().from(d2).where(d2.GRAPH_NAME.eq(df.GRAPH_NAME),
                    d2.TYPE_NAME.eq(df.TYPE_NAME), d2.FIELD_NAME.ne(df.FIELD_NAME))
                .and(notExists(selectOne().from(e).where(e.GRAPH_NAME.eq(d2.GRAPH_NAME),
                    e.TYPE_NAME.eq(d2.TYPE_NAME), e.FIELD_NAME.eq(d2.FIELD_NAME))))));
    }

    private static Field<Boolean> dataFieldLookup(GraphitronFieldTarget ft, Field<String> dataField) {
        var p = GRAPHITRON_PRODUCER;
        return field(exists(selectOne().from(p).where(p.GRAPH_NAME.eq(ft.GRAPH_NAME),
            p.TYPE_NAME.eq(ft.TARGET_TYPE_NAME), p.FIELD_NAME.eq(dataField), p.KIND.eq("LOOKUP"))));
    }

    private static Field<Boolean> dataFieldNonNullSingle(GraphitronFieldTarget ft, Field<String> dataField) {
        var df = GRAPHQL_FIELD.as("data_field_shape");
        return field(exists(selectOne().from(df).where(df.GRAPH_NAME.eq(ft.GRAPH_NAME),
            df.TYPE_NAME.eq(ft.TARGET_TYPE_NAME), df.FIELD_NAME.eq(dataField),
            df.IS_LIST.isFalse(), df.NON_NULL.isTrue())));
    }

    /**
     * Whether the data field's key is matched by name out of the routine's result: the table its key
     * row names, reached from the function's own table, the routine the producer calls.
     */
    private static Field<Boolean> carrierHop(GraphitronFieldTarget ft, Field<String> dataField,
                                             Field<String> occurrence) {
        var k = GRAPHITRON_FIELD_KEY_ROW;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        return field(exists(selectOne().from(k)
            .join(pr).on(pr.GRAPH_NAME.eq(k.GRAPH_NAME), pr.COORDINATE.eq(occurrence))
            .join(nm).on(nm.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME), nm.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA),
                nm.TABLE_NAME.eq(pr.ROUTINE_NAME), nm.TO_SOURCE_NAME.eq(k.TABLE_SOURCE_NAME),
                nm.TO_SCHEMA.eq(k.TABLE_SCHEMA), nm.TO_TABLE.eq(k.TABLE_NAME))
            .where(k.GRAPH_NAME.eq(ft.GRAPH_NAME), k.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                k.FIELD_NAME.eq(dataField), k.KIND.eq("COLUMNS"))));
    }

    // -------------------------------------------------------------------------------------
    // Statement one: the coordinate grain.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted coordinate with its call, its hops and, on the carrier seat, the table its data
     * field re-reads.
     *
     * <p>The call surface, the routine's result table and its constants class are inner joins: an
     * admitted seat resolves a unique call surface by the admission's own construction, so a missing
     * row means the admission and the resolution disagree, and dropping the coordinate silently is
     * worse. The carrier joins are left joins because a chain-seated coordinate has no carrier: its
     * data field is the one of the payload with a key row, which names the table it re-reads.
     */
    private static List<RawRow> coordinateRows(StoreHandle store, List<Seated> admitted) {
        var mf = GRAPHQL_FIELD.as("mutation_field");
        var ft = GRAPHITRON_FIELD_TARGET;
        var re = GRAPHITRON_ROUTINE_ENTRY;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var sr = SQL_ROUTINE;
        var rt = SQL_TABLE.as("routine_result");
        var rs = SQL_SCHEMA.as("routine_schema");
        var k = GRAPHITRON_FIELD_KEY_ROW;
        var ct = SQL_TABLE.as("carrier_target");
        var cs = SQL_SCHEMA.as("carrier_schema");
        var df = GRAPHQL_FIELD.as("carrier_data_field");
        var fr = GRAPHITRON_FIELD_REFERENCE_ENTRY;
        Field<String> occurrence = concat(mf.TYPE_NAME, inline("."), mf.FIELD_NAME);
        var seats = new LinkedHashMap<Coordinate, Seated>();
        admitted.forEach(seated -> seats.put(seated.coordinate(), seated));
        return store.dsl()
            .select(mf.TYPE_NAME, mf.FIELD_NAME, mf.IS_LIST, df.IS_LIST,
                sr.ROUTINES_CLASS_FQN, sr.ROUTINES_METHOD_NAME,
                rt.TABLE_NAME, rt.JOOQ_NAME, rt.CLASS_FQN, rs.TABLES_CLASS_FQN,
                arguments(re, pr),
                hops(mf, ft, occurrence),
                ct.TABLE_NAME, ct.JOOQ_NAME, ct.CLASS_FQN, cs.TABLES_CLASS_FQN)
            .from(mf)
            .join(ft).on(ft.GRAPH_NAME.eq(mf.GRAPH_NAME), ft.TYPE_NAME.eq(mf.TYPE_NAME),
                ft.FIELD_NAME.eq(mf.FIELD_NAME))
            .join(re).on(re.GRAPH_NAME.eq(mf.GRAPH_NAME), re.TYPE_NAME.eq(mf.TYPE_NAME),
                re.FIELD_NAME.eq(mf.FIELD_NAME))
            .join(pr).on(pr.GRAPH_NAME.eq(mf.GRAPH_NAME), pr.COORDINATE.eq(occurrence))
            .join(sr).on(sr.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                sr.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), sr.ROUTINE_NAME.eq(pr.ROUTINE_NAME),
                sr.ROUTINES_CLASS_FQN.isNotNull())
            .join(rt).on(rt.SOURCE_NAME.eq(sr.SOURCE_NAME), rt.TABLE_SCHEMA.eq(sr.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(sr.ROUTINE_NAME))
            .join(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            // A field writing no @reference is carrier-seated: its payload's data field re-reads.
            .leftJoin(k).on(k.GRAPH_NAME.eq(ft.GRAPH_NAME), k.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                k.KIND.eq("COLUMNS"), notExists(selectOne().from(fr).where(fr.GRAPH_NAME.eq(mf.GRAPH_NAME),
                    fr.TYPE_NAME.eq(mf.TYPE_NAME), fr.FIELD_NAME.eq(mf.FIELD_NAME))))
            .leftJoin(df).on(df.GRAPH_NAME.eq(k.GRAPH_NAME), df.TYPE_NAME.eq(k.TYPE_NAME),
                df.FIELD_NAME.eq(k.FIELD_NAME))
            .leftJoin(ct).on(ct.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME),
                ct.TABLE_SCHEMA.eq(k.TABLE_SCHEMA), ct.TABLE_NAME.eq(k.TABLE_NAME))
            .leftJoin(cs).on(cs.SOURCE_NAME.eq(ct.SOURCE_NAME), cs.TABLE_SCHEMA.eq(ct.TABLE_SCHEMA))
            .where(mf.GRAPH_NAME.eq(store.graphName()),
                row(mf.TYPE_NAME, mf.FIELD_NAME).in(coordinates(admitted)))
            .orderBy(mf.TYPE_NAME, mf.FIELD_NAME)
            .fetch(row -> rawRow(row, seats.get(new Coordinate(row.value1(), row.value2()))));
    }

    /** The admitted coordinates as rows, for a semi-join narrowing a read to what this run emits. */
    private static List<Row2<String, String>> coordinates(List<Seated> admitted) {
        return admitted.stream()
            .map(seated -> row(seated.coordinate().typeName(), seated.coordinate().fieldName()))
            .toList();
    }

    private static RawRow rawRow(org.jooq.Record16<String, String, Boolean, Boolean,
            String, String, String, String, String, String, List<Argument>,
            List<RawHop>, String, String, String, String> row, Seated seated) {
        var seat = seated.seat();
        boolean chain = seat == Seat.CHAIN;
        return new RawRow(row.value1(), row.value2(), seat, seated.returnTypeName(),
            Boolean.TRUE.equals(chain ? row.value3() : row.value4()),
            new Call(row.value5(), row.value6(),
                new Table(row.value7(), row.value8(), row.value9(), row.value10()),
                row.value11()),
            chain ? row.value12() : List.of(),
            chain ? null : new Table(row.value13(), row.value14(), row.value15(), row.value16()));
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
     * The chain's hops: the steps the field's join path takes from the routine's result, in path
     * order. The routine is the producer's call and no step: re-invoking it after the commit would
     * re-execute the write. A step's sequence counts the routine as zero, so the first step is one.
     *
     * <p>A step crossing a key names its constraint, and the {@code Keys} class is the generated
     * model's for the table that declares it. A step the generated model publishes no constant for
     * arrives with nulls and is refused where the row is built.
     */
    private static Field<List<RawHop>> hops(GraphqlField mf, GraphitronFieldTarget ft, Field<String> occurrence) {
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
        Field<String> via = when(keyName.isNotNull(), inline("KEY"))
            .when(exists(selectOne().from(sc).where(sc.GRAPH_NAME.eq(s.GRAPH_NAME),
                sc.COORDINATE.eq(s.COORDINATE), sc.TARGET_TYPE.eq(s.TARGET_TYPE),
                sc.POSITION.eq(s.POSITION))), inline("NAME_MATCH"))
            .otherwise(inline("CONDITION"));
        return multiset(
            select(s.POSITION.plus(1), via, t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN,
                ts.TABLES_CLASS_FQN, ks.KEYS_CLASS_FQN, k.JOOQ_NAME, ce.CLASS_NAME, ce.METHOD,
                mf.FIELD_NAME)
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
                .leftJoin(ce).on(writtenAt(ce, s, mf.GRAPH_NAME))
                .where(s.GRAPH_NAME.eq(mf.GRAPH_NAME), s.COORDINATE.eq(occurrence),
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
            condition(row.get(8, String.class), row.get(9, String.class)));
    }

    // -------------------------------------------------------------------------------------
    // Statement two: the chain hop's column pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Every admitted chain hop's ordered column pairing, as the join path's step states it: the
     * departing table's column and the arriving one's, whether a foreign key paired them or a function
     * result's names matched the arriving key.
     */
    private static Map<HopKey, List<Pair>> hopPairs(StoreHandle store, List<Seated> admitted) {
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var byOccurrence = new LinkedHashMap<String, Coordinate>();
        admitted.forEach(seated -> byOccurrence.put(seated.occurrence(), seated.coordinate()));
        var needs = admitted.stream()
            .map(seated -> row(seated.occurrence(), seated.returnTypeName()))
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
                var coordinate = byOccurrence.get(r.value1());
                byKey.computeIfAbsent(new HopKey(coordinate.typeName(), coordinate.fieldName(),
                        r.value2() + 1), key -> new ArrayList<>())
                    .add(pair(r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9()));
            });
        return byKey;
    }

    // -------------------------------------------------------------------------------------
    // Statement three: the carrier's captured pairing.
    // -------------------------------------------------------------------------------------

    /**
     * The carrier seat's captured key pairing: the routine result's column of each name beside the
     * arriving table's primary-key column of that name, in key order. The relation holds only total
     * pairings, since a capture missing one key column filters on a partial key and re-reads a row
     * the write did not commit. The routine result is the function's own table, the routine the
     * producer calls, and the arriving table is the one the payload data field's key row names.
     */
    private static Map<Coordinate, List<Pair>> carrierPairs(StoreHandle store, List<Seated> admitted) {
        var mf = GRAPHQL_FIELD.as("mutation_field");
        var ft = GRAPHITRON_FIELD_TARGET;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var k = GRAPHITRON_FIELD_KEY_ROW;
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var carriers = admitted.stream().filter(seated -> seated.seat() == Seat.CARRIER).toList();
        var byKey = new LinkedHashMap<Coordinate, List<Pair>>();
        store.dsl()
            .select(mf.TYPE_NAME, mf.FIELD_NAME,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(mf)
            .join(ft).on(ft.GRAPH_NAME.eq(mf.GRAPH_NAME), ft.TYPE_NAME.eq(mf.TYPE_NAME),
                ft.FIELD_NAME.eq(mf.FIELD_NAME))
            .join(pr).on(pr.GRAPH_NAME.eq(mf.GRAPH_NAME),
                pr.COORDINATE.eq(concat(mf.TYPE_NAME, inline("."), mf.FIELD_NAME)))
            .join(k).on(k.GRAPH_NAME.eq(ft.GRAPH_NAME), k.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                k.KIND.eq("COLUMNS"))
            .join(nm).on(nm.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                nm.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), nm.TABLE_NAME.eq(pr.ROUTINE_NAME),
                nm.TO_SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), nm.TO_SCHEMA.eq(k.TABLE_SCHEMA),
                nm.TO_TABLE.eq(k.TABLE_NAME))
            .join(src).on(src.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), src.TABLE_NAME.eq(pr.ROUTINE_NAME),
                src.COLUMN_NAME.eq(nm.COLUMN_NAME))
            .join(tgt).on(tgt.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(k.TABLE_SCHEMA), tgt.TABLE_NAME.eq(k.TABLE_NAME),
                tgt.COLUMN_NAME.eq(nm.TO_COLUMN))
            .where(mf.GRAPH_NAME.eq(store.graphName()),
                row(mf.TYPE_NAME, mf.FIELD_NAME).in(coordinates(carriers)))
            .orderBy(mf.TYPE_NAME, mf.FIELD_NAME, nm.POSITION)
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
