package no.sikt.graphitron.model.lint;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIMED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;

/**
 * The fan-out advisory, read off the steps of the join paths at each field's occurrences: a list
 * field whose {@code @reference} path passes <em>through</em> a table that can hold more than one row
 * per set of columns the entering and the leaving step bind gets a warning naming the step that
 * multiplies.
 *
 * <p>A {@code @reference} path is mechanical foreign-key traversal and a SQL join produces a bag,
 * so the duplicates are the declared path's correct result. What was missing is any signal that
 * the path has that property, and a field named for a set reads as one. So this changes no emitted
 * SQL: it says what the SQL does, and the remedy it suggests is a relation with set semantics
 * rather than a flag that changes what the join means.
 *
 * <p>An intermediate is the table one step arrives at and the next leaves, so a path's last step is
 * no intermediate. Three reads at three grains: the pairs of consecutive steps with how each joins,
 * the columns of each intermediate the two steps bind, and the primary-key and unique constraints of
 * the intermediates. The verdict over them is {@link #intermediates}'s, and two of its four values
 * deliberately mint nothing, being the rule's way of saying it declined to judge rather than that it
 * found nothing.
 *
 * <p>A producer rather than a lint visitor, folded into the build's one warning-assembly point
 * beside the codegen advisories, so it inherits the suppression filter, the editor replay and the
 * MCP projection with nothing moved.
 */
public final class ReferencePathFanout {

    private ReferencePathFanout() {}

    /** What the coverage question answers about one intermediate: a closed vocabulary. */
    public enum Verdict {
        /**
         * No PRIMARY KEY or UNIQUE constraint on the intermediate has every one of its columns
         * among the ones the two steps bind, so the table may hold several rows per bound set.
         */
        FANS_OUT,
        /** One does, so the step is one row in and one row out on the set the two steps bind. */
        COVERED,
        /**
         * The entering or the leaving step joins by an authored Java method, whose columns no
         * catalog row names, so one side of the bound set is unreadable.
         */
        UNDECIDABLE_CONDITION_HOP,
        /**
         * One of the two steps joins by matched names, from a table-valued function's result, which
         * declares no constraint to read, or onto a table's own primary key.
         */
        UNDECIDABLE_NAME_MATCH_HOP
    }

    /**
     * One intermediate of a field's path: the field, the place of the step entering it, the table,
     * the verdict, the covering constraint where the verdict is {@link Verdict#COVERED}, and the
     * {@code @reference} or {@code @routine} application that wrote the entering element.
     */
    public record Intermediate(String typeName, String fieldName, boolean list, int position,
                               String schema, String table, Verdict verdict, String coveringConstraint,
                               SourceLocation location) {}

