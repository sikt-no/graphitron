package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Derives graphitron's own anchors from the rows the same reading wrote.
 *
 * <p>{@link SdlAnchor}'s sibling, on the same terms: one statement per anchor, an insert over a
 * select, run once the corpus has been read because what a directive is cannot be settled by any
 * one file. Nothing is read into Java and written back, and nothing is decided here.
 *
 * <h2>Deprecation, which is one fact with two markers</h2>
 *
 * <p>GraphQL forbids {@code @deprecated} on a directive definition and admits it on that
 * definition's arguments, so graphitron says the first with a token in the description and the
 * second with the directive GraphQL gives it. A consumer asking whether a directive is deprecated
 * should not have to know which of the two marked it, and these two relations are what saves it
 * from knowing: one per marker, told apart by which relation a row is in rather than by a column,
 * because no site admits both.
 *
 * <p>The argument anchor is the one thing the {@code graphql_} anchors deliberately do not offer. A
 * directive applied to a directive definition's own argument reaches no applied-directive anchor,
 * an argument of a definition not being a schema element and a coordinate for one being a spelling
 * the specification does not have. So this resolves the entry rows itself, through the two parent
 * hops the AST relations already record, and is the one relation that says such an application
 * happened at a coordinate a reader can name.
 */
public final class GraphitronAnchor {

    private GraphitronAnchor() {}

    /**
     * graphitron's docstring deprecation marker: the token in a description, matched on a word
     * boundary so a mid-word occurrence such as {@code my@deprecated} does not mark anything.
     * The same expression the walk this replaces applies, and it is applied here rather than at a
     * reader because finding it is a decode and a decode belongs at capture.
     */
    private static final String DEPRECATED_TOKEN = "(?<![A-Za-z0-9])@deprecated\\b";

    /**
     * Makes {@code graph}'s graphitron anchors be what its rows now say.
     *
     * <p>The instant is the caller's and must be the one the rows it derives from carry, the sweep
     * telling readings apart by it.
     */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        deprecatedDirectives(dsl, graph, touchedAt);
        deprecatedDirectiveArguments(dsl, graph, touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /** What the sweep deletes from, listed rather than found by prefix. */
    private static final List<Table<?>> TABLES_TO_SWEEP =
        List.of(GRAPHITRON_DEPRECATED_DIRECTIVE, GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT);

    /**
     * A directive whose description carries the token. Read off {@code graphql_directive} rather
     * than off the definition entries, because the anchor beside it has already settled which of
     * several documents' declarations the corpus honours, and asking that question twice is how the
     * two would come to disagree.
     */
    private static void deprecatedDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_DIRECTIVE;
        var t = GRAPHITRON_DEPRECATED_DIRECTIVE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.REASON, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), d.DIRECTIVE_NAME, d.DESCRIPTION,
                    val(touchedAt, t.TOUCHED_AT))
                .from(d)
                .where(d.GRAPH_NAME.eq(graph))
                .and(d.DESCRIPTION.isNotNull())
                .and(condition("regexp_like({0}, {1})", d.DESCRIPTION, inline(DEPRECATED_TOKEN))))
            .onDuplicateKeyUpdate()
            .set(t.REASON, excluded(t.REASON))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * An argument of a declared directive carrying the native marker. Two parent hops resolve the
     * entry to its coordinate: the application's parent is the formal argument, and that argument's
     * parent is the definition that declares it, both recorded by the AST relations as positions in
     * one file. A reason the author omitted is stored as the empty string, the absence a reader
     * cares about being the absence of the row.
     */
    private static void deprecatedDirectiveArguments(DSLContext dsl, String graph,
                                                     LocalDateTime touchedAt) {
        var e = GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
        var a = GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
        var d = GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
        var applied = no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        var t = GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.ARGUMENT_NAME, t.REASON, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME), d.NAME, a.NAME,
                    coalesce(e.REASON, inline("")), val(touchedAt, t.TOUCHED_AT))
                .from(e)
                .join(applied)
                .on(applied.GRAPH_NAME.eq(e.GRAPH_NAME))
                .and(applied.SOURCE_NAME.eq(e.SOURCE_NAME))
                .and(applied.SOURCE_LINE.eq(e.SOURCE_LINE))
                .and(applied.SOURCE_COLUMN.eq(e.SOURCE_COLUMN))
                .join(a)
                .on(a.GRAPH_NAME.eq(applied.GRAPH_NAME))
                .and(a.SOURCE_NAME.eq(applied.SOURCE_NAME))
                .and(a.SOURCE_LINE.eq(applied.PARENT_LINE))
                .and(a.SOURCE_COLUMN.eq(applied.PARENT_COLUMN))
                .join(d)
                .on(d.GRAPH_NAME.eq(a.GRAPH_NAME))
                .and(d.SOURCE_NAME.eq(a.SOURCE_NAME))
                .and(d.SOURCE_LINE.eq(a.PARENT_LINE))
                .and(d.SOURCE_COLUMN.eq(a.PARENT_COLUMN))
                .where(e.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.REASON, excluded(t.REASON))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * Mark and sweep, per graph: the reading ends by deleting the graph's rows carrying a different
     * instant, which are the markers the corpus stopped carrying and which an upsert cannot find,
     * there being no incoming row to match.
     */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var named = GRAPHITRON_DEPRECATED_DIRECTIVE;
        for (Table<?> table : TABLES_TO_SWEEP) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }
}
