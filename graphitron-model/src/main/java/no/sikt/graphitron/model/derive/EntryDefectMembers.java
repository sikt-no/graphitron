package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The members a polymorphic {@code @nodeId}'s defect names, written under it in
 * {@code graphitron_entry_defect_member} from the rule view beside it. The defect itself is a row of
 * the graphitron rule, which {@link EntryDefects} writes just before this runs in the graphitron
 * gatherer's anchor phase; this reads the rows that reading marked and marks its own with the same
 * instant, and {@link EntryDefects#sweep} takes what the reading did not mark.
 */
public final class EntryDefectMembers {

    private EntryDefectMembers() {}

    /** Writes the members under each of the graph's member defects the reading marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var r = GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE;
        var t = GRAPHITRON_ENTRY_DEFECT_MEMBER;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE,
                t.MEMBER_TYPE_NAME, t.TOUCHED_AT)
            .select(select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN, r.CODE,
                    r.MEMBER_TYPE_NAME, val(readAt, t.TOUCHED_AT))
                .from(r)
                .join(d).on(d.GRAPH_NAME.eq(r.GRAPH_NAME), d.SOURCE_NAME.eq(r.SOURCE_NAME),
                    d.SOURCE_LINE.eq(r.SOURCE_LINE), d.SOURCE_COLUMN.eq(r.SOURCE_COLUMN),
                    d.CODE.eq(r.CODE))
                .where(r.GRAPH_NAME.eq(graph), d.TOUCHED_AT.eq(readAt)))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE,
                t.MEMBER_TYPE_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .execute();
    }
}
