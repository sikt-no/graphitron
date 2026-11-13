package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The members a per-member defect names, written under it in {@code graphitron_entry_defect_member}
 * from the member rule of the stage owning the defect: a polymorphic {@code @nodeId}'s,
 * occurrence-builds', and a {@code @discriminate}'s, entry-defects'. Each stage's defects are written
 * by {@link EntryDefects} just before this runs in that stage; this reads the rows that reading marked
 * and marks its own with the same instant, and {@link EntryDefects#sweep} takes what the reading did
 * not mark.
 */
public final class EntryDefectMembers {

    private EntryDefectMembers() {}

    /** occurrence-builds' member rule, the polymorphic {@code @nodeId}'s members. */
    public static final Table<?> BUILDS = GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE;

    /** entry-defects' member rule, the {@code @discriminate}'s members. */
    public static final Table<?> GRAPHITRON = GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE;

    /**
     * Writes the members {@code rule} names under each of the graph's defects {@code stage} raised that
     * the reading marked, under that stage, the one this runs in.
     */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt, Table<?> rule, String stage) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var t = GRAPHITRON_ENTRY_DEFECT_MEMBER;
        Field<String> graphName = rule.field(t.GRAPH_NAME.getName(), String.class);
        Field<String> file = rule.field(t.SOURCE_NAME.getName(), String.class);
        Field<Integer> line = rule.field(t.SOURCE_LINE.getName(), Integer.class);
        Field<Integer> column = rule.field(t.SOURCE_COLUMN.getName(), Integer.class);
        Field<String> code = rule.field(t.CODE.getName(), String.class);
        Field<String> member = rule.field(t.MEMBER_TYPE_NAME.getName(), String.class);
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.MEMBER_TYPE_NAME, t.TOUCHED_AT)
            .select(select(graphName, file, line, column, code, val(stage, t.STAGE), member,
                    val(readAt, t.TOUCHED_AT))
                .from(rule)
                .join(d).on(d.GRAPH_NAME.eq(graphName), d.SOURCE_NAME.eq(file),
                    d.SOURCE_LINE.eq(line), d.SOURCE_COLUMN.eq(column), d.CODE.eq(code),
                    d.STAGE.eq(stage))
                .where(graphName.eq(graph), d.TOUCHED_AT.eq(readAt)))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CODE, t.STAGE,
                t.MEMBER_TYPE_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .execute();
    }
}
