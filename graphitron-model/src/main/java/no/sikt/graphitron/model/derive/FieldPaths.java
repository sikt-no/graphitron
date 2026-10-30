package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START_LIFTED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_START_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_PATH_STOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_COLUMN;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;

/**
 * The walk: fills {@code graphitron_field_path} with its column pairs, the key each step rode (a
 * foreign key, or a table's own primary key joining a row to itself), the methods each step joins or
 * filters by, and where each walk stopped short of its written elements,
 * from the starts it writes first.
 *
 * <p>A walk starts on a table the parent type's result is a row of, on the table bound to a parent
 * type no field returns, or on the table of the routine a root field's query starts with;
 * {@code graphitron_field_path_start} holds all three, each kind in its subtype, and the steps from
 * there are one relation whichever way the walk started.
 *
 * <p>The rule is one round of the walk, every move the next written element allows from where each
 * walk stands; this merges the steps with exactly one move until a round changes nothing, as
 * {@link Results} merges its rule, a view being unable to state the fixpoint and H2 evaluating a
 * recursive view again for every row joined to it. The rule steps only from rows marked with the
 * reading's own instant, so a step only an earlier reading took leads nowhere. The moves are counted
 * here, over this graph's rows only, so the rule carries no window a predicate could not pass.
 */
public final class FieldPaths {

    private FieldPaths() {}

    /** Far more rounds than any path is long; reaching it means the rule stopped converging. */
    private static final int ROUND_LIMIT = 1000;

    private static final List<String> FIELD = List.of("GRAPH_NAME", "TYPE_NAME", "FIELD_NAME");
    private static final List<String> START = List.of("START_SOURCE_NAME", "START_SCHEMA", "START_TABLE");
    private static final List<String> WALK = concat(FIELD, START);
    private static final List<String> STEP = concat(WALK, List.of("POSITION"));
    private static final List<String> ELEMENT =
        List.of("ELEMENT_SOURCE_NAME", "ELEMENT_SOURCE_LINE", "ELEMENT_SOURCE_COLUMN");
    private static final List<String> ENDS = List.of("FROM_SOURCE_NAME", "FROM_SCHEMA", "FROM_TABLE",
        "TO_SOURCE_NAME", "TO_SCHEMA", "TO_TABLE");
    private static final List<String> CONSTRAINT = List.of("CONSTRAINT_SOURCE_NAME",
        "CONSTRAINT_SCHEMA", "CONSTRAINT_TABLE", "CONSTRAINT_NAME");
    private static final List<String> METHOD = List.of("METHOD_SOURCE_NAME", "METHOD_CLASS_NAME",
        "METHOD_NAME", "METHOD_DESCRIPTOR");
    private static final List<String> ROUTINE = List.of("ROUTINE_SOURCE_NAME", "TABLE_SCHEMA",
        "ROUTINE_NAME");
    private static final List<String> PARAMETER = List.of("PARAMETER_NAME", "FROM_SOURCE_NAME",
        "FROM_SCHEMA", "FROM_TABLE", "FROM_COLUMN", "ARGUMENT");

