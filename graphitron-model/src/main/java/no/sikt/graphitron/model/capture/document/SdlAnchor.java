package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_IMPLEMENTS_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_UNION_MEMBER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.choose;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Derives a graph's anchors from the entry rows the same reading wrote.
 *
 * <p>An anchor is what the corpus says, where an entry is what one document says. So this runs once
 * for the graph after every document has been read, and not once per document: a coordinate is
 * declared by the corpus, and no single file's reading can say that one went away.
 *
 * <p>One statement per anchor, an insert over a select over the entries, so the derivation is the
 * statement rather than a walk that reads rows into Java and writes them back. Nothing is read here
 * and nothing is decided here.
 *
 * <p>The anchors do not wait for the corpus to build. They are derived from the entries, so a
 * corpus that graphql-java refused still has them, which is the ordinary state of a schema while
 * somebody is editing it. Whether it built is {@code graphql_schema_problem}'s to say.
 *
 * <p>Mark and sweep, per graph. Every row carries the reading's instant and the reading ends by
 * deleting the graph's rows carrying a different one, which are the coordinates the corpus stopped
 * declaring and which an upsert cannot find: there is no incoming row to match.
 *
 * <h2>A union is a set or a bag, and which one says whether a choice is being made</h2>
 *
 * <p>Several anchors are filled from more than one population, and each such statement makes the
 * same decision in one of two ways. Reading the keyword tells a reader which, so it is worth
 * knowing what each means here.
 *
 * <p>{@code UNION} is a set, and it is the right one where the arms cannot disagree. Two documents
 * declaring one type contribute the same coordinate under the same kind, so the duplicate carries
 * no information and discarding it settles the anchor. Nothing chooses, because there is nothing to
 * choose between.
 *
 * <p>{@code UNION ALL} is a bag, and it is the right one where the arms can disagree and one of
 * them has to win. A coordinate declared as both an object and an enum is two candidates, and which
 * survives is the oldest, so each candidate has to arrive carrying the source that decides it.
 * Deduplicating first would discard the column the choice is made on. So the bag feeds a
 * {@code row_number}, the rank picks one candidate per key, and the anchor gets a set at the end
 * rather than at the start.
 *
 * <p>The distinction is worth reading for, because a statement that unions with {@code ALL} and then
 * does not rank is a statement that has not decided anything, and the key will decide for it by
 * refusing a row.
 */
public final class SdlAnchor {

    private SdlAnchor() {}

