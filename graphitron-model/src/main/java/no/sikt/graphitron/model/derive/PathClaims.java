package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.vocabulary.EntryKind;
import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_FIRST_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_FIRST_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TENANT_FAN_OUT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TENANT_FAN_OUT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_KEY_MOVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_KEY_MOVE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of {@code graphitron_join_path_claim}: every place a path is written, by the
 * declared element it is written on and the type it reaches, marked and swept.
 *
 * <p>The claims are the positions {@code graphitron_ast_path_entry} keys its elements under. A
 * {@code @referenceFor} application is written on the declaration that is its parent and reaches the
 * member it names; any other claim is the declaration itself, reaching its target type, the
 * argument's named type for an argument. The kind is read off the declaration's own entry kind, at
 * the position the claim was written, so it does not wait on the element being emitted. Positions
 * are joined only within one file.
 *
 * <p>Each claim states the places its path spans: the last written under its position, and the first,
 * which is 1 where place 0 is a root field's leading {@code @routine}, that routine being the
 * field's producer's call and its table the field's source, so not a step. A claim whose last place
 * is below its first writes no path, which is a root field writing only its leading routine.
 *
 * <p>Two claims on one element reaching one type keep one row: the first written, by the merge
 * order of the declarations they sit in, as {@code graphql_type_declaration} numbers them, then by
 * file and position. Two {@code @referenceFor} on one declaration naming one type are also a defect,
 * which the entry-defect rule reads off the entries rather than off this.
 *
 * <p>Beside the claims, the key moves their written elements offer, {@code graphitron_join_path_key_move}:
 * which way each written foreign key is ridden, judged once for every departure a walk may stand at;
 * and off those and the catalog, the key each output field's path starts with from its declaring type's
 * table, {@code graphitron_field_first_key}.
 *
 * <p>Runs after the targets are written and before the paths that follow the claims. Last, the
 * defects the claims judge, {@link EntryDefects#PATH_CLAIMS}, and the claims and steps under them.
 */
public final class PathClaims {

    private PathClaims() {}

    /** Re-derives the graph's claims and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var a = GRAPHITRON_JOIN_PATH_CLAIM;
        var p = GRAPHITRON_AST_PATH_ENTRY;
        var positions = selectDistinct(p.GRAPH_NAME, p.SOURCE_NAME, p.ANCHOR_LINE, p.ANCHOR_COLUMN)
            .from(p)
            .where(p.GRAPH_NAME.eq(graphName))
            .asTable("pos");
        var graph = positions.field(p.GRAPH_NAME);
        var file = positions.field(p.SOURCE_NAME);
        var line = positions.field(p.ANCHOR_LINE);
        var column = positions.field(p.ANCHOR_COLUMN);

        var rf = GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
        var at = GRAPHQL_AST_ENTRY.as("at");
        var el = GRAPHQL_AST_ELEMENT_ENTRY;
        var declared = GRAPHQL_AST_ENTRY.as("declared");
        var td = GRAPHQL_TYPE_DECLARATION;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var arg = GRAPHITRON_ARGUMENT;

        // A @referenceFor is written on its parent declaration; any other claim is the declaration.
        var declarationLine = when(rf.SOURCE_LINE.isNotNull(), at.PARENT_LINE).otherwise(line);
        var declarationColumn = when(rf.SOURCE_LINE.isNotNull(), at.PARENT_COLUMN).otherwise(column);
        var kind = when(rf.SOURCE_LINE.isNotNull(), val("REFERENCE_FOR"))
            .when(declared.ENTRY_KIND.eq(EntryKind.FIELD_DEFINITION), val("FIELD"))
            .when(declared.ENTRY_KIND.eq(EntryKind.FIELD_ARGUMENT), val("ARGUMENT"))
            .when(declared.ENTRY_KIND.eq(EntryKind.INPUT_FIELD), val("INPUT_FIELD"));
        var target = coalesce(rf.PARTICIPANT_TYPE_REF, ft.TARGET_TYPE_NAME, arg.NAMED_TYPE);
        // The places under the claim's position, and whether place 0 is a root field's leading routine.
        var span = GRAPHITRON_AST_PATH_ENTRY.as("span");
        var lastPlace = field(select(max(span.PLACE)).from(span)
            .where(span.GRAPH_NAME.eq(graph), span.SOURCE_NAME.eq(file), span.ANCHOR_LINE.eq(line),
                span.ANCHOR_COLUMN.eq(column)));
        var ro = GRAPHQL_ROOT_OPERATION;
        var lead = GRAPHITRON_AST_PATH_ENTRY.as("lead");
        var re = GRAPHITRON_AST_ROUTINE_ENTRY;
        var firstPlace = when(rf.SOURCE_LINE.isNull()
                .and(declared.ENTRY_KIND.eq(EntryKind.FIELD_DEFINITION))
                .and(exists(selectOne().from(ro)
                    .where(ro.GRAPH_NAME.eq(f.GRAPH_NAME), ro.TYPE_NAME.eq(f.TYPE_NAME))))
                .and(exists(selectOne().from(lead)
                    .join(re).on(re.GRAPH_NAME.eq(lead.GRAPH_NAME), re.SOURCE_NAME.eq(lead.SOURCE_NAME),
                        re.SOURCE_LINE.eq(lead.ELEMENT_LINE), re.SOURCE_COLUMN.eq(lead.ELEMENT_COLUMN))
                    .where(lead.GRAPH_NAME.eq(graph), lead.SOURCE_NAME.eq(file), lead.ANCHOR_LINE.eq(line),
                        lead.ANCHOR_COLUMN.eq(column), lead.PLACE.eq(0)))), inline(1))
            .otherwise(inline(0));
        // Whether the element reads one row: a field's as its target states it, an argument holding one
        // value or a scalar or enum.
        var argType = GRAPHITRON_TYPE.as("argtype");
        var singleValued = coalesce(ft.SINGLE_VALUED,
            when(arg.COORDINATE.isNotNull().and(argType.KIND.isNotNull()),
                field(arg.IS_LIST.isFalse().or(argType.KIND.in("SCALAR", "ENUM")))));
        var rank = rowNumber().over(partitionBy(el.COORDINATE, target)
            .orderBy(td.MERGE_ORDINAL.asc().nullsLast(), file.asc(), line.asc(), column.asc()));

        var ranked = dsl
            .select(graph.as("graph_name"), el.COORDINATE.as("element_coordinate"),
                target.as("target_type"), kind.as("claim_kind"), file.as("source_name"),
                line.as("source_line"), column.as("source_column"), firstPlace.as("first_place"),
                lastPlace.as("last_place"), singleValued.as("single_valued"), rank.as("rn"))
            .from(positions)
            .join(at).on(at.GRAPH_NAME.eq(graph), at.SOURCE_NAME.eq(file),
                at.SOURCE_LINE.eq(line), at.SOURCE_COLUMN.eq(column))
            .leftJoin(rf).on(rf.GRAPH_NAME.eq(graph), rf.SOURCE_NAME.eq(file),
                rf.SOURCE_LINE.eq(line), rf.SOURCE_COLUMN.eq(column))
            .join(el).on(el.GRAPH_NAME.eq(graph), el.SOURCE_NAME.eq(file),
                el.SOURCE_LINE.eq(declarationLine), el.SOURCE_COLUMN.eq(declarationColumn))
            .join(declared).on(declared.GRAPH_NAME.eq(el.GRAPH_NAME),
                declared.SOURCE_NAME.eq(el.SOURCE_NAME), declared.SOURCE_LINE.eq(el.SOURCE_LINE),
                declared.SOURCE_COLUMN.eq(el.SOURCE_COLUMN))
            .leftJoin(td).on(td.GRAPH_NAME.eq(el.GRAPH_NAME), td.SOURCE_NAME.eq(el.SOURCE_NAME),
                td.SOURCE_LINE.eq(el.DECLARATION_LINE), td.SOURCE_COLUMN.eq(el.DECLARATION_COLUMN))
            .leftJoin(f).on(f.GRAPH_NAME.eq(el.GRAPH_NAME), f.COORDINATE.eq(el.COORDINATE))
            .leftJoin(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(arg).on(arg.GRAPH_NAME.eq(el.GRAPH_NAME), arg.COORDINATE.eq(el.COORDINATE))
            .leftJoin(argType).on(argType.GRAPH_NAME.eq(arg.GRAPH_NAME), argType.TYPE_NAME.eq(arg.NAMED_TYPE))
            .asTable("ranked");

        var rGraph = ranked.field("graph_name", String.class);
        var rElement = ranked.field("element_coordinate", String.class);
        var rTarget = ranked.field("target_type", String.class);
        var e = GRAPHITRON_ELEMENT;
        var t = GRAPHITRON_TYPE;
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.ELEMENT_COORDINATE, a.TARGET_TYPE, a.CLAIM_KIND,
                a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN, a.FIRST_PLACE, a.LAST_PLACE, a.SINGLE_VALUED,
                a.TOUCHED_AT,
                a.ELEMENT_GRAPH_REF, a.ELEMENT_COORDINATE_REF, a.TARGET_GRAPH_REF,
                a.TARGET_TYPE_REF)
            .select(dsl
                .select(rGraph, rElement, rTarget, ranked.field("claim_kind", String.class),
                    ranked.field("source_name", String.class),
                    ranked.field("source_line", Integer.class),
                    ranked.field("source_column", Integer.class),
                    ranked.field("first_place", Integer.class), ranked.field("last_place", Integer.class),
                    ranked.field("single_valued", Boolean.class), val(touchedAt, a.TOUCHED_AT),
                    e.GRAPH_NAME, e.COORDINATE, t.GRAPH_NAME, t.TYPE_NAME)
                .from(ranked)
                .leftJoin(e).on(e.GRAPH_NAME.eq(rGraph), e.COORDINATE.eq(rElement))
                .leftJoin(t).on(t.GRAPH_NAME.eq(rGraph), t.TYPE_NAME.eq(rTarget))
                .where(ranked.field("rn", Integer.class).eq(1))
                .and(rTarget.isNotNull())
                .and(ranked.field("claim_kind", String.class).isNotNull()))
            .onDuplicateKeyUpdate()
            .set(a.CLAIM_KIND, excluded(a.CLAIM_KIND))
            .set(a.SOURCE_NAME, excluded(a.SOURCE_NAME))
            .set(a.SOURCE_LINE, excluded(a.SOURCE_LINE))
            .set(a.SOURCE_COLUMN, excluded(a.SOURCE_COLUMN))
            .set(a.FIRST_PLACE, excluded(a.FIRST_PLACE))
            .set(a.LAST_PLACE, excluded(a.LAST_PLACE))
            .set(a.SINGLE_VALUED, excluded(a.SINGLE_VALUED))
            .set(a.TOUCHED_AT, excluded(a.TOUCHED_AT))
            .set(a.ELEMENT_GRAPH_REF, excluded(a.ELEMENT_GRAPH_REF))
            .set(a.ELEMENT_COORDINATE_REF, excluded(a.ELEMENT_COORDINATE_REF))
            .set(a.TARGET_GRAPH_REF, excluded(a.TARGET_GRAPH_REF))
            .set(a.TARGET_TYPE_REF, excluded(a.TARGET_TYPE_REF))
            .execute();
        dsl.deleteFrom(a)
            .where(a.GRAPH_NAME.eq(graphName))
            .and(a.TOUCHED_AT.ne(touchedAt))
            .execute();
        keyMoves(dsl, graphName, touchedAt);
        firstKeys(dsl, graphName, touchedAt);
        tenantFanOuts(dsl, graphName, touchedAt);
        EntryDefects.derive(dsl, graphName, touchedAt, EntryDefects.PATH_CLAIMS);
        EntryDefectClaims.derive(dsl, graphName, touchedAt, EntryDefects.PATH_CLAIMS.stage());
    }

    /** The key each output field's path starts with from its declaring type's table, off {@code graphitron_field_first_key_rule}, marked and swept. */
    private static void firstKeys(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var k = GRAPHITRON_FIELD_FIRST_KEY;
        var r = GRAPHITRON_FIELD_FIRST_KEY_RULE;
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.ELEMENT_COORDINATE, k.TARGET_TYPE, k.ORIGIN, k.CONSTRAINT_SOURCE_NAME,
                k.CONSTRAINT_SCHEMA, k.CONSTRAINT_TABLE, k.CONSTRAINT_NAME, k.FK_ON_FROM, k.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.ELEMENT_COORDINATE, r.TARGET_TYPE, r.ORIGIN,
                    r.CONSTRAINT_SOURCE_NAME, r.CONSTRAINT_SCHEMA, r.CONSTRAINT_TABLE, r.CONSTRAINT_NAME,
                    r.FK_ON_FROM, val(touchedAt, k.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(k.ORIGIN, excluded(k.ORIGIN))
            .set(k.CONSTRAINT_SOURCE_NAME, excluded(k.CONSTRAINT_SOURCE_NAME))
            .set(k.CONSTRAINT_SCHEMA, excluded(k.CONSTRAINT_SCHEMA))
            .set(k.CONSTRAINT_TABLE, excluded(k.CONSTRAINT_TABLE))
            .set(k.CONSTRAINT_NAME, excluded(k.CONSTRAINT_NAME))
            .set(k.FK_ON_FROM, excluded(k.FK_ON_FROM))
            .set(k.TOUCHED_AT, excluded(k.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graphName))
            .and(k.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** The fields whose first key fans out by the parent row's tenant, off {@code graphitron_field_tenant_fan_out_rule}, marked and swept. */
    private static void tenantFanOuts(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_FIELD_TENANT_FAN_OUT;
        var r = GRAPHITRON_FIELD_TENANT_FAN_OUT_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.ELEMENT_COORDINATE, t.TARGET_TYPE, t.CONSTRAINT_SOURCE_NAME,
                t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE, t.CONSTRAINT_NAME, t.PARENT_COLUMN, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.ELEMENT_COORDINATE, r.TARGET_TYPE, r.CONSTRAINT_SOURCE_NAME,
                    r.CONSTRAINT_SCHEMA, r.CONSTRAINT_TABLE, r.CONSTRAINT_NAME, r.PARENT_COLUMN,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** The key moves each claim's written elements offer, off {@code graphitron_join_path_key_move_rule}, marked and swept. */
    private static void keyMoves(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var m = GRAPHITRON_JOIN_PATH_KEY_MOVE;
        var r = GRAPHITRON_JOIN_PATH_KEY_MOVE_RULE;
        dsl.insertInto(m)
            .columns(m.GRAPH_NAME, m.CLAIM_COORDINATE, m.CLAIM_TARGET_TYPE, m.POSITION,
                m.CONSTRAINT_SOURCE_NAME, m.CONSTRAINT_SCHEMA, m.CONSTRAINT_TABLE, m.CONSTRAINT_NAME,
                m.FK_ON_FROM, m.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.CLAIM_COORDINATE, r.CLAIM_TARGET_TYPE, r.POSITION,
                    r.CONSTRAINT_SOURCE_NAME, r.CONSTRAINT_SCHEMA, r.CONSTRAINT_TABLE, r.CONSTRAINT_NAME,
                    r.FK_ON_FROM, val(touchedAt, m.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(m.TOUCHED_AT, excluded(m.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(m)
            .where(m.GRAPH_NAME.eq(graphName))
            .and(m.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