    /** Starts the walks, walks them for one graph, then sweeps what this reading no longer reached. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        starts(dsl, graph, touchedAt);
        for (int round = 0; round(dsl, graph, touchedAt) > 0; round++) {
            if (round == ROUND_LIMIT) {
                throw new IllegalStateException("graphitron_field_path did not converge for "
                    + graph + " in " + ROUND_LIMIT + " rounds");
            }
        }
        var counted = counted(dsl, graph);
        keys(dsl, counted, touchedAt);
        primaryKeys(dsl, counted, graph, touchedAt);
        columns(dsl, counted, graph, touchedAt);
        methods(dsl, graph, touchedAt);
        routines(dsl, graph, touchedAt);
        parameters(dsl, graph, touchedAt);
        stops(dsl, counted, touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /** Where each walk starts, the supertype first and each kind's subtype after it. */
    private static void starts(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_FIELD_PATH_START_RULE;
        var kinded = concat(WALK, List.of("KIND"));
        merge(dsl, GRAPHITRON_FIELD_PATH_START,
            dsl.select(cols(r, kinded)).from(r).where(r.GRAPH_NAME.eq(graph)).asTable("s"),
            WALK, List.of("KIND"), touchedAt);
        merge(dsl, GRAPHITRON_FIELD_PATH_START_RESULT,
            dsl.select(cols(r, kinded)).from(r).where(r.GRAPH_NAME.eq(graph))
                .and(r.KIND.eq("RESULT")).asTable("s"),
            WALK, List.of("KIND"), touchedAt);
        merge(dsl, GRAPHITRON_FIELD_PATH_START_BINDING,
            dsl.select(cols(r, kinded)).from(r).where(r.GRAPH_NAME.eq(graph))
                .and(r.KIND.eq("BINDING")).asTable("s"),
            WALK, List.of("KIND"), touchedAt);
        merge(dsl, GRAPHITRON_FIELD_PATH_START_ROUTINE,
            dsl.select(cols(r, kinded)).from(r).where(r.GRAPH_NAME.eq(graph))
                .and(r.KIND.eq("ROUTINE")).asTable("s"),
            FIELD, concat(START, List.of("KIND")), touchedAt);
        // The lifted start names the key row it starts on, by the kind the key is taken as.
        var k = GRAPHITRON_FIELD_KEY_ROW;
        var lifted = new ArrayList<Field<?>>(cols(r, kinded));
        lifted.add(k.KIND.as("KEY_KIND"));
        merge(dsl, GRAPHITRON_FIELD_PATH_START_LIFTED,
            dsl.select(lifted).from(r)
                .join(k).on(k.GRAPH_NAME.eq(r.GRAPH_NAME), k.TYPE_NAME.eq(r.TYPE_NAME),
                    k.FIELD_NAME.eq(r.FIELD_NAME))
                .where(r.GRAPH_NAME.eq(graph)).and(r.KIND.eq("LIFTED")).asTable("s"),
            FIELD, concat(START, List.of("KIND", "KEY_KIND")), touchedAt);
    }

    /** The rule's rows for this graph, each with how many moves its step allows. */
    private static Table<Record> counted(DSLContext dsl, String graph) {
        var r = GRAPHITRON_FIELD_PATH_RULE;
        var step = STEP.stream().map(c -> (Field<?>) f(r, c)).toList();
        var fields = new ArrayList<Field<?>>(List.of(r.fields()));
        fields.add(count(f(r, "TO_TABLE")).over(partitionBy(step)).as("MOVES"));
        return dsl.select(fields).from(r).where(f(r, "GRAPH_NAME").eq(graph)).asTable("counted");
    }

    /**
     * Merges one round's steps, the ones with exactly one move, and answers how many rows it added
     * or marked; the rule's row with no destination, which every step has, is not the move. A step
     * this reading already marked changes nothing, which is what lets it reach zero.
     */
    private static int round(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_FIELD_PATH;
        var c = counted(dsl, graph);
        var keys = STEP;
        var values = concat(ELEMENT, ENDS);
        var steps = dsl.selectDistinct(cols(c, concat(keys, values))).from(c)
            .where(f(c, "MOVES").eq(1)).and(f(c, "TO_TABLE").isNotNull()).asTable("s");
        return merge(dsl, t, steps, keys, values, touchedAt);
    }

    /** The key each step rode, written or inferred, where it rode one. */
    private static void keys(DSLContext dsl, Table<Record> c, LocalDateTime touchedAt) {
        var keys = STEP;
        var source = dsl.selectDistinct(cols(c, concat(keys, CONSTRAINT))).from(c)
            .where(f(c, "MOVES").eq(1)).and(f(c, "CONSTRAINT_NAME").isNotNull()).asTable("k");
        merge(dsl, GRAPHITRON_FIELD_PATH_KEY, source, keys, CONSTRAINT, touchedAt);
    }

