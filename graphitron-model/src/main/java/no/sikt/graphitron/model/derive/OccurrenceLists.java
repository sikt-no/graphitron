package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIST_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIST_ELEMENT_RULE;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.noCondition;

/**
 * The {@code occurrence-lists} stage: which lists each input-field occurrence is read once per
 * element of, into {@code graphitron_occurrence_list_element}, a depth at a time, since an
 * occurrence is read per element of every list its parent is, the depth above's rows. Off the
 * occurrences and the declared elements alone, so it runs before {@link OccurrenceDepths}, whose
 * readers it serves.
 */
public final class OccurrenceLists {

    private OccurrenceLists() {}

    /** Writes every depth's rows of {@code graph}, then sweeps what no depth marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        Integer deepest = dsl.select(max(o.DEPTH)).from(o)
            .where(o.GRAPH_NAME.eq(graph)).and(o.TOUCHED_AT.eq(touchedAt))
            .fetchOne(0, Integer.class);
        // An input field sits at depth 1 at the shallowest, under its argument.
        if (deepest != null) {
            for (int depth = 1; depth <= deepest; depth++) {
                OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_LIST_ELEMENT,
                    GRAPHITRON_OCCURRENCE_LIST_ELEMENT_RULE, graph, depth, touchedAt, noCondition());
            }
        }
        var l = GRAPHITRON_OCCURRENCE_LIST_ELEMENT;
        dsl.deleteFrom(l)
            .where(l.GRAPH_NAME.eq(graph))
            .and(l.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
