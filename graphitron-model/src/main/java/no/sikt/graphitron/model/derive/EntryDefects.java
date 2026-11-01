package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_RULE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;

/**
 * The writer of {@code graphitron_entry_defect} from an owner gatherer's rule view: the defects that
 * gatherer's facts break, at the positions they were written, as the last step of its anchor phase.
 *
 * <p>Each owner has its own rule view, named in a {@link Rule} beside the gatherer that runs it, and
 * the writer is one statement over that view and a sweep scoped to the codes the owner holds in
 * {@code graphitron_defect_type.owner_gatherer}. A next owner adds its view and a {@link Rule} to
 * {@link #RULES} and calls {@link #derive} and {@link #sweep} from its own anchor phase, touching no
 * other owner's. What this class adds is who evaluates a rule and when.
 *
 * <p>The graphitron gatherer's rule view is {@code graphitron_entry_defect_rule}, its prefix being
 * that family's. What follows is about that rule.
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

    /**
     * One owner's rule: the gatherer that owns the codes {@code view} emits and runs its writer, and
     * the view, which has {@code graphitron_entry_defect}'s columns but the mark.
     */
    public record Rule(String gatherer, Table<?> view) {}

    /** The graphitron gatherer's rule. */
    public static final Rule GRAPHITRON = new Rule("graphitron", GRAPHITRON_ENTRY_DEFECT_RULE);

    /** Every owner's rule, one per owner gatherer. */
    public static final List<Rule> RULES = List.of(GRAPHITRON);

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

    private static <T> Field<T> column(Table<?> t, String column, Class<T> type) {
        return field(name(t.getName(), column), type);
    }
}
