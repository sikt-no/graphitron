package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTITY_KEY_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_DEPTH_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.META_STAGE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_entry_defect} from an owner stage's rule view: the defects that
 * stage's facts break, at the positions they were written, as the stage's last statement.
 *
 * <p>A code's owner is a stage of a gatherer's anchor phase, {@code graphitron_defect_type.owner_stage}
 * naming its {@code meta_stage} row. Each owning stage has its own rule view, named in a {@link Rule}
 * beside the stage that runs it, and the writer is one statement over that view; the sweep is the
 * gatherer's, once after its last stage, scoped to the codes its stages own. A stage taking its codes
 * adds its view and a {@link Rule} to {@link #RULES} and calls {@link #derive} as its last statement,
 * touching no other stage's. What this class adds is who evaluates a rule and when.
 *
 * <p>{@link Outboxes} owns the {@code OUTBOX_} codes its relations judge, from
 * {@code graphitron_outbox_entry_defect_rule}. This class's own stage, {@code entry-defects}, the
 * graphitron anchor phase's last step, owns every graphitron code no earlier stage writes itself, from
 * {@code graphitron_entry_defect_rule}. What follows is about that rule.
 *
 * <p><b>Stored because it is read interactively and costs a walk.</b> Most of the rule is cheap,
 * the write arms reading entries the gatherer already anchored. The chain arms were not: they
 * reached a two-sided recursive walk over every chain in the graph, and a per-file predicate cannot
 * push into a recursion behind a view boundary. As a
 * view the whole thing was re-walked on every read, and the read that matters is the editor's, one
 * file's diagnostics on the interactive path. The LSP's scan-count gate is what caught it, and
 * what it measured is recorded with the item's other measurements rather than here, a ceiling
 * being a number that moves.
 *
 * <p>The deciding argument is not the number, though, and would hold at a smaller one: the walk
 * already runs once per capture. {@link Steps} stores every step the walk takes and every place it
 * stops, and writes the walk's own defects as it goes.
 * Computing them again per read is a second evaluation of a rule the capture has already run, which
 * is the shape this whole arc exists to remove rather than a cost to be weighed. The same holds
 * inside this stage: the chain arms read the stored walk rather than walking, because a walk named
 * from a correlated subquery here is walked once per chain it drives.
 */
public final class EntryDefects {

    private EntryDefects() {}

    /**
     * One owner's rule: the {@code meta_stage} row that owns the codes {@code view} emits and runs
     * its writer, and the view, which has {@code graphitron_entry_defect}'s columns but the mark.
     */
    public record Rule(String stage, Table<?> view) {}

    /** The rule of the graphitron anchor phase's last stage, this class. */
    public static final Rule GRAPHITRON = new Rule("entry-defects", GRAPHITRON_ENTRY_DEFECT_RULE);

    /** The outbox stage's rule. */
    public static final Rule OUTBOX = new Rule("outbox", GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE);

    /** The build and decode-shape defects' rule, {@link OccurrenceBuilds}. */
    public static final Rule BUILDS = new Rule("occurrence-builds", GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE);

    /** The lift and column defects' rule, {@link OccurrenceDepthDefects}. */
    public static final Rule DEPTHS = new Rule("occurrence-depth-defects", GRAPHITRON_OCCURRENCE_DEPTH_ENTRY_DEFECT_RULE);

    /** The entity key stage's rule, {@link EntityKeyAlternatives}. */
    public static final Rule ENTITY_KEYS =
        new Rule("entity-key-alternatives", GRAPHITRON_ENTITY_KEY_ENTRY_DEFECT_RULE);

    /** Every owner's rule, one per owner stage. */
    public static final List<Rule> RULES = List.of(ENTITY_KEYS, BUILDS, DEPTHS, OUTBOX, GRAPHITRON);

    /**
     * Writes the graph's rows of {@code rule}'s view, each marked with {@code readAt}: a row already
     * stored under the same position and code takes the reading's mark and detail, a new one is
     * inserted. Nothing is deleted here; {@link #sweep} does that once every writer of the owner
     * has run.
     */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt, Rule rule) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var view = rule.view();
        var source = dsl.select(column(view, "GRAPH_NAME", String.class),
                column(view, "SOURCE_NAME", String.class), column(view, "SOURCE_LINE", Integer.class),
                column(view, "SOURCE_COLUMN", Integer.class), column(view, "CODE", String.class),
                column(view, "DETAIL", String.class))
            .from(view)
            .where(column(view, "GRAPH_NAME", String.class).eq(graphName))
            .asTable("r");
        Field<String> graph = column(source, "GRAPH_NAME", String.class);
        Field<String> file = column(source, "SOURCE_NAME", String.class);
        Field<Integer> line = column(source, "SOURCE_LINE", Integer.class);
        Field<Integer> column = column(source, "SOURCE_COLUMN", Integer.class);
        Field<String> code = column(source, "CODE", String.class);
        Field<String> detail = column(source, "DETAIL", String.class);
        dsl.mergeInto(target).using(source)
            .on(target.GRAPH_NAME.eq(graph), target.SOURCE_NAME.eq(file),
                target.SOURCE_LINE.eq(line), target.SOURCE_COLUMN.eq(column), target.CODE.eq(code))
            .whenMatchedThenUpdate()
            .set(target.DETAIL, detail)
            .set(target.TOUCHED_AT, val(readAt, target.TOUCHED_AT))
            .whenNotMatchedThenInsert(target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE,
                target.SOURCE_COLUMN, target.CODE, target.DETAIL, target.TOUCHED_AT)
            .values(graph, file, line, column, code, detail, val(readAt, target.TOUCHED_AT))
            .execute();
    }

    /**
     * Deletes the graph's defects of the codes {@code gatherer}'s stages own that the reading at
     * {@code readAt} did not mark. Another gatherer's codes are not touched, so each owner sweeps
     * only what it writes; a detail relation's rows under a swept defect go with it by their
     * foreign key. One sweep covers every stage, each having marked its rows with the same instant. A detail row the reading did not mark under a defect it did, a claim,
     * member, key column, table or path step a defect no longer names, is swept here first by its own mark.
     */
    public static void sweep(DSLContext dsl, String graphName, LocalDateTime readAt, String gatherer) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var type = GRAPHITRON_DEFECT_TYPE;
        var owned = select(type.CODE).from(type).join(META_STAGE)
            .on(META_STAGE.STAGE_NAME.eq(type.OWNER_STAGE))
            .where(META_STAGE.GATHERER_NAME.eq(gatherer));
        var claim = GRAPHITRON_ENTRY_DEFECT_CLAIM;
        dsl.deleteFrom(claim)
            .where(claim.GRAPH_NAME.eq(graphName))
            .and(claim.TOUCHED_AT.ne(readAt))
            .and(claim.CODE.in(owned))
            .execute();
        var member = GRAPHITRON_ENTRY_DEFECT_MEMBER;
        dsl.deleteFrom(member)
            .where(member.GRAPH_NAME.eq(graphName))
            .and(member.TOUCHED_AT.ne(readAt))
            .and(member.CODE.in(owned))
            .execute();
        var keyColumn = GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN;
        dsl.deleteFrom(keyColumn)
            .where(keyColumn.GRAPH_NAME.eq(graphName))
            .and(keyColumn.TOUCHED_AT.ne(readAt))
            .and(keyColumn.CODE.in(owned))
            .execute();
        var table = GRAPHITRON_ENTRY_DEFECT_TABLE;
        dsl.deleteFrom(table)
            .where(table.GRAPH_NAME.eq(graphName))
            .and(table.TOUCHED_AT.ne(readAt))
            .and(table.CODE.in(owned))
            .execute();
        var step = GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
        dsl.deleteFrom(step)
            .where(step.GRAPH_NAME.eq(graphName))
            .and(step.TOUCHED_AT.ne(readAt))
            .and(step.CODE.in(owned))
            .execute();
        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(readAt))
            .and(target.CODE.in(owned))
            .execute();
    }

    private static <T> Field<T> column(Table<?> t, String column, Class<T> type) {
        return field(name(t.getName(), column), type);
    }
}