    /**
     * The primary key each step that joined a row to itself rode: the step a key element naming the
     * table's own primary key took, which rides no foreign key and so is not in
     * {@code graphitron_field_path_key}.
     */
    private static void primaryKeys(DSLContext dsl, Table<Record> c, String graph,
                                    LocalDateTime touchedAt) {
        var pk = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
        var source = dsl.selectDistinct(cols(c, STEP))
            .select(pk.TABLE_SOURCE_NAME, pk.TABLE_SCHEMA, pk.TABLE_NAME)
            .from(c)
            .join(pk).on(pk.GRAPH_NAME.eq(graph), pk.SOURCE_NAME.eq(str(c, "ELEMENT_SOURCE_NAME")),
                pk.SOURCE_LINE.eq(f(c, "ELEMENT_SOURCE_LINE", Integer.class)),
                pk.SOURCE_COLUMN.eq(f(c, "ELEMENT_SOURCE_COLUMN", Integer.class)),
                pk.TABLE_SOURCE_NAME.eq(str(c, "FROM_SOURCE_NAME")), pk.TABLE_SCHEMA.eq(str(c, "FROM_SCHEMA")),
                pk.TABLE_NAME.eq(str(c, "FROM_TABLE")),
                pk.TABLE_SOURCE_NAME.eq(str(c, "TO_SOURCE_NAME")), pk.TABLE_SCHEMA.eq(str(c, "TO_SCHEMA")),
                pk.TABLE_NAME.eq(str(c, "TO_TABLE")))
            .where(f(c, "MOVES").eq(1)).and(f(c, "CONSTRAINT_NAME").isNull())
            .asTable("pk");
        merge(dsl, GRAPHITRON_FIELD_PATH_PRIMARY_KEY, source, STEP,
            List.of("TABLE_SOURCE_NAME", "TABLE_SCHEMA", "TABLE_NAME"), touchedAt);
    }

