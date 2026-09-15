package no.sikt.graphitron.model.capture.document;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT_FIELD;
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
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
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
        deprecatedDirectives(dsl, graph, touchedAt);
        deprecatedDirectiveArguments(dsl, graph, touchedAt);
        deprecatedInputFields(dsl, graph, touchedAt);
        tables(dsl, graph, touchedAt);
        scalarTypes(dsl, graph, touchedAt);
        records(dsl, graph, touchedAt);
        connections(dsl, graph, touchedAt);
        pivots(dsl, graph, touchedAt);
        mutations(dsl, graph, touchedAt);
        // The ordering before the fields it orders by, which reference it.
        defaultOrders(dsl, graph, touchedAt);
        defaultOrderFields(dsl, graph, touchedAt);
        chainLinks(dsl, graph, touchedAt);
        // Last, and children before parents within it, for the reason every delete here has that
        // order: a sweep that took a parent first would meet its own child's foreign key.
        sweep(dsl, graph, touchedAt);
    }

    /**
     * The chain a field's rows travel, one row per written element in written order.
     *
     * <p>Here and not with the endpoint relations, because the order is only in this stratum. A
     * chain composes {@code @routine} and {@code @reference} applications in the order they were
     * written, and capture numbers applications per directive name, so a routine at ordinal 0 and a
     * reference at ordinal 0 are two different writings whose numbers do not compare. A written
     * position is this stratum's primary key, and every directive of one field shares a parent, so
     * the order is a read rather than a reconstruction.
     *
     * <p>Ranked over three keys and not one: the declaration's merge order, a field declared across
     * several files having its directives spread over them; the directive's own position within a
     * declaration; and the element's authored index inside the directive, which orders the elements
     * of one {@code path:} against each other.
     *
     * <p>Ranked and not copied, which {@code GraphitronEntries.elementsOf} is the reason for: it
     * increments past an element it does not transcribe, so an authored index can skip. A chain
     * wants its links counted, so position 1 is the second link and never the second thing typed.
     *
     * <p>{@code graphql_ast_entry} is this relation's element reference and not one of its inputs:
     * what the supertype buys is one checked foreign key reaching either shape, where the statement
     * below reads each shape's own relation.
     *
     * <p>An element is one written thing, whatever it says. A {@code @routine} application is the
     * directive itself, and its decode is what admits it: an application this stratum refused
     * states no routine and composes no chain. A path element is the object written in the
     * {@code path:} list, either a member of it or the whole expression where GraphQL coerced a
     * lone value into the list of one; an object written inside an element, which is what a
     * condition's own shape is, has a parent and no position and is not an element.
     *
     * <p>Nothing here says what a link does. Those facts are keyed by the written position the row
     * points at, one relation per shape as this stratum already states them, so an element writing
     * a table beside a condition is one link with two facts and no column here has to choose.
     */
    private static void chainLinks(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var fd = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
        var e = GRAPHQL_AST_ENTRY;
        var ef = GRAPHQL_ELEMENT_FIELD;
        var ra = GRAPHITRON_AST_ROUTINE_ENTRY;
        var aa = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var v = GRAPHQL_AST_VALUE_ENTRY;
        var t = GRAPHITRON_FIELD_CHAIN_LINK;

        // The coordinate is read off the key the supertype forwards, not climbed to through the
        // field definition and the type declaration. Those two joins were here and one of them was
        // wrong: the declaration was joined for its merge order, to order the directives of a field
        // declared across several files, and a field coordinate is declared once, two declarations
        // of one field being a duplicate-field error rather than an order to settle. So all of a
        // field's directives sit in one definition in one file, the merge order answers a question
        // that cannot arise, and joining a type's declarations matched every declaration of that
        // type and multiplied each application by their number.
        var applications = dsl
            .select(ef.TYPE_NAME.as(TYPE_NAME), ef.FIELD_NAME.as(FIELD_NAME),
                fd.SOURCE_NAME.as(SITE_NAME), fd.SOURCE_LINE.as(SITE_LINE),
                fd.SOURCE_COLUMN.as(SITE_COLUMN), fd.NAME.as("name"))
            .from(fd)
            .join(e).on(e.GRAPH_NAME.eq(fd.GRAPH_NAME), e.SOURCE_NAME.eq(fd.SOURCE_NAME),
                e.SOURCE_LINE.eq(fd.SOURCE_LINE), e.SOURCE_COLUMN.eq(fd.SOURCE_COLUMN))
            .join(ef).on(ef.GRAPH_NAME.eq(e.GRAPH_NAME), ef.COORDINATE.eq(e.ELEMENT_COORDINATE))
            .where(fd.GRAPH_NAME.eq(graph))
            .and(ef.ARGUMENT_NAME.isNull())
            .asTable("applications");

        var routines = dsl
            .select(applications.field(TYPE_NAME),
                applications.field(FIELD_NAME),
                applications.field(SITE_LINE),
                applications.field(SITE_COLUMN),
                inline(0).as("authored"),
                applications.field(SITE_NAME).as("el_source"),
                applications.field(SITE_LINE).as("el_line"),
                applications.field(SITE_COLUMN).as("el_column"))
            .from(applications)
            .join(ra).on(ra.GRAPH_NAME.eq(graph),
                ra.SOURCE_NAME.eq(applications.field(SITE_NAME)),
                ra.SOURCE_LINE.eq(applications.field(SITE_LINE)),
                ra.SOURCE_COLUMN.eq(applications.field(SITE_COLUMN)));

        var steps = dsl
            .select(applications.field(TYPE_NAME),
                applications.field(FIELD_NAME),
                applications.field(SITE_LINE),
                applications.field(SITE_COLUMN),
                // A lone value coerced into the list of one holds no position in an enclosing list,
                // and index zero is what the coercion says it is.
                coalesce(v.POSITION, inline(0)).as("authored"),
                v.SOURCE_NAME.as("el_source"), v.SOURCE_LINE.as("el_line"),
                v.SOURCE_COLUMN.as("el_column"))
            .from(applications)
            .join(aa).on(aa.GRAPH_NAME.eq(graph),
                aa.SOURCE_NAME.eq(applications.field(SITE_NAME)),
                aa.PARENT_LINE.eq(applications.field(SITE_LINE)),
                aa.PARENT_COLUMN.eq(applications.field(SITE_COLUMN)),
                aa.NAME.eq("path"))
            // One join and not a walk down the value tree: the holder is repeated on every node of
            // an expression, so every element of the list names the applied argument directly.
            .join(v).on(v.GRAPH_NAME.eq(aa.GRAPH_NAME), v.SOURCE_NAME.eq(aa.SOURCE_NAME),
                v.HOLDER_LINE.eq(aa.SOURCE_LINE), v.HOLDER_COLUMN.eq(aa.SOURCE_COLUMN),
                v.KIND.eq("OBJECT"))
            .and(v.POSITION.isNotNull().or(v.PARENT_LINE.isNull()))
            .where(applications.field("name", String.class).in("reference", "referenceFor"));

        var elements = routines.unionAll(steps).asTable("elements");
        var d = GRAPHQL_AST_ENTRY;
        var ranked = dsl
            .select(elements.field(TYPE_NAME),
                elements.field(FIELD_NAME),
                elements.field("el_source", String.class),
                elements.field("el_line", Integer.class),
                elements.field("el_column", Integer.class),
                rowNumber().over(partitionBy(elements.field(TYPE_NAME),
                        elements.field(FIELD_NAME))
                    .orderBy(elements.field(SITE_LINE).asc(),
                        elements.field(SITE_COLUMN).asc(),
                        elements.field("authored", Integer.class).asc()))
                    .minus(inline(1)).as("position"))
            .from(elements)
            // The relation the element reference points at, joined because it is referenced: a row
            // naming a position the supertype does not hold would be a dangling reference, so the
            // join is what makes integrity hold by construction rather than by the order two
            // readings happen to run in. It is also the filter that makes a link a link, an element
            // this stratum never transcribed being nothing to travel through. Joined before the
            // rank, so the numbering counts the links that survive it.
            .join(d).on(d.GRAPH_NAME.eq(graph),
                d.SOURCE_NAME.eq(elements.field("el_source", String.class)),
                d.SOURCE_LINE.eq(elements.field("el_line", Integer.class)),
                d.SOURCE_COLUMN.eq(elements.field("el_column", Integer.class)))
            .asTable("ranked");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.POSITION, t.ELEMENT_SOURCE_NAME,
                t.ELEMENT_LINE, t.ELEMENT_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(TYPE_NAME),
                    ranked.field(FIELD_NAME),
                    ranked.field("position", Integer.class),
                    ranked.field("el_source", String.class),
                    ranked.field("el_line", Integer.class),
                    ranked.field("el_column", Integer.class),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked))
            .onDuplicateKeyUpdate()
            .set(t.ELEMENT_SOURCE_NAME, excluded(t.ELEMENT_SOURCE_NAME))
            .set(t.ELEMENT_LINE, excluded(t.ELEMENT_LINE))
            .set(t.ELEMENT_COLUMN, excluded(t.ELEMENT_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** What the sweep deletes from, children before parents, listed rather than found by prefix. */
    private static final List<Table<?>> TABLES_TO_SWEEP =
        List.of(GRAPHITRON_DEPRECATED_DIRECTIVE, GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT,
            GRAPHITRON_DEPRECATED_INPUT_FIELD, GRAPHITRON_TABLE_ENTRY,
            GRAPHITRON_SCALAR_TYPE_ENTRY, GRAPHITRON_RECORD_ENTRY,
            GRAPHITRON_CONNECTION_ENTRY, GRAPHITRON_PIVOT_ENTRY, GRAPHITRON_MUTATION_ENTRY,
            GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY, GRAPHITRON_DEFAULT_ORDER_ENTRY,
            GRAPHITRON_FIELD_CHAIN_LINK);

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
    private static void tables(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimed(dsl, graph, "table");
        var e = GRAPHITRON_AST_TABLE_ENTRY;
        var t = GRAPHITRON_TABLE_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TABLE_REF,
                t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(SITE_NAME),
                    c.field(DECLARATION_LINE), c.field(DECLARATION_COLUMN), c.field(SITE_LINE),
                    c.field(SITE_COLUMN), e.TABLE_REF, e.TABLE_REF_NAMESPACE_PART,
                    e.TABLE_REF_NAME_PART, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .leftJoin(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(e.SOURCE_LINE.eq(c.field(SITE_LINE)))
                    .and(e.SOURCE_COLUMN.eq(c.field(SITE_COLUMN)))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TABLE_REF, excluded(t.TABLE_REF))
            .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
            .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The Java constant a scalar declaration is backed by. An inner join where {@link #tables}
     * takes an outer one, because here the payload is the fact: {@code scalar_ref} is the
     * relation's one unkeyed column and it is NOT NULL, so an application naming nothing has
     * nothing to state and the walk this replaces returned without writing.
     */
    private static void scalarTypes(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimed(dsl, graph, "scalarType");
        var e = GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
        var t = GRAPHITRON_SCALAR_TYPE_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SCALAR_REF,
                t.SCALAR_REF_CLASS_PART, t.SCALAR_REF_FIELD_PART, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(SITE_NAME),
                    c.field(DECLARATION_LINE), c.field(DECLARATION_COLUMN), c.field(SITE_LINE),
                    c.field(SITE_COLUMN), e.SCALAR_REF, e.SCALAR_REF_CLASS_PART,
                    e.SCALAR_REF_FIELD_PART, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .join(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(e.SOURCE_LINE.eq(c.field(SITE_LINE)))
                    .and(e.SOURCE_COLUMN.eq(c.field(SITE_COLUMN)))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.SCALAR_REF, excluded(t.SCALAR_REF))
            .set(t.SCALAR_REF_CLASS_PART, excluded(t.SCALAR_REF_CLASS_PART))
            .set(t.SCALAR_REF_FIELD_PART, excluded(t.SCALAR_REF_FIELD_PART))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
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
    private static void connections(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimedOnField(dsl, graph, "asConnection");
        var e = GRAPHITRON_AST_CONNECTION_ENTRY;
        var t = GRAPHITRON_CONNECTION_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.DEFAULT_FIRST_VALUE, t.CONNECTION_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.DEFAULT_FIRST_VALUE, e.CONNECTION_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .leftJoin(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.DEFAULT_FIRST_VALUE, excluded(t.DEFAULT_FIRST_VALUE))
            .set(t.CONNECTION_NAME, excluded(t.CONNECTION_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The column a field pivots on and the column its value comes from. An inner join: both are
     * NOT NULL here and the walk this replaces returned without writing when either was unwritten,
     * so an application that named neither has no fact to state.
     */
    private static void pivots(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimedOnField(dsl, graph, "pivot");
        var e = GRAPHITRON_AST_PIVOT_ENTRY;
        var t = GRAPHITRON_PIVOT_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.ON_COLUMN, t.VALUE_COLUMN, t.VOCABULARY_REF, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.ON_COLUMN, e.VALUE_COLUMN, e.VOCABULARY_REF, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .join(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.ON_COLUMN, excluded(t.ON_COLUMN))
            .set(t.VALUE_COLUMN, excluded(t.VALUE_COLUMN))
            .set(t.VOCABULARY_REF, excluded(t.VOCABULARY_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * What a field mutates and how. An inner join, the operation being NOT NULL and the whole of
     * what the application asserts; the table spelling beside it is optional and stays with the
     * row. That spelling is also a row of {@code graphitron_spelled_reference_entry}, which is
     * keyed by the value across every site that writes one and so moves with the last of them.
     */
    private static void mutations(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimedOnField(dsl, graph, "mutation");
        var e = GRAPHITRON_AST_MUTATION_ENTRY;
        var t = GRAPHITRON_MUTATION_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.OPERATION, t.MULTI_ROW, t.TABLE_REF,
                t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.OPERATION, e.MULTI_ROW, e.TABLE_REF, e.TABLE_REF_NAMESPACE_PART,
                    e.TABLE_REF_NAME_PART, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .join(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.OPERATION, excluded(t.OPERATION))
            .set(t.MULTI_ROW, excluded(t.MULTI_ROW))
            .set(t.TABLE_REF, excluded(t.TABLE_REF))
            .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
            .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The ordering a field sorts by when the client asks for none. An outer join: an application
     * may state its basis as an index, as the primary key, or as the field list below, so every
     * payload column here is optional and the row is the application.
     */
    private static void defaultOrders(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimedOnField(dsl, graph, "defaultOrder");
        var e = GRAPHITRON_AST_DEFAULT_ORDER_ENTRY;
        var t = GRAPHITRON_DEFAULT_ORDER_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.INDEX_REF, t.PRIMARY_KEY, t.DIRECTION, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    c.field(SITE_NAME), c.field(SITE_LINE), c.field(SITE_COLUMN),
                    e.INDEX_REF, e.PRIMARY_KEY, e.DIRECTION, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .leftJoin(e).on(onSite(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    graph, c))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.INDEX_REF, excluded(t.INDEX_REF))
            .set(t.PRIMARY_KEY, excluded(t.PRIMARY_KEY))
            .set(t.DIRECTION, excluded(t.DIRECTION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
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
     * says so by selecting the index instead of ranking over it. The index is the element's own,
     * read off {@code graphql_ast_value_entry}: a decode states what an element meant and not where
     * it sat, that being a fact the element already carries.
     *
     * <p>Reaching the element from the application is one join and not a walk down the value tree.
     * {@code graphql_ast_value_entry} repeats the holder on every node of an expression, so every
     * element of the list names the applied argument directly, and the decode is found from the
     * element by the key it now carries.
     *
     * <p>An element with no index of its own is the one the author wrote without a list around it.
     * GraphQL coerces a lone value to the list of one, so the argument's whole expression is that
     * element, and a value at the root of an expression has no enclosing list to hold a position in.
     * Index zero is what the coercion says it is, and stating it here is cheaper than a second
     * relation for the one-element spelling.
     */
    private static void defaultOrderFields(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimedOnField(dsl, graph, "defaultOrder");
        var e = GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
        var v = GRAPHQL_AST_VALUE_ENTRY;
        var a = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var t = GRAPHITRON_DEFAULT_ORDER_FIELD_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.POSITION, t.NAME_REF, t.COLLATE,
                t.DIRECTION, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(FIELD_NAME),
                    coalesce(v.POSITION, inline(0)), e.NAME_REF, e.COLLATE, e.DIRECTION, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .join(a).on(a.GRAPH_NAME.eq(graph))
                    .and(a.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(a.PARENT_LINE.eq(c.field(SITE_LINE)))
                    .and(a.PARENT_COLUMN.eq(c.field(SITE_COLUMN)))
                    .and(a.NAME.eq("fields"))
                .join(v).on(v.GRAPH_NAME.eq(graph))
                    .and(v.SOURCE_NAME.eq(a.SOURCE_NAME))
                    .and(v.HOLDER_LINE.eq(a.SOURCE_LINE))
                    .and(v.HOLDER_COLUMN.eq(a.SOURCE_COLUMN))
                .join(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(v.SOURCE_NAME))
                    .and(e.SOURCE_LINE.eq(v.SOURCE_LINE))
                    .and(e.SOURCE_COLUMN.eq(v.SOURCE_COLUMN))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.NAME_REF, excluded(t.NAME_REF))
            .set(t.COLLATE, excluded(t.COLLATE))
            .set(t.DIRECTION, excluded(t.DIRECTION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
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
    private static void records(DSLContext dsl, String graph,
                                        LocalDateTime touchedAt) {
        var c = claimed(dsl, graph, "record");
        var e = GRAPHITRON_AST_RECORD_ENTRY;
        var t = GRAPHITRON_RECORD_ENTRY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_LINE, t.SOURCE_COLUMN, t.CLASS_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), c.field(TYPE_NAME), c.field(SITE_NAME),
                    c.field(DECLARATION_LINE), c.field(DECLARATION_COLUMN), c.field(SITE_LINE),
                    c.field(SITE_COLUMN), e.CLASS_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(c)
                .leftJoin(e).on(e.GRAPH_NAME.eq(graph))
                    .and(e.SOURCE_NAME.eq(c.field(SITE_NAME)))
                    .and(e.SOURCE_LINE.eq(c.field(SITE_LINE)))
                    .and(e.SOURCE_COLUMN.eq(c.field(SITE_COLUMN)))
                .where(c.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }
}
