package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTITY_KEY_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_DEPTH_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.META_STAGE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_entry_defect} from a stage's rule view: the defects that stage's
 * facts break, at the positions they were written, as the stage's last statement, each row keyed by
 * the stage that raised it.
 *
 * <p>Nobody owns a code: a rule is raised by the first stage holding its facts, and two stages may
 * raise one code, each its own rows. Each raising stage has its own rule view, whose rows name the
 * stage, given in a {@link Rule} beside the stage that runs it, and the writer is one statement over
 * that view; the sweep is the gatherer's, once after its last stage, taking the rows its stages raised.
 * A stage raising defects adds its view and a {@link Rule} to {@link #RULES} and calls {@link #derive}
 * as its last statement, touching no other stage's rows. What this class adds is who evaluates a rule
 * and when.
 *
 * <p>{@link Outboxes} raises the {@code OUTBOX_} codes its relations judge, from
 * {@code graphitron_outbox_entry_defect_rule}. This class's own stage, {@code entry-defects}, the
 * graphitron anchor phase's last step, raises every graphitron code no earlier stage raises itself,
 * from {@code graphitron_entry_defect_rule}. What follows is about that rule.
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
     * One stage's rule: the {@code meta_stage} row that raises what {@code view} emits and runs its
     * writer, and the view, which has {@code graphitron_entry_defect}'s columns but the mark, its
     * stage column naming that stage.
     */
    public record Rule(String stage, Table<?> view) {}

    /** The rule of the graphitron anchor phase's last stage, this class. */
    public static final Rule GRAPHITRON = new Rule("entry-defects", GRAPHITRON_ENTRY_DEFECT_RULE);

    /** The outbox stage's rule. */
    public static final Rule OUTBOX = new Rule("outbox", GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE);

    /** The node id binding defects' rule, {@link OccurrenceBindings}. */
    public static final Rule BINDINGS =
        new Rule("occurrence-bindings", GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE);

    /** The build and decode-shape defects' rule, {@link OccurrenceBuilds}. */
    public static final Rule BUILDS = new Rule("occurrence-builds", GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE);

    /** The lift and column defects' rule, {@link OccurrenceDepthDefects}. */
    public static final Rule DEPTHS = new Rule("occurrence-depth-defects", GRAPHITRON_OCCURRENCE_DEPTH_ENTRY_DEFECT_RULE);

    /** The entity key stage's rule, {@link EntityKeyAlternatives}. */
    public static final Rule ENTITY_KEYS =
        new Rule("entity-key-alternatives", GRAPHITRON_ENTITY_KEY_ENTRY_DEFECT_RULE);

    /** The minted-keys stage's rule, {@link MintedKeys}. */
    public static final Rule MINTED_KEYS = new Rule("minted-keys", GRAPHITRON_MINTED_KEY_ENTRY_DEFECT_RULE);

    /** The voucher stage's rule, {@link Vouchers}. */
    public static final Rule VOUCHERS = new Rule("vouchers", GRAPHITRON_VOUCHER_ENTRY_DEFECT_RULE);

    /** Every raising stage's rule, one per stage. */
    public static final List<Rule> RULES = List.of(VOUCHERS, MINTED_KEYS, ENTITY_KEYS, BINDINGS, BUILDS, DEPTHS, OUTBOX, GRAPHITRON);

    /**
     * Writes the graph's rows of {@code rule}'s view, each marked with {@code readAt}: a row already
     * stored under the same position, code and stage takes the reading's mark and detail, a new one is
     * inserted. Nothing is deleted here; {@link #sweep} does that once every stage of the gatherer
     * has run.
     */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt, Rule rule) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var view = rule.view();
        var source = dsl.select(column(view, "GRAPH_NAME", String.class),
                column(view, "SOURCE_NAME", String.class), column(view, "SOURCE_LINE", Integer.class),
                column(view, "SOURCE_COLUMN", Integer.class), column(view, "CODE", String.class),
                column(view, "STAGE", String.class), column(view, "DETAIL", String.class))
            .from(view)
            .where(column(view, "GRAPH_NAME", String.class).eq(graphName))
            .asTable("r");
        Field<String> graph = column(source, "GRAPH_NAME", String.class);
        Field<String> file = column(source, "SOURCE_NAME", String.class);
        Field<Integer> line = column(source, "SOURCE_LINE", Integer.class);
        Field<Integer> column = column(source, "SOURCE_COLUMN", Integer.class);
        Field<String> code = column(source, "CODE", String.class);
        Field<String> stage = column(source, "STAGE", String.class);
        Field<String> detail = column(source, "DETAIL", String.class);
        dsl.mergeInto(target).using(source)
            .on(target.GRAPH_NAME.eq(graph), target.SOURCE_NAME.eq(file),
                target.SOURCE_LINE.eq(line), target.SOURCE_COLUMN.eq(column), target.CODE.eq(code),
                target.STAGE.eq(stage))
            .whenMatchedThenUpdate()
            .set(target.DETAIL, detail)
            .set(target.TOUCHED_AT, val(readAt, target.TOUCHED_AT))
            .whenNotMatchedThenInsert(target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE,
                target.SOURCE_COLUMN, target.CODE, target.STAGE, target.DETAIL, target.TOUCHED_AT)
            .values(graph, file, line, column, code, stage, detail, val(readAt, target.TOUCHED_AT))
            .execute();
    }

    /**
     * Deletes the graph's defects {@code gatherer}'s stages raised that the reading at {@code readAt}
     * did not mark, each stage's by the stage the row names. Another gatherer's rows are not touched,
     * so each stage sweeps only what it raised; a detail relation's rows under a swept defect go with
     * it by their foreign key. One sweep covers every stage, each having marked its rows with the same
     * instant. A detail row the reading did not mark under a defect it did, a claim, member, key
     * column, table or path step a defect no longer names, is swept here first by its own mark, under
     * the stage that wrote it.
     */
    public static void sweep(DSLContext dsl, String graphName, LocalDateTime readAt, String gatherer) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var owned = select(META_STAGE.STAGE_NAME).from(META_STAGE)
            .where(META_STAGE.GATHERER_NAME.eq(gatherer));
        var claim = GRAPHITRON_ENTRY_DEFECT_CLAIM;
        dsl.deleteFrom(claim)
            .where(claim.GRAPH_NAME.eq(graphName))
            .and(claim.TOUCHED_AT.ne(readAt))
            .and(claim.STAGE.in(owned))
            .execute();
        var member = GRAPHITRON_ENTRY_DEFECT_MEMBER;
        dsl.deleteFrom(member)
            .where(member.GRAPH_NAME.eq(graphName))
            .and(member.TOUCHED_AT.ne(readAt))
            .and(member.STAGE.in(owned))
            .execute();
        var keyColumn = GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN;
        dsl.deleteFrom(keyColumn)
            .where(keyColumn.GRAPH_NAME.eq(graphName))
            .and(keyColumn.TOUCHED_AT.ne(readAt))
            .and(keyColumn.STAGE.in(owned))
            .execute();
        var table = GRAPHITRON_ENTRY_DEFECT_TABLE;
        dsl.deleteFrom(table)
            .where(table.GRAPH_NAME.eq(graphName))
            .and(table.TOUCHED_AT.ne(readAt))
            .and(table.STAGE.in(owned))
            .execute();
        var step = GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
        dsl.deleteFrom(step)
            .where(step.GRAPH_NAME.eq(graphName))
            .and(step.TOUCHED_AT.ne(readAt))
            .and(step.STAGE.in(owned))
            .execute();
        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(readAt))
            .and(target.STAGE.in(owned))
            .execute();
    }

    private static <T> Field<T> column(Table<?> t, String column, Class<T> type) {
        return field(name(t.getName(), column), type);
    }
}