    /**
     * Every finding for {@code store}'s graph, in coordinate order. The population is the list
     * fields: a scalar field over a fanning path lowers to a capped correlated subselect, so it
     * picks an arbitrary row rather than repeating one, which is a different defect with a
     * different remedy and not this rule's to word.
     *
     * <p>{@code excluded} is the consumer's {@code excludedTypes} globs, applied here for the same
     * reason the engine applies them before its walk: this finding lands at a coordinate on a type
     * the consumer asked not to be linted, and firing there would read as a bug. The matching is
     * {@link ExcludedTypes}', shared with the engine rather than restated.
     */
    public static List<BuildWarning.LintFinding> findings(StoreHandle store, ExcludedTypes excluded) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(excluded, "excluded");
        return intermediates(store).stream()
            .filter(Intermediate::list)
            .filter(row -> !excluded.matches(row.typeName()))
            .filter(row -> mints(row.verdict()))
            .map(row -> finding(row.typeName(), row.fieldName(), row.position(), row.schema(),
                row.table(), row.location()))
            .toList();
    }

    /** A step's key: its occurrence, the type its need reaches and its position. */
    private record Step(String coordinate, String targetType, int position) {}

    /** A table's key. */
    private record Table(String sourceName, String schema, String name) {}

    /**
     * Every intermediate of every field's path, in coordinate then position order, one row however
     * many of the field's occurrences or reached types pass through it the same way.
     */
    public static List<Intermediate> intermediates(StoreHandle store) {
        Objects.requireNonNull(store, "store");
        var pairs = pairs(store);
        var bound = boundColumns(store);
        var keys = keyColumns(store);
        var rows = new LinkedHashSet<Intermediate>();
        for (var pair : pairs) {
            var verdict = verdict(pair, bound.getOrDefault(pair.step(), Set.of()),
                keys.getOrDefault(pair.table(), Map.of()));
            rows.add(new Intermediate(pair.typeName(), pair.fieldName(), pair.list(), pair.place(),
                pair.table().schema(), pair.table().name(), verdict.verdict(), verdict.covering(),
                pair.location()));
        }
        return rows.stream()
            .sorted(Comparator.comparing(Intermediate::typeName).thenComparing(Intermediate::fieldName)
                .thenComparingInt(Intermediate::position))
            .toList();
    }

    private record Judged(Verdict verdict, String covering) {}

    /**
     * The coverage question at one intermediate. A step joined by a method or by matched names binds
     * columns no key states, so the question is declined by name; otherwise the lowest-named key
     * whose every column the two steps bind covers it.
     */
    private static Judged verdict(Pair pair, Set<String> bound, Map<String, Set<String>> keys) {
        if (pair.enterVia().equals("CONDITION") || pair.leaveVia().equals("CONDITION")) {
            return new Judged(Verdict.UNDECIDABLE_CONDITION_HOP, null);
        }
        if (pair.enterVia().equals("NAME_MATCH") || pair.leaveVia().equals("NAME_MATCH")) {
            return new Judged(Verdict.UNDECIDABLE_NAME_MATCH_HOP, null);
        }
        return keys.entrySet().stream()
            .filter(key -> bound.containsAll(key.getValue()))
            .map(Map.Entry::getKey)
            .min(Comparator.naturalOrder())
            .map(name -> new Judged(Verdict.COVERED, name))
            .orElse(new Judged(Verdict.FANS_OUT, null));
    }

    /** Two consecutive steps of a field's path and the table between them. */
    private record Pair(Step step, String typeName, String fieldName, boolean list, int place,
                        String enterVia, String leaveVia, Table table, SourceLocation location) {}

    /**
     * Every pair of consecutive steps at a field's occurrences, with how each joins: a step with a
     * routine row calls one, one with a key row rides a key, one with column pairs and no key matches
     * names, and one with none of those joins by its method alone. The entering step is placed by
     * the element it resolved, found through the claim its path follows, and reported at the
     * application that wrote that element.
     */
    private static List<Pair> pairs(StoreHandle store) {
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var e = GRAPHITRON_JOIN_PATH_STEP.as("entering");
        var l = GRAPHITRON_JOIN_PATH_STEP.as("leaving");
        var cl = GRAPHITRON_JOIN_PATH_CLAIMED;
        var c = GRAPHITRON_JOIN_PATH_CLAIM;
        var ap = GRAPHITRON_AST_PATH_ENTRY;
        var re = GRAPHITRON_AST_REFERENCE_ELEMENT_ENTRY;
        var ra = GRAPHITRON_AST_ROUTINE_ENTRY;
        return store.dsl()
            .selectDistinct(e.COORDINATE, e.TARGET_TYPE, e.POSITION, f.TYPE_NAME, f.FIELD_NAME, f.IS_LIST,
                coalesce(e.ELEMENT_PLACE, e.POSITION), via(e), via(l),
                e.TO_SOURCE_NAME, e.TO_SCHEMA, e.TO_TABLE,
                ap.SOURCE_NAME, coalesce(re.APPLICATION_LINE, ra.SOURCE_LINE),
                coalesce(re.APPLICATION_COLUMN, ra.SOURCE_COLUMN))
            .from(e)
            .join(l).on(l.GRAPH_NAME.eq(e.GRAPH_NAME), l.COORDINATE.eq(e.COORDINATE),
                l.TARGET_TYPE.eq(e.TARGET_TYPE), l.POSITION.eq(e.POSITION.plus(1)))
            .join(o).on(o.GRAPH_NAME.eq(e.GRAPH_NAME), o.COORDINATE.eq(e.COORDINATE), o.KIND.eq("FIELD"))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            // The claim the path follows, or, for a path that stopped and so follows none, the
            // occurrence's own claim for the type.
            .leftJoin(cl).on(cl.GRAPH_NAME.eq(e.GRAPH_NAME), cl.COORDINATE.eq(e.COORDINATE),
                cl.TARGET_TYPE.eq(e.TARGET_TYPE))
            .leftJoin(c).on(c.GRAPH_NAME.eq(e.GRAPH_NAME),
                c.ELEMENT_COORDINATE.eq(coalesce(cl.CLAIM_COORDINATE, o.ELEMENT_COORDINATE)),
                c.TARGET_TYPE.eq(coalesce(cl.CLAIM_TARGET_TYPE, e.TARGET_TYPE)))
            .leftJoin(ap).on(ap.GRAPH_NAME.eq(c.GRAPH_NAME), ap.SOURCE_NAME.eq(c.SOURCE_NAME),
                ap.ANCHOR_LINE.eq(c.SOURCE_LINE), ap.ANCHOR_COLUMN.eq(c.SOURCE_COLUMN),
                ap.PLACE.eq(e.ELEMENT_PLACE))
            .leftJoin(re).on(re.GRAPH_NAME.eq(ap.GRAPH_NAME), re.SOURCE_NAME.eq(ap.SOURCE_NAME),
                re.SOURCE_LINE.eq(ap.ELEMENT_LINE), re.SOURCE_COLUMN.eq(ap.ELEMENT_COLUMN))
            // an entering @routine is its own application
            .leftJoin(ra).on(ra.GRAPH_NAME.eq(ap.GRAPH_NAME), ra.SOURCE_NAME.eq(ap.SOURCE_NAME),
                ra.SOURCE_LINE.eq(ap.ELEMENT_LINE), ra.SOURCE_COLUMN.eq(ap.ELEMENT_COLUMN))
            .where(e.GRAPH_NAME.eq(store.graphName()))
            .fetch(row -> new Pair(new Step(row.value1(), row.value2(), row.value3()), row.value4(),
                row.value5(), Boolean.TRUE.equals(row.value6()), row.value7(), row.value8(), row.value9(),
                new Table(row.value10(), row.value11(), row.value12()),
                location(row.value13(), row.value14(), row.value15())));
    }

    /** How one step joins: ROUTINE, KEY, NAME_MATCH or CONDITION. */
    private static org.jooq.Field<String> via(no.sikt.graphitron.model.tables.GraphitronJoinPathStep s) {
        var r = GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
        var k = GRAPHITRON_JOIN_PATH_STEP_KEY;
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        return when(exists(selectOne().from(r).where(r.GRAPH_NAME.eq(s.GRAPH_NAME),
                r.COORDINATE.eq(s.COORDINATE), r.TARGET_TYPE.eq(s.TARGET_TYPE), r.POSITION.eq(s.POSITION))),
                inline("ROUTINE"))
            .when(exists(selectOne().from(k).where(k.GRAPH_NAME.eq(s.GRAPH_NAME),
                k.COORDINATE.eq(s.COORDINATE), k.TARGET_TYPE.eq(s.TARGET_TYPE), k.POSITION.eq(s.POSITION)))
                .or(exists(selectOne().from(ik).where(ik.GRAPH_NAME.eq(s.GRAPH_NAME),
                    ik.COORDINATE.eq(s.COORDINATE), ik.TARGET_TYPE.eq(s.TARGET_TYPE),
                    ik.POSITION.eq(s.POSITION)))),
                inline("KEY"))
            .when(exists(selectOne().from(sc).where(sc.GRAPH_NAME.eq(s.GRAPH_NAME),
                sc.COORDINATE.eq(s.COORDINATE), sc.TARGET_TYPE.eq(s.TARGET_TYPE), sc.POSITION.eq(s.POSITION))),
                inline("NAME_MATCH"))
            .otherwise(inline("CONDITION"));
    }

    /**
     * The columns of each intermediate the two steps bind, keyed by the entering step: where the
     * entering step arrives, where the leaving step departs, and the columns a leaving routine's
     * parameters are bound from.
     */
    private static Map<Step, Set<String>> boundColumns(StoreHandle store) {
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var p = GRAPHITRON_JOIN_PATH_STEP_PARAMETER;
        var graph = store.graphName();
        var bound = new HashMap<Step, Set<String>>();
        store.dsl().select(sc.COORDINATE, sc.TARGET_TYPE, sc.POSITION, sc.TO_COLUMN)
            .from(sc).where(sc.GRAPH_NAME.eq(graph))
            .unionAll(select(sc.COORDINATE, sc.TARGET_TYPE, sc.POSITION.minus(1), sc.FROM_COLUMN)
                .from(sc).where(sc.GRAPH_NAME.eq(graph), sc.POSITION.gt(0)))
            .unionAll(select(p.COORDINATE, p.TARGET_TYPE, p.POSITION.minus(1), p.FROM_COLUMN)
                .from(p).where(p.GRAPH_NAME.eq(graph), p.POSITION.gt(0), p.FROM_COLUMN.isNotNull()))
            .forEach(row -> bound.computeIfAbsent(new Step(row.value1(), row.value2(), row.value3()),
                key -> new HashSet<>()).add(row.value4()));
        return bound;
    }

    /** The PRIMARY KEY and UNIQUE constraints of every table a step reaches, each with its columns. */
    private static Map<Table, Map<String, Set<String>>> keyColumns(StoreHandle store) {
        var s = GRAPHITRON_JOIN_PATH_STEP;
        var k = SQL_CONSTRAINT;
        var kc = SQL_CONSTRAINT_COLUMN;
        var keys = new HashMap<Table, Map<String, Set<String>>>();
        store.dsl().selectDistinct(k.SOURCE_NAME, k.TABLE_SCHEMA, k.TABLE_NAME, k.CONSTRAINT_NAME, kc.COLUMN_NAME)
            .from(k)
            .join(kc).on(kc.SOURCE_NAME.eq(k.SOURCE_NAME), kc.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                kc.TABLE_NAME.eq(k.TABLE_NAME), kc.CONSTRAINT_NAME.eq(k.CONSTRAINT_NAME))
            .where(k.CONSTRAINT_TYPE.in("PRIMARY KEY", "UNIQUE"))
            .and(exists(selectOne().from(s).where(s.GRAPH_NAME.eq(store.graphName()),
                s.TO_SOURCE_NAME.eq(k.SOURCE_NAME), s.TO_SCHEMA.eq(k.TABLE_SCHEMA), s.TO_TABLE.eq(k.TABLE_NAME))))
            .forEach(row -> keys
                .computeIfAbsent(new Table(row.value1(), row.value2(), row.value3()), key -> new TreeMap<>())
                .computeIfAbsent(row.value4(), key -> new HashSet<>())
                .add(row.value5()));
        return keys;
    }

    /**
     * Whether a verdict words a finding: a total switch over the vocabulary with no
     * {@code default}, so a value added to it stops compiling here instead of falling into a
     * silence. Three of the four mint nothing, for two different reasons.
     */
    private static boolean mints(Verdict verdict) {
        return switch (verdict) {
            case FANS_OUT -> true;
            // One row in and one row out: the list is a set already and there is nothing to say.
            case COVERED -> false;
            // The rule declined to judge this intermediate, and a warning resting on a predicate
            // one of whose sides is unreadable would be a guess. The user documentation says in the
            // author's own terms where the rule stops looking, which is where they most need it.
            case UNDECIDABLE_CONDITION_HOP, UNDECIDABLE_NAME_MATCH_HOP -> false;
        };
    }

    /**
     * The message and its fix. The fix carries no edits: there is no mechanical rewrite from a
     * fanning path to a set, so what it offers an editor is the pattern's name, which is the
     * register {@link LintFix} reserves for a suggestion the build never performs.
     */
    private static BuildWarning.LintFinding finding(String typeName, String fieldName, int position,
                                                    String schema, String table,
                                                    SourceLocation location) {
        String qualified = schema + "." + table;
        String message = "Field '" + typeName + "." + fieldName + "' returns duplicate rows: its"
            + " @reference path passes through " + qualified + " at element " + position
            + ", and no primary key or unique constraint there has all its columns among the ones"
            + " the elements either side of it join on, so that table may hold several rows per"
            + " pair they bind. The duplicates are what the declared path means; if the field is"
            + " meant to be a set, traverse a relation with set semantics instead.";
        var fix = new LintFix(
            "Traverse a view over " + qualified + " selecting the bound columns DISTINCT, with a"
            + " synthetic key for the path to join on", List.of());
        return BuildWarning.LintFinding.of(message, location, LintRule.REFERENCE_PATH_FANS_OUT, fix);
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
