package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.vocabulary.EntryKind;
import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of {@code graphitron_path_claim}: every place a path is written, by the
 * declared element it is written on and the type it reaches, marked and swept.
 *
 * <p>The claims are the positions {@code graphitron_ast_path_entry} keys its elements under. A
 * {@code @referenceFor} application is written on the declaration that is its parent and reaches the
 * member it names; any other claim is the declaration itself, reaching its target type, the
 * argument's named type for an argument. The kind is read off the declaration's own entry kind, at
 * the position the claim was written, so it does not wait on the element being emitted. Positions
 * are joined only within one file.
 *
 * <p>Two claims on one element reaching one type keep one row: the first written, by the merge
 * order of the declarations they sit in, as {@code graphql_type_declaration} numbers them, then by
 * file and position. Two {@code @referenceFor} on one declaration naming one type are also a defect,
 * which the entry-defect rule reads off the entries rather than off this.
 *
 * <p>Runs after the targets are written and before the paths that follow the claims.
 */
public final class PathClaims {

    private PathClaims() {}

    /** Re-derives the graph's claims and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var a = GRAPHITRON_PATH_CLAIM;
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
        var rank = rowNumber().over(partitionBy(el.COORDINATE, target)
            .orderBy(td.MERGE_ORDINAL.asc().nullsLast(), file.asc(), line.asc(), column.asc()));

        var ranked = dsl
            .select(graph.as("graph_name"), el.COORDINATE.as("element_coordinate"),
                target.as("target_type"), kind.as("claim_kind"), file.as("source_name"),
                line.as("source_line"), column.as("source_column"), rank.as("rn"))
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
            .asTable("ranked");

        var rGraph = ranked.field("graph_name", String.class);
        var rElement = ranked.field("element_coordinate", String.class);
        var rTarget = ranked.field("target_type", String.class);
        var e = GRAPHITRON_ELEMENT;
        var t = GRAPHITRON_TYPE;
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.ELEMENT_COORDINATE, a.TARGET_TYPE, a.CLAIM_KIND,
                a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN, a.TOUCHED_AT,
                a.ELEMENT_GRAPH_REF, a.ELEMENT_COORDINATE_REF, a.TARGET_GRAPH_REF,
                a.TARGET_TYPE_REF)
            .select(dsl
                .select(rGraph, rElement, rTarget, ranked.field("claim_kind", String.class),
                    ranked.field("source_name", String.class),
                    ranked.field("source_line", Integer.class),
                    ranked.field("source_column", Integer.class), val(touchedAt, a.TOUCHED_AT),
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
    }
}