    /**
     * Makes {@code graph}'s anchors be what its entry rows now say.
     *
     * <p>The instant is the caller's and must be the one the entries carry, the sweep telling
     * readings apart by it.
     */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        elements(dsl, graph, touchedAt);
        typeElements(dsl, graph, touchedAt);
        fieldElements(dsl, graph, touchedAt);
        enumValueElements(dsl, graph, touchedAt);
        argumentElements(dsl, graph, touchedAt);
        typeDeclarations(dsl, graph, touchedAt);
        types(dsl, graph, touchedAt);
        fields(dsl, graph, touchedAt);
        enumValues(dsl, graph, touchedAt);
        fieldArguments(dsl, graph, touchedAt);
        unionMembers(dsl, graph, touchedAt);
        implementsInterfaces(dsl, graph, touchedAt);
        directives(dsl, graph, touchedAt);
        directiveLocations(dsl, graph, touchedAt);
        directiveArguments(dsl, graph, touchedAt);
        rootOperations(dsl, graph, touchedAt);
        typeDirectives(dsl, graph, touchedAt);
        fieldDirectives(dsl, graph, touchedAt);
        argumentDirectives(dsl, graph, touchedAt);
        enumValueDirectives(dsl, graph, touchedAt);
        schemaDirectives(dsl, graph, touchedAt);
        typeDirectiveArguments(dsl, graph, touchedAt);
        fieldDirectiveArguments(dsl, graph, touchedAt);
        argumentDirectiveArguments(dsl, graph, touchedAt);
        enumValueDirectiveArguments(dsl, graph, touchedAt);
        schemaDirectiveArguments(dsl, graph, touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /**
     * Every coordinate the corpus declares, and what kind of element sits at it.
     *
     * <p>Five arms, one per element kind, and after the entry family split by parent site each kind
     * is exactly one relation: a field and an input field are spelled alike and told apart by which
     * relation holds them, so neither arm needs a discriminator.
     *
     * <p>A bag and then a rank, on the terms the class comment sets out. Two arms can offer one
     * coordinate under two kinds, which is a corpus declaring {@code Film} as both an object and an
     * enum, and the oldest declaration wins it: that is the rule every collision in this store
     * takes, so each candidate carries the source the rank orders by.
     *
     * <p>An anchor holds no duplicates and refuses nothing. A corpus like that does not build and
     * says so in {@code graphql_schema_problem}, and its anchors still stand, which is what an
     * author halfway through a rename needs from them.
     */
    private static void elements(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var i = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        var e = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        var a = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        var s = STORE_SOURCE;
        var t = GRAPHQL_ELEMENT;
        var candidates = dsl
            .select(d.COORDINATE.as(COORDINATE), inline("NAMED_TYPE").as(ELEMENT_KIND),
                d.SOURCE_NAME.as(SITE_NAME), d.SOURCE_LINE.as(SITE_LINE),
                d.SOURCE_COLUMN.as(SITE_COLUMN))
            .from(d).where(d.GRAPH_NAME.eq(graph))
            .unionAll(dsl
                .select(f.COORDINATE, inline("FIELD"), f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN)
                .from(f).where(f.GRAPH_NAME.eq(graph)))
            .unionAll(dsl
                .select(i.COORDINATE, inline("INPUT_FIELD"), i.SOURCE_NAME, i.SOURCE_LINE,
                    i.SOURCE_COLUMN)
                .from(i).where(i.GRAPH_NAME.eq(graph)))
            .unionAll(dsl
                .select(e.COORDINATE, inline("ENUM_VALUE"), e.SOURCE_NAME, e.SOURCE_LINE,
                    e.SOURCE_COLUMN)
                .from(e).where(e.GRAPH_NAME.eq(graph)))
            .unionAll(dsl
                .select(a.COORDINATE, inline("FIELD_ARGUMENT"), a.SOURCE_NAME, a.SOURCE_LINE,
                    a.SOURCE_COLUMN)
                .from(a).where(a.GRAPH_NAME.eq(graph)))
            .asTable("candidates");
        var ranked = dsl
            .select(candidates.field(COORDINATE), candidates.field(ELEMENT_KIND),
                rowNumber().over(partitionBy(candidates.field(COORDINATE)).orderBy(
                    coalesce(s.MTIME, val(BEFORE_EVERY_FILE, s.MTIME)).asc(),
                    candidates.field(SITE_NAME).asc(), candidates.field(SITE_LINE).asc(),
                    candidates.field(SITE_COLUMN).asc())).as(RANK))
            .from(candidates)
            .join(s).on(s.SOURCE_NAME.eq(candidates.field(SITE_NAME)))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.ELEMENT_KIND, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(COORDINATE),
                    ranked.field(ELEMENT_KIND), val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.ELEMENT_KIND, excluded(t.ELEMENT_KIND))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * A coordinate under the key its own population spells it from, which is what a reader with a
     * type and a field name joins through to reach anything keyed by coordinate.
     *
     * <p>Both kinds of field land here, an object's and an input object's. They are one relation
     * because they are one key shape and one spelling, which is the same reason
     * {@code graphql_element} tells them apart by kind and this does not: a reader arriving with a
     * type and a field name already knows which it meant.
     *
     * <p>A set, because the two arms cannot disagree: a key determines its coordinate, so an arm
     * offering a key offers the only coordinate that key has. There is nothing to rank.
     */
    private static void fieldElements(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var i = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        var t = GRAPHQL_FIELD_ELEMENT;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.COORDINATE, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), f.TYPE_NAME, f.NAME, f.COORDINATE,
                    val(touchedAt, t.TOUCHED_AT))
                .from(f).where(f.GRAPH_NAME.eq(graph))
                .union(dsl
                    .select(val(graph, t.GRAPH_NAME), i.TYPE_NAME, i.NAME, i.COORDINATE,
                        val(touchedAt, t.TOUCHED_AT))
                    .from(i).where(i.GRAPH_NAME.eq(graph))))
            .onDuplicateKeyUpdate()
            .set(t.COORDINATE, excluded(t.COORDINATE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** A named type under its own name, which is the coordinate spelled as the key already is. */
    private static void typeElements(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var t = GRAPHQL_TYPE_ELEMENT;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.COORDINATE, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME), d.NAME, d.COORDINATE,
                    val(touchedAt, t.TOUCHED_AT))
                .from(d).where(d.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.COORDINATE, excluded(t.COORDINATE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** An enum value under its type and its own name. */
    private static void enumValueElements(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        var t = GRAPHQL_ENUM_VALUE_ELEMENT;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.VALUE_NAME, t.COORDINATE, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME), e.TYPE_NAME, e.NAME, e.COORDINATE,
                    val(touchedAt, t.TOUCHED_AT))
                .from(e).where(e.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.COORDINATE, excluded(t.COORDINATE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** A field argument under the three names that reach it. */
    private static void argumentElements(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        var t = GRAPHQL_ARGUMENT_ELEMENT;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ARGUMENT_NAME, t.COORDINATE,
                t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME), a.TYPE_NAME, a.FIELD_NAME, a.NAME,
                    a.COORDINATE, val(touchedAt, t.TOUCHED_AT))
                .from(a).where(a.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.COORDINATE, excluded(t.COORDINATE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The two columns a window function writes and the select above it reads back. Named once,
     * because filtering on a window result needs a derived table and these two names are the only
     * thing about these statements the compiler cannot check for us.
     */
    private static final Field<Integer> RANK = field(name("rank"), Integer.class);
    private static final Field<Integer> ORDINAL = field(name("ordinal"), Integer.class);

    /** The columns a candidate row carries up into a ranking that sits above a union. */
    private static final Field<String> TYPE_NAME = field(name("type_name"), String.class);
    private static final Field<String> FIELD_NAME = field(name("field_name"), String.class);
    private static final Field<String> TYPE_SDL = field(name("type_sdl"), String.class);
    private static final Field<String> NAMED_TYPE = field(name("named_type"), String.class);
    private static final Field<Boolean> NON_NULL = field(name("non_null"), Boolean.class);
    private static final Field<Boolean> IS_LIST = field(name("is_list"), Boolean.class);
    private static final Field<Boolean> ITEM_NON_NULL = field(name("item_non_null"), Boolean.class);
    private static final Field<String> DEFAULT_VALUE_SDL = field(name("default_value_sdl"), String.class);
    private static final Field<String> DESCRIPTION = field(name("description"), String.class);
    private static final Field<Integer> MERGE_ORDINAL = field(name("merge_ordinal"), Integer.class);

    /** Which arm a root-operation candidate came from, spelled sorting before assumed. */
    private static final Field<Integer> PRECEDENCE = field(name("precedence"), Integer.class);
    /**
     * The operation, upper-cased into the vocabulary the anchor admits. The entry spells it as the
     * author wrote it and the grammar writes it lower case, deliberately unchecked, so folding it
     * is the anchor's job and not the transcription's.
     */
    private static final Field<String> OPERATION = field(name("operation"), String.class);
    private static final Field<LocalDateTime> MTIME = field(name("mtime"), LocalDateTime.class);

    /** The coordinate and kind a candidate row carries into the ranking above the union. */
    private static final Field<String> COORDINATE = field(name("coordinate"), String.class);
    private static final Field<String> ELEMENT_KIND = field(name("element_kind"), String.class);

    /** An argument's own name, where the relation it is read beside also has a {@code name}. */
    private static final Field<String> ARGUMENT_NAME = field(name("argument_name"), String.class);
    private static final Field<String> VALUE_NAME = field(name("value_name"), String.class);
    private static final Field<String> DIRECTIVE_NAME = field(name("directive_name"), String.class);

    /** The site columns a candidate carries up into a ranking that sits above a union. */
    private static final Field<Integer> DECLARATION_LINE = field(name("declaration_line"), Integer.class);
    private static final Field<Integer> DECLARATION_COLUMN = field(name("declaration_column"), Integer.class);
    private static final Field<String> SITE_NAME = field(name("site_name"), String.class);
    private static final Field<Integer> SITE_LINE = field(name("site_line"), Integer.class);
    private static final Field<Integer> SITE_COLUMN = field(name("site_column"), Integer.class);

    /**
     * Where a source with no modification time sorts, which is before every source that has one.
     *
     * <p>The bundled directive vocabulary is the case and the only one: it is a classpath resource
     * rather than a file, so nothing can stat it and its {@code store_source.mtime} is null. The
     * reader offers it before any authored document, so sorting it first is the reading order
     * written down rather than a convention invented here. Coalesced rather than left null because
     * where a null sorts is a dialect's opinion, and this ordering decides which declaration of a
     * name the corpus honours.
     */
    private static final LocalDateTime BEFORE_EVERY_FILE = LocalDateTime.of(1, 1, 1, 0, 0);

    /**
     * Every declaration of a named type, ranked in the order the corpus merges them.
     *
     * <p>The merge order is stated once, here, and nothing downstream recomputes it: a type's kind
     * and a field's ordinal both order by the {@code merge_ordinal} this writes. That is why this
     * relation is derived before the four that read it, and it is the only place
     * {@code store_source.mtime} is joined at all.
     *
     * <p>The order is the reader's own rather than one invented for the store: a base declaration
     * before an extension, then oldest file first with the file's name to break a tie, which is
     * {@code SchemaLoader.oldestFirst} spelled as an {@code ORDER BY}. Position within one file
     * closes it, two declarations being unable to share one.
     */
    private static void typeDeclarations(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var s = STORE_SOURCE;
        var t = GRAPHQL_TYPE_DECLARATION;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.MERGE_ORDINAL, t.IS_EXTENSION, t.KIND, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), d.NAME, d.SOURCE_NAME, d.SOURCE_LINE,
                    d.SOURCE_COLUMN,
                    rowNumber().over(partitionBy(d.NAME).orderBy(
                        d.IS_EXTENSION.asc(),
                        coalesce(s.MTIME, val(BEFORE_EVERY_FILE, s.MTIME)).asc(),
                        d.SOURCE_NAME.asc(), d.SOURCE_LINE.asc(), d.SOURCE_COLUMN.asc())),
                    d.IS_EXTENSION, d.KIND, val(touchedAt, t.TOUCHED_AT))
                .from(d).join(s).on(s.SOURCE_NAME.eq(d.SOURCE_NAME))
                .where(d.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.MERGE_ORDINAL, excluded(t.MERGE_ORDINAL))
            .set(t.IS_EXTENSION, excluded(t.IS_EXTENSION))
            .set(t.KIND, excluded(t.KIND))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * A named type as the corpus sees it, which is its first declaration in merge order.
     *
     * <p>The first and not a merge of the rest, because neither column admits one. A kind is the
     * base definition's, an extension being unable to change it, and a description is the base
     * definition's because the grammar gives an extension nowhere to write one. Both are what this
     * relation's own comments already say; taking rank one is how a statement says it.
     */
    private static void types(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_TYPE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.KIND, t.DESCRIPTION, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), d.NAME, d.KIND, d.DESCRIPTION,
                    val(touchedAt, t.TOUCHED_AT))
                .from(d)
                .join(m).on(m.GRAPH_NAME.eq(graph))
                    .and(m.TYPE_NAME.eq(d.NAME))
                    .and(m.SOURCE_NAME.eq(d.SOURCE_NAME))
                    .and(m.SOURCE_LINE.eq(d.SOURCE_LINE))
                    .and(m.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
                .where(d.GRAPH_NAME.eq(graph))
                .and(m.MERGE_ORDINAL.eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.KIND, excluded(t.KIND))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * Every field of every type, at the ordinal the effective type gives it.
     *
     * <p>Both populations in one statement: an object's fields and an input object's. They are one
     * relation because they are one key shape, and they are one statement because a corpus that
     * declares {@code Film} as both an object and an input object would otherwise have them
     * numbered from one twice and the second write would quietly win. Ranking across both arms
     * makes the oldest declaration win instead, which is the rule the rest of the store takes.
     *
     * <p>The ordinal is a fact about the corpus rather than about the file, which is why the entries
     * refuse to carry one: the same field in the same file takes a different ordinal the day another
     * file extends its type. Base declaration first and then extensions, which is the declaration's
     * {@code merge_ordinal}, then the field's own position inside it.
     *
     * <p>Only the input arm can carry a default, so the object arm supplies a typed null rather than
     * the two arms differing in shape.
     */
    private static void fields(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var i = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_FIELD;
        var candidates = dsl
            .select(f.TYPE_NAME.as(TYPE_NAME), f.NAME.as(FIELD_NAME),
                f.PARENT_LINE.as(DECLARATION_LINE), f.PARENT_COLUMN.as(DECLARATION_COLUMN),
                f.TYPE_SDL.as(TYPE_SDL), f.NAMED_TYPE.as(NAMED_TYPE), f.NON_NULL.as(NON_NULL),
                f.IS_LIST.as(IS_LIST), f.ITEM_NON_NULL.as(ITEM_NON_NULL),
                castNull(t.DEFAULT_VALUE_SDL).as(DEFAULT_VALUE_SDL), f.DESCRIPTION.as(DESCRIPTION),
                f.SOURCE_NAME.as(SITE_NAME), f.SOURCE_LINE.as(SITE_LINE),
                f.SOURCE_COLUMN.as(SITE_COLUMN), m.MERGE_ORDINAL.as(MERGE_ORDINAL))
            .from(f)
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(f.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(f.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(f.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(f.PARENT_COLUMN))
            .where(f.GRAPH_NAME.eq(graph))
            .unionAll(dsl
                .select(i.TYPE_NAME, i.NAME, i.PARENT_LINE, i.PARENT_COLUMN, i.TYPE_SDL,
                    i.NAMED_TYPE, i.NON_NULL, i.IS_LIST, i.ITEM_NON_NULL, i.DEFAULT_VALUE_SDL,
                    i.DESCRIPTION, i.SOURCE_NAME, i.SOURCE_LINE, i.SOURCE_COLUMN, m.MERGE_ORDINAL)
                .from(i)
                .join(m).on(m.GRAPH_NAME.eq(graph))
                    .and(m.TYPE_NAME.eq(i.TYPE_NAME))
                    .and(m.SOURCE_NAME.eq(i.SOURCE_NAME))
                    .and(m.SOURCE_LINE.eq(i.PARENT_LINE))
                    .and(m.SOURCE_COLUMN.eq(i.PARENT_COLUMN))
                .where(i.GRAPH_NAME.eq(graph)))
            .asTable("candidates");
        var ranked = dsl
            .select(candidates.asterisk(),
                rowNumber().over(partitionBy(candidates.field(TYPE_NAME),
                    candidates.field(FIELD_NAME)).orderBy(
                    candidates.field(MERGE_ORDINAL).asc(), candidates.field(SITE_LINE).asc(),
                    candidates.field(SITE_COLUMN).asc())).as(RANK),
                rowNumber().over(partitionBy(candidates.field(TYPE_NAME)).orderBy(
                    candidates.field(MERGE_ORDINAL).asc(), candidates.field(SITE_LINE).asc(),
                    candidates.field(SITE_COLUMN).asc())).as(ORDINAL))
            .from(candidates)
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.TYPE_SDL, t.NAMED_TYPE, t.NON_NULL, t.IS_LIST,
                t.ITEM_NON_NULL, t.DEFAULT_VALUE_SDL, t.DESCRIPTION, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(TYPE_NAME), ranked.field(FIELD_NAME),
                    ranked.field(ORDINAL), ranked.field(DECLARATION_LINE),
                    ranked.field(DECLARATION_COLUMN), ranked.field(TYPE_SDL),
                    ranked.field(NAMED_TYPE), ranked.field(NON_NULL), ranked.field(IS_LIST),
                    ranked.field(ITEM_NON_NULL), ranked.field(DEFAULT_VALUE_SDL),
                    ranked.field(DESCRIPTION), ranked.field(SITE_NAME), ranked.field(SITE_LINE),
                    ranked.field(SITE_COLUMN), val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
            .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
            .set(t.NON_NULL, excluded(t.NON_NULL))
            .set(t.IS_LIST, excluded(t.IS_LIST))
            .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** An enum's values, ordered as its fields would be: base declaration first, then extensions. */
    private static void enumValues(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_ENUM_VALUE;
        var ranked = dsl
            .select(e.TYPE_NAME, e.NAME, e.PARENT_LINE, e.PARENT_COLUMN, e.DESCRIPTION,
                e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                rowNumber().over(partitionBy(e.TYPE_NAME, e.NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), e.SOURCE_LINE.asc(), e.SOURCE_COLUMN.asc())).as(RANK),
                rowNumber().over(partitionBy(e.TYPE_NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), e.SOURCE_LINE.asc(), e.SOURCE_COLUMN.asc())).as(ORDINAL))
            .from(e)
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(e.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(e.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(e.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(e.PARENT_COLUMN))
            .where(e.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.VALUE_NAME, t.ORDINAL, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.DESCRIPTION, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(e.TYPE_NAME), ranked.field(e.NAME),
                    ranked.field(ORDINAL), ranked.field(e.PARENT_LINE),
                    ranked.field(e.PARENT_COLUMN), ranked.field(e.DESCRIPTION),
                    ranked.field(e.SOURCE_NAME), ranked.field(e.SOURCE_LINE),
                    ranked.field(e.SOURCE_COLUMN), val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * A field's arguments, in the order they were written inside it.
     *
     * <p>Two hops rather than one: an argument's parent is a field definition, and the merge order
     * that decides which of two declarations of one field wins belongs to the declaration that field
     * sits in. So the join climbs to the grandparent, which is the only place in this class that
     * does.
     */
    private static void fieldArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_ARGUMENT;
        var ranked = dsl
            .select(a.TYPE_NAME, a.FIELD_NAME, a.NAME, a.TYPE_SDL, a.NAMED_TYPE, a.NON_NULL,
                a.IS_LIST, a.ITEM_NON_NULL, a.DEFAULT_VALUE_SDL, a.DESCRIPTION, a.SOURCE_NAME,
                a.SOURCE_LINE, a.SOURCE_COLUMN,
                rowNumber().over(partitionBy(a.TYPE_NAME, a.FIELD_NAME, a.NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc())).as(RANK),
                rowNumber().over(partitionBy(a.TYPE_NAME, a.FIELD_NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc())).as(ORDINAL))
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
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ARGUMENT_NAME, t.ORDINAL,
                t.TYPE_SDL, t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL,
                t.DEFAULT_VALUE_SDL, t.DESCRIPTION, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(a.TYPE_NAME),
                    ranked.field(a.FIELD_NAME), ranked.field(a.NAME), ranked.field(ORDINAL),
                    ranked.field(a.TYPE_SDL), ranked.field(a.NAMED_TYPE), ranked.field(a.NON_NULL),
                    ranked.field(a.IS_LIST), ranked.field(a.ITEM_NON_NULL),
                    ranked.field(a.DEFAULT_VALUE_SDL), ranked.field(a.DESCRIPTION),
                    ranked.field(a.SOURCE_NAME), ranked.field(a.SOURCE_LINE),
                    ranked.field(a.SOURCE_COLUMN), val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
            .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
            .set(t.NON_NULL, excluded(t.NON_NULL))
            .set(t.IS_LIST, excluded(t.IS_LIST))
            .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The members a union admits, in the order the union wrote them.
     *
     * <p>Its own relation rather than an arm of a shared one, because the declaring end is the
     * union: the key hangs off it, both foreign keys hang off it, and the position is the union's
     * authored order. Nothing here needs to say which kind it is.
     *
     * <p>Numbered from zero, where {@link #implementsInterfaces} numbers from one. The two bases
     * differ and consumers rely on each: a mapping-constant fingerprint digests a list in this
     * order, so the numbering is part of what these relations promise.
     */
    private static void unionMembers(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var u = GRAPHQL_AST_UNION_MEMBER_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_UNION_MEMBER;
        var ranked = dsl
            .select(u.TYPE_NAME, u.MEMBER_NAME, u.PARENT_LINE, u.PARENT_COLUMN, u.SOURCE_NAME,
                u.SOURCE_LINE, u.SOURCE_COLUMN,
                rowNumber().over(partitionBy(u.TYPE_NAME, u.MEMBER_NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), u.SOURCE_LINE.asc(), u.SOURCE_COLUMN.asc())).as(RANK),
                rowNumber().over(partitionBy(u.TYPE_NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), u.SOURCE_LINE.asc(), u.SOURCE_COLUMN.asc()))
                    .minus(inline(1)).as(ORDINAL))
            .from(u)
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(u.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(u.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(u.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(u.PARENT_COLUMN))
            .where(u.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.UNION_NAME, t.MEMBER_TYPE_NAME, t.POSITION, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(u.TYPE_NAME),
                    ranked.field(u.MEMBER_NAME), ranked.field(ORDINAL), ranked.field(u.PARENT_LINE),
                    ranked.field(u.PARENT_COLUMN), ranked.field(u.SOURCE_NAME),
                    ranked.field(u.SOURCE_LINE), ranked.field(u.SOURCE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.POSITION, excluded(t.POSITION))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The interfaces a type declares it implements, in the order of its implements clause.
     *
     * <p>The declaring end is the type, so this keys on the type where {@link #unionMembers} keys on
     * the union. That asymmetry is why the two are separate relations: each keys on the end that
     * declares it, each references that end, and neither needs a discriminator to say which it is.
     * {@code graphql_poly_member} unions them for the readers that take either.
     *
     * <p>The position orders an interface's implementors, so it partitions by the interface rather
     * than by the type: a reader asking who implements {@code Node} wants them in the order the
     * corpus declares them. A mapping-constant fingerprint digests a list in this order, so the
     * numbering is a fact consumers depend on rather than a presentation choice.
     */
    private static void implementsInterfaces(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var i = GRAPHQL_AST_IMPLEMENTS_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_IMPLEMENTS_INTERFACE;
        var ranked = dsl
            .select(i.TYPE_NAME, i.INTERFACE_NAME, i.PARENT_LINE, i.PARENT_COLUMN, i.SOURCE_NAME,
                i.SOURCE_LINE, i.SOURCE_COLUMN,
                rowNumber().over(partitionBy(i.TYPE_NAME, i.INTERFACE_NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), i.SOURCE_LINE.asc(), i.SOURCE_COLUMN.asc())).as(RANK),
                rowNumber().over(partitionBy(i.INTERFACE_NAME).orderBy(
                    m.MERGE_ORDINAL.asc(), i.SOURCE_LINE.asc(), i.SOURCE_COLUMN.asc())).as(ORDINAL))
            .from(i)
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(i.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(i.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(i.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(i.PARENT_COLUMN))
            .where(i.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.INTERFACE_NAME, t.POSITION, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(i.TYPE_NAME),
                    ranked.field(i.INTERFACE_NAME), ranked.field(ORDINAL),
                    ranked.field(i.PARENT_LINE), ranked.field(i.PARENT_COLUMN),
                    ranked.field(i.SOURCE_NAME), ranked.field(i.SOURCE_LINE),
                    ranked.field(i.SOURCE_COLUMN), val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.POSITION, excluded(t.POSITION))
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The directive vocabulary the corpus declares, one row per name.
     *
     * <p>The second and last place {@code store_source.mtime} is joined. A directive has no
     * extension form, so there is no {@code merge_ordinal} to inherit and no declaration relation to
     * inherit it from: two documents declaring one directive are simply two declarations, and which
     * of them the corpus honours is the reading order, the same rule the named types take.
     */
    private static void directives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
        var s = STORE_SOURCE;
        var t = GRAPHQL_DIRECTIVE;
        var ranked = dsl
            .select(d.NAME, d.REPEATABLE, d.DESCRIPTION, d.SOURCE_NAME, d.SOURCE_LINE,
                d.SOURCE_COLUMN,
                rowNumber().over(partitionBy(d.NAME).orderBy(
                    coalesce(s.MTIME, val(BEFORE_EVERY_FILE, s.MTIME)).asc(),
                    d.SOURCE_NAME.asc(), d.SOURCE_LINE.asc(), d.SOURCE_COLUMN.asc())).as(RANK))
            .from(d).join(s).on(s.SOURCE_NAME.eq(d.SOURCE_NAME))
            .where(d.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.REPEATABLE, t.DESCRIPTION, t.SOURCE_NAME,
                t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(d.NAME), ranked.field(d.REPEATABLE),
                    ranked.field(d.DESCRIPTION), ranked.field(d.SOURCE_NAME),
                    ranked.field(d.SOURCE_LINE), ranked.field(d.SOURCE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.REPEATABLE, excluded(t.REPEATABLE))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * Where each directive may be written, which the key is and the payload is not.
     *
     * <p>Read from the declaration that won rather than from every declaration of the name. Two
     * documents declaring one directive at different locations are one vocabulary entry, and taking
     * the union of both would give it locations no single declaration granted. Joining through
     * {@code graphql_directive}'s own position is what says "the one the corpus honours", and it is
     * why this needs no ordering of its own.
     */
    private static void directiveLocations(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var l = GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY;
        var w = GRAPHQL_DIRECTIVE;
        var t = GRAPHQL_DIRECTIVE_LOCATION;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.LOCATION, t.TOUCHED_AT)
            .select(dsl
                .selectDistinct(val(graph, t.GRAPH_NAME), w.DIRECTIVE_NAME, l.LOCATION,
                    val(touchedAt, t.TOUCHED_AT))
                .from(l)
                .join(w).on(w.GRAPH_NAME.eq(graph))
                    .and(w.SOURCE_NAME.eq(l.SOURCE_NAME))
                    .and(w.SOURCE_LINE.eq(l.PARENT_LINE))
                    .and(w.SOURCE_COLUMN.eq(l.PARENT_COLUMN))
                .where(l.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * A directive's arguments, in the order they were written inside the declaration that won.
     *
     * <p>Ordered by position alone, which is all one declaration needs: reaching them through
     * {@code graphql_directive} has already settled which declaration that is, so nothing here reads
     * a modification time.
     */
    private static void directiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
        var w = GRAPHQL_DIRECTIVE;
        var t = GRAPHQL_DIRECTIVE_ARGUMENT;
        var ranked = dsl
            .select(w.DIRECTIVE_NAME, a.NAME.as(ARGUMENT_NAME), a.TYPE_SDL, a.NAMED_TYPE,
                a.NON_NULL, a.IS_LIST, a.ITEM_NON_NULL, a.DEFAULT_VALUE_SDL, a.DESCRIPTION,
                a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN,
                rowNumber().over(partitionBy(w.DIRECTIVE_NAME, a.NAME).orderBy(
                    a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc())).as(RANK),
                rowNumber().over(partitionBy(w.DIRECTIVE_NAME).orderBy(
                    a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc())).as(ORDINAL))
            .from(a)
            .join(w).on(w.GRAPH_NAME.eq(graph))
                .and(w.SOURCE_NAME.eq(a.SOURCE_NAME))
                .and(w.SOURCE_LINE.eq(a.PARENT_LINE))
                .and(w.SOURCE_COLUMN.eq(a.PARENT_COLUMN))
            .where(a.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.ARGUMENT_NAME, t.ORDINAL, t.TYPE_SDL,
                t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.DEFAULT_VALUE_SDL,
                t.DESCRIPTION, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(w.DIRECTIVE_NAME),
                    ranked.field(ARGUMENT_NAME), ranked.field(ORDINAL),
                    ranked.field(a.TYPE_SDL), ranked.field(a.NAMED_TYPE), ranked.field(a.NON_NULL),
                    ranked.field(a.IS_LIST), ranked.field(a.ITEM_NON_NULL),
                    ranked.field(a.DEFAULT_VALUE_SDL), ranked.field(a.DESCRIPTION),
                    ranked.field(a.SOURCE_NAME), ranked.field(a.SOURCE_LINE),
                    ranked.field(a.SOURCE_COLUMN), val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
            .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
            .set(t.NON_NULL, excluded(t.NON_NULL))
            .set(t.IS_LIST, excluded(t.IS_LIST))
            .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * Which type answers each operation, written or assumed.
     *
     * <p>One statement over both arms, ranked, rather than two with the second checking what the
     * first left. A {@code schema} block binds an operation outright and that binding wins; where no
     * block spells one, an object type named for the operation is that operation's root by the
     * specification's own convention. Making the spelled arm sort first says exactly that, and says
     * it without either statement reading what the other wrote: a guard that queries the table it is
     * inserting into is correct only while the statements run in one order, and nothing in the
     * relation records that they did.
     *
     * <p>The convention row carries no position, and its three provenance columns are null for the
     * reason the relation's comment gives: no SDL line spells the binding, so there is nowhere to
     * point. That is also how a reader tells the two apart without a discriminator.
     *
     * <p>An operation the author spelled that is none of the three is dropped rather than written.
     * The entry transcribes whatever was written and this vocabulary is the specification's, so a
     * corpus naming a fourth operation is a corpus with a problem recorded elsewhere, not a reason
     * for every anchor in the graph to fail.
     */
    private static void rootOperations(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY;
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var s = STORE_SOURCE;
        var t = GRAPHQL_ROOT_OPERATION;

        var spelled = dsl
            .select(upper(o.OPERATION).as(OPERATION), o.TYPE_NAME.as(TYPE_NAME),
                o.SOURCE_NAME.as(SITE_NAME), o.SOURCE_LINE.as(SITE_LINE),
                o.SOURCE_COLUMN.as(SITE_COLUMN), inline(0).as(PRECEDENCE),
                coalesce(s.MTIME, val(BEFORE_EVERY_FILE, s.MTIME)).as(MTIME))
            .from(o).join(s).on(s.SOURCE_NAME.eq(o.SOURCE_NAME))
            .where(o.GRAPH_NAME.eq(graph))
            .and(upper(o.OPERATION).in(CONVENTION_ROOTS.keySet()));

        // The convention's own arm: a declared object type whose name is the operation's, carrying
        // no position because no line spells the binding, and sorting after anything spelled.
        var byConvention = dsl
            .select(operationOf(d.NAME).as(OPERATION), d.NAME, castNull(t.SOURCE_NAME),
                castNull(t.SOURCE_LINE), castNull(t.SOURCE_COLUMN), inline(1).as(PRECEDENCE),
                coalesce(s.MTIME, val(BEFORE_EVERY_FILE, s.MTIME)).as(MTIME))
            .from(d).join(s).on(s.SOURCE_NAME.eq(d.SOURCE_NAME))
            .where(d.GRAPH_NAME.eq(graph))
            .and(d.KIND.eq(inline("OBJECT")))
            .and(d.NAME.in(CONVENTION_ROOTS.values()));

        var candidates = spelled.unionAll(byConvention).asTable("candidates");
        var ranked = dsl
            .select(candidates.asterisk(),
                rowNumber().over(partitionBy(candidates.field(OPERATION)).orderBy(
                    candidates.field(PRECEDENCE).asc(), candidates.field(MTIME).asc(),
                    candidates.field(SITE_NAME).asc(), candidates.field(SITE_LINE).asc(),
                    candidates.field(SITE_COLUMN).asc())).as(RANK))
            .from(candidates)
            .asTable("ranked");

        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.OPERATION, t.TYPE_NAME, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(OPERATION), ranked.field(TYPE_NAME),
                    ranked.field(SITE_NAME), ranked.field(SITE_LINE), ranked.field(SITE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked).where(ranked.field(RANK).eq(1)))
            .onDuplicateKeyUpdate()
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }


    /**
     * Every directive an author applied, at the coordinate they applied it to. Five relations
     * because five kinds of coordinate carry one, which is the split the entries already made: each
     * reads one entry relation and resolves its parent position to a coordinate.
     *
     * <p>The ordinal is the repeat, numbered from zero within one coordinate and one directive
     * name, in merge order. A directive is not repeatable unless its definition says so, so on
     * almost every application it is zero and the column exists for the ones that are.
     *
     * <p>No rank filter, where the declaration anchors take rank one. Two documents applying one
     * directive to one type are two applications and the corpus has both; which of two declarations
     * of a name survives is a different question, and it is settled before this reads.
     *
     * <p>Directives applied to a directive definition's own arguments reach no relation here. The
     * anchor family has no coordinate for one, an argument of a definition not being a schema
     * element, and minting a spelling to hold it would put a coordinate in the store that the
     * specification does not have.
     */
    private static void typeDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_TYPE_DIRECTIVE;
        var ranked = dsl
            .select(d.NAME.as(TYPE_NAME), a.NAME.as(DIRECTIVE_NAME),
                d.SOURCE_LINE.as(DECLARATION_LINE), d.SOURCE_COLUMN.as(DECLARATION_COLUMN),
                a.SOURCE_NAME.as(SITE_NAME), a.SOURCE_LINE.as(SITE_LINE),
                a.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(d.NAME, a.NAME).orderBy(
                        m.MERGE_ORDINAL.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc()))
                    .minus(inline(1)).as(ORDINAL))
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
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.DIRECTIVE_NAME, t.ORDINAL, t.DECLARATION_LINE,
                t.DECLARATION_COLUMN, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(TYPE_NAME),
                    ranked.field(DIRECTIVE_NAME), ranked.field(ORDINAL),
                    ranked.field(DECLARATION_LINE), ranked.field(DECLARATION_COLUMN),
                    ranked.field(SITE_NAME), ranked.field(SITE_LINE), ranked.field(SITE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked))
            .onDuplicateKeyUpdate()
            .set(t.DECLARATION_LINE, excluded(t.DECLARATION_LINE))
            .set(t.DECLARATION_COLUMN, excluded(t.DECLARATION_COLUMN))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The directives on a field, and an input object's fields are fields here as they are in
     * {@link #fields}. Two entry relations and one anchor, because the coordinate is the same shape
     * and which relation holds the parent is the only thing that differs; ranked over the union, so
     * a repeat numbers across both arms rather than within one.
     */
    private static void fieldDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var v = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        var i = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_FIELD_DIRECTIVE;
        var candidates = dsl
            .select(f.TYPE_NAME.as(TYPE_NAME), f.NAME.as(FIELD_NAME), a.NAME.as(DIRECTIVE_NAME),
                a.SOURCE_NAME.as(SITE_NAME), a.SOURCE_LINE.as(SITE_LINE),
                a.SOURCE_COLUMN.as(SITE_COLUMN), m.MERGE_ORDINAL.as(MERGE_ORDINAL))
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
            .unionAll(dsl
                .select(i.TYPE_NAME, i.NAME, v.NAME, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                    m.MERGE_ORDINAL)
                .from(v)
                .join(i).on(i.GRAPH_NAME.eq(v.GRAPH_NAME))
                    .and(i.SOURCE_NAME.eq(v.SOURCE_NAME))
                    .and(i.SOURCE_LINE.eq(v.PARENT_LINE))
                    .and(i.SOURCE_COLUMN.eq(v.PARENT_COLUMN))
                .join(m).on(m.GRAPH_NAME.eq(graph))
                    .and(m.TYPE_NAME.eq(i.TYPE_NAME))
                    .and(m.SOURCE_NAME.eq(i.SOURCE_NAME))
                    .and(m.SOURCE_LINE.eq(i.PARENT_LINE))
                    .and(m.SOURCE_COLUMN.eq(i.PARENT_COLUMN))
                .where(v.GRAPH_NAME.eq(graph)))
            .asTable("candidates");
        var ranked = dsl
            .select(candidates.asterisk(),
                rowNumber().over(partitionBy(candidates.field(TYPE_NAME),
                        candidates.field(FIELD_NAME), candidates.field(DIRECTIVE_NAME)).orderBy(
                        candidates.field(MERGE_ORDINAL).asc(), candidates.field(SITE_LINE).asc(),
                        candidates.field(SITE_COLUMN).asc()))
                    .minus(inline(1)).as(ORDINAL))
            .from(candidates)
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(TYPE_NAME),
                    ranked.field(FIELD_NAME), ranked.field(DIRECTIVE_NAME), ranked.field(ORDINAL),
                    ranked.field(SITE_NAME), ranked.field(SITE_LINE), ranked.field(SITE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** The directives on a field's argument, whose parent is the argument the entry holds. */
    private static void argumentDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var v = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        var g = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_ARGUMENT_DIRECTIVE;
        var ranked = dsl
            .select(g.TYPE_NAME.as(TYPE_NAME), g.FIELD_NAME.as(FIELD_NAME),
                g.NAME.as(ARGUMENT_NAME), v.NAME.as(DIRECTIVE_NAME), v.SOURCE_NAME.as(SITE_NAME),
                v.SOURCE_LINE.as(SITE_LINE), v.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(g.TYPE_NAME, g.FIELD_NAME, g.NAME, v.NAME).orderBy(
                        m.MERGE_ORDINAL.asc(), v.SOURCE_LINE.asc(), v.SOURCE_COLUMN.asc()))
                    .minus(inline(1)).as(ORDINAL))
            .from(v)
            .join(g).on(g.GRAPH_NAME.eq(v.GRAPH_NAME))
                .and(g.SOURCE_NAME.eq(v.SOURCE_NAME))
                .and(g.SOURCE_LINE.eq(v.PARENT_LINE))
                .and(g.SOURCE_COLUMN.eq(v.PARENT_COLUMN))
            .join(f).on(f.GRAPH_NAME.eq(g.GRAPH_NAME))
                .and(f.SOURCE_NAME.eq(g.SOURCE_NAME))
                .and(f.SOURCE_LINE.eq(g.PARENT_LINE))
                .and(f.SOURCE_COLUMN.eq(g.PARENT_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(f.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(f.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(f.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(f.PARENT_COLUMN))
            .where(v.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ARGUMENT_NAME, t.DIRECTIVE_NAME,
                t.ORDINAL, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(TYPE_NAME),
                    ranked.field(FIELD_NAME), ranked.field(ARGUMENT_NAME),
                    ranked.field(DIRECTIVE_NAME), ranked.field(ORDINAL), ranked.field(SITE_NAME),
                    ranked.field(SITE_LINE), ranked.field(SITE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** The directives on an enum value, whose parent is the value the entry holds. */
    private static void enumValueDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
        var e = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        var t = GRAPHQL_ENUM_VALUE_DIRECTIVE;
        var ranked = dsl
            .select(e.TYPE_NAME.as(TYPE_NAME), e.NAME.as(VALUE_NAME), a.NAME.as(DIRECTIVE_NAME),
                a.SOURCE_NAME.as(SITE_NAME), a.SOURCE_LINE.as(SITE_LINE),
                a.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(e.TYPE_NAME, e.NAME, a.NAME).orderBy(
                        m.MERGE_ORDINAL.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc()))
                    .minus(inline(1)).as(ORDINAL))
            .from(a)
            .join(e).on(e.GRAPH_NAME.eq(a.GRAPH_NAME))
                .and(e.SOURCE_NAME.eq(a.SOURCE_NAME))
                .and(e.SOURCE_LINE.eq(a.PARENT_LINE))
                .and(e.SOURCE_COLUMN.eq(a.PARENT_COLUMN))
            .join(m).on(m.GRAPH_NAME.eq(graph))
                .and(m.TYPE_NAME.eq(e.TYPE_NAME))
                .and(m.SOURCE_NAME.eq(e.SOURCE_NAME))
                .and(m.SOURCE_LINE.eq(e.PARENT_LINE))
                .and(m.SOURCE_COLUMN.eq(e.PARENT_COLUMN))
            .where(a.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.VALUE_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(TYPE_NAME), ranked.field(VALUE_NAME),
                    ranked.field(DIRECTIVE_NAME), ranked.field(ORDINAL), ranked.field(SITE_NAME),
                    ranked.field(SITE_LINE), ranked.field(SITE_COLUMN),
                    val(touchedAt, t.TOUCHED_AT))
                .from(ranked))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The directives on the schema block, which has no coordinate to partition by: the graph is the
     * coordinate. Ordered by the file's own age rather than by a merge ordinal, a schema block
     * having no declaration relation to carry one, which is {@link #rootOperations}'s rule.
     */
    private static void schemaDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY;
        var s = STORE_SOURCE;
        var t = GRAPHQL_SCHEMA_DIRECTIVE;
        var ranked = dsl
            .select(a.NAME.as(DIRECTIVE_NAME), a.SOURCE_NAME.as(SITE_NAME),
                a.SOURCE_LINE.as(SITE_LINE), a.SOURCE_COLUMN.as(SITE_COLUMN),
                rowNumber().over(partitionBy(a.NAME).orderBy(
                        coalesce(s.MTIME, val(BEFORE_EVERY_FILE, s.MTIME)).asc(),
                        a.SOURCE_NAME.asc(), a.SOURCE_LINE.asc(), a.SOURCE_COLUMN.asc()))
                    .minus(inline(1)).as(ORDINAL))
            .from(a).join(s).on(s.SOURCE_NAME.eq(a.SOURCE_NAME))
            .where(a.GRAPH_NAME.eq(graph))
            .asTable("ranked");
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.ORDINAL, t.SOURCE_NAME, t.SOURCE_LINE,
                t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME), ranked.field(DIRECTIVE_NAME),
                    ranked.field(ORDINAL), ranked.field(SITE_NAME), ranked.field(SITE_LINE),
                    ranked.field(SITE_COLUMN), val(touchedAt, t.TOUCHED_AT))
                .from(ranked))
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }


    /**
     * The arguments an author passed to an application, one relation per coordinate the
     * applications have. Each joins the argument entry to the application anchor by the position of
     * the at sign it was written inside, which is the key that anchor already carries, so the
     * ordinal a repeat took is read rather than counted a second time.
     *
     * <p>Nothing is ranked. An argument is named once inside one application, so the key is the
     * application's plus the name; a document naming one twice is a schema problem, and the
     * upsert keeps the later of the two rather than refusing the row.
     */
    private static void typeDirectiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var g = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var d = GRAPHQL_TYPE_DIRECTIVE;
        var t = GRAPHQL_TYPE_DIRECTIVE_ARG;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.DIRECTIVE_ARGUMENT_NAME, t.VALUE_SDL, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    d.TYPE_NAME,
                    d.DIRECTIVE_NAME, d.ORDINAL, g.NAME, g.VALUE_SDL,
                    val(touchedAt, t.TOUCHED_AT))
                .from(g)
                // This reading's applications only. A directive replaced in place leaves the
                // application it displaced sitting at the same at sign until the sweep runs, and
                // an argument that matched it too would restamp it and outlive its own directive.
                .join(d).on(d.GRAPH_NAME.eq(g.GRAPH_NAME))
                    .and(d.TOUCHED_AT.eq(touchedAt))
                    .and(d.SOURCE_NAME.eq(g.SOURCE_NAME))
                    .and(d.SOURCE_LINE.eq(g.PARENT_LINE))
                    .and(d.SOURCE_COLUMN.eq(g.PARENT_COLUMN))
                .where(g.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.VALUE_SDL, excluded(t.VALUE_SDL))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    private static void fieldDirectiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var g = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var d = GRAPHQL_FIELD_DIRECTIVE;
        var t = GRAPHQL_FIELD_DIRECTIVE_ARG;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.DIRECTIVE_ARGUMENT_NAME, t.VALUE_SDL, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    d.TYPE_NAME,
                    d.FIELD_NAME,
                    d.DIRECTIVE_NAME, d.ORDINAL, g.NAME, g.VALUE_SDL,
                    val(touchedAt, t.TOUCHED_AT))
                .from(g)
                // This reading's applications only. A directive replaced in place leaves the
                // application it displaced sitting at the same at sign until the sweep runs, and
                // an argument that matched it too would restamp it and outlive its own directive.
                .join(d).on(d.GRAPH_NAME.eq(g.GRAPH_NAME))
                    .and(d.TOUCHED_AT.eq(touchedAt))
                    .and(d.SOURCE_NAME.eq(g.SOURCE_NAME))
                    .and(d.SOURCE_LINE.eq(g.PARENT_LINE))
                    .and(d.SOURCE_COLUMN.eq(g.PARENT_COLUMN))
                .where(g.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.VALUE_SDL, excluded(t.VALUE_SDL))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    private static void argumentDirectiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var g = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var d = GRAPHQL_ARGUMENT_DIRECTIVE;
        var t = GRAPHQL_ARGUMENT_DIRECTIVE_ARG;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.ARGUMENT_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.DIRECTIVE_ARGUMENT_NAME, t.VALUE_SDL, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    d.TYPE_NAME,
                    d.FIELD_NAME,
                    d.ARGUMENT_NAME,
                    d.DIRECTIVE_NAME, d.ORDINAL, g.NAME, g.VALUE_SDL,
                    val(touchedAt, t.TOUCHED_AT))
                .from(g)
                // This reading's applications only. A directive replaced in place leaves the
                // application it displaced sitting at the same at sign until the sweep runs, and
                // an argument that matched it too would restamp it and outlive its own directive.
                .join(d).on(d.GRAPH_NAME.eq(g.GRAPH_NAME))
                    .and(d.TOUCHED_AT.eq(touchedAt))
                    .and(d.SOURCE_NAME.eq(g.SOURCE_NAME))
                    .and(d.SOURCE_LINE.eq(g.PARENT_LINE))
                    .and(d.SOURCE_COLUMN.eq(g.PARENT_COLUMN))
                .where(g.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.VALUE_SDL, excluded(t.VALUE_SDL))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    private static void enumValueDirectiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var g = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var d = GRAPHQL_ENUM_VALUE_DIRECTIVE;
        var t = GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.VALUE_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.DIRECTIVE_ARGUMENT_NAME, t.VALUE_SDL, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    d.TYPE_NAME,
                    d.VALUE_NAME,
                    d.DIRECTIVE_NAME, d.ORDINAL, g.NAME, g.VALUE_SDL,
                    val(touchedAt, t.TOUCHED_AT))
                .from(g)
                // This reading's applications only. A directive replaced in place leaves the
                // application it displaced sitting at the same at sign until the sweep runs, and
                // an argument that matched it too would restamp it and outlive its own directive.
                .join(d).on(d.GRAPH_NAME.eq(g.GRAPH_NAME))
                    .and(d.TOUCHED_AT.eq(touchedAt))
                    .and(d.SOURCE_NAME.eq(g.SOURCE_NAME))
                    .and(d.SOURCE_LINE.eq(g.PARENT_LINE))
                    .and(d.SOURCE_COLUMN.eq(g.PARENT_COLUMN))
                .where(g.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.VALUE_SDL, excluded(t.VALUE_SDL))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    private static void schemaDirectiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var g = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var d = GRAPHQL_SCHEMA_DIRECTIVE;
        var t = GRAPHQL_SCHEMA_DIRECTIVE_ARG;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.ORDINAL,
                t.DIRECTIVE_ARGUMENT_NAME, t.VALUE_SDL, t.TOUCHED_AT)
            .select(dsl
                .select(val(graph, t.GRAPH_NAME),
                    d.DIRECTIVE_NAME, d.ORDINAL, g.NAME, g.VALUE_SDL,
                    val(touchedAt, t.TOUCHED_AT))
                .from(g)
                // This reading's applications only. A directive replaced in place leaves the
                // application it displaced sitting at the same at sign until the sweep runs, and
                // an argument that matched it too would restamp it and outlive its own directive.
                .join(d).on(d.GRAPH_NAME.eq(g.GRAPH_NAME))
                    .and(d.TOUCHED_AT.eq(touchedAt))
                    .and(d.SOURCE_NAME.eq(g.SOURCE_NAME))
                    .and(d.SOURCE_LINE.eq(g.PARENT_LINE))
                    .and(d.SOURCE_COLUMN.eq(g.PARENT_COLUMN))
                .where(g.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.VALUE_SDL, excluded(t.VALUE_SDL))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** The operation a conventionally-named type answers, as a case over the three names. */
    private static Field<String> operationOf(Field<String> typeName) {
        var name = CONVENTION_ROOTS.entrySet().iterator();
        var first = name.next();
        var second = name.next();
        var third = name.next();
        return choose(typeName)
            .when(inline(first.getValue()), inline(first.getKey()))
            .when(inline(second.getValue()), inline(second.getKey()))
            .otherwise(inline(third.getKey()));
    }

    /**
     * The type name each operation takes when no {@code schema} block binds it, which the
     * specification fixes rather than the author.
     */
    private static final Map<String, String> CONVENTION_ROOTS = Map.of(
        "QUERY", "Query", "MUTATION", "Mutation", "SUBSCRIPTION", "Subscription");

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix, on the entry family's reasoning: an anchor filled above and not swept here would keep
     * rows the corpus stopped declaring, and a list that has to be edited alongside is the cheapest
     * way to make that visible.
     *
     * <p>In filling order, parents first, which is the order the foreign keys demand. The sweep
     * walks it backwards, because a coordinate cannot go while a key-to-coordinate row still names
     * it and a type cannot go while one of its fields does.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHQL_ELEMENT, GRAPHQL_TYPE_ELEMENT, GRAPHQL_FIELD_ELEMENT, GRAPHQL_ENUM_VALUE_ELEMENT,
        GRAPHQL_ARGUMENT_ELEMENT, GRAPHQL_TYPE_DECLARATION, GRAPHQL_TYPE, GRAPHQL_FIELD,
        GRAPHQL_ENUM_VALUE, GRAPHQL_ARGUMENT, GRAPHQL_UNION_MEMBER,
        GRAPHQL_IMPLEMENTS_INTERFACE, GRAPHQL_DIRECTIVE,
        GRAPHQL_DIRECTIVE_LOCATION, GRAPHQL_DIRECTIVE_ARGUMENT, GRAPHQL_ROOT_OPERATION,
        GRAPHQL_TYPE_DIRECTIVE, GRAPHQL_FIELD_DIRECTIVE, GRAPHQL_ARGUMENT_DIRECTIVE,
        GRAPHQL_ENUM_VALUE_DIRECTIVE, GRAPHQL_SCHEMA_DIRECTIVE, GRAPHQL_TYPE_DIRECTIVE_ARG,
        GRAPHQL_FIELD_DIRECTIVE_ARG, GRAPHQL_ARGUMENT_DIRECTIVE_ARG,
        GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG, GRAPHQL_SCHEMA_DIRECTIVE_ARG);

    /**
     * Deletes this graph's anchor rows that this reading did not derive.
     *
     * <p>Scoped to the graph rather than to a file, which is what makes it an anchor's sweep: a
     * coordinate survives while any document still declares it, so only a reading of the whole
     * corpus can say one is gone.
     *
     * <p>Total, because every row carries an instant. That is what the {@code NOT NULL} on
     * {@code touched_at} buys and the reason it is worth having: a nullable stamp would make this
     * predicate skip the rows that carry none, so the one column deciding what survives would be
     * the one column a writer could forget.
     */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var named = GRAPHQL_ELEMENT;
        for (Table<?> table : TABLES_TO_SWEEP.reversed()) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }
}