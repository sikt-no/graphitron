package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_RULE;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.select;

/**
 * The graphitron gatherer's writer of {@code graphitron_entry_defect}: the written graphitron
 * directives the generator will not emit for, at the positions they were written, as the last step of
 * the gatherer's anchor phase.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here, and a sweep
 * scoped to the codes the gatherer owns in {@code graphitron_defect_type.owner_gatherer}. What this
 * class adds is who evaluates the rule and when.
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
 * already runs once per capture. {@link FieldPaths} stores every step the walk takes and every
 * place it stops, and a chain defect is read off those.
 * Computing them again per read is a second evaluation of a rule the capture has already run, which
 * is the shape this whole arc exists to remove rather than a cost to be weighed. The same holds
 * inside this stage: the chain arms read the stored walk rather than walking, because a walk named
 * from a correlated subquery here is walked once per chain it drives.
 */
public final class EntryDefects {

    private EntryDefects() {}

    /** The gatherer that runs this class and owns the codes its rule produces. */
    public static final String GATHERER = "graphitron";

    /**
     * Writes the graph's rule rows, each marked with {@code readAt}: a row already stored under the
     * same position and code takes the reading's mark and detail, a new one is inserted. Nothing is
     * deleted here; {@link #sweep} does that once every writer of the gatherer has run.
     */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var rule = GRAPHITRON_ENTRY_DEFECT_RULE;
        var source = dsl.select(rule.GRAPH_NAME, rule.SOURCE_NAME, rule.SOURCE_LINE,
                rule.SOURCE_COLUMN, rule.CODE, rule.DETAIL)
            .from(rule)
            .where(rule.GRAPH_NAME.eq(graphName))
            .asTable("r");
        Field<String> graph = source.field(rule.GRAPH_NAME);
        Field<String> file = source.field(rule.SOURCE_NAME);
        Field<Integer> line = source.field(rule.SOURCE_LINE);
        Field<Integer> column = source.field(rule.SOURCE_COLUMN);
        Field<String> code = source.field(rule.CODE);
        Field<String> detail = source.field(rule.DETAIL);
        dsl.mergeInto(target).using(source)
            .on(target.GRAPH_NAME.eq(graph), target.SOURCE_NAME.eq(file),
                target.SOURCE_LINE.eq(line), target.SOURCE_COLUMN.eq(column), target.CODE.eq(code))
            .whenMatchedThenUpdate()
            .set(target.DETAIL, detail)
            .set(target.TOUCHED_AT, inline(readAt))
            .whenNotMatchedThenInsert(target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE,
                target.SOURCE_COLUMN, target.CODE, target.DETAIL, target.TOUCHED_AT)
            .values(graph, file, line, column, code, detail, inline(readAt))
            .execute();
    }

    /**
     * Deletes the graph's defects of the codes {@code gatherer} owns that the reading at
     * {@code readAt} did not mark. Another gatherer's codes are not touched, so each owner sweeps
     * only what it writes; an argMapping entry's rows under a swept string go with it by their
     * foreign key.
     */
    public static void sweep(DSLContext dsl, String graphName, LocalDateTime readAt, String gatherer) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var type = GRAPHITRON_DEFECT_TYPE;
        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(readAt))
            .and(target.CODE.in(select(type.CODE).from(type).where(type.OWNER_GATHERER.eq(gatherer))))
            .execute();
    }
}