    /**
     * The column pairs each step joins on: a key's own columns, each paired with the referenced
     * column its {@code REFERENCES} list names, oriented the way the step rode it; for a step joining
     * a row to itself, each of the table's primary-key columns paired with itself; or, for a table
     * reached from a function's result, the named table's key matched to the result's columns by name.
     */
    private static void columns(DSLContext dsl, Table<Record> c, String graph,
                                LocalDateTime touchedAt) {
        var keys = STEP;
        var fc = SQL_CONSTRAINT_COLUMN.as("fc");
        var rcc = SQL_REFERENTIAL_CONSTRAINT_COLUMN.as("rcc");
        var pc = SQL_CONSTRAINT_COLUMN.as("pc");
        Field<Boolean> onFrom = f(c, "FK_ON_FROM", Boolean.class);
        var byKey = dsl.select(cols(c, keys))
            .select(fc.POSITION.as("ORDINAL"),
                f(c, "FROM_SOURCE_NAME").as("FROM_SOURCE_NAME"), f(c, "FROM_SCHEMA").as("FROM_SCHEMA"),
                f(c, "FROM_TABLE").as("FROM_TABLE"),
                when(onFrom, fc.COLUMN_NAME).otherwise(pc.COLUMN_NAME).as("FROM_COLUMN"),
                f(c, "TO_SOURCE_NAME").as("TO_SOURCE_NAME"), f(c, "TO_SCHEMA").as("TO_SCHEMA"),
                f(c, "TO_TABLE").as("TO_TABLE"),
                when(onFrom, pc.COLUMN_NAME).otherwise(fc.COLUMN_NAME).as("TO_COLUMN"))
            .from(c)
            .join(fc).on(fc.SOURCE_NAME.eq(str(c, "CONSTRAINT_SOURCE_NAME")),
                fc.TABLE_SCHEMA.eq(str(c, "CONSTRAINT_SCHEMA")), fc.TABLE_NAME.eq(str(c, "CONSTRAINT_TABLE")),
                fc.CONSTRAINT_NAME.eq(str(c, "CONSTRAINT_NAME")))
            .join(rcc).on(rcc.SOURCE_NAME.eq(fc.SOURCE_NAME), rcc.TABLE_SCHEMA.eq(fc.TABLE_SCHEMA),
                rcc.TABLE_NAME.eq(fc.TABLE_NAME), rcc.CONSTRAINT_NAME.eq(fc.CONSTRAINT_NAME),
                rcc.POSITION.eq(fc.POSITION))
            .join(pc).on(pc.SOURCE_NAME.eq(rcc.REFERENCED_SOURCE_NAME),
                pc.TABLE_SCHEMA.eq(rcc.REFERENCED_SCHEMA), pc.TABLE_NAME.eq(rcc.REFERENCED_TABLE),
                pc.CONSTRAINT_NAME.eq(rcc.REFERENCED_CONSTRAINT_NAME),
                pc.POSITION.eq(rcc.REFERENCED_POSITION))
            .where(f(c, "MOVES").eq(1));
        var n = SQL_NAME_MATCHED_KEY_COLUMN;
        var ts = GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY;
        var byName = dsl.select(cols(c, keys))
            .select(n.POSITION.as("ORDINAL"),
                n.SOURCE_NAME.as("FROM_SOURCE_NAME"), n.TABLE_SCHEMA.as("FROM_SCHEMA"),
                n.TABLE_NAME.as("FROM_TABLE"), n.COLUMN_NAME.as("FROM_COLUMN"),
                n.TO_SOURCE_NAME.as("TO_SOURCE_NAME"), n.TO_SCHEMA.as("TO_SCHEMA"),
                n.TO_TABLE.as("TO_TABLE"), n.TO_COLUMN.as("TO_COLUMN"))
            .from(c)
            .join(n).on(n.SOURCE_NAME.eq(str(c, "FROM_SOURCE_NAME")), n.TABLE_SCHEMA.eq(str(c, "FROM_SCHEMA")),
                n.TABLE_NAME.eq(str(c, "FROM_TABLE")), n.TO_SOURCE_NAME.eq(str(c, "TO_SOURCE_NAME")),
                n.TO_SCHEMA.eq(str(c, "TO_SCHEMA")), n.TO_TABLE.eq(str(c, "TO_TABLE")))
            .where(f(c, "MOVES").eq(1)).and(f(c, "CONSTRAINT_NAME").isNull())
            // A table element is what reaches a table by name, and so is the step no element wrote
            // after a routine; a condition alone joins by its method.
            .and(f(c, "ELEMENT_SOURCE_NAME").isNull()
                .or(exists(selectOne().from(ts).where(ts.GRAPH_NAME.eq(graph),
                    ts.SOURCE_NAME.eq(str(c, "ELEMENT_SOURCE_NAME")),
                    ts.SOURCE_LINE.eq(f(c, "ELEMENT_SOURCE_LINE", Integer.class)),
                    ts.SOURCE_COLUMN.eq(f(c, "ELEMENT_SOURCE_COLUMN", Integer.class))))));
        var epk = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
        var spk = SQL_PRIMARY_KEY;
        var kc = SQL_CONSTRAINT_COLUMN.as("kc");
        var byPrimaryKey = dsl.select(cols(c, keys))
            .select(kc.POSITION.as("ORDINAL"),
                f(c, "FROM_SOURCE_NAME").as("FROM_SOURCE_NAME"), f(c, "FROM_SCHEMA").as("FROM_SCHEMA"),
                f(c, "FROM_TABLE").as("FROM_TABLE"), kc.COLUMN_NAME.as("FROM_COLUMN"),
                f(c, "TO_SOURCE_NAME").as("TO_SOURCE_NAME"), f(c, "TO_SCHEMA").as("TO_SCHEMA"),
                f(c, "TO_TABLE").as("TO_TABLE"), kc.COLUMN_NAME.as("TO_COLUMN"))
            .from(c)
            .join(epk).on(epk.GRAPH_NAME.eq(graph), epk.SOURCE_NAME.eq(str(c, "ELEMENT_SOURCE_NAME")),
                epk.SOURCE_LINE.eq(f(c, "ELEMENT_SOURCE_LINE", Integer.class)),
                epk.SOURCE_COLUMN.eq(f(c, "ELEMENT_SOURCE_COLUMN", Integer.class)),
                epk.TABLE_SOURCE_NAME.eq(str(c, "FROM_SOURCE_NAME")), epk.TABLE_SCHEMA.eq(str(c, "FROM_SCHEMA")),
                epk.TABLE_NAME.eq(str(c, "FROM_TABLE")),
                epk.TABLE_SOURCE_NAME.eq(str(c, "TO_SOURCE_NAME")), epk.TABLE_SCHEMA.eq(str(c, "TO_SCHEMA")),
                epk.TABLE_NAME.eq(str(c, "TO_TABLE")))
            .join(spk).on(spk.SOURCE_NAME.eq(epk.TABLE_SOURCE_NAME), spk.TABLE_SCHEMA.eq(epk.TABLE_SCHEMA),
                spk.TABLE_NAME.eq(epk.TABLE_NAME))
            .join(kc).on(kc.SOURCE_NAME.eq(spk.SOURCE_NAME), kc.TABLE_SCHEMA.eq(spk.TABLE_SCHEMA),
                kc.TABLE_NAME.eq(spk.TABLE_NAME), kc.CONSTRAINT_NAME.eq(spk.CONSTRAINT_NAME))
            .where(f(c, "MOVES").eq(1)).and(f(c, "CONSTRAINT_NAME").isNull());
        var pairs = byKey.unionAll(byName).unionAll(byPrimaryKey).asTable("p");
        merge(dsl, GRAPHITRON_FIELD_PATH_COLUMN, pairs, concat(keys, List.of("ORDINAL")),
            concat(List.of("FROM_SOURCE_NAME", "FROM_SCHEMA", "FROM_TABLE", "FROM_COLUMN"),
                List.of("TO_SOURCE_NAME", "TO_SCHEMA", "TO_TABLE", "TO_COLUMN")), touchedAt);
    }

