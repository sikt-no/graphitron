package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_COLUMN_RULE;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The columns a defect names, written under it in {@code graphitron_entry_defect_column} from the rule
 * view beside the stage raising it: the columns a validating service's record reads through no getter,
 * its {@code VALIDATION_COLUMN_UNCHECKED} a row of {@link EntryDefects#PARAMETER_BUILDS}. {@link EntryDefects}
 * writes the defects just before this runs in their stage; this reads the rows that reading marked and
 * marks its own with the same instant, and {@link EntryDefects#sweep} takes what the reading did not
 * mark.
 */
public final class EntryDefectColumns {

    private EntryDefectColumns() {}

    /**
     * Writes the unchecked columns under each of the graph's {@code VALIDATION_COLUMN_UNCHECKED}
     * defects the reading marked in {@code stage}, the stage that raised them and runs this.
     */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt, String stage) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var r = GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_COLUMN_RULE;
        var t = GRAPHITRON_ENTRY_DEFECT_COLUMN;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.COLUMN_NAME, t.TOUCHED_AT)
            .select(select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN, r.CODE, val(stage, t.STAGE),
                    r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.COLUMN_NAME, val(readAt, t.TOUCHED_AT))
                .from(r)
                .join(d).on(d.GRAPH_NAME.eq(r.GRAPH_NAME), d.SOURCE_NAME.eq(r.SOURCE_NAME),
                    d.SOURCE_LINE.eq(r.SOURCE_LINE), d.SOURCE_COLUMN.eq(r.SOURCE_COLUMN),
                    d.CODE.eq(r.CODE), d.STAGE.eq(stage))
                .where(r.GRAPH_NAME.eq(graph), d.TOUCHED_AT.eq(readAt)))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.COLUMN_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .execute();
    }
}
