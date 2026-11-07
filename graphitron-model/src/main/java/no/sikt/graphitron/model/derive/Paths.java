package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.tables.GraphitronOccurrence;
import no.sikt.graphitron.model.tables.GraphitronPathNeed;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Select;
import org.jooq.Table;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE_REFERENCE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of the paths nothing written covers: a {@code graphitron_path} of kind
 * {@code JOIN} with its {@code graphitron_join_path}, for every need no claim covers that is met
 * without one, marked and swept.
 *
 * <p>A need is covered by a claim at its own element, a {@code @referenceFor} naming its target type
 * or the element's other claim, or, for a field, by one at the same-named field of an interface its
 * type implements, by the same rule. A covered need has no row here: its path is the claim's, which
 * exists once the claim's steps reach the target, and is written with them.
 *
 * <p>An uncovered need is met in two ways. Where its start is its landing it is {@code EMPTY}, its
 * target its start: an argument or input field, a target bound to no table, and a field a producer
 * lifted onto its target's table, its own {@code @service} handing back the table's records or its
 * parent's service or write. Where an output field starts and lands on two different tables joined
 * by exactly one route it is {@code INFERRED}, its target its landing: one foreign key in either
 * direction, or, from the derived table a routine returns, the landing's primary key, every column
 * of it matched by name among the routine's result columns. An output field starting and landing on
 * one table whose one self-referencing key joins it to itself is {@code INFERRED} over that key,
 * which {@link Steps} rides by the field's cardinality. An argument or input field carrying
 * {@code @nodeId}, whose node is its {@code typeName} or else the type the need reaches, is
 * {@code INFERRED} where its start declares exactly one foreign key to the node's table, its target
 * that table; one starting on the node's table, or declaring no key or several, stays {@code EMPTY},
 * which {@code graphitron_entry_defect_rule} judges. The one step it takes is {@link Steps}'s to
 * write. Any other uncovered field between two tables, the same table included, is a defect, which
 * {@code graphitron_entry_defect_rule} raises.
 *
 * <p>The foreign keys are counted off {@code sql_table_reference}, which holds the count per ordered
 * pair of tables: both orders summed between two tables, the one pair of a table with itself for its
 * self-referencing keys. An argument or input field never infers a self-referencing key.
 *
 * <p>A step of the assembly's per-depth loop: reads the claims {@link PathClaims} writes before the
 * loop and the needs {@link PathNeeds} writes for the same depth, a depth at a time; {@link #sweep}
 * runs after the last depth.
 */
public final class Paths {

    private Paths() {}

    private static final String JOIN = "JOIN";
    private static final String EMPTY = "EMPTY";
    private static final String INFERRED = "INFERRED";

    /** States the uncovered paths of the needs of the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, graph, candidates(graph, depth, touchedAt).asTable("c"), touchedAt);
    }

    /** Deletes the paths of this stage's origins no depth of this reading marked. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        // Only the origins written here: a written or inherited path is the steps' to sweep, and its
        // join path goes with it.
        dsl.deleteFrom(GRAPHITRON_PATH)
            .where(GRAPHITRON_PATH.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_PATH.ORIGIN.in(EMPTY, INFERRED))
            .and(GRAPHITRON_PATH.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** Upserts the paths and their join paths, the supertype before the subtype that references it. */
    private static void write(DSLContext dsl, String graph, Table<?> c, LocalDateTime touchedAt) {
        var cGraph = c.field("graph_name", String.class);
        var cCoordinate = c.field("coordinate", String.class);
        var cTarget = c.field("target_type", String.class);
        var cOrigin = c.field("origin", String.class);
        var cSource = c.field("target_source_name", String.class);
        var cSchema = c.field("target_schema", String.class);
        var cTable = c.field("target_table", String.class);

        var p = GRAPHITRON_PATH;
        dsl.insertInto(p)
            .columns(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE, p.KIND, p.ORIGIN, p.TOUCHED_AT)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(JOIN), cOrigin,
                    val(touchedAt, p.TOUCHED_AT))
                .from(c))
            .onConflict(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE)
            .doUpdate()
            .set(p.KIND, excluded(p.KIND))
            .set(p.ORIGIN, excluded(p.ORIGIN))
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();

        var j = GRAPHITRON_JOIN_PATH;
        var st = SQL_TABLE;
        dsl.insertInto(j)
            .columns(j.GRAPH_NAME, j.COORDINATE, j.TARGET_TYPE, j.KIND, j.TARGET_SOURCE_NAME,
                j.TARGET_SCHEMA, j.TARGET_TABLE, j.TOUCHED_AT, j.TARGET_SOURCE_REF,
                j.TARGET_SCHEMA_REF, j.TARGET_TABLE_REF)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(JOIN), cSource, cSchema,
                    cTable, val(touchedAt, j.TOUCHED_AT), st.SOURCE_NAME, st.TABLE_SCHEMA,
                    st.TABLE_NAME)
                .from(c)
                .leftJoin(st).on(st.SOURCE_NAME.eq(cSource), st.TABLE_SCHEMA.eq(cSchema),
                    st.TABLE_NAME.eq(cTable)))
            .onConflict(j.GRAPH_NAME, j.COORDINATE, j.TARGET_TYPE)
            .doUpdate()
            .set(j.TARGET_SOURCE_NAME, excluded(j.TARGET_SOURCE_NAME))
            .set(j.TARGET_SCHEMA, excluded(j.TARGET_SCHEMA))
            .set(j.TARGET_TABLE, excluded(j.TARGET_TABLE))
            .set(j.TOUCHED_AT, excluded(j.TOUCHED_AT))
            .set(j.TARGET_SOURCE_REF, excluded(j.TARGET_SOURCE_REF))
            .set(j.TARGET_SCHEMA_REF, excluded(j.TARGET_SCHEMA_REF))
            .set(j.TARGET_TABLE_REF, excluded(j.TARGET_TABLE_REF))
            .execute();
    }

    /**
     * Every uncovered need of an occurrence at {@code depth} met without a claim, with its origin and
     * the table its path reaches: the start for an empty path, the landing for an inferred one.
     */
    private static Select<?> candidates(String graph, int depth, LocalDateTime touchedAt) {
        var n = GRAPHITRON_PATH_NEED.as("n");
        var o = GRAPHITRON_OCCURRENCE.as("o");
        Condition sameTable = n.START_SOURCE_NAME.eq(n.LANDING_SOURCE_NAME)
            .and(n.START_SCHEMA.eq(n.LANDING_SCHEMA))
            .and(n.START_TABLE.eq(n.LANDING_TABLE));
        Condition empty = sameTable.and(o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD")
            .or(notExists(selectOne().from(GRAPHITRON_TABLETYPE)
                .where(GRAPHITRON_TABLETYPE.GRAPH_NAME.eq(n.GRAPH_NAME),
                    GRAPHITRON_TABLETYPE.TYPE_NAME.eq(n.TARGET_TYPE))))
            .or(lifted(o)));
        Condition inferred = o.KIND.eq("FIELD").andNot(sameTable)
            .and(startsOnFunction(n).and(primaryKeyMatched(n))
                .or(startsOnFunction(n).not().and(foreignKeys(n).eq(1))));
        // An output field on its own table, to a table type and not lifted onto it, over the one
        // self-referencing key.
        Condition selfInferred = o.KIND.eq("FIELD").and(sameTable).andNot(empty)
            .and(selfKeys(n).eq(1));
        // The node an argument's or input field's @nodeId names, its typeName or else the type the
        // need reaches, and that node's table. A bare one whose need reaches no node takes the one
        // node over where its path lands, which with nothing written is its start: nothing to infer.
        var cl = GRAPHITRON_BINDING_CLAIM.as("nic");
        var gn = GRAPHITRON_NODE.as("nin");
        var nt = GRAPHITRON_TABLETYPE.as("nit");
        Condition nodeInferred = sameTable.and(nt.TABLE_NAME.isNotNull())
            .andNot(nt.TABLE_SOURCE_NAME.eq(n.START_SOURCE_NAME).and(nt.TABLE_SCHEMA.eq(n.START_SCHEMA))
                .and(nt.TABLE_NAME.eq(n.START_TABLE)))
            .and(declaredKeys(n, nt).eq(1));
        return select(n.GRAPH_NAME.as("graph_name"), n.COORDINATE.as("coordinate"),
                n.TARGET_TYPE.as("target_type"),
                when(nodeInferred, inline(INFERRED)).when(empty, inline(EMPTY))
                    .otherwise(inline(INFERRED)).as("origin"),
                when(nodeInferred, nt.TABLE_SOURCE_NAME).otherwise(n.LANDING_SOURCE_NAME)
                    .as("target_source_name"),
                when(nodeInferred, nt.TABLE_SCHEMA).otherwise(n.LANDING_SCHEMA).as("target_schema"),
                when(nodeInferred, nt.TABLE_NAME).otherwise(n.LANDING_TABLE).as("target_table"))
            .from(n)
            .join(o).on(o.GRAPH_NAME.eq(n.GRAPH_NAME), o.COORDINATE.eq(n.COORDINATE),
                o.TOUCHED_AT.eq(touchedAt), o.DEPTH.eq(val(depth)))
            .leftJoin(cl).on(cl.GRAPH_NAME.eq(o.GRAPH_NAME), cl.COORDINATE.eq(o.ELEMENT_COORDINATE),
                cl.KIND.eq("NODE_ID"), o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"))
            .leftJoin(gn).on(gn.GRAPH_NAME.eq(cl.GRAPH_NAME),
                gn.TYPE_NAME.eq(coalesce(cl.NAMED, n.TARGET_TYPE)))
            .leftJoin(nt).on(nt.GRAPH_NAME.eq(gn.GRAPH_NAME), nt.TYPE_NAME.eq(gn.TYPE_NAME))
            .where(n.GRAPH_NAME.eq(graph), n.TOUCHED_AT.eq(touchedAt), n.LANDING_TABLE.isNotNull())
            .andNot(covered(o, n))
            .and(empty.or(inferred).or(selfInferred));
    }

    /**
     * Whether the need {@code n} of the occurrence {@code o} is covered by a claim: at its own
     * element, a {@code @referenceFor} naming its target type or any other claim there; or, for a
     * field, the same at the same-named field of an interface its type implements.
     */
    private static Condition covered(GraphitronOccurrence o, GraphitronPathNeed n) {
        var own = GRAPHITRON_JOIN_PATH_CLAIM.as("own");
        var f = GRAPHITRON_FIELD.as("cov_f");
        var ii = GRAPHQL_IMPLEMENTS_INTERFACE.as("cov_ii");
        var inf = GRAPHITRON_FIELD.as("cov_if");
        var inh = GRAPHITRON_JOIN_PATH_CLAIM.as("inh");
        return exists(selectOne().from(own)
                .where(own.GRAPH_NAME.eq(o.GRAPH_NAME), own.ELEMENT_COORDINATE.eq(o.ELEMENT_COORDINATE))
                .and(own.CLAIM_KIND.ne("REFERENCE_FOR").or(own.TARGET_TYPE.eq(n.TARGET_TYPE))))
            .or(o.KIND.eq("FIELD").and(exists(selectOne().from(f)
                .join(ii).on(ii.GRAPH_NAME.eq(f.GRAPH_NAME), ii.TYPE_NAME.eq(f.TYPE_NAME))
                .join(inf).on(inf.GRAPH_NAME.eq(ii.GRAPH_NAME), inf.TYPE_NAME.eq(ii.INTERFACE_NAME),
                    inf.FIELD_NAME.eq(f.FIELD_NAME))
                .join(inh).on(inh.GRAPH_NAME.eq(inf.GRAPH_NAME),
                    inh.ELEMENT_COORDINATE.eq(inf.COORDINATE))
                .where(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
                .and(inh.CLAIM_KIND.ne("REFERENCE_FOR").or(inh.TARGET_TYPE.eq(n.TARGET_TYPE))))));
    }

    /**
     * Whether a producer lifted the field occurrence {@code o} onto the table it starts on: its own
     * {@code @service}, handing back that table's records, or the service or write of the field it
     * hangs under, handing it a key row, a record or the keys a write returned. Its source is then
     * the table it lifts onto, not one it joins from.
     */
    private static Condition lifted(GraphitronOccurrence o) {
        var f = GRAPHITRON_FIELD.as("lf");
        var fr = GRAPHITRON_PRODUCER.as("lfr");
        var p = GRAPHITRON_OCCURRENCE.as("lp");
        var pf = GRAPHITRON_FIELD.as("lpf");
        var pr = GRAPHITRON_PRODUCER.as("lpr");
        return o.KIND.eq("FIELD").and(exists(selectOne().from(f)
                .join(fr).on(fr.GRAPH_NAME.eq(f.GRAPH_NAME), fr.TYPE_NAME.eq(f.TYPE_NAME),
                    fr.FIELD_NAME.eq(f.FIELD_NAME))
                .where(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE),
                    fr.KIND.eq("SERVICE")))
            .or(exists(selectOne().from(p)
                .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
                .join(pr).on(pr.GRAPH_NAME.eq(pf.GRAPH_NAME), pr.TYPE_NAME.eq(pf.TYPE_NAME),
                    pr.FIELD_NAME.eq(pf.FIELD_NAME))
                .where(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE),
                    p.KIND.eq("FIELD"), pr.KIND.in("SERVICE", "INSERT", "UPDATE", "DELETE")))));
    }

    /**
     * Whether the need starts on the derived table a routine returns, which has no foreign key and
     * reaches its landing by the landing's primary key instead.
     */
    private static Condition startsOnFunction(GraphitronPathNeed n) {
        var st = SQL_TABLE.as("fn");
        return exists(selectOne().from(st)
            .where(st.SOURCE_NAME.eq(n.START_SOURCE_NAME), st.TABLE_SCHEMA.eq(n.START_SCHEMA),
                st.TABLE_NAME.eq(n.START_TABLE), st.TABLE_TYPE.eq("FUNCTION")));
    }

    /**
     * Whether the need's landing has a primary key whose every column the table it starts on has a
     * column of the same name for.
     */
    private static Condition primaryKeyMatched(GraphitronPathNeed n) {
        var pk = SQL_PRIMARY_KEY.as("pk");
        var mk = SQL_PRIMARY_KEY.as("mk");
        var cc = SQL_CONSTRAINT_COLUMN.as("cc");
        var fc = SQL_COLUMN.as("fc");
        return exists(selectOne().from(pk)
                .where(pk.SOURCE_NAME.eq(n.LANDING_SOURCE_NAME), pk.TABLE_SCHEMA.eq(n.LANDING_SCHEMA),
                    pk.TABLE_NAME.eq(n.LANDING_TABLE)))
            .andNotExists(selectOne().from(mk)
                .join(cc).on(cc.SOURCE_NAME.eq(mk.SOURCE_NAME), cc.TABLE_SCHEMA.eq(mk.TABLE_SCHEMA),
                    cc.TABLE_NAME.eq(mk.TABLE_NAME), cc.CONSTRAINT_NAME.eq(mk.CONSTRAINT_NAME))
                .where(mk.SOURCE_NAME.eq(n.LANDING_SOURCE_NAME), mk.TABLE_SCHEMA.eq(n.LANDING_SCHEMA),
                    mk.TABLE_NAME.eq(n.LANDING_TABLE))
                .andNotExists(selectOne().from(fc)
                    .where(fc.SOURCE_NAME.eq(n.START_SOURCE_NAME), fc.TABLE_SCHEMA.eq(n.START_SCHEMA),
                        fc.TABLE_NAME.eq(n.START_TABLE), fc.COLUMN_NAME.eq(cc.COLUMN_NAME))));
    }

    /** How many foreign keys the need's start declares to the node's table {@code nt}. */
    private static org.jooq.Field<Integer> declaredKeys(GraphitronPathNeed n,
                                                        no.sikt.graphitron.model.tables.GraphitronTabletype nt) {
        var f = SQL_TABLE_REFERENCE.as("nk_f");
        return coalesce(field(select(f.CONSTRAINTS).from(f)
            .where(f.SOURCE_NAME.eq(n.START_SOURCE_NAME), f.TABLE_SCHEMA.eq(n.START_SCHEMA),
                f.TABLE_NAME.eq(n.START_TABLE), f.REFERENCED_SOURCE_NAME.eq(nt.TABLE_SOURCE_NAME),
                f.REFERENCED_SCHEMA.eq(nt.TABLE_SCHEMA), f.REFERENCED_TABLE.eq(nt.TABLE_NAME))), 0);
    }

    /** How many self-referencing keys the need's start, which is its landing, declares. */
    private static org.jooq.Field<Integer> selfKeys(GraphitronPathNeed n) {
        var f = SQL_TABLE_REFERENCE.as("sk_f");
        return coalesce(field(select(f.CONSTRAINTS).from(f)
            .where(f.SOURCE_NAME.eq(n.START_SOURCE_NAME), f.TABLE_SCHEMA.eq(n.START_SCHEMA),
                f.TABLE_NAME.eq(n.START_TABLE), f.REFERENCED_SOURCE_NAME.eq(n.START_SOURCE_NAME),
                f.REFERENCED_SCHEMA.eq(n.START_SCHEMA), f.REFERENCED_TABLE.eq(n.START_TABLE))), 0);
    }

    /** How many foreign keys join the need's start and landing, in either direction. */
    private static org.jooq.Field<Integer> foreignKeys(GraphitronPathNeed n) {
        var f = SQL_TABLE_REFERENCE.as("fk_f");
        var b = SQL_TABLE_REFERENCE.as("fk_b");
        org.jooq.Field<Integer> forward = field(select(f.CONSTRAINTS).from(f)
            .where(f.SOURCE_NAME.eq(n.START_SOURCE_NAME), f.TABLE_SCHEMA.eq(n.START_SCHEMA),
                f.TABLE_NAME.eq(n.START_TABLE), f.REFERENCED_SOURCE_NAME.eq(n.LANDING_SOURCE_NAME),
                f.REFERENCED_SCHEMA.eq(n.LANDING_SCHEMA), f.REFERENCED_TABLE.eq(n.LANDING_TABLE)));
        org.jooq.Field<Integer> backward = field(select(b.CONSTRAINTS).from(b)
            .where(b.SOURCE_NAME.eq(n.LANDING_SOURCE_NAME), b.TABLE_SCHEMA.eq(n.LANDING_SCHEMA),
                b.TABLE_NAME.eq(n.LANDING_TABLE), b.REFERENCED_SOURCE_NAME.eq(n.START_SOURCE_NAME),
                b.REFERENCED_SCHEMA.eq(n.START_SCHEMA), b.REFERENCED_TABLE.eq(n.START_TABLE)));
        return coalesce(forward, 0).plus(coalesce(backward, 0));
    }
}