    /** The methods each step joins or filters by: every overload its element names. */
    private static void methods(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var keys = STEP;
        var p = GRAPHITRON_FIELD_PATH;
        var m = GRAPHITRON_PATH_ELEMENT_METHOD;
        var source = dsl.select(cols(p, keys)).select(m.METHOD_SOURCE_NAME, m.METHOD_CLASS_NAME,
                m.METHOD_NAME, m.METHOD_DESCRIPTOR)
            .from(p)
            .join(m).on(m.GRAPH_NAME.eq(str(p, "GRAPH_NAME")), m.SOURCE_NAME.eq(str(p, "ELEMENT_SOURCE_NAME")),
                m.SOURCE_LINE.eq(f(p, "ELEMENT_SOURCE_LINE", Integer.class)),
                m.SOURCE_COLUMN.eq(f(p, "ELEMENT_SOURCE_COLUMN", Integer.class)))
            .where(f(p, "GRAPH_NAME").eq(graph)).and(f(p, "TOUCHED_AT", LocalDateTime.class).eq(touchedAt))
            .asTable("m");
        merge(dsl, GRAPHITRON_FIELD_PATH_METHOD, source, concat(keys, METHOD), List.of(), touchedAt);
    }

    /** The routine each step a {@code @routine} element took calls: the one its element names. */
    private static void routines(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var keys = STEP;
        var p = GRAPHITRON_FIELD_PATH;
        var r = GRAPHITRON_PATH_ELEMENT_ROUTINE;
        var source = dsl.select(cols(p, keys)).select(r.ROUTINE_SOURCE_NAME, r.TABLE_SCHEMA,
                r.ROUTINE_NAME)
            .from(p)
            .join(r).on(r.GRAPH_NAME.eq(str(p, "GRAPH_NAME")), r.SOURCE_NAME.eq(str(p, "ELEMENT_SOURCE_NAME")),
                r.SOURCE_LINE.eq(f(p, "ELEMENT_SOURCE_LINE", Integer.class)),
                r.SOURCE_COLUMN.eq(f(p, "ELEMENT_SOURCE_COLUMN", Integer.class)))
            .where(f(p, "GRAPH_NAME").eq(graph)).and(f(p, "TOUCHED_AT", LocalDateTime.class).eq(touchedAt))
            .asTable("r");
        merge(dsl, GRAPHITRON_FIELD_PATH_ROUTINE, source, keys, ROUTINE, touchedAt);
    }

