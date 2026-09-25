package no.sikt.graphitron.model.capture.document;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.DescribedNode;
import graphql.language.Description;
import graphql.language.Directive;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import graphql.language.DirectiveDefinition;
import graphql.language.DirectiveLocation;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValue;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.FloatValue;
import graphql.language.ImplementingTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.IntValue;
import graphql.language.ListType;
import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectValue;
import graphql.language.OperationTypeDefinition;
import graphql.language.SDLExtensionDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.language.AstPrinter.printAstCompact;
import no.sikt.graphitron.model.vocabulary.EntryKind;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
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
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_UNION_MEMBER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes one parsed document into the store, as written. One relation per SDL node kind and site,
 * keyed by the position the node was written at, so two documents declaring one name are two rows
 * and this class has nothing to decide: no claim, no first-wins, no ordinal.
 *
 * <p>Eighteen relations and eighteen methods, each saying for itself where in the document its
 * nodes live, so a method is changed alone.
 *
 * <p>A node written inside another names its parent's position, and where that parent is one
 * relation the position is a key into it: both rows come out of one parse of one file and one
 * method writes both, so nothing a document can say breaks the reference. Two of the relations
 * have a parent that is a union and carry a position with no key. A directive applied to an input
 * value has one of three parents, and an argument of an application has one of five. The keys are
 * why the writers run outermost first and the sweep runs their list backwards.
 *
 * <p>Nothing here resolves, counts or compares. What a written name refers to, which of two
 * declarations the corpus honours, and whether a collision is an error are queries over these rows.
 * The anchors are derived from them afterwards, which is why no row here points at one.
 *
 * <p>Mark and sweep, because a store is not always empty. Every row carries its reading's instant and
 * the reading ends by deleting its file's older rows. Those are the nodes the author removed, and an
 * upsert cannot find them: there is no incoming row to match.
 */
public final class GraphQLAstEntries {

    private GraphQLAstEntries() {}

    /**
     * Makes {@code source}'s rows under {@code graph} be exactly what {@code document} wrote.
     *
     * <p>The file is a parameter rather than read off the nodes: a document whose every declaration
     * was deleted still has rows to sweep and no nodes left to say which file it was. The instant is
     * the caller's for the same reason, the eighteen relations having to agree on which reading is
     * current.
     */
    public static void write(DSLContext dsl, String graph, String source,
                             TypeDefinitionRegistry document, LocalDateTime touchedAt) {
        typeDeclarations(dsl, graph, touchedAt, document);
        directiveDefinitions(dsl, graph, touchedAt, document);
        schemaDefinitions(dsl, graph, touchedAt, document);
        fieldDefinitions(dsl, graph, touchedAt, document);
        enumValueDefinitions(dsl, graph, touchedAt, document);
        implementsClauses(dsl, graph, touchedAt, document);
        unionMembers(dsl, graph, touchedAt, document);
        directiveLocations(dsl, graph, touchedAt, document);
        operationTypeDefinitions(dsl, graph, touchedAt, document);
        fieldArguments(dsl, graph, touchedAt, document);
        inputFields(dsl, graph, touchedAt, document);
        directiveArguments(dsl, graph, touchedAt, document);
        typeDirectives(dsl, graph, touchedAt, document);
        fieldDirectives(dsl, graph, touchedAt, document);
        inputValueDirectives(dsl, graph, touchedAt, document);
        enumValueDirectives(dsl, graph, touchedAt, document);
        schemaDirectives(dsl, graph, touchedAt, document);
        appliedArguments(dsl, graph, touchedAt, document);
        values(dsl, graph, touchedAt, document);
        sweep(dsl, graph, source, touchedAt);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently, and a list
     * that has to be edited alongside is the cheapest way to make that visible.
     *
     * <p>In writing order, parents first, which is the order the keys demand. The sweep walks it
     * backwards.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHQL_AST_ENTRY, GRAPHQL_AST_ELEMENT_ENTRY, GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY,
        GRAPHQL_AST_TYPE_DECLARATION_ENTRY, GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY,
        GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY, GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
        GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY, GRAPHQL_AST_IMPLEMENTS_ENTRY,
        GRAPHQL_AST_UNION_MEMBER_ENTRY, GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY,
        GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY, GRAPHQL_AST_FIELD_ARGUMENT_ENTRY,
        GRAPHQL_AST_INPUT_FIELD_ENTRY, GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY,
        GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY, GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY,
        GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY, GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY,
        GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY, GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY,
        GRAPHQL_AST_VALUE_ENTRY);

