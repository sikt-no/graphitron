package no.sikt.graphitron.model.capture.document;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_MUTATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PIVOT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_RECORD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_INPUT_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PIVOT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RECORD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static org.jooq.impl.DSL.coalesce;
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
 * <p>{@link SdlAnchor}'s sibling, on the same terms: one statement per anchor, an insert over a
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
 * <h2>The type site, where the resolution is a rank</h2>
 *
 * <p>The relations below the deprecation ones are the first of the incumbent decode's own to move.
 * They are keyed by the type a directive was written on, where the entry stating the same fact is
 * keyed by the position it was written at, so the resolution each of them performs is the one a
 * type-keyed grain forces: a type carrying an application on its base declaration and on an
 * extension has two entries and admits one row, and something has to pick. {@link #claimed} is
 * that pick, stated once for every directive at the site.
 *
 * <p>Three relations over one decode, and the three differ in how far the resolution reaches. The
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
        // Once, children before parents, rather than once per statement. A derivation that emptied
        // only its own relation would meet its child's foreign key, and getting that order right
        // per statement is the same order stated many times over.
        clear(dsl, graph);
        deprecatedDirectives(dsl, graph, touchedAt);
        deprecatedDirectiveArguments(dsl, graph, touchedAt);
        deprecatedInputFields(dsl, graph, touchedAt);
        sweep(dsl, graph, touchedAt);
        tables(dsl, graph);
        scalarTypes(dsl, graph);
        records(dsl, graph);
        connections(dsl, graph);
        pivots(dsl, graph);
        mutations(dsl, graph);
        // The ordering before the fields it orders by, which reference it.
        defaultOrders(dsl, graph);
        defaultOrderFields(dsl, graph);
    }

    /** What the sweep deletes from, listed rather than found by prefix. */
    private static final List<Table<?>> TABLES_TO_SWEEP =
        List.of(GRAPHITRON_DEPRECATED_DIRECTIVE, GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT,
            GRAPHITRON_DEPRECATED_INPUT_FIELD);

    /** Everything this writer owns, which is what {@link #clear} empties. Listed for the same reason. */
    private static final List<Table<?>> OWNED =
        List.of(GRAPHITRON_DEPRECATED_DIRECTIVE, GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT,
            GRAPHITRON_DEPRECATED_INPUT_FIELD, GRAPHITRON_TABLE_ENTRY,
            GRAPHITRON_SCALAR_TYPE_ENTRY, GRAPHITRON_RECORD_ENTRY,
            GRAPHITRON_CONNECTION_ENTRY, GRAPHITRON_PIVOT_ENTRY, GRAPHITRON_MUTATION_ENTRY,
            // Children before parents, this being what the clear deletes in order.
            GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY, GRAPHITRON_DEFAULT_ORDER_ENTRY);

    /**
     * Empties {@code graph}'s rows across everything this writer owns, ahead of the reading that
     * states them again.
     *
     * <p>Separate from {@link #write} and called before the SDL anchors rather than with them,
     * because of what those anchors do at the end of their own reading: they sweep, deleting the
     * coordinates the corpus stopped declaring. Every relation here keys into one of them, so a row
     * about a type an author has just deleted would refuse that sweep and take the whole reading
     * down with it. Emptying first is what makes the two readings independent, and it costs nothing
     * that is not paid anyway: these relations are rewritten whole every reading.
     *
     * <p>The relations carrying the reading's instant are emptied here too, although their own
     * sweep would have found them. One discipline over the writer's whole population is a thing a
     * reader can check; two, applied by which columns a relation happens to carry, is not.
     *
     * <p>Delete and insert rather than upsert and sweep, for the relations that moved off the walk,
     * and the difference is a column rather than a preference: they carry no instant of their own,
     * having been written until now by a walk whose whole partition was emptied before it ran. So
     * this is that same discipline in the only form their columns admit, and it is why moving one
     * of them owes no schema change.
     *
     * <p>Children before parents, and once rather than once per statement. A derivation that
     * emptied only the relation it fills would meet its own child's foreign key, and getting that
     * order right inside each statement is one order stated many times over.
     */
    public static void clear(DSLContext dsl, String graph) {
        var named = GRAPHITRON_DEPRECATED_DIRECTIVE;
        for (Table<?> table : OWNED) {
            dsl.deleteFrom(table).where(table.field(named.GRAPH_NAME).eq(graph)).execute();
        }
    }

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
        var applied = no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        var t = GRAPHITRON_DEPRECATED_INPUT_FIELD;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.REASON, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME), f.TYPE_NAME, f.NAME,
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
        var named = GRAPHITRON_DEPRECATED_DIRECTIVE;
        for (Table<?> table : TABLES_TO_SWEEP) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    // ------------------------------------------------------------------- the type site's decode

    /**
     * The type a claimed application was written on, carried up out of the ranking below. Named
     * apart from the entry relations' own columns so a join above the ranking is unambiguous.
     */
    private static final Field<String> TYPE_NAME = field(name("type_name"), String.class);

    /** The file the declaration is in, which is the file its applications are in too. */
    private static final Field<String> SITE_NAME = field(name("site_name"), String.class);

    /** Where the declaration this was applied to opens. */
    private static final Field<Integer> DECLARATION_LINE =
        field(name("declaration_line"), Integer.class);

    private static final Field<Integer> DECLARATION_COLUMN =
        field(name("declaration_column"), Integer.class);

    /** Where the application itself was written, which is the entry relations' key. */
    private static final Field<Integer> SITE_LINE = field(name("site_line"), Integer.class);

    private static final Field<Integer> SITE_COLUMN = field(name("site_column"), Integer.class);

    /** The rank that picks one application per type; see {@link #claimed}. */
    private static final Field<Integer> RANK = field(name("rank"), Integer.class);

    /**
     * Every application of one type-site directive, ranked so that rank one is the application a
     * type-keyed anchor states.
     *
     * <p>A type-keyed relation admits one row per type, and a type can carry the same directive on
     * its base declaration and on any number of extensions, so something has to choose. The walk
     * this replaces chose by arriving first and keeping what it had, and the order it arrived in
     * was the corpus merge order, so the rank states that order rather than reproducing the
     * arrival. A repeat is not a collision, which is what lets this rank rather than refuse: the
     * losing application is still a row in the entry stratum at its own position, and that a
     * single-application directive repeated is a detection over those rows.
     *
     * <p>Ranked over the applications rather than over the decoded entries, because the decode is
     * not total: an application whose arguments state nothing an entry relation can hold has no
     * entry row and is still an application. Taking the rank here and joining the decode afterwards
     * is what keeps "applied, and named nothing" distinguishable from "not applied".
     */
    private static Table<?> claimed(DSLContext dsl, String graph, String directive) {
        var a = GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        return dsl
            .select(d.NAME.as(TYPE_NAME), d.SOURCE_NAME.as(SITE_NAME),
                d.SOURCE_LINE.as(DECLARATION_LINE), d.SOURCE_COLUMN.as(DECLARATION_COLUMN),
                a.SOURCE_LINE.as(SITE_LINE), a.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(d.NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc())).as(RANK))
            .from(a)
            .join(d).on(d.GRAPH_NAME.eq(a.GRAPH_NAME))
                .and(d.SOURCE_NAME.eq(a.SOURCE_NAME))
                .and(d.SOURCE_LINE.eq(a.PARENT_LINE))
                .and(d.SOURCE_COLUMN.eq(a.PARENT_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(d.NAME))
                .and(m.SOURCE_NAME.eq(d.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(d.SOURCE_LINE))
                .and(m.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .where(a.GRAPH_NAME.eq(graph))
            .and(a.NAME.eq(directive))
            .asTable("claimed");
    }

    /**
     * The table a type declaration is bound to: rank one of the {@code @table} applications, with
     * the written name joined on from the decode.
     *
     * <p>A left join, and it is the whole reason this reads two relations. A bare {@code @table}
     * writes no entry row, the author having written no name to record, and it is still a binding:
     * the type resolves against its own name. So the application is the row's existence and the
     * decode is its payload, and an absent payload is the deduction the author asked for by
     * leaving the argument out rather than the absence of the directive.
     */
    private static void tables(DSLContext dsl, String graph) {
        var c = claimed(dsl, graph, "table");
        var e = GRAPHITRON_AST_TABLE_ENTRY;
        var t = GRAPHITRON_TABLE_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TABLE_REF,
                t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(SITE_NAME),
                    c.field(DECLARATION_LINE), c.field(DECLARATION_COLUMN), c.field(SITE_LINE),
                    c.field(SITE_COLUMN), e.TABLE_REF, e.TABLE_REF_NAMESPACE_PART,
                    e.TABLE_REF_NAME_PART)
                .from(c)
                .leftJoin(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(e.SOURCE_LINE.eq(c.field(SITE_LINE)))
                    .and(e.SOURCE_COLUMN.eq(c.field(SITE_COLUMN)))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    /**
     * The Java constant a scalar declaration is backed by. An inner join where {@link #tables}
     * takes an outer one, because here the payload is the fact: {@code scalar_ref} is the
     * relation's one unkeyed column and it is NOT NULL, so an application naming nothing has
     * nothing to state and the walk this replaces returned without writing.
     */
    private static void scalarTypes(DSLContext dsl, String graph) {
        var c = claimed(dsl, graph, "scalarType");
        var e = GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
        var t = GRAPHITRON_SCALAR_TYPE_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SCALAR_REF,
                t.SCALAR_REF_CLASS_PART, t.SCALAR_REF_FIELD_PART)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(SITE_NAME),
                    c.field(DECLARATION_LINE), c.field(DECLARATION_COLUMN), c.field(SITE_LINE),
                    c.field(SITE_COLUMN), e.SCALAR_REF, e.SCALAR_REF_CLASS_PART,
                    e.SCALAR_REF_FIELD_PART)
                .from(c)
                .join(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(e.SOURCE_LINE.eq(c.field(SITE_LINE)))
                    .and(e.SOURCE_COLUMN.eq(c.field(SITE_COLUMN)))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    // ------------------------------------------------------------------ the field site's decode

    /** The field a claimed application was written on, beside {@link #TYPE_NAME}. */
    private static final Field<String> FIELD_NAME = field(name("field_name"), String.class);

    /** The position a list element was written at, carried up out of the ranking. */
    private static final Field<Integer> WRITTEN_AT = field(name("written_at"), Integer.class);

    /**
     * {@link #claimed} at the field site: every application of one field-site directive, ranked so
     * that rank one is the application a coordinate-keyed anchor states.
     *
     * <p>The same choice one hop further out. A field's coordinate is its type and its name, and a
     * type is declared by a base declaration and any number of extensions, so the merge order that
     * decides between two applications is the order of the declarations the fields were written in.
     * That is two joins rather than one: the application to the field definition it sits on, and
     * the field definition to the declaration it was written inside.
     *
     * <p>Only the four directives the specification admits at {@code FIELD_DEFINITION} alone go
     * through this. A directive also legal on an input object's field has its applications split
     * across two entry relations, an input object's field being an input value rather than a field
     * in the entry stratum, and an anchor for one of those is a union with a parent-kind test
     * rather than this select.
     */
    private static Table<?> claimedOnField(DSLContext dsl, String graph, String directive) {
        var a = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        return dsl
            .select(f.TYPE_NAME.as(TYPE_NAME), f.NAME.as(FIELD_NAME),
                a.SOURCE_NAME.as(SITE_NAME), a.SOURCE_LINE.as(SITE_LINE),
                a.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(f.TYPE_NAME, f.NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc())).as(RANK))
            .from(a)
            .join(f).on(f.GRAPH_NAME.eq(a.GRAPH_NAME))
                .and(f.SOURCE_NAME.eq(a.SOURCE_NAME))
                .and(f.SOURCE_LINE.eq(a.PARENT_LINE))
                .and(f.SOURCE_COLUMN.eq(a.PARENT_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(f.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(f.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(f.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(f.PARENT_COLUMN))
            .where(a.GRAPH_NAME.eq(graph))
            .and(a.NAME.eq(directive))
            .asTable("claimed_field");
    }

    /**
     * What an {@code @asConnection} application asked for. Both columns are optional, the directive
     * defaulting the page size and deriving the type name, so the row is the application and the
     * payload is whatever was written; an outer join for the reason {@link #tables} takes one.
     */
    private static void connections(DSLContext dsl, String graph) {
        var c = claimedOnField(dsl, graph, "asConnection");
        var e = GRAPHITRON_AST_CONNECTION_ENTRY;
        var t = GRAPHITRON_CONNECTION_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.DEFAULT_FIRST_VALUE, t.CONNECTION_NAME)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.DEFAULT_FIRST_VALUE, e.CONNECTION_NAME)
                .from(c)
                .leftJoin(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    /**
     * The column a field pivots on and the column its value comes from. An inner join: both are
     * NOT NULL here and the walk this replaces returned without writing when either was unwritten,
     * so an application that named neither has no fact to state.
     */
    private static void pivots(DSLContext dsl, String graph) {
        var c = claimedOnField(dsl, graph, "pivot");
        var e = GRAPHITRON_AST_PIVOT_ENTRY;
        var t = GRAPHITRON_PIVOT_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.ON_COLUMN, t.VALUE_COLUMN, t.VOCABULARY_REF)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.ON_COLUMN, e.VALUE_COLUMN, e.VOCABULARY_REF)
                .from(c)
                .join(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    /**
     * What a field mutates and how. An inner join, the operation being NOT NULL and the whole of
     * what the application asserts; the table spelling beside it is optional and stays with the
     * row. That spelling is also a row of {@code graphitron_spelled_reference_entry}, which is
     * keyed by the value across every site that writes one and so moves with the last of them.
     */
    private static void mutations(DSLContext dsl, String graph) {
        var c = claimedOnField(dsl, graph, "mutation");
        var e = GRAPHITRON_AST_MUTATION_ENTRY;
        var t = GRAPHITRON_MUTATION_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.OPERATION, t.MULTI_ROW, t.TABLE_REF,
                t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.OPERATION, e.MULTI_ROW, e.TABLE_REF, e.TABLE_REF_NAMESPACE_PART,
                    e.TABLE_REF_NAME_PART)
                .from(c)
                .join(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    /**
     * The ordering a field sorts by when the client asks for none. An outer join: an application
     * may state its basis as an index, as the primary key, or as the field list below, so every
     * payload column here is optional and the row is the application.
     */
    private static void defaultOrders(DSLContext dsl, String graph) {
        var c = claimedOnField(dsl, graph, "defaultOrder");
        var e = GRAPHITRON_AST_DEFAULT_ORDER_ENTRY;
        var t = GRAPHITRON_DEFAULT_ORDER_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.INDEX_REF, t.PRIMARY_KEY, t.DIRECTION)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.INDEX_REF, e.PRIMARY_KEY, e.DIRECTION)
                .from(c)
                .leftJoin(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    /**
     * The fields that ordering sorts by, in order, with the order copied rather than recomputed.
     *
     * <p>It used to be ranked again here. The entry numbers an element by where it was written, and
     * an element the decode could not use took its index and contributed no row, so the anchor
     * renumbered densely from zero to close the gap, which is what the walk this replaces did by
     * counting as it wrote. Under the rule that an entry holds what the directive definition
     * admits, that gap cannot open: an element of the wrong shape or missing its required name is
     * not a {@code FieldSort}, so its whole application is refused and the elements of an admitted
     * one are the elements the author wrote, numbered from zero without a hole.
     *
     * <p>So the two strata agree about this number as they agree about the rows, and the statement
     * says so by selecting {@code position} instead of ranking over it.
     */
    private static void defaultOrderFields(DSLContext dsl, String graph) {
        var c = claimedOnField(dsl, graph, "defaultOrder");
        var e = GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
        var t = GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.POSITION, t.NAME_REF, t.COLLATE,
                t.DIRECTION)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    e.POSITION, e.NAME_REF, e.COLLATE, e.DIRECTION)
                .from(c)
                .join(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .execute();
    }

    /**
     * The join from a ranked application to the decode of that same application, which is four
     * columns every site spells identically: the graph, and the position the application was
     * written at.
     */
    private static Condition onSite(Field<String> entryGraph, Field<String> entrySource,
                                    Field<Integer> entryLine, Field<Integer> entryColumn,
                                    String graph, Table<?> claimed) {
        return entryGraph.eq(graph)
            .and(entrySource.eq(claimed.field(SITE_NAME)))
            .and(entryLine.eq(claimed.field(SITE_LINE)))
            .and(entryColumn.eq(claimed.field(SITE_COLUMN)));
    }

    /**
     * The class a declaration says it is backed by. A left join like {@link #tables}, and here the
     * outer half costs nothing to state: {@code class_name} is the only payload column and it is
     * nullable, so an application the decode refused lands as the null row the walk wrote too.
     */
    private static void records(DSLContext dsl, String graph) {
        var c = claimed(dsl, graph, "record");
        var e = GRAPHITRON_AST_RECORD_ENTRY;
        var t = GRAPHITRON_RECORD_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CLASS_NAME)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(SITE_NAME),
                    c.field(DECLARATION_LINE), c.field(DECLARATION_COLUMN), c.field(SITE_LINE),
                    c.field(SITE_COLUMN), e.CLASS_NAME)
                .from(c)
                .leftJoin(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(e.SOURCE_LINE.eq(c.field(SITE_LINE)))
                    .and(e.SOURCE_COLUMN.eq(c.field(SITE_COLUMN)))
                .where(c.field(RANK).eq(1)))
            .execute();
    }
}