    /** Where each routine step's parameters take their values, off the routine rows just written. */
    private static void parameters(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_FIELD_PATH_PARAMETER_RULE;
        var keys = concat(STEP, List.of("PARAMETER_POSITION"));
        merge(dsl, GRAPHITRON_FIELD_PATH_PARAMETER,
            dsl.select(cols(r, concat(keys, PARAMETER))).from(r).where(r.GRAPH_NAME.eq(graph))
                .asTable("pr"),
            keys, PARAMETER, touchedAt);
    }

    /** Where each walk stopped: a step that allows no move or several, one per walk. */
    private static void stops(DSLContext dsl, Table<Record> c, LocalDateTime touchedAt) {
        var source = dsl.selectDistinct(cols(c, STEP))
            .select(cols(c, ELEMENT)).select(f(c, "MOVES", Integer.class))
            .from(c).where(f(c, "MOVES", Integer.class).ne(1)).asTable("st");
        merge(dsl, GRAPHITRON_FIELD_PATH_STOP, source, WALK, concat(List.of("POSITION"), ELEMENT,
            List.of("MOVES")), touchedAt);
    }

    /** What this reading did not mark, children before the steps and starts they hang on. */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Table<?> t : List.of(GRAPHITRON_FIELD_PATH_COLUMN, GRAPHITRON_FIELD_PATH_KEY,
                GRAPHITRON_FIELD_PATH_PRIMARY_KEY,
                GRAPHITRON_FIELD_PATH_METHOD, GRAPHITRON_FIELD_PATH_PARAMETER,
                GRAPHITRON_FIELD_PATH_ROUTINE, GRAPHITRON_FIELD_PATH_STOP, GRAPHITRON_FIELD_PATH,
                GRAPHITRON_FIELD_PATH_START_RESULT, GRAPHITRON_FIELD_PATH_START_BINDING,
                GRAPHITRON_FIELD_PATH_START_ROUTINE, GRAPHITRON_FIELD_PATH_START_LIFTED,
                GRAPHITRON_FIELD_PATH_START)) {
            dsl.deleteFrom(t).where(f(t, "GRAPH_NAME").eq(graph))
                .and(f(t, "TOUCHED_AT", LocalDateTime.class).ne(touchedAt)).execute();
        }
    }

    /**
     * Merges rows into a relation on its key columns, setting the value columns and the mark where
     * the row is new or carries another reading's mark, and answers how many rows it changed.
     */
    private static int merge(DSLContext dsl, Table<?> target, Table<?> source, List<String> keys,
                             List<String> values, LocalDateTime touchedAt) {
        Field<LocalDateTime> touched = f(target, "TOUCHED_AT", LocalDateTime.class);
        Condition on = noCondition();
        for (String k : keys) {
            on = on.and(f(target, k).eq(f(source, k)));
        }
        var insertColumns = new ArrayList<Field<?>>();
        var insertValues = new ArrayList<Field<?>>();
        for (String col : concat(keys, values)) {
            insertColumns.add(f(target, col));
            insertValues.add(f(source, col));
        }
        insertColumns.add(touched);
        insertValues.add(inline(touchedAt));
        var sets = new java.util.LinkedHashMap<Field<?>, Field<?>>();
        sets.put(touched, inline(touchedAt));
        for (String col : values) {
            sets.put(f(target, col), f(source, col));
        }
        return dsl.mergeInto(target).using(source).on(on)
            .whenMatchedAnd(touched.ne(touchedAt)).thenUpdate().set(sets)
            .whenNotMatchedThenInsert(insertColumns).values(insertValues).execute();
    }

    private static Field<Object> f(Table<?> t, String column) {
        return field(name(t.getName(), column));
    }

    private static Field<String> str(Table<?> t, String column) {
        return field(name(t.getName(), column), String.class);
    }

    private static <T> Field<T> f(Table<?> t, String column, Class<T> type) {
        return field(name(t.getName(), column), type);
    }

    private static List<Field<?>> cols(Table<?> t, List<String> columns) {
        var out = new ArrayList<Field<?>>();
        for (String c : columns) {
            out.add(f(t, c).as(c));
        }
        return out;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    private static List<String> concat(List<String> first, List<String> second, List<String> third) {
        return concat(concat(first, second), third);
    }
}