    /**
     * Deletes this file's rows that this reading did not touch, which are the nodes the author
     * removed. Scoped to the file, so a reading of one document says nothing about another's rows.
     */
    private static void sweep(DSLContext dsl, String graph, String source, LocalDateTime touchedAt) {
        // Table.field(Field) is a lookup by name returning the loop's own typed column, so one
        // relation's three name them on all eighteen.
        var named = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        for (Table<?> table : TABLES_TO_SWEEP.reversed()) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.SOURCE_NAME).eq(source))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    /**
     * The supertype row for each node one arm is about to write, on graphql_element's terms: the
     * relation that says a written position exists, which every entry relation then keys into.
     *
     * <p>Not an anchor, whatever the position grain is classified as. The anchors are what
     * {@link GraphQLAstCapture#anchor} writes, resolving what several documents said into one answer, and this
     * runs before any of that: the entry stratum's claim is that it exists before anything is
     * composed, and a row here is one document saying it wrote something at a position.
     *
     * <p>One method for all nineteen, because graphql-java already gives them the supertype: every
     * entry is a {@link Node}, a node knows the position it was read at, and {@link Nested} pairs
     * one with what it was written inside. A position and a parent position are the whole of what
     * an anchor holds, so nothing here needs to know which kind it is looking at beyond being told.
     *
     * <p>Called before the arm's own insert rather than after, the arm's foreign key naming this
     * row. Root kinds pass a null parent and say so once, here, rather than nineteen times.
     */
    private static void supertype(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                  EntryKind kind, List<? extends Written> nodes) {
        var a = GRAPHQL_AST_ENTRY;
        var rows = nodes.stream().collect(Rows.toRowList(
            nested -> val(graph, a.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> val(kind, a.ENTRY_KIND),
            // A root was written inside nothing, so its parent columns are null together. The
            // three top-level kinds are the roots and every other arm always has one.
            nested -> nested.parent() == null ? inline((Integer) null) : parentLine(nested.parent()),
            nested -> nested.parent() == null ? inline((Integer) null) : parentColumn(nested.parent()),
            nested -> val(touchedAt, a.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(a, a.GRAPH_NAME, a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN,
                    a.ENTRY_KIND, a.PARENT_LINE, a.PARENT_COLUMN, a.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(a.ENTRY_KIND, excluded(a.ENTRY_KIND))
                .set(a.PARENT_LINE, excluded(a.PARENT_LINE))
                .set(a.PARENT_COLUMN, excluded(a.PARENT_COLUMN))
                .set(a.TOUCHED_AT, excluded(a.TOUCHED_AT)));
    }

    /**
     * The element row for each node one arm is about to write: the coordinate that declaration
     * names, in the specification's grammar.
     *
     * <p>The spelling is the caller's, because it is the one thing the five kinds do not share: a
     * named type is its own name, a field and an enum value and an input field are the parent's
     * name and a dot, and an argument goes one deeper. Everything around it is shared, which is why
     * this takes the coordinate already spelled rather than a rule for spelling it.
     */
    private static void element(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<? extends Written> nodes,
                                Function<Written, String> coordinate) {
        var e = GRAPHQL_AST_ELEMENT_ENTRY;
        var rows = nodes.stream().collect(Rows.toRowList(
            nested -> val(graph, e.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> val(coordinate.apply(nested), e.COORDINATE),
            nested -> val(touchedAt, e.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(e, e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN,
                    e.COORDINATE, e.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(e.COORDINATE, excluded(e.COORDINATE))
                .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT)));
    }

    /**
     * The application row for each directive one arm is about to write: the name it applies.
     *
     * <p>No spelling rule to pass in, unlike the element row beside it: every site writes the name
     * the same way, which is exactly why the five have a supertype to write into at all.
     */
    private static void application(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Nested<Directive>> nodes) {
        var a = GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
        var rows = nodes.stream().collect(Rows.toRowList(
            nested -> val(graph, a.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> val(nested.node().getName(), a.NAME),
            nested -> val(touchedAt, a.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(a, a.GRAPH_NAME, a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN,
                    a.NAME, a.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(a.NAME, excluded(a.NAME))
                .set(a.TOUCHED_AT, excluded(a.TOUCHED_AT)));
    }

    /** A root: written inside nothing, so the anchor's parent columns are null together. */
    private static Written root(Node<?> node) {
        return new Nested<>(null, node);
    }

    private static void typeDeclarations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.TYPE_DECLARATION,
            declarations(document).map(GraphQLAstEntries::root).toList());
        element(dsl, graph, touchedAt, declarations(document).map(GraphQLAstEntries::root).toList(),
            nested -> ((TypeDefinition<?>) nested.node()).getName());
        var t = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        var rows = declarations(document).collect(Rows.toRowList(
            node -> val(graph, t.GRAPH_NAME),
            node -> sourceName(node),
            node -> sourceLine(node),
            node -> sourceColumn(node),
            node -> sourceRef(node),
            node -> val(touchedAt, t.TOUCHED_AT),
            node -> val(kind(node), t.KIND),
            node -> val(node instanceof SDLExtensionDefinition, t.IS_EXTENSION),
            node -> val(node.getName(), t.NAME),
            node -> val(text(node), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.KIND, t.IS_EXTENSION, t.NAME, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.KIND, excluded(t.KIND))
                .set(t.IS_EXTENSION, excluded(t.IS_EXTENSION))
                .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void directiveDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                              TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.DIRECTIVE_DEFINITION,
            directives(document).map(GraphQLAstEntries::root).toList());
        element(dsl, graph, touchedAt, directives(document).map(GraphQLAstEntries::root).toList(),
            nested -> "@" + ((DirectiveDefinition) nested.node()).getName());
        var t = GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
        var rows = directives(document).collect(Rows.toRowList(
            node -> val(graph, t.GRAPH_NAME),
            node -> sourceName(node),
            node -> sourceLine(node),
            node -> sourceColumn(node),
            node -> sourceRef(node),
            node -> val(touchedAt, t.TOUCHED_AT),
            node -> val(node.getName(), t.NAME),
            node -> val(node.isRepeatable(), t.REPEATABLE),
            node -> val(text(node), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.NAME, t.REPEATABLE, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME, excluded(t.NAME))
                .set(t.REPEATABLE, excluded(t.REPEATABLE))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void schemaDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                           TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.SCHEMA_DEFINITION,
            schemas(document).map(GraphQLAstEntries::root).toList());
        // The schema block declares a coordinate like every other site, and the coordinate is a
        // constant because a schema block has no name to compose one from. A dollar sign is
        // illegal in a GraphQL name, so this can never be a type an author wrote.
        element(dsl, graph, touchedAt, schemas(document).map(GraphQLAstEntries::root).toList(),
            nested -> SchemaCoordinateSyntax.ofSchema());
        var t = GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY;
        var rows = schemas(document).collect(Rows.toRowList(
            node -> val(graph, t.GRAPH_NAME),
            node -> sourceName(node),
            node -> sourceLine(node),
            node -> sourceColumn(node),
            node -> sourceRef(node),
            node -> val(touchedAt, t.TOUCHED_AT),
            node -> val(node instanceof SDLExtensionDefinition, t.IS_EXTENSION),
            node -> val(text(node), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.IS_EXTENSION, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.IS_EXTENSION, excluded(t.IS_EXTENSION))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void fieldDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.FIELD_DEFINITION, fields(document));
        element(dsl, graph, touchedAt, fields(document),
            nested -> nameOf(nested.parent()) + "." + ((FieldDefinition) nested.node()).getName());
        var t = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var rows = fields(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nameOf(nested.parent()), t.TYPE_NAME),
            nested -> val(nested.node().getName(), t.NAME),
            nested -> val(printAstCompact(nested.node().getType()), t.TYPE_SDL),
            nested -> val(namedType(nested.node().getType()), t.NAMED_TYPE),
            nested -> val(nonNull(nested.node().getType()), t.NON_NULL),
            nested -> val(isList(nested.node().getType()), t.IS_LIST),
            nested -> val(itemNonNull(nested.node().getType()), t.ITEM_NON_NULL),
            nested -> val(listDepth(nested.node().getType()), t.LIST_DEPTH),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.NAME, t.TYPE_SDL,
                    t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.LIST_DEPTH,
                    t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
                .set(t.NAME, excluded(t.NAME))
                .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
                .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
                .set(t.NON_NULL, excluded(t.NON_NULL))
                .set(t.IS_LIST, excluded(t.IS_LIST))
                .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
                .set(t.LIST_DEPTH, excluded(t.LIST_DEPTH))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void fieldArguments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.FIELD_ARGUMENT, argumentsOfFields(document));
        element(dsl, graph, touchedAt, argumentsOfFields(document),
            nested -> ((NestedArgument) nested).typeName() + "."
                + ((FieldDefinition) nested.parent()).getName() + "("
                + ((NestedArgument) nested).node().getName() + ":)");
        var t = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        var rows = argumentsOfFields(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.typeName(), t.TYPE_NAME),
            nested -> val(nameOf(nested.parent()), t.FIELD_NAME),
            nested -> val(nested.node().getName(), t.NAME),
            nested -> val(printAstCompact(nested.node().getType()), t.TYPE_SDL),
            nested -> val(namedType(nested.node().getType()), t.NAMED_TYPE),
            nested -> val(nonNull(nested.node().getType()), t.NON_NULL),
            nested -> val(isList(nested.node().getType()), t.IS_LIST),
            nested -> val(itemNonNull(nested.node().getType()), t.ITEM_NON_NULL),
            nested -> val(listDepth(nested.node().getType()), t.LIST_DEPTH),
            nested -> val(writtenSdl(nested.node().getDefaultValue()), t.DEFAULT_VALUE_SDL),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME,
                    t.FIELD_NAME, t.NAME, t.TYPE_SDL, t.NAMED_TYPE,
                    t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.LIST_DEPTH, t.DEFAULT_VALUE_SDL,
                    t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
                .set(t.FIELD_NAME, excluded(t.FIELD_NAME))
                .set(t.NAME, excluded(t.NAME))
                .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
                .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
                .set(t.NON_NULL, excluded(t.NON_NULL))
                .set(t.IS_LIST, excluded(t.IS_LIST))
                .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
                .set(t.LIST_DEPTH, excluded(t.LIST_DEPTH))
                .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void inputFields(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.INPUT_FIELD, fieldsOfInputObjects(document));
        element(dsl, graph, touchedAt, fieldsOfInputObjects(document),
            nested -> nameOf(nested.parent()) + "." + ((InputValueDefinition) nested.node()).getName());
        var t = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        var rows = fieldsOfInputObjects(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nameOf(nested.parent()), t.TYPE_NAME),
            nested -> val(nested.node().getName(), t.NAME),
            nested -> val(printAstCompact(nested.node().getType()), t.TYPE_SDL),
            nested -> val(namedType(nested.node().getType()), t.NAMED_TYPE),
            nested -> val(nonNull(nested.node().getType()), t.NON_NULL),
            nested -> val(isList(nested.node().getType()), t.IS_LIST),
            nested -> val(itemNonNull(nested.node().getType()), t.ITEM_NON_NULL),
            nested -> val(listDepth(nested.node().getType()), t.LIST_DEPTH),
            nested -> val(writtenSdl(nested.node().getDefaultValue()), t.DEFAULT_VALUE_SDL),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.NAME, t.TYPE_SDL,
                    t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.LIST_DEPTH,
                    t.DEFAULT_VALUE_SDL,
                    t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
                .set(t.NAME, excluded(t.NAME))
                .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
                .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
                .set(t.NON_NULL, excluded(t.NON_NULL))
                .set(t.IS_LIST, excluded(t.IS_LIST))
                .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
                .set(t.LIST_DEPTH, excluded(t.LIST_DEPTH))
                .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    /** What a directive definition declares, where appliedArguments below is what an application passes. */
    private static void directiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.DIRECTIVE_ARGUMENT, argumentsOfDirectiveDefinitions(document));
        element(dsl, graph, touchedAt, argumentsOfDirectiveDefinitions(document),
            nested -> "@" + nameOf(nested.parent()) + "("
                + ((InputValueDefinition) nested.node()).getName() + ":)");
        var t = GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
        var rows = argumentsOfDirectiveDefinitions(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME),
            nested -> val(printAstCompact(nested.node().getType()), t.TYPE_SDL),
            nested -> val(namedType(nested.node().getType()), t.NAMED_TYPE),
            nested -> val(nonNull(nested.node().getType()), t.NON_NULL),
            nested -> val(isList(nested.node().getType()), t.IS_LIST),
            nested -> val(itemNonNull(nested.node().getType()), t.ITEM_NON_NULL),
            nested -> val(listDepth(nested.node().getType()), t.LIST_DEPTH),
            nested -> val(writtenSdl(nested.node().getDefaultValue()), t.DEFAULT_VALUE_SDL),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME, t.TYPE_SDL, t.NAMED_TYPE,
                    t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.LIST_DEPTH, t.DEFAULT_VALUE_SDL,
                    t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME))
                .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
                .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
                .set(t.NON_NULL, excluded(t.NON_NULL))
                .set(t.IS_LIST, excluded(t.IS_LIST))
                .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
                .set(t.LIST_DEPTH, excluded(t.LIST_DEPTH))
                .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void enumValueDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                              TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.ENUM_VALUE_DEFINITION, enumValues(document));
        element(dsl, graph, touchedAt, enumValues(document),
            nested -> nameOf(nested.parent()) + "." + ((EnumValueDefinition) nested.node()).getName());
        var t = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        var rows = enumValues(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nameOf(nested.parent()), t.TYPE_NAME),
            nested -> val(nested.node().getName(), t.NAME),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.NAME, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
                .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void implementsClauses(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.IMPLEMENTS, implementedInterfaces(document));
        var t = GRAPHQL_AST_IMPLEMENTS_ENTRY;
        var rows = implementedInterfaces(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nameOf(nested.parent()), t.TYPE_NAME),
            nested -> val(nested.node().getName(), t.INTERFACE_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.INTERFACE_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
                .set(t.INTERFACE_NAME, excluded(t.INTERFACE_NAME)));
    }

    private static void unionMembers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.UNION_MEMBER, members(document));
        var t = GRAPHQL_AST_UNION_MEMBER_ENTRY;
        var rows = members(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nameOf(nested.parent()), t.TYPE_NAME),
            nested -> val(nested.node().getName(), t.MEMBER_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.MEMBER_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
                .set(t.MEMBER_NAME, excluded(t.MEMBER_NAME)));
    }

    private static void directiveLocations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                            TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.DIRECTIVE_LOCATION, locations(document));
        var t = GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY;
        var rows = locations(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.LOCATION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.LOCATION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.LOCATION, excluded(t.LOCATION)));
    }

    private static void operationTypeDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                                  TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.OPERATION_TYPE_DEFINITION, operations(document));
        var t = GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY;
        var rows = operations(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.OPERATION),
            nested -> val(nested.node().getTypeName().getName(), t.TYPE_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.OPERATION, t.TYPE_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.OPERATION, excluded(t.OPERATION))
                .set(t.TYPE_NAME, excluded(t.TYPE_NAME)));
    }

    private static void typeDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.TYPE_DIRECTIVE, directivesOnTypes(document));
        application(dsl, graph, touchedAt, directivesOnTypes(document));
        var t = GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
        var rows = directivesOnTypes(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME)));
    }
    private static void fieldDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.FIELD_DIRECTIVE, directivesOnFields(document));
        application(dsl, graph, touchedAt, directivesOnFields(document));
        var t = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
        var rows = directivesOnFields(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME)));
    }
    /** All three input-value sites in one relation: the parent is a union whichever way this is cut. */
    private static void inputValueDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.INPUT_VALUE_DIRECTIVE, directivesOnInputValues(document));
        application(dsl, graph, touchedAt, directivesOnInputValues(document));
        var t = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        var rows = directivesOnInputValues(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME)));
    }
    private static void enumValueDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.ENUM_VALUE_DIRECTIVE, directivesOnEnumValues(document));
        application(dsl, graph, touchedAt, directivesOnEnumValues(document));
        var t = GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
        var rows = directivesOnEnumValues(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME)));
    }
    private static void schemaDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.SCHEMA_DIRECTIVE, directivesOnSchemas(document));
        application(dsl, graph, touchedAt, directivesOnSchemas(document));
        var t = GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY;
        var rows = directivesOnSchemas(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME)));
    }

    private static void appliedArguments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
        supertype(dsl, graph, touchedAt, EntryKind.APPLIED_ARGUMENT, applicationArguments(document));
        var t = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var rows = applicationArguments(document).stream().collect(Rows.toRowList(
            nested -> val(graph, t.GRAPH_NAME),
            nested -> sourceName(nested.node()),
            nested -> sourceLine(nested.node()),
            nested -> sourceColumn(nested.node()),
            nested -> sourceRef(nested.node()),
            nested -> val(touchedAt, t.TOUCHED_AT),
            nested -> parentLine(nested.parent()),
            nested -> parentColumn(nested.parent()),
            nested -> val(nested.node().getName(), t.NAME),
            nested -> val(printAstCompact(nested.node().getValue()), t.VALUE_SDL)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME, t.VALUE_SDL)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.NAME, excluded(t.NAME))
                .set(t.VALUE_SDL, excluded(t.VALUE_SDL)));
    }

    /**
     * Every value node the document wrote, one row each, whichever slot the expression was written
     * at.
     *
     * <p>One relation and one writer for four holders, where the directive sites get one relation
     * each. The difference is what a key can defend: a directive's parent is one kind of node and so
     * one relation, while a value's holder is an applied argument or one of three declarations
     * carrying a default, and no key names four tables. The row says which position holds it and
     * stops there, the way {@code graphql_ast_applied_argument_entry} already does for its own
     * parent.
     */
    private static void values(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               TypeDefinitionRegistry document) {
        // One statement per level, shallowest first, because the parent reference is a key into
        // this same relation and a batch has no order for a constraint to see: inside one insert a
        // child can be checked before the parent it names is there.
        new TreeMap<>(writtenValues(document).stream()
            .collect(Collectors.groupingBy(WrittenValue::depth)))
            .values().forEach(level -> valuesOfDepth(dsl, graph, touchedAt, level));
    }

    /** The rows of one level of one document's expressions, whose parents are all already written. */
    private static void valuesOfDepth(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      List<WrittenValue> level) {
        var t = GRAPHQL_AST_VALUE_ENTRY;
        // A value is written inside the value enclosing it where there is one, and inside the node
        // holding the whole expression otherwise. Both are the tree the supertype records, so the
        // choice is made here rather than left to which component the record happens to carry.
        supertype(dsl, graph, touchedAt, EntryKind.VALUE, level.stream()
            .map(written -> new Nested<>(
                written.parent() == null ? written.holder() : written.parent(), written.node()))
            .toList());
        var rows = level.stream().collect(Rows.toRowList(
            written -> val(graph, t.GRAPH_NAME),
            written -> sourceName(written.node()),
            written -> sourceLine(written.node()),
            written -> sourceColumn(written.node()),
            written -> sourceRef(written.node()),
            written -> val(touchedAt, t.TOUCHED_AT),
            written -> parentLine(written.holder()),
            written -> parentColumn(written.holder()),
            written -> val(enclosingLine(written.parent()), t.PARENT_LINE),
            written -> val(enclosingColumn(written.parent()), t.PARENT_COLUMN),
            written -> val(written.position(), t.POSITION),
            written -> val(written.objectFieldName(), t.OBJECT_FIELD_NAME),
            written -> val(valueKind(written.node()), t.KIND),
            written -> val(valueText(written.node()), t.WRITTEN_TEXT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                    t.TOUCHED_AT, t.HOLDER_LINE, t.HOLDER_COLUMN, t.PARENT_LINE, t.PARENT_COLUMN,
                    t.POSITION, t.OBJECT_FIELD_NAME, t.KIND, t.WRITTEN_TEXT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.HOLDER_LINE, excluded(t.HOLDER_LINE))
                .set(t.HOLDER_COLUMN, excluded(t.HOLDER_COLUMN))
                .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
                .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
                .set(t.POSITION, excluded(t.POSITION))
                .set(t.OBJECT_FIELD_NAME, excluded(t.OBJECT_FIELD_NAME))
                .set(t.KIND, excluded(t.KIND))
                .set(t.WRITTEN_TEXT, excluded(t.WRITTEN_TEXT)));
    }

    // ------------------------------------------------- where in a document each kind of node lives

    /** One accessor's extensions, whose map holds them per name and several per name. */
    private static <N> List<N> flat(Map<String, List<N>> byName) {
        return byName.values().stream().flatMap(List::stream).toList();
    }

    /** Every named type declaration the document wrote, base and extension alike. */
    private static Stream<TypeDefinition<?>> declarations(TypeDefinitionRegistry document) {
        List<TypeDefinition<?>> nodes = new ArrayList<>();
        objectsAndInterfaces(document).forEach(nodes::add);
        unions(document).forEach(nodes::add);
        enums(document).forEach(nodes::add);
        inputObjects(document).forEach(nodes::add);
        scalars(document).forEach(nodes::add);
        return nodes.stream();
    }

    /**
     * The two kinds that carry fields and an implements clause, their extensions among them, an
     * extension node class being a subclass of the base's.
     */
    private static Stream<ImplementingTypeDefinition<?>> objectsAndInterfaces(TypeDefinitionRegistry document) {
        List<ImplementingTypeDefinition<?>> nodes = new ArrayList<>();
        nodes.addAll(document.getTypes(ObjectTypeDefinition.class));
        nodes.addAll(document.getTypes(InterfaceTypeDefinition.class));
        nodes.addAll(flat(document.objectTypeExtensions()));
        nodes.addAll(flat(document.interfaceTypeExtensions()));
        return nodes.stream();
    }

    private static Stream<UnionTypeDefinition> unions(TypeDefinitionRegistry document) {
        List<UnionTypeDefinition> nodes = new ArrayList<>(document.getTypes(UnionTypeDefinition.class));
        nodes.addAll(flat(document.unionTypeExtensions()));
        return nodes.stream();
    }

    private static Stream<EnumTypeDefinition> enums(TypeDefinitionRegistry document) {
        List<EnumTypeDefinition> nodes = new ArrayList<>(document.getTypes(EnumTypeDefinition.class));
        nodes.addAll(flat(document.enumTypeExtensions()));
        return nodes.stream();
    }

    private static Stream<InputObjectTypeDefinition> inputObjects(TypeDefinitionRegistry document) {
        List<InputObjectTypeDefinition> nodes =
            new ArrayList<>(document.getTypes(InputObjectTypeDefinition.class));
        nodes.addAll(flat(document.inputObjectTypeExtensions()));
        return nodes.stream();
    }

    /**
     * The scalars the document declared.
     *
     * <p>{@code scalars()} is the only accessor in this file that hands back a node no document
     * wrote. It prepends the five specification scalars to what the document declared, and the
     * declared ones live in a map graphql-java keeps to itself, so there is no accessor that answers
     * this question alone. They are removed here, by the name that makes them built in, rather than
     * by a property they happen to have: a built-in is a built-in because the specification names
     * it, and testing instead for the absence of a position would reach the same conclusion from
     * weaker evidence.
     *
     * <p>Measured rather than assumed. Across a corpus and the bundled vocabulary, every other
     * declaration accessor and every value node reachable from one came back positioned; these five
     * are the whole of what the engine contributes.
     */
    private static Stream<ScalarTypeDefinition> scalars(TypeDefinitionRegistry document) {
        List<ScalarTypeDefinition> nodes = document.scalars().values().stream()
            .filter(scalar -> !ScalarInfo.isGraphqlSpecifiedScalar(scalar.getName()))
            .collect(Collectors.toCollection(ArrayList::new));
        nodes.addAll(flat(document.scalarTypeExtensions()));
        return nodes.stream();
    }

    private static Stream<DirectiveDefinition> directives(TypeDefinitionRegistry document) {
        return document.getDirectiveDefinitions().values().stream();
    }

    private static Stream<SchemaDefinition> schemas(TypeDefinitionRegistry document) {
        List<SchemaDefinition> nodes = new ArrayList<>();
        document.schemaDefinition().ifPresent(nodes::add);
        nodes.addAll(document.getSchemaExtensionDefinitions());
        return nodes.stream();
    }

    /**
     * A written node and what it was written inside, which is the whole of what a supertype row
     * holds. The two records below carry more than that and differ in what they carry; this is the
     * part they share, and naming it is what lets one writer serve every arm.
     */
    interface Written {
        Node<?> parent();

        Node<?> node();
    }


    // ------------------------------------------------------- a node and the node it was written in

    /**
     * A node and the node it was written inside, which is what a row with a parent needs and what
     * the gatherers below hand back. Parent is graphql-java's own word for it and the columns are
     * named for it too. The three top-level kinds have no parent and stream the node alone.
     *
     * <p>Visible to the package because {@link GraphitronTypeEntries} decodes the same walk this one
     * transcribes, and two walks over one document would be two answers to which nodes it holds.
     */
    record Nested<N extends Node<?>>(Node<?> parent, N node) implements Written {}

    /**
     * One value node and where it was written: the node holding the whole expression, the value
     * enclosing this one where there is one, and the index and field name it sits at inside that.
     * The three positional fields are null together at the root of an expression, which sits inside
     * no value. The depth is how many values enclose this one, held only so the insert can go a
     * level at a time and never written down.
     */
    private record WrittenValue(Node<?> holder, Node<?> parent, Integer position,
                                String objectFieldName, int depth, Value<?> node) {}

    private static <N extends Node<?>> void nest(List<Nested<N>> into, Node<?> parent, List<N> nodes) {
        nodes.forEach(node -> into.add(new Nested<>(parent, node)));
    }

    private static List<Nested<FieldDefinition>> fields(TypeDefinitionRegistry document) {
        List<Nested<FieldDefinition>> nested = new ArrayList<>();
        objectsAndInterfaces(document).forEach(parent -> nest(nested, parent, parent.getFieldDefinitions()));
        return nested;
    }

    /**
     * An argument, the field it was written inside, and the name of the declaration that field was
     * written inside.
     *
     * <p>The one three-level shape in the family, and the coordinate is why: a field argument is
     * spelled from both ancestors, and a generated column reads no row but its own. The other four
     * element kinds need one ancestor and travel as an ordinary {@link Nested}.
     */
    private record NestedArgument(Node<?> parent, InputValueDefinition node, String typeName)
        implements Written {}

    /** The three parents an input value may have, which are the same node kind to the parser. */
    private static List<NestedArgument> argumentsOfFields(TypeDefinitionRegistry document) {
        List<NestedArgument> nested = new ArrayList<>();
        for (Nested<FieldDefinition> field : fields(document)) {
            String declaration = nameOf(field.parent());
            field.node().getInputValueDefinitions()
                .forEach(argument -> nested.add(new NestedArgument(field.node(), argument, declaration)));
        }
        return nested;
    }

    private static List<Nested<InputValueDefinition>> fieldsOfInputObjects(TypeDefinitionRegistry document) {
        List<Nested<InputValueDefinition>> nested = new ArrayList<>();
        inputObjects(document).forEach(parent -> nest(nested, parent, parent.getInputValueDefinitions()));
        return nested;
    }

    private static List<Nested<InputValueDefinition>> argumentsOfDirectiveDefinitions(TypeDefinitionRegistry document) {
        List<Nested<InputValueDefinition>> nested = new ArrayList<>();
        directives(document).forEach(parent -> nest(nested, parent, parent.getInputValueDefinitions()));
        return nested;
    }

    /**
     * The three sites' input values as one list, for the one reader that takes an input value
     * whatever encloses it.
     */
    private static List<Nested<InputValueDefinition>> inputValues(TypeDefinitionRegistry document) {
        List<Nested<InputValueDefinition>> nested = new ArrayList<>();
        argumentsOfFields(document)
            .forEach(argument -> nested.add(new Nested<>(argument.parent(), argument.node())));
        nested.addAll(fieldsOfInputObjects(document));
        nested.addAll(argumentsOfDirectiveDefinitions(document));
        return nested;
    }

    private static List<Nested<EnumValueDefinition>> enumValues(TypeDefinitionRegistry document) {
        List<Nested<EnumValueDefinition>> nested = new ArrayList<>();
        enums(document).forEach(parent -> nest(nested, parent, parent.getEnumValueDefinitions()));
        return nested;
    }

    private static List<Nested<TypeName>> implementedInterfaces(TypeDefinitionRegistry document) {
        List<Nested<TypeName>> nested = new ArrayList<>();
        objectsAndInterfaces(document).forEach(parent -> nest(nested, parent, typeNames(parent.getImplements())));
        return nested;
    }

    private static List<Nested<TypeName>> members(TypeDefinitionRegistry document) {
        List<Nested<TypeName>> nested = new ArrayList<>();
        unions(document).forEach(parent -> nest(nested, parent, typeNames(parent.getMemberTypes())));
        return nested;
    }

    private static List<Nested<DirectiveLocation>> locations(TypeDefinitionRegistry document) {
        List<Nested<DirectiveLocation>> nested = new ArrayList<>();
        directives(document).forEach(parent -> nest(nested, parent, parent.getDirectiveLocations()));
        return nested;
    }

    private static List<Nested<OperationTypeDefinition>> operations(TypeDefinitionRegistry document) {
        List<Nested<OperationTypeDefinition>> nested = new ArrayList<>();
        schemas(document).forEach(parent -> nest(nested, parent, parent.getOperationTypeDefinitions()));
        return nested;
    }

    /**
     * An application and the SDL directive location of the node it was written on, spelled as a
     * directive definition spells it.
     *
     * <p>The location is stated where the walk stands rather than recovered from the application
     * afterwards, which is the difference between naming a fact and reconstructing one. The
     * input-value site is why it has to be: {@link #inputValues} knows which of three enclosures
     * each value came from while it is collecting and then concatenates the distinction away, and
     * two of those enclosures are {@code ARGUMENT_DEFINITION} while the third is
     * {@code INPUT_FIELD_DEFINITION}. After the flattening no reader can tell them apart, and the
     * vocabulary does: {@code @orderBy} is declared on the first and {@code @asFacet} on the
     * second.
     *
     * @param application the application and the node it was written on
     * @param location    that node's SDL directive location
     */
    record Located(Nested<Directive> application, String location) {}

    private static void locate(List<Located> into, Node<?> node, List<Directive> applications,
                               String location) {
        applications.forEach(application ->
            into.add(new Located(new Nested<>(node, application), location)));
    }

    /**
     * Every type-site application with the location its declaration offers. The six kinds are every
     * kind {@link #declarations} yields, so a declaration reaching the fallback is one this walk
     * does not collect, and an unnamed location declines to judge rather than refusing.
     */
    static List<Located> locatedOnTypes(TypeDefinitionRegistry document) {
        List<Located> located = new ArrayList<>();
        declarations(document).forEach(parent ->
            locate(located, parent, parent.getDirectives(), switch (parent) {
                case ObjectTypeDefinition ignored -> "OBJECT";
                case InterfaceTypeDefinition ignored -> "INTERFACE";
                case UnionTypeDefinition ignored -> "UNION";
                case EnumTypeDefinition ignored -> "ENUM";
                case ScalarTypeDefinition ignored -> "SCALAR";
                case InputObjectTypeDefinition ignored -> "INPUT_OBJECT";
                default -> null;
            }));
        return located;
    }

    /** Every field-site application; one enclosure, so one location. */
    static List<Located> locatedOnFields(TypeDefinitionRegistry document) {
        List<Located> located = new ArrayList<>();
        fields(document).forEach(field ->
            locate(located, field.node(), field.node().getDirectives(), "FIELD_DEFINITION"));
        return located;
    }

    /**
     * Every input-value-site application with the location of the enclosure it was written in,
     * in the order {@link #inputValues} yields them. A field's argument and a directive
     * definition's argument are both {@code ARGUMENT_DEFINITION}; an input object's field is not.
     */
    static List<Located> locatedOnInputValues(TypeDefinitionRegistry document) {
        List<Located> located = new ArrayList<>();
        argumentsOfFields(document).forEach(argument ->
            locate(located, argument.node(), argument.node().getDirectives(), "ARGUMENT_DEFINITION"));
        fieldsOfInputObjects(document).forEach(value ->
            locate(located, value.node(), value.node().getDirectives(), "INPUT_FIELD_DEFINITION"));
        argumentsOfDirectiveDefinitions(document).forEach(value ->
            locate(located, value.node(), value.node().getDirectives(), "ARGUMENT_DEFINITION"));
        return located;
    }

    /** Every enum-value-site application; one enclosure, so one location. */
    static List<Located> locatedOnEnumValues(TypeDefinitionRegistry document) {
        List<Located> located = new ArrayList<>();
        enumValues(document).forEach(value ->
            locate(located, value.node(), value.node().getDirectives(), "ENUM_VALUE"));
        return located;
    }

    /** Every schema-site application; one enclosure, so one location. */
    static List<Located> locatedOnSchemas(TypeDefinitionRegistry document) {
        List<Located> located = new ArrayList<>();
        schemas(document).forEach(parent ->
            locate(located, parent, parent.getDirectives(), "SCHEMA"));
        return located;
    }

    static List<Nested<Directive>> directivesOnTypes(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        declarations(document).forEach(parent -> nest(nested, parent, parent.getDirectives()));
        return nested;
    }

    static List<Nested<Directive>> directivesOnFields(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        fields(document).forEach(field -> nest(nested, field.node(), field.node().getDirectives()));
        return nested;
    }

    static List<Nested<Directive>> directivesOnInputValues(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        inputValues(document).forEach(value -> nest(nested, value.node(), value.node().getDirectives()));
        return nested;
    }

    static List<Nested<Directive>> directivesOnEnumValues(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        enumValues(document).forEach(value -> nest(nested, value.node(), value.node().getDirectives()));
        return nested;
    }

    static List<Nested<Directive>> directivesOnSchemas(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        schemas(document).forEach(parent -> nest(nested, parent, parent.getDirectives()));
        return nested;
    }

    /**
     * Every application, whichever site it sits at, for the one reader that takes them all: an
     * argument's parent is a directive and nothing narrower.
     */
    private static List<Nested<Directive>> applications(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        nested.addAll(directivesOnTypes(document));
        nested.addAll(directivesOnFields(document));
        nested.addAll(directivesOnInputValues(document));
        nested.addAll(directivesOnEnumValues(document));
        nested.addAll(directivesOnSchemas(document));
        return nested;
    }

    private static List<Nested<Argument>> applicationArguments(TypeDefinitionRegistry document) {
        List<Nested<Argument>> nested = new ArrayList<>();
        applications(document).forEach(application ->
            nest(nested, application.node(), application.node().getArguments()));
        return nested;
    }

    /**
     * Every value node in the document, flattened, each carrying where it sits.
     *
     * <p>Four slots can hold one: an argument of a directive application holds the value passed to
     * it, and an argument, an input field and a directive definition's argument each hold a default.
     * All four are the same fact, that a slot was written with a literal, so all four are walked
     * into one list here rather than by four callers into four relations.
     */
    private static List<WrittenValue> writtenValues(TypeDefinitionRegistry document) {
        List<WrittenValue> flat = new ArrayList<>();
        applicationArguments(document).forEach(argument ->
            walk(flat, argument.node(), null, null, null, 0, argument.node().getValue()));
        inputValues(document).forEach(slot ->
            walk(flat, slot.node(), null, null, null, 0, slot.node().getDefaultValue()));
        return flat;
    }

    /**
     * Adds this value and everything written inside it, depth first, each row carrying how many
     * values enclose it so the caller can insert a level at a time.
     *
     * <p>Every value reached here was written. A value hangs off a declaration, the declarations
     * this file collects are the ones a document wrote, and the five the engine contributes are
     * scalars, which carry no arguments and no defaults and so reach no value at all. The guard
     * that used to stop the walk at an unwritten node never stopped it.
     *
     * <p>An object's field gets no row of its own. A field is a name and a value; the value is the
     * row and the name is a column on it, which is one node fewer for a reader to walk through and
     * loses nothing but the position of the name itself.
     */
    private static void walk(List<WrittenValue> flat, Node<?> holder, Node<?> parent,
                             Integer position, String objectFieldName, int depth, Value<?> node) {
        if (node == null) {
            return;
        }
        flat.add(new WrittenValue(holder, parent, position, objectFieldName, depth, node));
        switch (node) {
            case ArrayValue list -> {
                var items = list.getValues();
                for (int index = 0; index < items.size(); index++) {
                    walk(flat, holder, node, index, null, depth + 1, items.get(index));
                }
            }
            case ObjectValue object -> {
                var fields = object.getObjectFields();
                for (int index = 0; index < fields.size(); index++) {
                    var field = fields.get(index);
                    walk(flat, holder, node, index, field.getName(), depth + 1, field.getValue());
                }
            }
            default -> {
                // A leaf, which is every kind but the two that contain values.
            }
        }
    }

    /**
     * The bare type names in a grammar position that allows no wrapper: an implements clause and a
     * union's members. The accessors hand these back as the raw supertype, so the narrowing happens
     * here, and a node that is somehow not a name is left out rather than refused, this being a
     * gatherer.
     */
    @SuppressWarnings("rawtypes")
    private static List<TypeName> typeNames(List<Type> types) {
        return types.stream()
            .filter(TypeName.class::isInstance)
            .map(TypeName.class::cast)
            .toList();
    }

    // ------------------------------------------------------------------------ reading a node's parts

    /**
     * The position columns, bound to the Java type each holds rather than to any one relation's
     * column. Visible to the package for the reason {@link Nested} is: {@link GraphitronTypeEntries}
     * keys its rows at the position of a node this class also writes, and two readings of one
     * node's location are two chances to disagree about it.
     */
    static Field<String> sourceName(Node<?> node) {
        return val(node.getSourceLocation().getSourceName(), String.class);
    }

    static Field<Integer> sourceLine(Node<?> node) {
        return val(node.getSourceLocation().getLine(), Integer.class);
    }

    static Field<Integer> sourceColumn(Node<?> node) {
        return val(node.getSourceLocation().getColumn(), Integer.class);
    }

    /**
     * The file again, in the nullable twin carrying the registry reference. Always set at write time;
     * the null arrives later, when a removal withdraws the registry's vouch.
     */
    private static Field<String> sourceRef(Node<?> node) {
        return val(node.getSourceLocation().getSourceName(), String.class);
    }

    /** Where the node this one was written inside was written. */
    private static Field<Integer> parentLine(Node<?> parent) {
        return val(parent.getSourceLocation().getLine(), Integer.class);
    }

    private static Field<Integer> parentColumn(Node<?> parent) {
        return val(parent.getSourceLocation().getColumn(), Integer.class);
    }

    /**
     * Where the value this one was written inside was written, or null at the root of an expression.
     * Separate from {@link #parentLine} because that one's argument is always there and this one's
     * absence is the fact that the row is a root.
     */
    private static Integer enclosingLine(Node<?> parent) {
        return parent == null ? null : parent.getSourceLocation().getLine();
    }

    private static Integer enclosingColumn(Node<?> parent) {
        return parent == null ? null : parent.getSourceLocation().getColumn();
    }

    /**
     * A value exactly as written, or null where none was written.
     *
     * <p>Not {@code printAstCompact} directly: it renders a missing node as the empty string, and
     * the column this feeds says NULL where none was. An empty default and an absent one are
     * different things to say about a declaration, and only one of them an author can write.
     */
    private static String writtenSdl(Value<?> value) {
        return value == null ? null : printAstCompact(value);
    }

    /**
     * A node's own name, held on the children whose coordinate is spelled from it. The position
     * beside it is still the key; this is the spelling, not a second one. For the parents that
     * have a name, Every parent a coordinate is spelled from
     * is a declaration or a field definition, both of which graphql-java hands back as a
     * {@link NamedNode}; anything else would be a parent no coordinate names.
     */
    private static String nameOf(Node<?> node) {
        return node instanceof NamedNode<?> named ? named.getName() : null;
    }

    /**
     * Which of the nine value forms this is, read off the node class the parser produced. Eight are
     * named and the ninth, a variable reference, is what is left: the parser makes no other kind of
     * value, and naming it as the default is the same reading the declaration kinds take below.
     */
    private static String valueKind(Value<?> node) {
        return switch (node) {
            case StringValue ignored -> "STRING";
            case IntValue ignored -> "INT";
            case FloatValue ignored -> "FLOAT";
            case BooleanValue ignored -> "BOOLEAN";
            case NullValue ignored -> "NULL";
            case EnumValue ignored -> "ENUM";
            case ArrayValue ignored -> "LIST";
            case ObjectValue ignored -> "OBJECT";
            default -> "VARIABLE";
        };
    }

    /**
     * A leaf's text as the author wrote it. A string loses its quotes, which are grammar rather than
     * value; nothing else is interpreted, so an int stays its digits and a float stays its own
     * spelling. A list, an object and the null literal have no text of their own and give null,
     * which is what the column's check requires of exactly those three.
     */
    private static String valueText(Value<?> node) {
        return switch (node) {
            case StringValue string -> string.getValue();
            case IntValue integer -> integer.getValue().toString();
            case FloatValue number -> number.getValue().toString();
            case BooleanValue bool -> String.valueOf(bool.isValue());
            case EnumValue name -> name.getName();
            case VariableReference variable -> variable.getName();
            default -> null;
        };
    }

    /** Which of the six declaration forms this is, read off the node class the parser produced. */
    private static String kind(TypeDefinition<?> node) {
        return switch (node) {
            case ObjectTypeDefinition ignored -> "OBJECT";
            case InterfaceTypeDefinition ignored -> "INTERFACE";
            case UnionTypeDefinition ignored -> "UNION";
            case EnumTypeDefinition ignored -> "ENUM";
            case InputObjectTypeDefinition ignored -> "INPUT_OBJECT";
            default -> "SCALAR";
        };
    }

    private static String text(Node<?> node) {
        Description description = node instanceof DescribedNode<?> described
            ? described.getDescription() : null;
        return description == null ? null : description.getContent();
    }

    /**
     * The type name at the bottom of a written type expression, the wrappers stripped. Reading the
     * node down its own spine, which is what makes the four columns beside {@code type_sdl} a
     * transcription and not a resolution: nothing outside this expression is consulted.
     */
    private static String namedType(Type<?> type) {
        return switch (type) {
            case NonNullType wrapper -> namedType(wrapper.getType());
            case ListType wrapper -> namedType(wrapper.getType());
            case TypeName named -> named.getName();
            default -> null;
        };
    }

    private static boolean nonNull(Type<?> type) {
        return type instanceof NonNullType;
    }

    private static boolean isList(Type<?> type) {
        return unwrapped(type) instanceof ListType;
    }

    private static Boolean itemNonNull(Type<?> type) {
        return unwrapped(type) instanceof ListType list ? list.getType() instanceof NonNullType : null;
    }

    /**
     * How many list wrappers the expression has, which is what tells {@code [Film]} from
     * {@code [[Film]]}. The three above cannot: they read one level and a nested list reads as a
     * list of something nullable, true of the outer list and silent about the inner one. Without
     * this a reader wanting the difference has to parse {@code type_sdl}, in SQL.
     */
    private static int listDepth(Type<?> type) {
        return unwrapped(type) instanceof ListType list ? 1 + listDepth(list.getType()) : 0;
    }

    /** The expression with its outermost non-null removed, which is where a list shows itself. */
    private static Type<?> unwrapped(Type<?> type) {
        return type instanceof NonNullType wrapper ? wrapper.getType() : type;
    }
}
