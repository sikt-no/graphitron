package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK_IMPORT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK_IMPORT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_federation_link} and its imports: whether a graph opts in to
 * federation, and what its link imports, inserted from the two rules for one graph.
 *
 * <p>A step of the graphitron-ast anchor, before the emitted population, whose key mint reads it.
 * One relation for the two ways a graph opts in, the {@code @link} an author writes and the one a
 * {@code <schemaInput tag>} asks for, so a reader asks one question rather than two. Marked and
 * swept: both statements upsert on the reading's instant, and the deletes after them take the
 * imports and the opt-ins this reading no longer derives, imports first since an import of a link
 * that stands has only its own stamp to go by.
 */
final class FederationLinks {

    private FederationLinks() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_FEDERATION_LINK_RULE;
        var t = GRAPHITRON_FEDERATION_LINK;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.ORIGIN, t.VERSION, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.ORIGIN, r.VERSION, r.SOURCE_NAME, r.SOURCE_LINE,
                    r.SOURCE_COLUMN, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.ORIGIN, excluded(t.ORIGIN))
            .set(t.VERSION, excluded(t.VERSION))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        var ir = GRAPHITRON_FEDERATION_LINK_IMPORT_RULE;
        var i = GRAPHITRON_FEDERATION_LINK_IMPORT;
        dsl.insertInto(i)
            .columns(i.GRAPH_NAME, i.ORDINAL, i.NAME, i.ALIAS, i.TOUCHED_AT)
            .select(dsl.select(ir.GRAPH_NAME, ir.ORDINAL, ir.NAME, ir.ALIAS,
                    val(touchedAt, i.TOUCHED_AT))
                .from(ir)
                .where(ir.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(i.NAME, excluded(i.NAME))
            .set(i.ALIAS, excluded(i.ALIAS))
            .set(i.TOUCHED_AT, excluded(i.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(i)
            .where(i.GRAPH_NAME.eq(graph))
            .and(i.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
