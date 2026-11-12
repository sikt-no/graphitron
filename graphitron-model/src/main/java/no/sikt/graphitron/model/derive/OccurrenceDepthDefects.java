package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIST_ELEMENT_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIST_ELEMENT_DEFECT_RULE;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The {@code occurrence-depth-defects} stage: the defects judging what {@link OccurrenceDepths} and
 * {@link OccurrenceBuilds} write, a child producer's lift and the key it takes, and the column a
 * field reads or writes, from their rule view. A stage of their own, so neither the depth loop nor
 * the gatherer's last rule carries them. First, the rules each list element occurrence breaks, into
 * {@code graphitron_occurrence_list_element_defect}, which the defect rule groups to the declaration.
 */
public final class OccurrenceDepthDefects {

    private OccurrenceDepthDefects() {}

    /** Writes the stage's defects, marked with this reading. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt) {
        var t = GRAPHITRON_OCCURRENCE_LIST_ELEMENT_DEFECT;
        var r = GRAPHITRON_OCCURRENCE_LIST_ELEMENT_DEFECT_RULE;
        dsl.insertInto(t).columns(t.GRAPH_NAME, t.COORDINATE, t.CODE, t.TOUCHED_AT)
            .select(select(r.GRAPH_NAME, r.COORDINATE, r.CODE, val(readAt, t.TOUCHED_AT))
                .from(r).where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.CODE)
            .doUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph), t.TOUCHED_AT.ne(readAt)).execute();
        EntryDefects.derive(dsl, graph, readAt, EntryDefects.DEPTHS);
    }
}
