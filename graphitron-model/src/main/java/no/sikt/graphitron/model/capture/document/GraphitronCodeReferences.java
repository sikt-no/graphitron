package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.derive.EntryDefects;
import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE_SITE;
import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_code_reference}: the one method each written Java code reference
 * names, where the graph's classpath answers with exactly one.
 *
 * <p>A step of the graphitron anchor, which runs after the classpath has been read and declares
 * {@code code} upstream of it. What a reference names is read off
 * {@code graphitron_code_reference_site}, which states the {@code @externalField} default once for
 * this and for the defects that report what does not resolve.
 *
 * <p>Keyed by the reference's names, its application and what it was written under in it, so an
 * edit above it in the file changes no row; where it was written rides along for a defect to point
 * at. Last, the stage writes its own defects, a resolution the directive cannot call, from
 * {@code graphitron_code_reference_entry_defect_rule}.
 *
 * <p>Only a settled resolution is a row: the class is the one the graph resolves the name to in
 * {@code code_graph_class}, the first entry on its classpath declaring it, and the count is per
 * reference, so a name two overloads answer draws none, which the anti-join reports. Marked and swept: the statement
 * upserts on the reading's instant and the delete after it takes the references this reading no
 * longer resolves.
 */
final class GraphitronCodeReferences {

    private GraphitronCodeReferences() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var s = GRAPHITRON_CODE_REFERENCE_SITE;
        var v = CODE_GRAPH_CLASS;
        var m = CODE_METHOD;
        var t = GRAPHITRON_CODE_REFERENCE;

        // Aliased to the target's names: the written position and the method each carry a
        // source_name, one a file and one a classpath entry.
        var resolved = dsl.select(s.GRAPH_NAME, s.COORDINATE, s.DIRECTIVE_NAME, s.ORDINAL, s.OWNER,
                s.KIND, s.SOURCE_NAME, s.SOURCE_LINE, s.SOURCE_COLUMN,
                m.SOURCE_NAME.as(t.METHOD_SOURCE_NAME.getName()),
                m.CLASS_NAME.as(t.METHOD_CLASS_NAME.getName()), m.METHOD_NAME,
                m.DESCRIPTOR.as(t.METHOD_DESCRIPTOR.getName()),
                count().over(partitionBy(s.GRAPH_NAME, s.COORDINATE, s.DIRECTIVE_NAME, s.ORDINAL,
                    s.OWNER)).as("candidates"))
            .from(s)
            // The class the graph resolves the name to, so classpath order settles a name two
            // entries declare and only overloads are left to count.
            .join(v).on(v.GRAPH_NAME.eq(s.GRAPH_NAME), v.CLASS_NAME.eq(s.CLASS_NAME))
            .join(m).on(m.SOURCE_NAME.eq(v.SOURCE_NAME), m.CLASS_NAME.eq(v.CLASS_NAME),
                m.METHOD_NAME.eq(s.METHOD_NAME))
            .where(s.GRAPH_NAME.eq(graph))
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.KIND,
                t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.METHOD_SOURCE_NAME, t.METHOD_CLASS_NAME, t.METHOD_NAME, t.METHOD_DESCRIPTOR,
                t.TOUCHED_AT)
            .select(dsl.select(
                    resolved.field(s.GRAPH_NAME), resolved.field(s.COORDINATE),
                    resolved.field(s.DIRECTIVE_NAME), resolved.field(s.ORDINAL),
                    resolved.field(s.OWNER), resolved.field(s.KIND),
                    resolved.field(s.SOURCE_NAME),
                    resolved.field(s.SOURCE_LINE), resolved.field(s.SOURCE_COLUMN),
                    resolved.field(t.METHOD_SOURCE_NAME.getName(), String.class),
                    resolved.field(t.METHOD_CLASS_NAME.getName(), String.class),
                    resolved.field(m.METHOD_NAME),
                    resolved.field(t.METHOD_DESCRIPTOR.getName(), String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(field(name("resolved", "candidates"), Integer.class).eq(1)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER)
            .doUpdate()
            .set(t.KIND, excluded(t.KIND))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
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
