package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIMED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIMED_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of the join path's steps: every need a claim covers walked one written
 * element at a time into {@code graphitron_join_path_step} and the subtype saying how each step
 * joins, to a {@code WRITTEN} or {@code INHERITED} {@code graphitron_path} with its
 * {@code graphitron_join_path} and {@code graphitron_join_path_claimed} where the walk reaches the
 * need's target, or to a {@code graphitron_join_path_stop} where an element allows no move or
 * several; and the one step of every {@code INFERRED} path {@link Paths} wrote. Marked and swept.
 *
 * <p>Which claim a need follows is {@code graphitron_join_path_claimed_rule}'s, and what one element
 * allows from a table is {@code graphitron_join_path_step_rule}'s. A depth at a time, and within it
 * a position at a time, one statement with the graph, the depth, the position and the instant bound
 * joins the two, from each need's start at position 0 and from the step it took at the position
 * before after that; the moves each need finds are counted here. Exactly one is the step, none or several the stop, a stopped need keeping
 * the steps it took. A move that no element writes, the step after a path ending on a routine, is
 * taken where it is the only one and otherwise leaves the path where it stands.
 *
 * <p>What a step calls is settled after the walk: a condition's overload by the step's two tables, a
 * routine's parameters by the argMapping written beside it, a key's column pairs by the catalog.
 *
 * <p>The walk's defects are this stage's, at the written element they are about, found through the
 * claim: {@code ROUTE_AMBIGUOUS} for several moves, {@code NO_ROUTE_FROM_DEPARTURE} or
 * {@code ELEMENT_UNRESOLVED} for none, the first naming why where a condition alone takes any table
 * as its second parameter, {@code NO_ROUTE_TO_TARGET} where a walk takes every step and
 * ends off its target, {@code ROUTINE_PARAMETER_UNBOUND}, and the two overload defects with the
 * declarations under each in {@code graphitron_entry_defect_method}. Each row is marked with the
 * reading's instant as its depth is written, an overload defect's detail naming the pairs of every
 * depth; {@link EntryDefects#sweep} takes the rows of the gatherer's codes a reading did not mark.
 *
 * <p>The last step of the assembly's per-depth loop: reads the claims {@link PathClaims} writes
 * before the loop, and the needs {@link PathNeeds} and the paths {@link Paths} write for the same
 * depth; the next depth's sources and needs read the join paths it writes. {@link #sweep} runs after the last depth.
 */
public final class Steps {

    private Steps() {}

    /** A step no two-parameter declaration of its condition method takes. */
    public static final String NO_OVERLOAD_FOR_PAIR = "CONDITION_NO_OVERLOAD_FOR_PAIR";

    /** A step several declarations take with none more specific than every other. */
    public static final String OVERLOAD_TIE = "CONDITION_OVERLOAD_TIE";

    private static final String ROUTE_AMBIGUOUS = "ROUTE_AMBIGUOUS";
    private static final String NO_ROUTE_FROM_DEPARTURE = "NO_ROUTE_FROM_DEPARTURE";
    private static final String ELEMENT_UNRESOLVED = "ELEMENT_UNRESOLVED";
    private static final String NO_ROUTE_TO_TARGET = "NO_ROUTE_TO_TARGET";
    private static final String ROUTINE_PARAMETER_UNBOUND = "ROUTINE_PARAMETER_UNBOUND";

    /** The {@code graphitron_entry_defect} codes this stage writes. */
    public static final List<String> ENTRY_DEFECT_CODES = List.of(NO_OVERLOAD_FOR_PAIR, OVERLOAD_TIE,
        ROUTE_AMBIGUOUS, NO_ROUTE_FROM_DEPARTURE, ELEMENT_UNRESOLVED, NO_ROUTE_TO_TARGET,
        ROUTINE_PARAMETER_UNBOUND);

    /** Far more positions than any written path is long; reaching it means a walk stopped ending. */
    private static final int POSITION_LIMIT = 1000;

    private static final String JOIN = "JOIN";

    /** A catalog table by its key. */
    private record Tbl(String source, String schema, String name) {}

    /** A written position, within one file. */
    private record Pos(String source, int line, int column) {}

    /** A need, by its key within the graph. */
    private record NeedKey(String coordinate, String targetType) {}

    /** One move a position allows, as the step rule states it. */
    private record Move(Tbl to, String kind, Tbl constraintTable, String constraintName,
                        Tbl routine) {}

    /** A defect's key: where and what. */
    private record DefectKey(Pos pos, String code) {}

    /** A step whose element was written, kept for the passes after the walk. */
    private record WrittenStep(NeedKey need, int position, Pos element, Tbl from, Tbl to,
                               String claimCoordinate) {}

    /** A routine step, kept for its parameters. */
    private record RoutineStep(WrittenStep step, Tbl routine) {}

    /** Where one need's walk stands. */
    private static final class Walk {
        final NeedKey need;
        final Tbl start;
        final Tbl landing;
        final String claimCoordinate;
        final String claimTarget;
        final String origin;
        Tbl at;
        Pos lastElement;
        boolean stopped;

        Walk(NeedKey need, Tbl start, Tbl landing, String claimCoordinate, String claimTarget,
             String origin) {
            this.need = need;
            this.start = start;
            this.landing = landing;
            this.claimCoordinate = claimCoordinate;
            this.claimTarget = claimTarget;
            this.origin = origin;
            this.at = start;
        }
    }

    /**
     * Walks the claimed needs of the occurrences at {@code depth} and steps their inferred paths,
     * writing what the walks come to and their defects.
     */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var walks = claimed(dsl, graph, depth, touchedAt);
        var defects = new LinkedHashMap<DefectKey, String>();
        var written = new ArrayList<WrittenStep>();
        var routines = new ArrayList<RoutineStep>();
        for (int position = 0; ; position++) {
            if (position == POSITION_LIMIT) {
                throw new IllegalStateException("graphitron_join_path_step did not end for " + graph
                    + " in " + POSITION_LIMIT + " positions");
            }
            var rows = position(dsl, graph, depth, touchedAt, position);
            if (rows.isEmpty()) {
                break;
            }
            take(dsl, graph, touchedAt, position, rows, walks, defects, written, routines);
        }
        inferred(dsl, graph, depth, touchedAt);
        columns(dsl, graph, depth, touchedAt);
        var methodDefects = methods(dsl, graph, touchedAt, written, defects);
        parameters(dsl, graph, touchedAt, routines, defects);
        paths(dsl, graph, touchedAt, walks.values(), defects);
        wildcardArrivals(dsl, graph, defects);
        writeDefects(dsl, graph, touchedAt, defects, methodDefects);
    }

    /** Whether the occurrence at {@code coordinate} sits at {@code depth}. */
    private static Condition atDepth(Field<String> graph, Field<String> coordinate, int depth) {
        var o = GRAPHITRON_OCCURRENCE.as("d");
        return org.jooq.impl.DSL.exists(selectOne().from(o)
            .where(o.GRAPH_NAME.eq(graph), o.COORDINATE.eq(coordinate), o.DEPTH.eq(depth)));
    }

    /**
     * Every claimed need of an occurrence at {@code depth}, with where it starts and lands and the
     * claim it follows.
     */
    private static Map<NeedKey, Walk> claimed(DSLContext dsl, String graph, int depth,
                                              LocalDateTime touchedAt) {
        var n = GRAPHITRON_PATH_NEED.as("n");
        var cr = GRAPHITRON_JOIN_PATH_CLAIMED_RULE.as("cr");
        var out = new LinkedHashMap<NeedKey, Walk>();
        dsl.select(n.COORDINATE, n.TARGET_TYPE, n.START_SOURCE_NAME, n.START_SCHEMA, n.START_TABLE,
                n.LANDING_SOURCE_NAME, n.LANDING_SCHEMA, n.LANDING_TABLE, cr.CLAIM_COORDINATE,
                cr.CLAIM_TARGET_TYPE, cr.ORIGIN)
            .from(n)
            .join(cr).on(cr.GRAPH_NAME.eq(n.GRAPH_NAME), cr.COORDINATE.eq(n.COORDINATE),
                cr.TARGET_TYPE.eq(n.TARGET_TYPE))
            .where(n.GRAPH_NAME.eq(graph), n.TOUCHED_AT.eq(touchedAt))
            .and(atDepth(n.GRAPH_NAME, n.COORDINATE, depth))
            .forEach(r -> {
                var key = new NeedKey(r.value1(), r.value2());
                var landing = r.value8() == null ? null : new Tbl(r.value6(), r.value7(), r.value8());
                out.put(key, new Walk(key, new Tbl(r.value3(), r.value4(), r.value5()), landing,
                    r.value9(), r.value10(), r.value11()));
            });
        return out;
    }

    /**
     * What the step rule allows at {@code position} from where each claimed need of an occurrence at
     * {@code depth} stands: its start at position 0, and the step it took at the position before
     * after that.
     */
    private static Result<Record> position(DSLContext dsl, String graph, int depth,
                                           LocalDateTime touchedAt, int position) {
        var cr = GRAPHITRON_JOIN_PATH_CLAIMED_RULE.as("cr");
        var r = GRAPHITRON_JOIN_PATH_STEP_RULE.as("r");
        var fields = new ArrayList<Field<?>>();
        if (position == 0) {
            var n = GRAPHITRON_PATH_NEED.as("n");
            fields.add(n.COORDINATE.as("NEED_COORDINATE"));
            fields.add(n.TARGET_TYPE.as("NEED_TARGET_TYPE"));
            fields.addAll(List.of(r.fields()));
            return dsl.select(fields)
                .from(n)
                .join(cr).on(cr.GRAPH_NAME.eq(n.GRAPH_NAME), cr.COORDINATE.eq(n.COORDINATE),
                    cr.TARGET_TYPE.eq(n.TARGET_TYPE))
                .join(r).on(r.GRAPH_NAME.eq(cr.GRAPH_NAME), r.CLAIM_COORDINATE.eq(cr.CLAIM_COORDINATE),
                    r.CLAIM_TARGET_TYPE.eq(cr.CLAIM_TARGET_TYPE), r.POSITION.eq(position),
                    departs(r, n.START_SOURCE_NAME, n.START_SCHEMA, n.START_TABLE))
                .where(n.GRAPH_NAME.eq(graph), n.TOUCHED_AT.eq(touchedAt))
                .and(atDepth(n.GRAPH_NAME, n.COORDINATE, depth))
                .fetch();
        }
        var s = GRAPHITRON_JOIN_PATH_STEP.as("s");
        fields.add(s.COORDINATE.as("NEED_COORDINATE"));
        fields.add(s.TARGET_TYPE.as("NEED_TARGET_TYPE"));
        fields.addAll(List.of(r.fields()));
        return dsl.select(fields)
            .from(s)
            .join(cr).on(cr.GRAPH_NAME.eq(s.GRAPH_NAME), cr.COORDINATE.eq(s.COORDINATE),
                cr.TARGET_TYPE.eq(s.TARGET_TYPE))
            .join(r).on(r.GRAPH_NAME.eq(cr.GRAPH_NAME), r.CLAIM_COORDINATE.eq(cr.CLAIM_COORDINATE),
                r.CLAIM_TARGET_TYPE.eq(cr.CLAIM_TARGET_TYPE), r.POSITION.eq(position),
                departs(r, s.TO_SOURCE_NAME, s.TO_SCHEMA, s.TO_TABLE))
            .where(s.GRAPH_NAME.eq(graph), s.TOUCHED_AT.eq(touchedAt), s.POSITION.eq(position - 1))
            .and(atDepth(s.GRAPH_NAME, s.COORDINATE, depth))
            .fetch();
    }

    /** Whether the rule's row departs the table named, or any table, or is the element's own row. */
    private static Condition departs(no.sikt.graphitron.model.tables.GraphitronJoinPathStepRule r,
                                     Field<String> source, Field<String> schema, Field<String> table) {
        return r.FROM_TABLE.isNull()
            .or(r.FROM_SOURCE_NAME.eq(source).and(r.FROM_SCHEMA.eq(schema)).and(r.FROM_TABLE.eq(table)));
    }

    /**
     * Counts each need's moves at {@code position} and writes what they come to: the one move as a
     * step and its subtype, or a stop with its defect at the element.
     */
    private static void take(DSLContext dsl, String graph, LocalDateTime touchedAt, int position,
                             Result<Record> rows, Map<NeedKey, Walk> walks,
                             Map<DefectKey, String> defects, List<WrittenStep> written,
                             List<RoutineStep> routines) {
        var r = GRAPHITRON_JOIN_PATH_STEP_RULE;
        var byNeed = new LinkedHashMap<NeedKey, List<Record>>();
        for (var row : rows) {
            byNeed.computeIfAbsent(new NeedKey(row.get("NEED_COORDINATE", String.class),
                row.get("NEED_TARGET_TYPE", String.class)), k -> new ArrayList<>()).add(row);
        }
        var steps = new ArrayList<Object[]>();
        var keys = new ArrayList<Object[]>();
        var inferredKeys = new ArrayList<Object[]>();
        var primaryKeys = new ArrayList<Object[]>();
        var matchedKeys = new ArrayList<Object[]>();
        var calls = new ArrayList<Object[]>();
        var stops = new ArrayList<Object[]>();
        for (var entry : byNeed.entrySet()) {
            var walk = walks.get(entry.getKey());
            if (walk == null || walk.stopped) {
                continue;
            }
            Record element = null;
            var moves = new LinkedHashSet<Move>();
            for (var row : entry.getValue()) {
                if (row.get(r.TO_TABLE.getName(), String.class) == null) {
                    element = row;
                    continue;
                }
                moves.add(new Move(
                    tbl(row, r.TO_SOURCE_NAME, r.TO_SCHEMA, r.TO_TABLE),
                    row.get(r.MOVE.getName(), String.class),
                    tbl(row, r.CONSTRAINT_SOURCE_NAME, r.CONSTRAINT_SCHEMA, r.CONSTRAINT_TABLE),
                    row.get(r.CONSTRAINT_NAME.getName(), String.class),
                    tbl(row, r.ROUTINE_SOURCE_NAME, r.ROUTINE_SCHEMA, r.ROUTINE_NAME)));
            }
            var need = entry.getKey();
            if (element != null && walk.landing != null
                    && Boolean.TRUE.equals(element.get(r.LAST_ELEMENT.getName(), Boolean.class))
                    && moves.stream().anyMatch(m -> m.kind().equals("METHOD")
                        && m.to().equals(walk.landing))) {
                // A condition alone written last lands on the need's target where one of its
                // overloads arrives there, as one method reused across a target's members does.
                moves.removeIf(m -> m.kind().equals("METHOD") && !m.to().equals(walk.landing));
            }
            Pos pos = element == null ? null : new Pos(
                element.get(r.ELEMENT_SOURCE_NAME.getName(), String.class),
                element.get(r.ELEMENT_LINE.getName(), Integer.class),
                element.get(r.ELEMENT_COLUMN.getName(), Integer.class));
            Integer place = element == null ? null : element.get(r.ELEMENT_PLACE.getName(), Integer.class);
            if (moves.size() != 1) {
                if (element == null) {
                    // The step after a routine, taken only where it is the one way on.
                    continue;
                }
                walk.stopped = true;
                stops.add(new Object[] {graph, need.coordinate(), need.targetType(), position, place,
                    moves.size(), touchedAt});
                String code = moves.size() > 1 ? ROUTE_AMBIGUOUS
                    : Boolean.TRUE.equals(element.get(r.RESOLVED.getName(), Boolean.class))
                        ? NO_ROUTE_FROM_DEPARTURE : ELEMENT_UNRESOLVED;
                defects.putIfAbsent(new DefectKey(pos, code), null);
                continue;
            }
            var move = moves.iterator().next();
            var from = walk.at;
            var to = move.to();
            String asName = element == null ? null : element.get(r.AS_NAME.getName(), String.class);
            steps.add(new Object[] {graph, need.coordinate(), need.targetType(), position,
                from.source(), from.schema(), from.name(), to.source(), to.schema(), to.name(), place,
                asName, touchedAt, from.source(), from.schema(), from.name(), to.source(), to.schema(),
                to.name()});
            Object[] stepKey = {graph, need.coordinate(), need.targetType(), position};
            switch (move.kind()) {
                case "KEY", "INFERRED_KEY" -> {
                    var c = move.constraintTable();
                    var target = move.kind().equals("KEY") ? keys : inferredKeys;
                    target.add(concat(stepKey, c.source(), c.schema(), c.name(), move.constraintName(),
                        touchedAt, c.source(), c.schema(), c.name(), move.constraintName()));
                }
                case "PRIMARY_KEY" -> primaryKeys.add(concat(stepKey, from.source(), from.schema(),
                    from.name(), touchedAt, from.source(), from.schema(), from.name()));
                case "MATCHED_KEY" -> matchedKeys.add(concat(stepKey, to.source(), to.schema(),
                    to.name(), touchedAt, to.source(), to.schema(), to.name()));
                case "ROUTINE" -> {
                    var routine = move.routine();
                    calls.add(concat(stepKey, routine.source(), routine.schema(), routine.name(),
                        touchedAt, routine.source(), routine.schema(), routine.name()));
                }
                default -> { }
            }
            if (pos != null) {
                var step = new WrittenStep(need, position, pos, from, to, walk.claimCoordinate);
                written.add(step);
                if (move.kind().equals("ROUTINE")) {
                    routines.add(new RoutineStep(step, move.routine()));
                }
            }
            walk.at = to;
            walk.lastElement = pos;
        }
        var s = GRAPHITRON_JOIN_PATH_STEP;
        upsert(dsl, s, List.of(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION),
            List.of(s.FROM_SOURCE_NAME, s.FROM_SCHEMA, s.FROM_TABLE, s.TO_SOURCE_NAME, s.TO_SCHEMA,
                s.TO_TABLE, s.ELEMENT_PLACE, s.AS_NAME, s.TOUCHED_AT, s.FROM_SOURCE_REF,
                s.FROM_SCHEMA_REF, s.FROM_TABLE_REF, s.TO_SOURCE_REF, s.TO_SCHEMA_REF, s.TO_TABLE_REF),
            steps);
        var k = GRAPHITRON_JOIN_PATH_STEP_KEY;
        upsert(dsl, k, List.of(k.GRAPH_NAME, k.COORDINATE, k.TARGET_TYPE, k.POSITION),
            List.of(k.CONSTRAINT_SOURCE_NAME, k.CONSTRAINT_SCHEMA, k.CONSTRAINT_TABLE, k.CONSTRAINT_NAME,
                k.TOUCHED_AT, k.CONSTRAINT_SOURCE_REF, k.CONSTRAINT_SCHEMA_REF, k.CONSTRAINT_TABLE_REF,
                k.CONSTRAINT_NAME_REF),
            keys);
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        upsert(dsl, ik, List.of(ik.GRAPH_NAME, ik.COORDINATE, ik.TARGET_TYPE, ik.POSITION),
            List.of(ik.CONSTRAINT_SOURCE_NAME, ik.CONSTRAINT_SCHEMA, ik.CONSTRAINT_TABLE,
                ik.CONSTRAINT_NAME, ik.TOUCHED_AT, ik.CONSTRAINT_SOURCE_REF, ik.CONSTRAINT_SCHEMA_REF,
                ik.CONSTRAINT_TABLE_REF, ik.CONSTRAINT_NAME_REF),
            inferredKeys);
        var pk = GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY;
        upsert(dsl, pk, List.of(pk.GRAPH_NAME, pk.COORDINATE, pk.TARGET_TYPE, pk.POSITION),
            List.of(pk.TABLE_SOURCE_NAME, pk.TABLE_SCHEMA, pk.TABLE_NAME, pk.TOUCHED_AT,
                pk.TABLE_SOURCE_REF, pk.TABLE_SCHEMA_REF, pk.TABLE_NAME_REF),
            primaryKeys);
        var mk = GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY;
        upsert(dsl, mk, List.of(mk.GRAPH_NAME, mk.COORDINATE, mk.TARGET_TYPE, mk.POSITION),
            List.of(mk.TABLE_SOURCE_NAME, mk.TABLE_SCHEMA, mk.TABLE_NAME, mk.TOUCHED_AT,
                mk.TABLE_SOURCE_REF, mk.TABLE_SCHEMA_REF, mk.TABLE_NAME_REF),
            matchedKeys);
        var rt = GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
        upsert(dsl, rt, List.of(rt.GRAPH_NAME, rt.COORDINATE, rt.TARGET_TYPE, rt.POSITION),
            List.of(rt.ROUTINE_SOURCE_NAME, rt.TABLE_SCHEMA, rt.ROUTINE_NAME, rt.TOUCHED_AT,
                rt.ROUTINE_SOURCE_REF, rt.ROUTINE_SCHEMA_REF, rt.ROUTINE_NAME_REF),
            calls);
        var st = GRAPHITRON_JOIN_PATH_STOP;
        upsert(dsl, st, List.of(st.GRAPH_NAME, st.COORDINATE, st.TARGET_TYPE),
            List.of(st.POSITION, st.ELEMENT_PLACE, st.MOVES, st.TOUCHED_AT), stops);
    }

    /**
     * The one step of each {@code INFERRED} path this reading's {@link Paths} wrote for an occurrence
     * at {@code depth}: from the need's start to its landing over the one foreign key between them,
     * a table's one self-referencing key included, or, from a routine's derived table, by the
     * landing's primary key matched by name.
     */
    private static void inferred(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var p = GRAPHITRON_PATH.as("p");
        var j = GRAPHITRON_JOIN_PATH.as("j");
        var n = GRAPHITRON_PATH_NEED.as("n");
        Condition inferredHere = p.GRAPH_NAME.eq(graph).and(p.TOUCHED_AT.eq(touchedAt))
            .and(p.ORIGIN.eq("INFERRED")).and(atDepth(p.GRAPH_NAME, p.COORDINATE, depth));
        var s = GRAPHITRON_JOIN_PATH_STEP;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION, s.FROM_SOURCE_NAME,
                s.FROM_SCHEMA, s.FROM_TABLE, s.TO_SOURCE_NAME, s.TO_SCHEMA, s.TO_TABLE, s.ELEMENT_PLACE,
                s.AS_NAME, s.TOUCHED_AT, s.FROM_SOURCE_REF, s.FROM_SCHEMA_REF, s.FROM_TABLE_REF,
                s.TO_SOURCE_REF, s.TO_SCHEMA_REF, s.TO_TABLE_REF)
            .select(dsl.select(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE, inline(0),
                    n.START_SOURCE_NAME, n.START_SCHEMA, n.START_TABLE, j.TARGET_SOURCE_NAME,
                    j.TARGET_SCHEMA, j.TARGET_TABLE, castNull(s.ELEMENT_PLACE), castNull(s.AS_NAME),
                    val(touchedAt, s.TOUCHED_AT), n.START_SOURCE_NAME, n.START_SCHEMA, n.START_TABLE,
                    j.TARGET_SOURCE_NAME, j.TARGET_SCHEMA, j.TARGET_TABLE)
                .from(p)
                .join(j).on(j.GRAPH_NAME.eq(p.GRAPH_NAME), j.COORDINATE.eq(p.COORDINATE),
                    j.TARGET_TYPE.eq(p.TARGET_TYPE))
                .join(n).on(n.GRAPH_NAME.eq(p.GRAPH_NAME), n.COORDINATE.eq(p.COORDINATE),
                    n.TARGET_TYPE.eq(p.TARGET_TYPE))
                .where(inferredHere))
            .onConflict(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION)
            .doUpdate()
            .set(s.FROM_SOURCE_NAME, excluded(s.FROM_SOURCE_NAME))
            .set(s.FROM_SCHEMA, excluded(s.FROM_SCHEMA))
            .set(s.FROM_TABLE, excluded(s.FROM_TABLE))
            .set(s.TO_SOURCE_NAME, excluded(s.TO_SOURCE_NAME))
            .set(s.TO_SCHEMA, excluded(s.TO_SCHEMA))
            .set(s.TO_TABLE, excluded(s.TO_TABLE))
            .set(s.ELEMENT_PLACE, excluded(s.ELEMENT_PLACE))
            .set(s.AS_NAME, excluded(s.AS_NAME))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .set(s.FROM_SOURCE_REF, excluded(s.FROM_SOURCE_REF))
            .set(s.FROM_SCHEMA_REF, excluded(s.FROM_SCHEMA_REF))
            .set(s.FROM_TABLE_REF, excluded(s.FROM_TABLE_REF))
            .set(s.TO_SOURCE_REF, excluded(s.TO_SOURCE_REF))
            .set(s.TO_SCHEMA_REF, excluded(s.TO_SCHEMA_REF))
            .set(s.TO_TABLE_REF, excluded(s.TO_TABLE_REF))
            .execute();

        // The one foreign key between two tables, in whichever direction it is declared for an
        // output field, and the one the start declares for an argument or input field. A
        // self-referencing key meets both sides once; its columns orient by cardinality.
        var rc = SQL_REFERENTIAL_CONSTRAINT.as("rc");
        var ko = GRAPHITRON_OCCURRENCE.as("ko");
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        dsl.insertInto(ik)
            .columns(ik.GRAPH_NAME, ik.COORDINATE, ik.TARGET_TYPE, ik.POSITION,
                ik.CONSTRAINT_SOURCE_NAME, ik.CONSTRAINT_SCHEMA, ik.CONSTRAINT_TABLE, ik.CONSTRAINT_NAME,
                ik.TOUCHED_AT, ik.CONSTRAINT_SOURCE_REF, ik.CONSTRAINT_SCHEMA_REF,
                ik.CONSTRAINT_TABLE_REF, ik.CONSTRAINT_NAME_REF)
            .select(dsl.select(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE, inline(0), rc.SOURCE_NAME,
                    rc.TABLE_SCHEMA, rc.TABLE_NAME, rc.CONSTRAINT_NAME, val(touchedAt, ik.TOUCHED_AT),
                    rc.SOURCE_NAME, rc.TABLE_SCHEMA, rc.TABLE_NAME, rc.CONSTRAINT_NAME)
                .from(p)
                .join(j).on(j.GRAPH_NAME.eq(p.GRAPH_NAME), j.COORDINATE.eq(p.COORDINATE),
                    j.TARGET_TYPE.eq(p.TARGET_TYPE))
                .join(n).on(n.GRAPH_NAME.eq(p.GRAPH_NAME), n.COORDINATE.eq(p.COORDINATE),
                    n.TARGET_TYPE.eq(p.TARGET_TYPE))
                .join(ko).on(ko.GRAPH_NAME.eq(p.GRAPH_NAME), ko.COORDINATE.eq(p.COORDINATE))
                .join(rc).on(rc.SOURCE_NAME.eq(n.START_SOURCE_NAME).and(rc.TABLE_SCHEMA.eq(n.START_SCHEMA))
                        .and(rc.TABLE_NAME.eq(n.START_TABLE))
                        .and(rc.REFERENCED_SOURCE_NAME.eq(j.TARGET_SOURCE_NAME))
                        .and(rc.REFERENCED_SCHEMA.eq(j.TARGET_SCHEMA))
                        .and(rc.REFERENCED_TABLE.eq(j.TARGET_TABLE))
                    .or(ko.KIND.eq("FIELD").and(rc.SOURCE_NAME.eq(j.TARGET_SOURCE_NAME))
                        .and(rc.TABLE_SCHEMA.eq(j.TARGET_SCHEMA))
                        .and(rc.TABLE_NAME.eq(j.TARGET_TABLE))
                        .and(rc.REFERENCED_SOURCE_NAME.eq(n.START_SOURCE_NAME))
                        .and(rc.REFERENCED_SCHEMA.eq(n.START_SCHEMA))
                        .and(rc.REFERENCED_TABLE.eq(n.START_TABLE))))
                .where(inferredHere))
            .onConflict(ik.GRAPH_NAME, ik.COORDINATE, ik.TARGET_TYPE, ik.POSITION)
            .doUpdate()
            .set(ik.CONSTRAINT_SOURCE_NAME, excluded(ik.CONSTRAINT_SOURCE_NAME))
            .set(ik.CONSTRAINT_SCHEMA, excluded(ik.CONSTRAINT_SCHEMA))
            .set(ik.CONSTRAINT_TABLE, excluded(ik.CONSTRAINT_TABLE))
            .set(ik.CONSTRAINT_NAME, excluded(ik.CONSTRAINT_NAME))
            .set(ik.TOUCHED_AT, excluded(ik.TOUCHED_AT))
            .set(ik.CONSTRAINT_SOURCE_REF, excluded(ik.CONSTRAINT_SOURCE_REF))
            .set(ik.CONSTRAINT_SCHEMA_REF, excluded(ik.CONSTRAINT_SCHEMA_REF))
            .set(ik.CONSTRAINT_TABLE_REF, excluded(ik.CONSTRAINT_TABLE_REF))
            .set(ik.CONSTRAINT_NAME_REF, excluded(ik.CONSTRAINT_NAME_REF))
            .execute();

        // From a routine's derived table, which has no foreign key: the landing's primary key.
        var fn = SQL_TABLE.as("fn");
        var lk = SQL_PRIMARY_KEY.as("lk");
        var mk = GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY;
        dsl.insertInto(mk)
            .columns(mk.GRAPH_NAME, mk.COORDINATE, mk.TARGET_TYPE, mk.POSITION, mk.TABLE_SOURCE_NAME,
                mk.TABLE_SCHEMA, mk.TABLE_NAME, mk.TOUCHED_AT, mk.TABLE_SOURCE_REF, mk.TABLE_SCHEMA_REF,
                mk.TABLE_NAME_REF)
            .select(dsl.select(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE, inline(0),
                    j.TARGET_SOURCE_NAME, j.TARGET_SCHEMA, j.TARGET_TABLE, val(touchedAt, mk.TOUCHED_AT),
                    lk.SOURCE_NAME, lk.TABLE_SCHEMA, lk.TABLE_NAME)
                .from(p)
                .join(j).on(j.GRAPH_NAME.eq(p.GRAPH_NAME), j.COORDINATE.eq(p.COORDINATE),
                    j.TARGET_TYPE.eq(p.TARGET_TYPE))
                .join(n).on(n.GRAPH_NAME.eq(p.GRAPH_NAME), n.COORDINATE.eq(p.COORDINATE),
                    n.TARGET_TYPE.eq(p.TARGET_TYPE))
                .join(fn).on(fn.SOURCE_NAME.eq(n.START_SOURCE_NAME), fn.TABLE_SCHEMA.eq(n.START_SCHEMA),
                    fn.TABLE_NAME.eq(n.START_TABLE), fn.TABLE_TYPE.eq("FUNCTION"))
                .leftJoin(lk).on(lk.SOURCE_NAME.eq(j.TARGET_SOURCE_NAME),
                    lk.TABLE_SCHEMA.eq(j.TARGET_SCHEMA), lk.TABLE_NAME.eq(j.TARGET_TABLE))
                .where(inferredHere))
            .onConflict(mk.GRAPH_NAME, mk.COORDINATE, mk.TARGET_TYPE, mk.POSITION)
            .doUpdate()
            .set(mk.TABLE_SOURCE_NAME, excluded(mk.TABLE_SOURCE_NAME))
            .set(mk.TABLE_SCHEMA, excluded(mk.TABLE_SCHEMA))
            .set(mk.TABLE_NAME, excluded(mk.TABLE_NAME))
            .set(mk.TOUCHED_AT, excluded(mk.TOUCHED_AT))
            .set(mk.TABLE_SOURCE_REF, excluded(mk.TABLE_SOURCE_REF))
            .set(mk.TABLE_SCHEMA_REF, excluded(mk.TABLE_SCHEMA_REF))
            .set(mk.TABLE_NAME_REF, excluded(mk.TABLE_NAME_REF))
            .execute();
    }

    /**
     * The column pairs each step this reading took for an occurrence at {@code depth} joins on: a
     * foreign key's own columns paired with those it references, oriented the way the step rode it,
     * a key from a table to itself departing the declaring row where the field reads one row; a
     * primary key's columns each paired with itself; and a derived table's columns matched by name
     * to the landing's primary key.
     */
    private static void columns(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        keyColumns(dsl, graph, depth, touchedAt, GRAPHITRON_JOIN_PATH_STEP_KEY);
        keyColumns(dsl, graph, depth, touchedAt, GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY);

        var s = GRAPHITRON_JOIN_PATH_STEP.as("s");
        var pk = GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY.as("pk");
        var spk = SQL_PRIMARY_KEY.as("spk");
        var kc = SQL_CONSTRAINT_COLUMN.as("kc");
        writeColumns(dsl, dsl.select(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION,
                    kc.POSITION, s.FROM_SOURCE_NAME, s.FROM_SCHEMA, s.FROM_TABLE, kc.COLUMN_NAME,
                    s.TO_SOURCE_NAME, s.TO_SCHEMA, s.TO_TABLE, kc.COLUMN_NAME)
                .from(s)
                .join(pk).on(pk.GRAPH_NAME.eq(s.GRAPH_NAME), pk.COORDINATE.eq(s.COORDINATE),
                    pk.TARGET_TYPE.eq(s.TARGET_TYPE), pk.POSITION.eq(s.POSITION),
                    pk.TOUCHED_AT.eq(touchedAt))
                .join(spk).on(spk.SOURCE_NAME.eq(pk.TABLE_SOURCE_NAME), spk.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA),
                    spk.TABLE_NAME.eq(pk.TABLE_NAME))
                .join(kc).on(kc.SOURCE_NAME.eq(spk.SOURCE_NAME), kc.TABLE_SCHEMA.eq(spk.TABLE_SCHEMA),
                    kc.TABLE_NAME.eq(spk.TABLE_NAME), kc.CONSTRAINT_NAME.eq(spk.CONSTRAINT_NAME))
                .where(s.GRAPH_NAME.eq(graph), s.TOUCHED_AT.eq(touchedAt))
                .and(atDepth(s.GRAPH_NAME, s.COORDINATE, depth)),
            touchedAt);

        var mk = GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY.as("mk");
        var nm = SQL_NAME_MATCHED_KEY_COLUMN.as("nm");
        writeColumns(dsl, dsl.select(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION,
                    nm.POSITION, s.FROM_SOURCE_NAME, s.FROM_SCHEMA, s.FROM_TABLE, nm.COLUMN_NAME,
                    s.TO_SOURCE_NAME, s.TO_SCHEMA, s.TO_TABLE, nm.TO_COLUMN)
                .from(s)
                .join(mk).on(mk.GRAPH_NAME.eq(s.GRAPH_NAME), mk.COORDINATE.eq(s.COORDINATE),
                    mk.TARGET_TYPE.eq(s.TARGET_TYPE), mk.POSITION.eq(s.POSITION),
                    mk.TOUCHED_AT.eq(touchedAt))
                .join(nm).on(nm.SOURCE_NAME.eq(s.FROM_SOURCE_NAME), nm.TABLE_SCHEMA.eq(s.FROM_SCHEMA),
                    nm.TABLE_NAME.eq(s.FROM_TABLE), nm.TO_SOURCE_NAME.eq(mk.TABLE_SOURCE_NAME),
                    nm.TO_SCHEMA.eq(mk.TABLE_SCHEMA), nm.TO_TABLE.eq(mk.TABLE_NAME))
                .where(s.GRAPH_NAME.eq(graph), s.TOUCHED_AT.eq(touchedAt))
                .and(atDepth(s.GRAPH_NAME, s.COORDINATE, depth)),
            touchedAt);
    }

    /**
     * The column pairs of the steps riding a foreign key, written or inferred, held in
     * {@code keyTable}, for the occurrences at {@code depth}.
     */
    @SuppressWarnings("unchecked")
    private static void keyColumns(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt,
                                   Table<?> keyTable) {
        var s = GRAPHITRON_JOIN_PATH_STEP.as("s");
        var k = keyTable.as("k");
        var fc = SQL_CONSTRAINT_COLUMN.as("fc");
        var rcc = SQL_REFERENTIAL_CONSTRAINT_COLUMN.as("rcc");
        var pc = SQL_CONSTRAINT_COLUMN.as("pc");
        var o = GRAPHITRON_OCCURRENCE.as("o");
        var f = GRAPHITRON_FIELD.as("f");
        var nv = GRAPHITRON_FIELD_TARGET.as("nv");
        var nt = GRAPHITRON_TYPE.as("nt");
        var a = GRAPHITRON_ARGUMENT.as("a");
        var at = GRAPHITRON_TYPE.as("at");
        Field<String> kSource = (Field<String>) k.field("CONSTRAINT_SOURCE_NAME");
        Field<String> kSchema = (Field<String>) k.field("CONSTRAINT_SCHEMA");
        Field<String> kTable = (Field<String>) k.field("CONSTRAINT_TABLE");
        Field<String> kName = (Field<String>) k.field("CONSTRAINT_NAME");
        Condition selfReferencing = s.FROM_SOURCE_NAME.eq(s.TO_SOURCE_NAME)
            .and(s.FROM_SCHEMA.eq(s.TO_SCHEMA)).and(s.FROM_TABLE.eq(s.TO_TABLE));
        Condition departsDeclaring = s.FROM_SOURCE_NAME.eq(kSource).and(s.FROM_SCHEMA.eq(kSchema))
            .and(s.FROM_TABLE.eq(kTable));
        // A key from a table to itself departs the declaring row where the field reads one row, or
        // the argument holds one value.
        Condition singleValued = f.COORDINATE.isNotNull()
                .and(condition(f.IS_LIST).or(nv.WRAPPER_TYPE_NAME.isNotNull()).not()
                    .or(nt.KIND.in("SCALAR", "ENUM")))
            .or(a.COORDINATE.isNotNull()
                .and(condition(a.IS_LIST).not().or(at.KIND.in("SCALAR", "ENUM"))));
        Condition onFrom = selfReferencing.not().and(departsDeclaring)
            .or(selfReferencing.and(singleValued));
        writeColumns(dsl, dsl.select(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION,
                    fc.POSITION, s.FROM_SOURCE_NAME, s.FROM_SCHEMA, s.FROM_TABLE,
                    when(onFrom, fc.COLUMN_NAME).otherwise(pc.COLUMN_NAME),
                    s.TO_SOURCE_NAME, s.TO_SCHEMA, s.TO_TABLE,
                    when(onFrom, pc.COLUMN_NAME).otherwise(fc.COLUMN_NAME))
                .from(s)
                .join(k).on(((Field<String>) k.field("GRAPH_NAME")).eq(s.GRAPH_NAME),
                    ((Field<String>) k.field("COORDINATE")).eq(s.COORDINATE),
                    ((Field<String>) k.field("TARGET_TYPE")).eq(s.TARGET_TYPE),
                    ((Field<Integer>) k.field("POSITION")).eq(s.POSITION),
                    ((Field<LocalDateTime>) k.field("TOUCHED_AT")).eq(touchedAt))
                .join(fc).on(fc.SOURCE_NAME.eq(kSource), fc.TABLE_SCHEMA.eq(kSchema),
                    fc.TABLE_NAME.eq(kTable), fc.CONSTRAINT_NAME.eq(kName))
                .join(rcc).on(rcc.SOURCE_NAME.eq(fc.SOURCE_NAME), rcc.TABLE_SCHEMA.eq(fc.TABLE_SCHEMA),
                    rcc.TABLE_NAME.eq(fc.TABLE_NAME), rcc.CONSTRAINT_NAME.eq(fc.CONSTRAINT_NAME),
                    rcc.POSITION.eq(fc.POSITION))
                .join(pc).on(pc.SOURCE_NAME.eq(rcc.REFERENCED_SOURCE_NAME),
                    pc.TABLE_SCHEMA.eq(rcc.REFERENCED_SCHEMA), pc.TABLE_NAME.eq(rcc.REFERENCED_TABLE),
                    pc.CONSTRAINT_NAME.eq(rcc.REFERENCED_CONSTRAINT_NAME),
                    pc.POSITION.eq(rcc.REFERENCED_POSITION))
                .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE),
                    o.DEPTH.eq(depth))
                .leftJoin(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
                .leftJoin(nv).on(nv.GRAPH_NAME.eq(f.GRAPH_NAME), nv.TYPE_NAME.eq(f.TYPE_NAME),
                    nv.FIELD_NAME.eq(f.FIELD_NAME))
                .leftJoin(nt).on(nt.GRAPH_NAME.eq(f.GRAPH_NAME), nt.TYPE_NAME.eq(f.NAMED_TYPE))
                .leftJoin(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME), a.COORDINATE.eq(o.ELEMENT_COORDINATE))
                .leftJoin(at).on(at.GRAPH_NAME.eq(a.GRAPH_NAME), at.TYPE_NAME.eq(a.NAMED_TYPE))
                .where(s.GRAPH_NAME.eq(graph), s.TOUCHED_AT.eq(touchedAt)),
            touchedAt);
    }

    /**
     * Upserts column pairs from {@code pairs}, whose columns are the step's key, the ordinal, and the
     * from and to table and column, each column referenced where it stands.
     */
    private static void writeColumns(DSLContext dsl, org.jooq.Select<?> pairs, LocalDateTime touchedAt) {
        var c = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var p = pairs.asTable("p", "GRAPH_NAME", "COORDINATE", "TARGET_TYPE", "POSITION", "ORDINAL",
            "FROM_SOURCE_NAME", "FROM_SCHEMA", "FROM_TABLE", "FROM_COLUMN", "TO_SOURCE_NAME",
            "TO_SCHEMA", "TO_TABLE", "TO_COLUMN");
        var fcol = SQL_COLUMN.as("fcol");
        var tcol = SQL_COLUMN.as("tcol");
        Field<?>[] f = p.fields();
        @SuppressWarnings("unchecked") Field<String> fromSource = (Field<String>) f[5];
        @SuppressWarnings("unchecked") Field<String> fromSchema = (Field<String>) f[6];
        @SuppressWarnings("unchecked") Field<String> fromTable = (Field<String>) f[7];
        @SuppressWarnings("unchecked") Field<String> fromColumn = (Field<String>) f[8];
        @SuppressWarnings("unchecked") Field<String> toSource = (Field<String>) f[9];
        @SuppressWarnings("unchecked") Field<String> toSchema = (Field<String>) f[10];
        @SuppressWarnings("unchecked") Field<String> toTable = (Field<String>) f[11];
        @SuppressWarnings("unchecked") Field<String> toColumn = (Field<String>) f[12];
        dsl.insertInto(c)
            .columns(List.<Field<?>>of(c.GRAPH_NAME, c.COORDINATE, c.TARGET_TYPE, c.POSITION, c.ORDINAL,
                c.FROM_SOURCE_NAME, c.FROM_SCHEMA, c.FROM_TABLE, c.FROM_COLUMN, c.TO_SOURCE_NAME,
                c.TO_SCHEMA, c.TO_TABLE, c.TO_COLUMN, c.TOUCHED_AT, c.FROM_SOURCE_REF, c.FROM_SCHEMA_REF,
                c.FROM_TABLE_REF, c.FROM_COLUMN_REF, c.TO_SOURCE_REF, c.TO_SCHEMA_REF, c.TO_TABLE_REF,
                c.TO_COLUMN_REF))
            .select(dsl.select(f[0], f[1], f[2], f[3], f[4], fromSource, fromSchema, fromTable,
                    fromColumn, toSource, toSchema, toTable, toColumn, val(touchedAt, c.TOUCHED_AT),
                    fcol.SOURCE_NAME, fcol.TABLE_SCHEMA, fcol.TABLE_NAME, fcol.COLUMN_NAME,
                    tcol.SOURCE_NAME, tcol.TABLE_SCHEMA, tcol.TABLE_NAME, tcol.COLUMN_NAME)
                .from(p)
                .leftJoin(fcol).on(fcol.SOURCE_NAME.eq(fromSource), fcol.TABLE_SCHEMA.eq(fromSchema),
                    fcol.TABLE_NAME.eq(fromTable), fcol.COLUMN_NAME.eq(fromColumn))
                .leftJoin(tcol).on(tcol.SOURCE_NAME.eq(toSource), tcol.TABLE_SCHEMA.eq(toSchema),
                    tcol.TABLE_NAME.eq(toTable), tcol.COLUMN_NAME.eq(toColumn)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.TARGET_TYPE, c.POSITION, c.ORDINAL)
            .doUpdate()
            .set(c.FROM_SOURCE_NAME, excluded(c.FROM_SOURCE_NAME))
            .set(c.FROM_SCHEMA, excluded(c.FROM_SCHEMA))
            .set(c.FROM_TABLE, excluded(c.FROM_TABLE))
            .set(c.FROM_COLUMN, excluded(c.FROM_COLUMN))
            .set(c.TO_SOURCE_NAME, excluded(c.TO_SOURCE_NAME))
            .set(c.TO_SCHEMA, excluded(c.TO_SCHEMA))
            .set(c.TO_TABLE, excluded(c.TO_TABLE))
            .set(c.TO_COLUMN, excluded(c.TO_COLUMN))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .set(c.FROM_SOURCE_REF, excluded(c.FROM_SOURCE_REF))
            .set(c.FROM_SCHEMA_REF, excluded(c.FROM_SCHEMA_REF))
            .set(c.FROM_TABLE_REF, excluded(c.FROM_TABLE_REF))
            .set(c.FROM_COLUMN_REF, excluded(c.FROM_COLUMN_REF))
            .set(c.TO_SOURCE_REF, excluded(c.TO_SOURCE_REF))
            .set(c.TO_SCHEMA_REF, excluded(c.TO_SCHEMA_REF))
            .set(c.TO_TABLE_REF, excluded(c.TO_TABLE_REF))
            .set(c.TO_COLUMN_REF, excluded(c.TO_COLUMN_REF))
            .execute();
    }

    /** One declaration a condition step could call, with how exactly it takes each end. */
    private record Candidate(String source, String className, String method, String descriptor,
                             boolean fromExact, boolean toExact, String fromType, String toType) {
        Object[] key() {
            return new Object[] {source, className, method, descriptor};
        }
    }

    /** A condition element and the pair one of its steps stepped between. */
    private record Pair(Pos element, Tbl from, Tbl to) {}

    /**
     * The method each condition step joins or filters by: of the declarations its element names, the
     * one javac picks for the call the generator emits with the step's two tables. A declaration is
     * a candidate where it takes both, its own generated class or any table at each end; the pick is
     * the candidate exact at every end any candidate is exact at. A step none takes is
     * {@code CONDITION_NO_OVERLOAD_FOR_PAIR} at its element, naming each pair and every declaration
     * the element names; candidates exact at the two ends apart with none exact at both are
     * {@code CONDITION_OVERLOAD_TIE}, naming the competing declarations. Answers the declarations
     * under each defect.
     */
    private static Map<DefectKey, Set<List<Object>>> methods(DSLContext dsl, String graph,
            LocalDateTime touchedAt, List<WrittenStep> written, Map<DefectKey, String> defects) {
        var pm = GRAPHITRON_PATH_ELEMENT_METHOD;
        var named = new LinkedHashMap<Pos, List<Object[]>>();
        dsl.select(pm.SOURCE_NAME, pm.SOURCE_LINE, pm.SOURCE_COLUMN, pm.METHOD_SOURCE_NAME,
                pm.METHOD_CLASS_NAME, pm.METHOD_NAME, pm.METHOD_DESCRIPTOR)
            .from(pm).where(pm.GRAPH_NAME.eq(graph))
            .forEach(r -> named.computeIfAbsent(new Pos(r.value1(), r.value2(), r.value3()),
                x -> new ArrayList<>()).add(new Object[] {r.value4(), r.value5(), r.value6(), r.value7()}));
        var byPair = new LinkedHashMap<Pair, List<WrittenStep>>();
        for (var step : written) {
            if (named.containsKey(step.element())) {
                byPair.computeIfAbsent(new Pair(step.element(), step.from(), step.to()),
                    x -> new ArrayList<>()).add(step);
            }
        }
        var rows = new ArrayList<Object[]>();
        var noOverload = new LinkedHashMap<Pos, TreeSet<String>>();
        var tied = new LinkedHashMap<Pos, TreeSet<String>>();
        var methods = new LinkedHashMap<DefectKey, Set<List<Object>>>();
        for (var entry : byPair.entrySet()) {
            var pair = entry.getKey();
            var candidates = candidates(dsl, graph, pair);
            String pairText = pair.from().name() + " to " + pair.to().name();
            if (candidates.isEmpty()) {
                noOverload.computeIfAbsent(pair.element(), x -> new TreeSet<>()).add(pairText);
                var under = methods.computeIfAbsent(new DefectKey(pair.element(), NO_OVERLOAD_FOR_PAIR),
                    x -> new LinkedHashSet<>());
                for (var m : named.get(pair.element())) {
                    under.add(List.of(m));
                }
                continue;
            }
            boolean anyFrom = candidates.stream().anyMatch(Candidate::fromExact);
            boolean anyTo = candidates.stream().anyMatch(Candidate::toExact);
            boolean anyBoth = candidates.stream().anyMatch(c -> c.fromExact() && c.toExact());
            if (anyFrom && anyTo && !anyBoth) {
                var competing = candidates.stream().filter(c -> c.fromExact() || c.toExact())
                    .sorted(Comparator.comparing(Candidate::descriptor)).toList();
                tied.computeIfAbsent(pair.element(), x -> new TreeSet<>()).add(pairText + ": "
                    + competing.stream().map(Steps::declaration).collect(Collectors.joining(", ")));
                var under = methods.computeIfAbsent(new DefectKey(pair.element(), OVERLOAD_TIE),
                    x -> new LinkedHashSet<>());
                for (var c : competing) {
                    under.add(List.of(c.key()));
                }
                continue;
            }
            for (var c : candidates) {
                if (c.fromExact() == anyFrom && c.toExact() == anyTo) {
                    for (var step : entry.getValue()) {
                        rows.add(new Object[] {graph, step.need().coordinate(), step.need().targetType(),
                            step.position(), c.source(), c.className(), c.method(), c.descriptor(),
                            touchedAt, c.source(), c.className(), c.method(), c.descriptor()});
                    }
                }
            }
        }
        noOverload.forEach((pos, pairs) -> defects.put(new DefectKey(pos, NO_OVERLOAD_FOR_PAIR),
            String.join("; ", withMarked(dsl, graph, touchedAt, pos, NO_OVERLOAD_FOR_PAIR, pairs))));
        tied.forEach((pos, details) -> defects.put(new DefectKey(pos, OVERLOAD_TIE),
            String.join("; ", withMarked(dsl, graph, touchedAt, pos, OVERLOAD_TIE, details))));
        var m = GRAPHITRON_JOIN_PATH_STEP_METHOD;
        upsert(dsl, m, List.of(m.GRAPH_NAME, m.COORDINATE, m.TARGET_TYPE, m.POSITION,
                m.METHOD_SOURCE_NAME, m.METHOD_CLASS_NAME, m.METHOD_NAME, m.METHOD_DESCRIPTOR),
            List.of(m.TOUCHED_AT, m.METHOD_SOURCE_REF, m.METHOD_CLASS_REF, m.METHOD_NAME_REF,
                m.METHOD_DESCRIPTOR_REF),
            rows);
        return methods;
    }

    /**
     * {@code parts} with those of the detail this reading already marked for {@code code} at the
     * element, which an occurrence at another depth stepping it between other tables gave.
     */
    private static TreeSet<String> withMarked(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                              Pos pos, String code, TreeSet<String> parts) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        String marked = dsl.select(d.DETAIL).from(d)
            .where(d.GRAPH_NAME.eq(graph), d.SOURCE_NAME.eq(pos.source()), d.SOURCE_LINE.eq(pos.line()),
                d.SOURCE_COLUMN.eq(pos.column()), d.CODE.eq(code), d.TOUCHED_AT.eq(touchedAt))
            .fetchOne(d.DETAIL);
        if (marked != null) {
            parts.addAll(List.of(marked.split("; ")));
        }
        return parts;
    }

    /** The two-parameter declarations of a condition element's methods taking a pair's two tables. */
    private static List<Candidate> candidates(DSLContext dsl, String graph, Pair pair) {
        var pm = GRAPHITRON_PATH_ELEMENT_METHOD;
        var ft = SQL_TABLE.as("ft");
        var tt = SQL_TABLE.as("tt");
        var dp = CODE_METHOD_PARAMETER.as("dp");
        var ap = CODE_METHOD_PARAMETER.as("ap");
        var xp = CODE_METHOD_PARAMETER.as("xp");
        return dsl.select(pm.METHOD_SOURCE_NAME, pm.METHOD_CLASS_NAME, pm.METHOD_NAME,
                pm.METHOD_DESCRIPTOR, dp.ERASED_CLASS, ft.CLASS_FQN, ap.ERASED_CLASS, tt.CLASS_FQN,
                dp.PARAMETER_TYPE, ap.PARAMETER_TYPE)
            .from(pm)
            .join(ft).on(ft.SOURCE_NAME.eq(pair.from().source()), ft.TABLE_SCHEMA.eq(pair.from().schema()),
                ft.TABLE_NAME.eq(pair.from().name()))
            .join(tt).on(tt.SOURCE_NAME.eq(pair.to().source()), tt.TABLE_SCHEMA.eq(pair.to().schema()),
                tt.TABLE_NAME.eq(pair.to().name()))
            .join(dp).on(dp.SOURCE_NAME.eq(pm.METHOD_SOURCE_NAME), dp.CLASS_NAME.eq(pm.METHOD_CLASS_NAME),
                dp.METHOD_NAME.eq(pm.METHOD_NAME), dp.DESCRIPTOR.eq(pm.METHOD_DESCRIPTOR),
                dp.POSITION.eq(0))
            .join(ap).on(ap.SOURCE_NAME.eq(pm.METHOD_SOURCE_NAME), ap.CLASS_NAME.eq(pm.METHOD_CLASS_NAME),
                ap.METHOD_NAME.eq(pm.METHOD_NAME), ap.DESCRIPTOR.eq(pm.METHOD_DESCRIPTOR),
                ap.POSITION.eq(1))
            .where(pm.GRAPH_NAME.eq(graph), pm.SOURCE_NAME.eq(pair.element().source()),
                pm.SOURCE_LINE.eq(pair.element().line()), pm.SOURCE_COLUMN.eq(pair.element().column()))
            .and(dp.ERASED_CLASS.eq(ft.CLASS_FQN).or(dp.ROLE.eq("TABLE_ANY")))
            .and(ap.ERASED_CLASS.eq(tt.CLASS_FQN).or(ap.ROLE.eq("TABLE_ANY")))
            .and(notExists(selectOne().from(xp).where(xp.SOURCE_NAME.eq(pm.METHOD_SOURCE_NAME),
                xp.CLASS_NAME.eq(pm.METHOD_CLASS_NAME), xp.METHOD_NAME.eq(pm.METHOD_NAME),
                xp.DESCRIPTOR.eq(pm.METHOD_DESCRIPTOR), xp.POSITION.eq(2))))
            .fetch(r -> new Candidate(r.value1(), r.value2(), r.value3(), r.value4(),
                Objects.equals(r.value5(), r.value6()), Objects.equals(r.value7(), r.value8()),
                r.value9(), r.value10()));
    }

    /** A declaration quoted by its simple parameter types, the packages being noise in a message. */
    private static String declaration(Candidate c) {
        String packages = "([A-Za-z_$][A-Za-z0-9_$]*\\.)+";
        return c.method() + "(" + c.fromType().replaceAll(packages, "") + ", "
            + c.toType().replaceAll(packages, "") + ")";
    }

    /** One parameter of a routine step, with what its name reads. */
    private record Parameter(int position, String name, String boundTo, String argument,
                             Tbl column, String columnName) {}

    /**
     * Where each routine step's parameters take their values: a column of the table the step departs
     * where its argMapping entry is {@code $source.<column>}, else the field's argument its entry, or
     * its own name where no entry names it, heads. A parameter binding neither is
     * {@code ROUTINE_PARAMETER_UNBOUND} at the {@code @routine}; one binding both binds nothing.
     */
    private static void parameters(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<RoutineStep> routines, Map<DefectKey, String> defects) {
        var rows = new ArrayList<Object[]>();
        var bound = new LinkedHashMap<List<Object>, List<Parameter>>();
        for (var call : routines) {
            var step = call.step();
            var key = List.<Object>of(step.element(), call.routine(), step.from(), step.claimCoordinate());
            var parameters = bound.computeIfAbsent(key,
                x -> binding(dsl, graph, step.element(), call.routine(), step.from(), step.claimCoordinate()));
            for (var p : parameters) {
                boolean column = p.columnName() != null;
                boolean argument = p.argument() != null;
                if (!column && !argument) {
                    defects.putIfAbsent(new DefectKey(step.element(), ROUTINE_PARAMETER_UNBOUND), null);
                    continue;
                }
                if (column == argument) {
                    continue;
                }
                var t = column ? p.column() : null;
                rows.add(new Object[] {graph, step.need().coordinate(), step.need().targetType(),
                    step.position(), p.position(), p.name(),
                    t == null ? null : t.source(), t == null ? null : t.schema(),
                    t == null ? null : t.name(), column ? p.columnName() : null,
                    column ? null : p.boundTo(), touchedAt,
                    t == null ? null : t.source(), t == null ? null : t.schema(),
                    t == null ? null : t.name(), column ? p.columnName() : null});
            }
        }
        var pp = GRAPHITRON_JOIN_PATH_STEP_PARAMETER;
        upsert(dsl, pp, List.of(pp.GRAPH_NAME, pp.COORDINATE, pp.TARGET_TYPE, pp.POSITION,
                pp.PARAMETER_POSITION),
            List.of(pp.PARAMETER_NAME, pp.FROM_SOURCE_NAME, pp.FROM_SCHEMA, pp.FROM_TABLE,
                pp.FROM_COLUMN, pp.ARGUMENT, pp.TOUCHED_AT, pp.FROM_SOURCE_REF, pp.FROM_SCHEMA_REF,
                pp.FROM_TABLE_REF, pp.FROM_COLUMN_REF),
            rows);
    }

    /**
     * What each parameter of {@code routine} reads at the {@code @routine} written at {@code element},
     * called from {@code from} on the field {@code claimCoordinate} names: the name its argMapping
     * entry binds it to, the argument that name heads and the column of {@code from} it spells.
     */
    private static List<Parameter> binding(DSLContext dsl, String graph, Pos element, Tbl routine,
                                           Tbl from, String claimCoordinate) {
        var sp = SQL_ROUTINE_PARAMETER.as("sp");
        var da = GRAPHQL_DIRECTIVE_APPLICATION.as("da");
        var ams = GRAPHITRON_ARGMAPPING_SITE.as("ams");
        var am = GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY.as("am");
        var f = GRAPHITRON_FIELD.as("f");
        var a = GRAPHQL_ARGUMENT.as("a");
        var c = SQL_COLUMN.as("c");
        return dsl.select(sp.POSITION, sp.JOOQ_NAME, coalesce(am.BOUND_TO, sp.JOOQ_NAME),
                a.ARGUMENT_NAME, c.COLUMN_NAME)
            .from(sp)
            .leftJoin(da).on(da.GRAPH_NAME.eq(graph), da.SOURCE_NAME.eq(element.source()),
                da.SOURCE_LINE.eq(element.line()), da.SOURCE_COLUMN.eq(element.column()),
                da.DIRECTIVE_NAME.eq("routine"))
            .leftJoin(ams).on(ams.GRAPH_NAME.eq(da.GRAPH_NAME), ams.COORDINATE.eq(da.COORDINATE),
                ams.DIRECTIVE_NAME.eq("routine"), ams.ORDINAL.eq(da.ORDINAL))
            .leftJoin(am).on(am.GRAPH_NAME.eq(ams.GRAPH_NAME), am.SOURCE_NAME.eq(ams.SOURCE_NAME),
                am.SOURCE_LINE.eq(ams.SOURCE_LINE), am.SOURCE_COLUMN.eq(ams.SOURCE_COLUMN),
                am.PARAM_NAME.eq(sp.JOOQ_NAME))
            .leftJoin(f).on(f.GRAPH_NAME.eq(graph), f.COORDINATE.eq(claimCoordinate))
            .leftJoin(a).on(a.GRAPH_NAME.eq(f.GRAPH_NAME), a.TYPE_NAME.eq(f.TYPE_NAME),
                a.FIELD_NAME.eq(f.FIELD_NAME), a.ARGUMENT_NAME.eq(coalesce(am.ROOT_NAME, sp.JOOQ_NAME)))
            .leftJoin(c).on(c.SOURCE_NAME.eq(from.source()), c.TABLE_SCHEMA.eq(from.schema()),
                c.TABLE_NAME.eq(from.name()), am.HEAD_PATH.eq("$source"),
                c.COLUMN_NAME_UPPER.eq(am.TAIL_NAME_UPPER))
            .where(sp.SOURCE_NAME.eq(routine.source()), sp.TABLE_SCHEMA.eq(routine.schema()),
                sp.ROUTINE_NAME.eq(routine.name()))
            .fetch(r -> new Parameter(r.value1(), r.value2(), r.value3(), r.value4(), from, r.value5()));
    }

    /**
     * The path of each need whose walk took every written step without stopping and ended on its
     * target, any table where the target has none, with the claim it followed; a walk ending off its
     * target is {@code NO_ROUTE_TO_TARGET} at the element of its last step.
     */
    private static void paths(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              java.util.Collection<Walk> walks, Map<DefectKey, String> defects) {
        var paths = new ArrayList<Object[]>();
        var joins = new ArrayList<Object[]>();
        var claims = new ArrayList<Object[]>();
        for (var walk : walks) {
            if (walk.stopped) {
                continue;
            }
            var end = walk.at;
            if (walk.landing != null && !walk.landing.equals(end)) {
                if (walk.lastElement != null) {
                    defects.putIfAbsent(new DefectKey(walk.lastElement, NO_ROUTE_TO_TARGET), null);
                }
                continue;
            }
            var need = walk.need;
            paths.add(new Object[] {graph, need.coordinate(), need.targetType(), JOIN, walk.origin,
                touchedAt});
            joins.add(new Object[] {graph, need.coordinate(), need.targetType(), JOIN, end.source(),
                end.schema(), end.name(), touchedAt, end.source(), end.schema(), end.name()});
            claims.add(new Object[] {graph, need.coordinate(), need.targetType(), walk.claimCoordinate,
                walk.claimTarget, touchedAt, graph, walk.claimCoordinate, walk.claimTarget});
        }
        var p = GRAPHITRON_PATH;
        upsert(dsl, p, List.of(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE),
            List.of(p.KIND, p.ORIGIN, p.TOUCHED_AT), paths);
        var j = GRAPHITRON_JOIN_PATH;
        upsert(dsl, j, List.of(j.GRAPH_NAME, j.COORDINATE, j.TARGET_TYPE),
            List.of(j.KIND, j.TARGET_SOURCE_NAME, j.TARGET_SCHEMA, j.TARGET_TABLE, j.TOUCHED_AT,
                j.TARGET_SOURCE_REF, j.TARGET_SCHEMA_REF, j.TARGET_TABLE_REF),
            joins);
        var c = GRAPHITRON_JOIN_PATH_CLAIMED;
        upsert(dsl, c, List.of(c.GRAPH_NAME, c.COORDINATE, c.TARGET_TYPE),
            List.of(c.CLAIM_COORDINATE, c.CLAIM_TARGET_TYPE, c.TOUCHED_AT, c.CLAIM_GRAPH_REF,
                c.CLAIM_COORDINATE_REF, c.CLAIM_TARGET_TYPE_REF),
            claims);
    }

    /** Why a condition alone taking any table as its second parameter moves nowhere. */
    static final String WILDCARD_ARRIVAL = "the condition's second parameter takes any table, so the"
        + " path does not say where it arrives; type the parameter with the table it joins to";

    /**
     * Gives {@code NO_ROUTE_FROM_DEPARTURE} its reason where the element is a condition alone whose
     * method's second parameter takes any table, {@code Table<?>} or {@code TableLike<?>}: a
     * condition alone arrives where its second parameter says, and such a parameter says nowhere.
     * That shape is not supported, so it stays the defect, with the remedy in its detail.
     */
    private static void wildcardArrivals(DSLContext dsl, String graph, Map<DefectKey, String> defects) {
        var pm = GRAPHITRON_PATH_ELEMENT_METHOD;
        var ap = CODE_METHOD_PARAMETER;
        var k = GRAPHITRON_PATH_ELEMENT_KEY;
        var pk = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
        var t = GRAPHITRON_PATH_ELEMENT_TABLE;
        for (var key : List.copyOf(defects.keySet())) {
            if (!key.code().equals(NO_ROUTE_FROM_DEPARTURE)) {
                continue;
            }
            var pos = key.pos();
            boolean wildcard = dsl.fetchExists(dsl.selectOne().from(pm)
                .join(ap).on(ap.SOURCE_NAME.eq(pm.METHOD_SOURCE_NAME), ap.CLASS_NAME.eq(pm.METHOD_CLASS_NAME),
                    ap.METHOD_NAME.eq(pm.METHOD_NAME), ap.DESCRIPTOR.eq(pm.METHOD_DESCRIPTOR),
                    ap.POSITION.eq(1), ap.ROLE.eq("TABLE_ANY"))
                .where(pm.GRAPH_NAME.eq(graph), pm.SOURCE_NAME.eq(pos.source()),
                    pm.SOURCE_LINE.eq(pos.line()), pm.SOURCE_COLUMN.eq(pos.column()))
                .andNotExists(selectOne().from(k).where(k.GRAPH_NAME.eq(graph),
                    k.SOURCE_NAME.eq(pos.source()), k.SOURCE_LINE.eq(pos.line()),
                    k.SOURCE_COLUMN.eq(pos.column())))
                .andNotExists(selectOne().from(pk).where(pk.GRAPH_NAME.eq(graph),
                    pk.SOURCE_NAME.eq(pos.source()), pk.SOURCE_LINE.eq(pos.line()),
                    pk.SOURCE_COLUMN.eq(pos.column())))
                .andNotExists(selectOne().from(t).where(t.GRAPH_NAME.eq(graph),
                    t.SOURCE_NAME.eq(pos.source()), t.SOURCE_LINE.eq(pos.line()),
                    t.SOURCE_COLUMN.eq(pos.column()))));
            if (wildcard) {
                defects.put(key, WILDCARD_ARRIVAL);
            }
        }
    }

    /** The walk's defects and the declarations under each, marked with the reading's instant. */
    private static void writeDefects(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                     Map<DefectKey, String> defects,
                                     Map<DefectKey, Set<List<Object>>> methods) {
        var rows = new ArrayList<Object[]>();
        defects.forEach((k, detail) -> rows.add(new Object[] {graph, k.pos().source(), k.pos().line(),
            k.pos().column(), k.code(), detail, touchedAt}));
        var d = GRAPHITRON_ENTRY_DEFECT;
        upsert(dsl, d, List.of(d.GRAPH_NAME, d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE),
            List.of(d.DETAIL, d.TOUCHED_AT), rows);
        var methodRows = new ArrayList<Object[]>();
        methods.forEach((k, under) -> {
            for (var m : under) {
                methodRows.add(new Object[] {graph, k.pos().source(), k.pos().line(), k.pos().column(),
                    k.code(), m.get(0), m.get(1), m.get(2), m.get(3), touchedAt});
            }
        });
        var dm = GRAPHITRON_ENTRY_DEFECT_METHOD;
        upsert(dsl, dm, List.of(dm.GRAPH_NAME, dm.SOURCE_NAME, dm.SOURCE_LINE, dm.SOURCE_COLUMN,
                dm.CODE, dm.METHOD_SOURCE_NAME, dm.METHOD_CLASS_NAME, dm.METHOD_NAME,
                dm.METHOD_DESCRIPTOR),
            List.of(dm.TOUCHED_AT), methodRows);
    }

    /**
     * What no depth of this reading marked: the step family children first, then the written and
     * inherited paths, whose join paths and claimed rows go with them; the inferred and empty paths
     * are {@link Paths}'s to sweep.
     */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Table<?> t : List.of(GRAPHITRON_JOIN_PATH_STEP_PARAMETER, GRAPHITRON_JOIN_PATH_STEP_COLUMN,
                GRAPHITRON_JOIN_PATH_STEP_KEY, GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY,
                GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY, GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY,
                GRAPHITRON_JOIN_PATH_STEP_METHOD, GRAPHITRON_JOIN_PATH_STEP_ROUTINE,
                GRAPHITRON_JOIN_PATH_STEP, GRAPHITRON_JOIN_PATH_STOP, GRAPHITRON_JOIN_PATH_CLAIMED)) {
            dsl.deleteFrom(t).where(field(t, "GRAPH_NAME", String.class).eq(graph))
                .and(field(t, "TOUCHED_AT", LocalDateTime.class).ne(touchedAt)).execute();
        }
        dsl.deleteFrom(GRAPHITRON_PATH)
            .where(GRAPHITRON_PATH.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_PATH.ORIGIN.in("WRITTEN", "INHERITED"))
            .and(GRAPHITRON_PATH.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * Upserts {@code rows} into {@code table} through one statement bound once per row, each row
     * holding the key columns' values and then the others'.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void upsert(DSLContext dsl, Table<?> table, List<? extends Field<?>> keys,
                               List<? extends Field<?>> values, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var columns = Stream.concat(keys.stream(), values.stream()).map(f -> (Field<?>) f).toList();
        var sets = new LinkedHashMap<Field<?>, Field<?>>();
        for (Field value : values) {
            sets.put(value, excluded(value));
        }
        var query = dsl.insertInto(table).columns(columns)
            .values(Collections.nCopies(columns.size(), null))
            .onConflict(keys)
            .doUpdate()
            .set(sets);
        dsl.batch(query, rows.toArray(new Object[0][])).execute();
    }

    private static <T> Field<T> field(Table<?> t, String column, Class<T> type) {
        return t.field(column, type);
    }

    private static Tbl tbl(Record row, Field<String> source, Field<String> schema, Field<String> name) {
        String n = row.get(name.getName(), String.class);
        return n == null ? null : new Tbl(row.get(source.getName(), String.class),
            row.get(schema.getName(), String.class), n);
    }

    private static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
