package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE_SITE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
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
 * <p>A step of the graphitron-ast anchor, which runs after the classpath has been read and declares
 * {@code code} upstream of it. What a reference names is read off
 * {@code graphitron_code_reference_site}, which states the {@code @externalField} default once for
 * this and for the defects that report what does not resolve.
 *
 * <p>Only a settled resolution is a row: the count is per reference, and a name two overloads or two
 * classpath entries answer draws none, which the anti-join reports. Marked and swept: the statement
 * upserts on the reading's instant and the delete after it takes the references this reading no
 * longer resolves.
 */
final class GraphitronCodeReferences {

    private GraphitronCodeReferences() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var s = GRAPHITRON_CODE_REFERENCE_SITE;
        var gs = STORE_GRAPH_SOURCE;
        var m = CODE_METHOD;
        var t = GRAPHITRON_CODE_REFERENCE;

        // Aliased to the target's names: the written position and the method each carry a
        // source_name, one a file and one a classpath entry.
        var resolved = dsl.select(s.GRAPH_NAME, s.SOURCE_NAME, s.SOURCE_LINE, s.SOURCE_COLUMN,
                m.SOURCE_NAME.as(t.METHOD_SOURCE_NAME.getName()),
                m.CLASS_NAME.as(t.METHOD_CLASS_NAME.getName()), m.METHOD_NAME,
                m.DESCRIPTOR.as(t.METHOD_DESCRIPTOR.getName()),
                count().over(partitionBy(s.GRAPH_NAME, s.SOURCE_NAME, s.SOURCE_LINE,
                    s.SOURCE_COLUMN)).as("candidates"))
            .from(s)
            .join(gs).on(gs.GRAPH_NAME.eq(s.GRAPH_NAME))
            .join(m).on(m.SOURCE_NAME.eq(gs.SOURCE_NAME), m.CLASS_NAME.eq(s.CLASS_NAME),
                m.METHOD_NAME.eq(s.METHOD_NAME))
            .where(s.GRAPH_NAME.eq(graph))
            .asTable("resolved");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.METHOD_SOURCE_NAME, t.METHOD_CLASS_NAME, t.METHOD_NAME, t.METHOD_DESCRIPTOR,
                t.TOUCHED_AT)
            .select(dsl.select(
                    resolved.field(s.GRAPH_NAME), resolved.field(s.SOURCE_NAME),
                    resolved.field(s.SOURCE_LINE), resolved.field(s.SOURCE_COLUMN),
                    resolved.field(t.METHOD_SOURCE_NAME.getName(), String.class),
                    resolved.field(t.METHOD_CLASS_NAME.getName(), String.class),
                    resolved.field(m.METHOD_NAME),
                    resolved.field(t.METHOD_DESCRIPTOR.getName(), String.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(resolved)
                .where(field(name("resolved", "candidates"), Integer.class).eq(1)))
            .onDuplicateKeyUpdate()
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
    }
}
