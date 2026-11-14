package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.derive.EntryDefects;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT_FIELD;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The writer of {@code graphitron_code_reference}: every written Java code reference, where it sits
 * and what it names, and the one method it names where the graph's classpath answers with exactly
 * one.
 *
 * <p>A step of the graphitron anchor, which runs after the classpath has been read and declares
 * {@code code} upstream of it. Where a reference sits is climbed from the transcription: the value's
 * holder is the applied argument at any depth, the argument's parent the application, and the
 * application's parent the element. The {@code @externalField} default, the field's own name where
 * no method is written, is applied here once, so a rule judging a reference reads it off the row.
 *
 * <p>Keyed by the reference's names, its application and what it was written under in it, so an
 * edit above it in the file changes no row; where it was written rides along for a defect to point
 * at. Last, the stage writes its own defects, a resolution the directive cannot call, from
 * {@code graphitron_code_reference_entry_defect_rule}.
 *
 * <p>The method columns are set only for a settled resolution: the class is the one the graph
 * resolves the name to in {@code code_graph_class}, the first entry on its classpath declaring it,
 * and the count is per reference, so a name two overloads answer carries no method, which the entry
 * defects report. Marked and swept: the statement upserts on the reading's instant and the delete
 * after it takes the references this reading no longer derives.
 */
final class GraphitronCodeReferences {

    private GraphitronCodeReferences() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
        var v = GRAPHQL_AST_VALUE_ENTRY;
        var arg = GRAPHQL_AST_ENTRY.as("arg");
        var da = GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
        var an = GRAPHQL_AST_ENTRY.as("an");
        var el = GRAPHQL_AST_ELEMENT_ENTRY;
        var app = GRAPHQL_DIRECTIVE_APPLICATION;
        var ef = GRAPHQL_ELEMENT_FIELD;
        var k = CODE_GRAPH_CLASS;
        var m = CODE_METHOD;
        var t = GRAPHITRON_CODE_REFERENCE;

        // The names the reference was written under, spelled: the argument, its index in the
        // argument's list, its index in a path below it.
        Field<String> owner = r.ARGUMENT_NAME
            .concat(when(r.ARGUMENT_INDEX.isNotNull(),
                inline(".").concat(r.ARGUMENT_INDEX.cast(String.class))).otherwise(inline("")))
            .concat(when(r.PATH_INDEX.isNotNull(),
                inline(".path.").concat(r.PATH_INDEX.cast(String.class))).otherwise(inline("")));
        Field<String> kind = when(r.ARGUMENT_INDEX.isNull(), inline("ROOT"))
            .when(r.ARGUMENT_NAME.eq("path"), inline("PATH_ELEMENT"))
            .when(r.ARGUMENT_NAME.eq("fields").and(r.PATH_INDEX.isNull()), inline("ORDER_FIELD"))
            .when(r.ARGUMENT_NAME.eq("fields"), inline("ORDER_FIELD_PATH"))
            .when(r.ARGUMENT_NAME.eq("routes"), inline("ROUTE_PATH"));
        // The one site whose omitted method has a default: an @externalField names its field.
        Field<String> namedMethod = coalesce(r.METHOD,
            when(da.NAME.eq("externalField"), ef.FIELD_NAME));

