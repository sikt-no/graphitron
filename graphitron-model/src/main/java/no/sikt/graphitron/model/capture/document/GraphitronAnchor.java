package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.vocabulary.EntryKind;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_OUTBOX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.val;

/**
 * Derives graphitron's own anchors from the rows the same reading wrote.
 *
 * <p>The sibling of the anchoring step in {@link GraphQLAstCapture}, on the same terms: one statement per anchor, an insert over a
 * select, run once the corpus has been read because what a directive is cannot be settled by any
 * one file. Nothing is read into Java and written back, and nothing is decided here.
 *
 * <h2>Deprecation, which is one fact with two markers</h2>
 *
 * <p>GraphQL forbids the deprecation directive on a directive definition and admits it on that
 * definition's arguments, so graphitron says the first with a token in the description and the
 * second with the directive GraphQL gives it. A consumer asking whether something is deprecated
 * should not have to know which of the two marked it, and these relations are what save it from
 * knowing: told apart by which relation a row is in rather than by a column, because no site
 * admits both.
 *
 * <h2>The field site, where the key is an ordinal</h2>
 *
 * <p>The relations below the deprecation ones are keyed by the field a repeatable directive was
 * written on and its place among that field's applications, where the entry stating the same fact
 * is keyed by the position it was written at.
 *
 * <p>The three deprecation relations differ in how far the resolution reaches. The
 * directive-argument one is the one thing the {@code graphql_} anchors deliberately do not offer:
 * a directive applied to a directive definition's own argument reaches no applied-directive anchor,
 * an argument of a definition not being a schema element and a coordinate for one being a spelling
 * the specification does not have. The input-field one does have such an anchor and is derived here
 * anyway, because that anchor carries the reason as the rendered literal and a reader taking it
 * from there would be re-reading SDL for the text.
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
        deprecatedInputFields(dsl, graph, touchedAt);
        routines(dsl, graph, touchedAt);
        outboxes(dsl, graph, touchedAt);
        // Last, and children before parents within it, for the reason every delete here has that
        // order: a sweep that took a parent first would meet its own child's foreign key.
        sweep(dsl, graph, touchedAt);
    }

    /** What the sweep deletes from, children before parents, listed rather than found by prefix. */
    private static final List<Table<?>> TABLES_TO_SWEEP =
        List.of(GRAPHITRON_DEPRECATED, GRAPHITRON_ROUTINE_ENTRY, GRAPHITRON_OUTBOX_ENTRY);

    /**
     * A directive whose description carries the token. Read off {@code graphql_directive} rather
     * than off the definition entries, because the anchor beside it has already settled which of
     * several documents' declarations the corpus honours, and asking that question twice is how the
     * two would come to disagree.
     */
    private static void deprecatedDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_DIRECTIVE;
        var t = GRAPHITRON_DEPRECATED;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.REASON, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), concat(inline("@"), d.DIRECTIVE_NAME),
                    d.DESCRIPTION,
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
        var applied = GRAPHQL_AST_ENTRY;
        var t = GRAPHITRON_DEPRECATED;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.REASON, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME),
                    concat(inline("@"), d.NAME, inline("("), a.NAME, inline(":)")),
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
     * A field of an input object carrying the native marker. One parent hop, where the argument arm
     * takes two: an input field's parent is the type declaring it, and that declaration carries the
     * type name the coordinate needs.
     *
     * <p>This coordinate does have an applied-directive anchor, unlike a directive definition's
     * argument, so the fact could be read from there. It is derived here because that anchor holds
     * the reason as the rendered literal and a reader taking it from there would be re-reading SDL
     * for the text. One decode, three resolutions, and no consumer parses anything.
     */
    private static void deprecatedInputFields(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var e = GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
        var f = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        var applied = GRAPHQL_AST_ENTRY;
        var t = GRAPHITRON_DEPRECATED;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.REASON, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME),
                    concat(f.TYPE_NAME, inline("."), f.NAME),
                    coalesce(e.REASON, inline("")), val(touchedAt, t.TOUCHED_AT))
                .from(e)
                .join(applied)
                .on(applied.GRAPH_NAME.eq(e.GRAPH_NAME))
                .and(applied.SOURCE_NAME.eq(e.SOURCE_NAME))
                .and(applied.SOURCE_LINE.eq(e.SOURCE_LINE))
                .and(applied.SOURCE_COLUMN.eq(e.SOURCE_COLUMN))
                .join(f)
                .on(f.GRAPH_NAME.eq(applied.GRAPH_NAME))
                .and(f.SOURCE_NAME.eq(applied.SOURCE_NAME))
                .and(f.SOURCE_LINE.eq(applied.PARENT_LINE))
                .and(f.SOURCE_COLUMN.eq(applied.PARENT_COLUMN))
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
        var named = GRAPHITRON_DEPRECATED;
        for (Table<?> table : TABLES_TO_SWEEP) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    // ------------------------------------------------------------------ the field site's decode

    /**
     * The type a numbered application was written on, carried up out of the numbering below. Named
     * apart from the entry relations' own columns so a join above the numbering is unambiguous.
     */
    private static final Field<String> TYPE_NAME = field(name("type_name"), String.class);

    /** The file the application is in. */
    private static final Field<String> SITE_NAME = field(name("site_name"), String.class);

    /** Where the application itself was written, which is the entry relations' key. */
    private static final Field<Integer> SITE_LINE = field(name("site_line"), Integer.class);

    private static final Field<Integer> SITE_COLUMN = field(name("site_column"), Integer.class);

    /**
     * Every {@code @routine} application at its coordinate, numbered in written order.
     *
     * <p>Shared by the anchor and the pair child below it rather than spelled twice, because the
     * ordinal is the key both of them land on and a second spelling is how the two would come to
     * disagree about which application a pair belongs to.
     */
    private static Table<?> routineApplications(DSLContext dsl, String graph) {
        return applicationsOf(dsl, graph, "routine");
    }

    /**
     * Every application of the repeatable field directive {@code directive} at its coordinate,
     * numbered in written order: the numbering {@link #routineApplications} is, for any directive
     * whose anchor is keyed by the ordinal.
     */
    private static Table<?> applicationsOf(DSLContext dsl, String graph, String directive) {
        var fd = GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
        var ix = GRAPHQL_AST_ENTRY;
        var ee = GRAPHQL_AST_ELEMENT_ENTRY;
        var ef = GRAPHQL_ELEMENT_FIELD;
        return dsl
            .select(ef.TYPE_NAME.as(TYPE_NAME), ef.FIELD_NAME.as(FIELD_NAME),
                fd.SOURCE_NAME.as(SITE_NAME), fd.SOURCE_LINE.as(SITE_LINE),
                fd.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(ef.TYPE_NAME, ef.FIELD_NAME)
                    .orderBy(fd.SOURCE_LINE.asc(), fd.SOURCE_COLUMN.asc()))
                    .minus(inline(1)).as(WRITTEN_AT))
            .from(fd)
            .join(ix).on(ix.GRAPH_NAME.eq(fd.GRAPH_NAME), ix.SOURCE_NAME.eq(fd.SOURCE_NAME),
                ix.SOURCE_LINE.eq(fd.SOURCE_LINE), ix.SOURCE_COLUMN.eq(fd.SOURCE_COLUMN))
            // The coordinate comes off the enclosing element's own entry rather than off a
            // column on the index, which the supertype split moved: the index states the position
            // and the element entry beside it states what is written there.
            .join(ee).on(ee.GRAPH_NAME.eq(ix.GRAPH_NAME), ee.SOURCE_NAME.eq(ix.SOURCE_NAME),
                ee.SOURCE_LINE.eq(ix.PARENT_LINE), ee.SOURCE_COLUMN.eq(ix.PARENT_COLUMN))
            .join(ef).on(ef.GRAPH_NAME.eq(ix.GRAPH_NAME), ef.COORDINATE.eq(ee.COORDINATE))
            .where(fd.GRAPH_NAME.eq(graph))
            .and(fd.NAME.eq(directive))
            .and(ix.ENTRY_KIND.eq(EntryKind.FIELD_DIRECTIVE))
            .and(ef.ARGUMENT_NAME.isNull())
            .asTable("applications");
    }

    /**
     * Every {@code @routine} a field carries, numbered in the order they were written.
     *
     * <p>Not {@link #claimedOnField}, and the difference is the point rather than a preference. That
     * helper ranks to pick one application per coordinate; this relation is keyed by the ordinal, so
     * every application is a row and the rank <em>is</em> the ordinal. A numbering has to be right
     * at every value where a choice only has to be right at one.
     *
     * <p>What it does not need is that helper's declaration join, which is there to order
     * applications by the merge order of the declaration each was written in. A field coordinate is
     * declared once, two declarations of one field being a duplicate-field error rather than an
     * order to settle, so all of a field's applications sit in one definition in one file and their
     * written positions already order them. The coordinate comes off the entry stratum's own index
     * instead.
     *
     * <p>Numbered over the applications and joined to the decode afterwards, which is what keeps the
     * ordinals agreeing with the walk this replaces: {@code routine_ref} is NOT NULL, so an
     * application naming nothing writes no row in either stratum, and it consumed its ordinal all
     * the same. Ranking after the join would close that gap and renumber every application behind
     * it.
     */
    private static void routines(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHITRON_AST_ROUTINE_ENTRY;
        var t = GRAPHITRON_ROUTINE_ENTRY;
        var applications = routineApplications(dsl, graph);
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL, t.SOURCE_NAME,
                t.SOURCE_LINE, t.SOURCE_COLUMN, t.ROUTINE_REF, t.ROUTINE_REF_NAMESPACE_PART,
                t.ROUTINE_REF_NAME_PART, t.ARGMAPPING, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), applications.field(TYPE_NAME),
                    applications.field(FIELD_NAME), applications.field(WRITTEN_AT),
                    applications.field(SITE_NAME), applications.field(SITE_LINE),
                    applications.field(SITE_COLUMN), e.ROUTINE_REF,
                    e.ROUTINE_REF_NAMESPACE_PART, e.ROUTINE_REF_NAME_PART, e.ARGMAPPING,
                    val(touchedAt, t.TOUCHED_AT))
                .from(applications)
                .join(e).on(e.GRAPH_NAME.eq(graph),
                    e.SOURCE_NAME.eq(applications.field(SITE_NAME)),
                    e.SOURCE_LINE.eq(applications.field(SITE_LINE)),
                    e.SOURCE_COLUMN.eq(applications.field(SITE_COLUMN))))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.ROUTINE_REF, excluded(t.ROUTINE_REF))
            .set(t.ROUTINE_REF_NAMESPACE_PART, excluded(t.ROUTINE_REF_NAMESPACE_PART))
            .set(t.ROUTINE_REF_NAME_PART, excluded(t.ROUTINE_REF_NAME_PART))
            .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * Every {@code @outbox} a field carries, numbered in the order they were written. Every
     * application is a row and the rank is the ordinal, as for {@link #routines}; nothing is
     * claimed, because a repeated directive is a message each. The decode is joined after the
     * numbering so an application that wrote nothing still consumes its ordinal.
     */
    private static void outboxes(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHITRON_AST_OUTBOX_ENTRY;
        var t = GRAPHITRON_OUTBOX_ENTRY;
        var applications = applicationsOf(dsl, graph, "outbox");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL, t.SOURCE_NAME,
                t.SOURCE_LINE, t.SOURCE_COLUMN, t.EVENT_REF, t.ARGMAPPING, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), applications.field(TYPE_NAME),
                    applications.field(FIELD_NAME), applications.field(WRITTEN_AT),
                    applications.field(SITE_NAME), applications.field(SITE_LINE),
                    applications.field(SITE_COLUMN), e.EVENT_REF, e.ARGMAPPING,
                    val(touchedAt, t.TOUCHED_AT))
                .from(applications)
                .join(e).on(e.GRAPH_NAME.eq(graph),
                    e.SOURCE_NAME.eq(applications.field(SITE_NAME)),
                    e.SOURCE_LINE.eq(applications.field(SITE_LINE)),
                    e.SOURCE_COLUMN.eq(applications.field(SITE_COLUMN))))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.EVENT_REF, excluded(t.EVENT_REF))
            .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    // ------------------------------------------------------------------ the field site's decode

    /** The field a claimed application was written on, beside {@link #TYPE_NAME}. */
    private static final Field<String> FIELD_NAME = field(name("field_name"), String.class);

    /** The position a list element was written at, carried up out of the ranking. */
    private static final Field<Integer> WRITTEN_AT = field(name("written_at"), Integer.class);
}
