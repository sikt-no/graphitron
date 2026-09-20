package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.grammar.FieldSetGrammar;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DISCRIMINATE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DISCRIMINATOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_SEGMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_SELECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_RECORD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.bool;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.classPart;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.elementsOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.fieldPart;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.naming;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.ofKind;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.writtenIn;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.stringOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The type site's half of the decode: what a directive written on a type declaration meant.
 *
 * <p>Keyed by the application's own position, which is {@code graphql_ast_type_directive_entry}'s
 * key, so a row here is the decode of exactly one row there. Which declaration the directive was
 * written on, and in which file, is one join away rather than a column.
 *
 * <p>The type-site directives this writes are {@code @table}, {@code @scalarType}, {@code @enum},
 * {@code @record}, {@code @error}, {@code @node}, {@code @discriminate},
 * {@code @discriminator} and federation's {@code @key}, and two of those nine surprise. {@code @error} has no
 * relation, its only argument being the handler list, so a row carrying its key and nothing else
 * would say what the applied-directive row already says. Its handlers have one relation per kind,
 * because the kind decides which of the input's six fields mean anything: GENERIC matches by class
 * identity, DATABASE by one of two SQL discriminators, and VALIDATION carries nothing at all.
 *
 * @see GraphitronAstEntries for what every site's decode holds in common
 */
final class GraphitronTypeEntries {

    private GraphitronTypeEntries() {}

