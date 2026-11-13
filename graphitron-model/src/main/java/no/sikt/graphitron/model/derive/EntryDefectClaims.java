package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM_KIND;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * What a claim defect is about, written under it: each claim the conflict rule weighed and found in
 * conflict, in {@code graphitron_entry_defect_claim}, and each {@code @routine} a field's written
 * path steps through beside them, in {@code graphitron_entry_defect_path_step}. The defect itself is
 * a row of the graphitron rule, which {@link EntryDefects} writes just before this runs in the
 * graphitron gatherer's anchor phase; this reads the rows that reading marked and marks its own with
 * the same instant, and {@link EntryDefects#sweep} takes what the reading did not mark.
 *
 * <p>The claims are the ones the rule weighs: the strongest of each family at the element. An
 * overridden claim is no part of a conflict, so it is no detail of one.
 */
public final class EntryDefectClaims {

    private EntryDefectClaims() {}

    /** The codes whose claims this writes. */
    public static final List<String> CODES = List.of("CLAIMS_CONFLICT", "ROUTINE_LOOKUP_DEFERRED");

    /** The stage that raised those defects and writes their details, the one this runs in. */
    private static final String STAGE = EntryDefects.GRAPHITRON.stage();

    /** Writes the claims and steps under each of the graph's claim defects the reading marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var fe = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var f = GRAPHITRON_FIELD;
        var te = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        Condition marked = d.GRAPH_NAME.eq(graph).and(d.CODE.in(CODES)).and(d.STAGE.eq(STAGE))
            .and(d.TOUCHED_AT.eq(readAt));
        var fieldSites = select(d.GRAPH_NAME, d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE,
                f.COORDINATE)
            .from(d)
            .join(fe).on(fe.GRAPH_NAME.eq(d.GRAPH_NAME), fe.SOURCE_NAME.eq(d.SOURCE_NAME),
                fe.SOURCE_LINE.eq(d.SOURCE_LINE), fe.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .join(f).on(f.GRAPH_NAME.eq(fe.GRAPH_NAME), f.TYPE_NAME.eq(fe.TYPE_NAME),
                f.FIELD_NAME.eq(fe.NAME))
            .where(marked);
        var typeSites = select(d.GRAPH_NAME, d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE,
                te.NAME.as(f.COORDINATE.getName()))
            .from(d)
            .join(te).on(te.GRAPH_NAME.eq(d.GRAPH_NAME), te.SOURCE_NAME.eq(d.SOURCE_NAME),
                te.SOURCE_LINE.eq(d.SOURCE_LINE), te.SOURCE_COLUMN.eq(d.SOURCE_COLUMN),
                te.IS_EXTENSION.isFalse())
            .where(marked);
        claims(dsl, fieldSites.unionAll(typeSites).asTable("s"), readAt);
        steps(dsl, fieldSites.asTable("s"), readAt);
    }

    /** The strongest claims of each family at each site's element. */
    private static void claims(DSLContext dsl, Table<?> s, LocalDateTime readAt) {
        var c = GRAPHITRON_CLAIM;
        var k = GRAPHITRON_CLAIM_KIND;
        var stronger = GRAPHITRON_CLAIM.as("stronger");
        var strongerKind = GRAPHITRON_CLAIM_KIND.as("stronger_kind");
        var t = GRAPHITRON_ENTRY_DEFECT_CLAIM;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.COORDINATE, t.KIND, t.CLAIMER, t.TOUCHED_AT)
            .select(select(site(s, "GRAPH_NAME"), site(s, "SOURCE_NAME"), line(s, "SOURCE_LINE"),
                    line(s, "SOURCE_COLUMN"), site(s, "CODE"), val(STAGE, t.STAGE), c.COORDINATE, c.KIND,
                    c.CLAIMER, val(readAt, t.TOUCHED_AT))
                .from(s)
                .join(c).on(c.GRAPH_NAME.eq(site(s, "GRAPH_NAME")),
                    c.COORDINATE.eq(site(s, "COORDINATE")))
                .join(k).on(k.KIND.eq(c.KIND))
                .where(notExists(selectOne()
                    .from(stronger)
                    .join(strongerKind).on(strongerKind.KIND.eq(stronger.KIND))
                    .where(stronger.GRAPH_NAME.eq(c.GRAPH_NAME), stronger.COORDINATE.eq(c.COORDINATE),
                        strongerKind.FAMILY.eq(k.FAMILY), stronger.PRIORITY.lt(c.PRIORITY)))))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.COORDINATE, t.KIND, t.CLAIMER)
            .doUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .execute();
    }

    /** Each {@code @routine} a site's field's written path steps through and no producer claim holds. */
    private static void steps(DSLContext dsl, Table<?> s, LocalDateTime readAt) {
        var pc = GRAPHITRON_JOIN_PATH_CLAIM;
        var pe = GRAPHITRON_AST_PATH_ENTRY;
        var re = GRAPHITRON_AST_ROUTINE_ENTRY;
        var r = GRAPHITRON_CLAIM;
        var t = GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.STEP_SOURCE_NAME, t.STEP_SOURCE_LINE, t.STEP_SOURCE_COLUMN, t.TOUCHED_AT)
            .select(selectDistinct(site(s, "GRAPH_NAME"), site(s, "SOURCE_NAME"),
                    line(s, "SOURCE_LINE"), line(s, "SOURCE_COLUMN"), site(s, "CODE"), val(STAGE, t.STAGE),
                    re.SOURCE_NAME, re.SOURCE_LINE, re.SOURCE_COLUMN, val(readAt, t.TOUCHED_AT))
                .from(s)
                .join(pc).on(pc.GRAPH_NAME.eq(site(s, "GRAPH_NAME")),
                    pc.ELEMENT_COORDINATE.eq(site(s, "COORDINATE")), pc.CLAIM_KIND.eq("FIELD"))
                .join(pe).on(pe.GRAPH_NAME.eq(pc.GRAPH_NAME), pe.SOURCE_NAME.eq(pc.SOURCE_NAME),
                    pe.ANCHOR_LINE.eq(pc.SOURCE_LINE), pe.ANCHOR_COLUMN.eq(pc.SOURCE_COLUMN))
                .join(re).on(re.GRAPH_NAME.eq(pe.GRAPH_NAME), re.SOURCE_NAME.eq(pe.SOURCE_NAME),
                    re.SOURCE_LINE.eq(pe.ELEMENT_LINE), re.SOURCE_COLUMN.eq(pe.ELEMENT_COLUMN))
                .where(site(s, "CODE").eq("CLAIMS_CONFLICT"))
                .and(notExists(selectOne().from(r)
                    .where(r.GRAPH_NAME.eq(pc.GRAPH_NAME), r.COORDINATE.eq(pc.ELEMENT_COORDINATE),
                        r.KIND.eq("ROUTINE")))))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.STEP_SOURCE_NAME, t.STEP_SOURCE_LINE, t.STEP_SOURCE_COLUMN)
            .doUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .execute();
    }

    private static Field<String> site(Table<?> s, String column) {
        return s.field(column, String.class);
    }

    private static Field<Integer> line(Table<?> s, String column) {
        return s.field(column, Integer.class);
    }
}