        // Aliased to the target's names, so the resolution below reads each by the column it fills.
        var site = dsl.select(r.GRAPH_NAME, el.COORDINATE, da.NAME.as(t.DIRECTIVE_NAME.getName()),
                app.ORDINAL, owner.as(t.OWNER.getName()), kind.as(t.KIND.getName()),
                r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN, ef.TYPE_NAME, ef.FIELD_NAME,
                r.CLASS_NAME, namedMethod.as(t.NAMED_METHOD.getName()))
            .from(r)
            .join(v).on(v.GRAPH_NAME.eq(r.GRAPH_NAME), v.SOURCE_NAME.eq(r.SOURCE_NAME),
                v.SOURCE_LINE.eq(r.SOURCE_LINE), v.SOURCE_COLUMN.eq(r.SOURCE_COLUMN))
            .join(arg).on(arg.GRAPH_NAME.eq(v.GRAPH_NAME), arg.SOURCE_NAME.eq(v.SOURCE_NAME),
                arg.SOURCE_LINE.eq(v.HOLDER_LINE), arg.SOURCE_COLUMN.eq(v.HOLDER_COLUMN))
            .join(da).on(da.GRAPH_NAME.eq(arg.GRAPH_NAME), da.SOURCE_NAME.eq(arg.SOURCE_NAME),
                da.SOURCE_LINE.eq(arg.PARENT_LINE), da.SOURCE_COLUMN.eq(arg.PARENT_COLUMN))
            .join(an).on(an.GRAPH_NAME.eq(da.GRAPH_NAME), an.SOURCE_NAME.eq(da.SOURCE_NAME),
                an.SOURCE_LINE.eq(da.SOURCE_LINE), an.SOURCE_COLUMN.eq(da.SOURCE_COLUMN))
            .join(el).on(el.GRAPH_NAME.eq(an.GRAPH_NAME), el.SOURCE_NAME.eq(an.SOURCE_NAME),
                el.SOURCE_LINE.eq(an.PARENT_LINE), el.SOURCE_COLUMN.eq(an.PARENT_COLUMN))
            // The application anchored: where it was written names its directive and ordinal.
            .join(app).on(app.GRAPH_NAME.eq(da.GRAPH_NAME), app.SOURCE_NAME.eq(da.SOURCE_NAME),
                app.SOURCE_LINE.eq(da.SOURCE_LINE), app.SOURCE_COLUMN.eq(da.SOURCE_COLUMN))
            .leftJoin(ef).on(ef.GRAPH_NAME.eq(el.GRAPH_NAME), ef.COORDINATE.eq(el.COORDINATE))
            .where(r.GRAPH_NAME.eq(graph))
            .asTable("site");

        // The class the graph resolves the name to, so classpath order settles a name two entries
        // declare and only overloads are left to count: one method is the resolution, none or two
        // leave the method columns NULL.
        var one = count(m.DESCRIPTOR).eq(1);
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.KIND,
                t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TYPE_NAME, t.FIELD_NAME,
                t.CLASS_NAME, t.NAMED_METHOD,
                t.METHOD_SOURCE_NAME, t.METHOD_CLASS_NAME, t.METHOD_NAME, t.METHOD_DESCRIPTOR,
                t.TOUCHED_AT)
            .select(dsl.select(
                    site.field(t.GRAPH_NAME), site.field(t.COORDINATE),
                    site.field(t.DIRECTIVE_NAME), site.field(t.ORDINAL),
                    site.field(t.OWNER), site.field(t.KIND),
                    site.field(t.SOURCE_NAME), site.field(t.SOURCE_LINE),
                    site.field(t.SOURCE_COLUMN), site.field(t.TYPE_NAME),
                    site.field(t.FIELD_NAME), site.field(t.CLASS_NAME),
                    site.field(t.NAMED_METHOD),
                    when(one, max(m.SOURCE_NAME)), when(one, max(m.CLASS_NAME)),
                    when(one, max(m.METHOD_NAME)), when(one, max(m.DESCRIPTOR)),
                    val(touchedAt, t.TOUCHED_AT))
                .from(site)
                .leftJoin(k).on(k.GRAPH_NAME.eq(site.field(t.GRAPH_NAME)),
                    k.CLASS_NAME.eq(site.field(t.CLASS_NAME)))
                .leftJoin(m).on(m.SOURCE_NAME.eq(k.SOURCE_NAME), m.CLASS_NAME.eq(k.CLASS_NAME),
                    m.METHOD_NAME.eq(site.field(t.NAMED_METHOD)))
                .groupBy(site.fields()))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER)
            .doUpdate()
            .set(t.KIND, excluded(t.KIND))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .set(t.FIELD_NAME, excluded(t.FIELD_NAME))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.NAMED_METHOD, excluded(t.NAMED_METHOD))
            .set(t.METHOD_SOURCE_NAME, excluded(t.METHOD_SOURCE_NAME))
            .set(t.METHOD_CLASS_NAME, excluded(t.METHOD_CLASS_NAME))
            .set(t.METHOD_NAME, excluded(t.METHOD_NAME))
            .set(t.METHOD_DESCRIPTOR, excluded(t.METHOD_DESCRIPTOR))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();

        // Last, the stage's own codes: a resolution its directive cannot call.
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.CODE_REFERENCES);
    }
}