    /**
     * Makes {@code source}'s type-site rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        tables(dsl, graph, touchedAt, wrote(applied(applications, "table"), "name"));
        scalarTypes(dsl, graph, touchedAt, wrote(applied(applications, "scalarType"), "scalar"));
        enums(dsl, graph, touchedAt, naming(applied(applications, "enum"), "enumReference"));
        records(dsl, graph, touchedAt, naming(applied(applications, "record"), "record"));
        var handlers = elementsOf(applied(applications, "error"), "handlers");
        genericHandlers(dsl, graph, touchedAt, ofKind(handlers, "GENERIC").stream()
            .filter(handler -> stringOf(inside(handler.node(), "className")) != null).toList());
        databaseHandlers(dsl, graph, touchedAt, ofKind(handlers, "DATABASE"));
        validationHandlers(dsl, graph, touchedAt, ofKind(handlers, "VALIDATION"));

        var nodes = applied(applications, "node");
        nodes(dsl, graph, touchedAt, nodes);
        nodeKeyColumns(dsl, graph, touchedAt, nodes);

        discriminates(dsl, graph, touchedAt, wrote(applied(applications, "discriminate"), "on"));
        discriminators(dsl, graph, touchedAt,
            wrote(applied(applications, "discriminator"), "value"));

        var keys = wrote(applied(applications, "key"), "fields");
        federationKeys(dsl, graph, touchedAt, keys);
        var selections = selectionsOf(keys);
        federationKeySelections(dsl, graph, touchedAt, selections);
        federationKeySegments(dsl, graph, touchedAt, selections);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     *
     * <p>In no particular order, these relations referencing one another not at all. An application
     * the author moved or deleted is swept by the cascade from the directive row it hung on; what
     * is left for this sweep is the application whose position another directive now occupies.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_TABLE_ENTRY, GRAPHITRON_AST_SCALAR_TYPE_ENTRY, GRAPHITRON_AST_ENUM_ENTRY,
        GRAPHITRON_AST_RECORD_ENTRY, GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY,
        GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY,
        GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY,
        GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY, GRAPHITRON_AST_NODE_ENTRY,
        GRAPHITRON_AST_DISCRIMINATE_ENTRY, GRAPHITRON_AST_DISCRIMINATOR_ENTRY,
        GRAPHITRON_AST_FEDERATION_KEY_SEGMENT_ENTRY,
        GRAPHITRON_AST_FEDERATION_KEY_SELECTION_ENTRY, GRAPHITRON_AST_FEDERATION_KEY_ENTRY);

    private static void tables(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               List<Directive> applications) {
        var t = GRAPHITRON_AST_TABLE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.TABLE_REF),
            application -> val(QualifiedNameGrammar.namespacePart(string(application, "name")),
                t.TABLE_REF_NAMESPACE_PART),
            application -> val(QualifiedNameGrammar.namePart(string(application, "name")),
                t.TABLE_REF_NAME_PART)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.TABLE_REF, excluded(t.TABLE_REF))
                .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
                .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART)));
    }

    private static void scalarTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "scalar"), t.SCALAR_REF),
            application -> val(classPart(string(application, "scalar")), t.SCALAR_REF_CLASS_PART),
            application -> val(fieldPart(string(application, "scalar")), t.SCALAR_REF_FIELD_PART)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.SCALAR_REF, t.SCALAR_REF_CLASS_PART, t.SCALAR_REF_FIELD_PART)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SCALAR_REF, excluded(t.SCALAR_REF))
                .set(t.SCALAR_REF_CLASS_PART, excluded(t.SCALAR_REF_CLASS_PART))
                .set(t.SCALAR_REF_FIELD_PART, excluded(t.SCALAR_REF_FIELD_PART)));
    }

    private static void enums(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              List<Directive> applications) {
        var t = GRAPHITRON_AST_ENUM_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "enumReference", "className"), t.CLASS_NAME),
            application -> val(inside(application, "enumReference", "method"), t.METHOD),
            application -> val(inside(application, "enumReference", "argMapping"), t.ARGMAPPING)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    private static void records(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_RECORD_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "record", "className"), t.CLASS_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME)));
    }

    private static void genericHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                        List<GraphitronAstEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> GraphQLAstEntries.sourceName(handler.node()),
            handler -> GraphQLAstEntries.sourceLine(handler.node()),
            handler -> GraphQLAstEntries.sourceColumn(handler.node()),
            handler -> val(touchedAt, t.TOUCHED_AT),
            handler -> val(stringOf(inside(handler.node(), "className")), t.CLASS_NAME),
            handler -> val(stringOf(inside(handler.node(), "matches")), t.MATCHES),
            handler -> val(stringOf(inside(handler.node(), "description")), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.MATCHES, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.MATCHES, excluded(t.MATCHES))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void databaseHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                         List<GraphitronAstEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> GraphQLAstEntries.sourceName(handler.node()),
            handler -> GraphQLAstEntries.sourceLine(handler.node()),
            handler -> GraphQLAstEntries.sourceColumn(handler.node()),
            handler -> val(touchedAt, t.TOUCHED_AT),
            handler -> val(stringOf(inside(handler.node(), "code")), t.CODE),
            handler -> val(stringOf(inside(handler.node(), "sqlState")), t.SQL_STATE),
            handler -> val(stringOf(inside(handler.node(), "matches")), t.MATCHES),
            handler -> val(stringOf(inside(handler.node(), "description")), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CODE, t.SQL_STATE, t.MATCHES, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CODE, excluded(t.CODE))
                .set(t.SQL_STATE, excluded(t.SQL_STATE))
                .set(t.MATCHES, excluded(t.MATCHES))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    /** The position is the whole row: the directive gives this kind no field it may carry. */
    private static void validationHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                           List<GraphitronAstEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> GraphQLAstEntries.sourceName(handler.node()),
            handler -> GraphQLAstEntries.sourceLine(handler.node()),
            handler -> GraphQLAstEntries.sourceColumn(handler.node()),
            handler -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * A row for every application and not only for those that wrote an argument. Both arguments are
     * optional, an author writing a bare {@code @node} is asking for the type's own name and the
     * database's own key, and the key-column list is the child relation its elements hang off.
     */
    private static void nodes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              List<Directive> applications) {
        var t = GRAPHITRON_AST_NODE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "typeId"), t.TYPE_ID)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.TYPE_ID)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.TYPE_ID, excluded(t.TYPE_ID)));
    }

    /**
     * The key columns, which are a list of bare strings rather than of object literals, so they are
     * read through {@link GraphitronAstEntries#writtenIn}. Each row names the element it decodes. The
     * order is part of what the directive says, being the order the columns take inside an id, and
     * that is why it is not restated here: the element's own row carries the index already.
     */
    private static void nodeKeyColumns(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<Directive> applications) {
        var t = GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY;
        var rows = writtenIn(applications, "keyColumns").stream().collect(Rows.toRowList(
            column -> val(graph, t.GRAPH_NAME),
            column -> GraphQLAstEntries.sourceName(column.node()),
            column -> GraphQLAstEntries.sourceLine(column.node()),
            column -> GraphQLAstEntries.sourceColumn(column.node()),
            column -> val(touchedAt, t.TOUCHED_AT),
            column -> val(column.value(), t.COLUMN_REF)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.COLUMN_REF)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.COLUMN_REF, excluded(t.COLUMN_REF)));
    }

    /**
     * The interface or union end of the discriminating pair: which column decides the subtype.
     *
     * <p>Filtered on the argument although the definition requires it, which costs nothing and says
     * what the NOT NULL column needs: an application that wrote no {@code on} is refused as illegal
     * before it reaches this writer, so the filter removes nothing a legal corpus contains.
     */
    private static void discriminates(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      List<Directive> applications) {
        var t = GRAPHITRON_AST_DISCRIMINATE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "on"), t.ON_COLUMN)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.ON_COLUMN)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.ON_COLUMN, excluded(t.ON_COLUMN)));
    }

    /** The subtype end: which value of that column means this object. */
    private static void discriminators(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<Directive> applications) {
        var t = GRAPHITRON_AST_DISCRIMINATOR_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "value"), t.DISCRIMINATOR_VALUE)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.DISCRIMINATOR_VALUE)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.DISCRIMINATOR_VALUE, excluded(t.DISCRIMINATOR_VALUE)));
    }

    /**
     * Federation's {@code @key}, whose field set is kept whole here and parsed into the two
     * relations below.
     *
     * <p>The string stays because the parse is a reading of it: an emitter re-declaring the
     * directive wants what the author typed, and a reader wanting the key's shape takes the rows.
     */
    private static void federationKeys(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<Directive> applications) {
        var t = GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "fields"), t.FIELDS_SDL),
            application -> val(bool(application, "resolvable"), t.RESOLVABLE)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.FIELDS_SDL, t.RESOLVABLE)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.FIELDS_SDL, excluded(t.FIELDS_SDL))
                .set(t.RESOLVABLE, excluded(t.RESOLVABLE)));
    }

    /** One leaf selection of one application's field set, at the index the parse gave it. */
    private record Selection(Directive application, int position, List<String> segments) {}

    /**
     * Every application's field set, parsed. The grammar yields an ordered list and a relation
     * holds a set, so each selection carries its index.
     */
    private static List<Selection> selectionsOf(List<Directive> applications) {
        var selections = new ArrayList<Selection>();
        for (Directive application : applications) {
            int position = 0;
            for (List<String> segments : FieldSetGrammar.paths(string(application, "fields"))) {
                selections.add(new Selection(application, position++, segments));
            }
        }
        return selections;
    }

    private static void federationKeySelections(DSLContext dsl, String graph,
                                                LocalDateTime touchedAt,
                                                List<Selection> selections) {
        var t = GRAPHITRON_AST_FEDERATION_KEY_SELECTION_ENTRY;
        var rows = selections.stream().collect(Rows.toRowList(
            selection -> val(graph, t.GRAPH_NAME),
            selection -> GraphQLAstEntries.sourceName(selection.application()),
            selection -> GraphQLAstEntries.sourceLine(selection.application()),
            selection -> GraphQLAstEntries.sourceColumn(selection.application()),
            selection -> val(selection.position(), t.POSITION),
            selection -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.POSITION, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** One segment of one parsed selection, at its depth from the outermost inward. */
    private record Segment(Selection selection, int depth, String name) {}

    private static void federationKeySegments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                              List<Selection> selections) {
        var t = GRAPHITRON_AST_FEDERATION_KEY_SEGMENT_ENTRY;
        var segments = new ArrayList<Segment>();
        for (Selection selection : selections) {
            for (int depth = 0; depth < selection.segments().size(); depth++) {
                segments.add(new Segment(selection, depth, selection.segments().get(depth)));
            }
        }
        var rows = segments.stream().collect(Rows.toRowList(
            segment -> val(graph, t.GRAPH_NAME),
            segment -> GraphQLAstEntries.sourceName(segment.selection().application()),
            segment -> GraphQLAstEntries.sourceLine(segment.selection().application()),
            segment -> GraphQLAstEntries.sourceColumn(segment.selection().application()),
            segment -> val(segment.selection().position(), t.POSITION),
            segment -> val(segment.depth(), t.SEGMENT_POSITION),
            segment -> val(touchedAt, t.TOUCHED_AT),
            segment -> val(segment.name(), t.SEGMENT_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.POSITION, t.SEGMENT_POSITION, t.TOUCHED_AT, t.SEGMENT_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SEGMENT_NAME, excluded(t.SEGMENT_NAME)));
    }
}
