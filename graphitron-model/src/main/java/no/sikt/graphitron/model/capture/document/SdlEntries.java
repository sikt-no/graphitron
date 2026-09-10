package no.sikt.graphitron.model.capture.document;

import graphql.language.Argument;
import graphql.language.DescribedNode;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.DirectiveLocation;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.ImplementingTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.language.SDLExtensionDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.SourceLocation;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static graphql.language.AstPrinter.printAstCompact;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
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
import static org.jooq.impl.DSL.excluded;
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
public final class SdlEntries {

    private SdlEntries() {}

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
        GRAPHQL_AST_TYPE_DECLARATION_ENTRY, GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY,
        GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY, GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
        GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY, GRAPHQL_AST_IMPLEMENTS_ENTRY,
        GRAPHQL_AST_UNION_MEMBER_ENTRY, GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY,
        GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY, GRAPHQL_AST_FIELD_ARGUMENT_ENTRY,
        GRAPHQL_AST_INPUT_FIELD_ENTRY, GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY,
        GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY, GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY,
        GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY, GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY,
        GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY, GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY);

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

    private static void typeDeclarations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.KIND, t.IS_EXTENSION, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.KIND, excluded(t.KIND))
            .set(t.IS_EXTENSION, excluded(t.IS_EXTENSION))
            .set(t.NAME, excluded(t.NAME))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void directiveDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                              TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.NAME, t.REPEATABLE, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .set(t.REPEATABLE, excluded(t.REPEATABLE))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void schemaDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                           TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.IS_EXTENSION, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.IS_EXTENSION, excluded(t.IS_EXTENSION))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void fieldDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
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
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.NAME, t.TYPE_SDL,
                t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.DESCRIPTION)
            .valuesOfRows(rows)
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
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void fieldArguments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
            nested -> val(written(nested.node().getDefaultValue()), t.DEFAULT_VALUE_SDL),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME,
                t.FIELD_NAME, t.NAME, t.TYPE_SDL, t.NAMED_TYPE,
                t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.DEFAULT_VALUE_SDL, t.DESCRIPTION)
            .valuesOfRows(rows)
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
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void inputFields(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
            nested -> val(written(nested.node().getDefaultValue()), t.DEFAULT_VALUE_SDL),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.NAME, t.TYPE_SDL,
                t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.DEFAULT_VALUE_SDL,
                t.DESCRIPTION)
            .valuesOfRows(rows)
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
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    /** What a directive definition declares, where appliedArguments below is what an application passes. */
    private static void directiveArguments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
            nested -> val(written(nested.node().getDefaultValue()), t.DEFAULT_VALUE_SDL),
            nested -> val(text(nested.node()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME, t.TYPE_SDL, t.NAMED_TYPE,
                t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.DEFAULT_VALUE_SDL, t.DESCRIPTION)
            .valuesOfRows(rows)
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
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void enumValueDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                              TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .set(t.NAME, excluded(t.NAME))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void implementsClauses(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.INTERFACE_NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .set(t.INTERFACE_NAME, excluded(t.INTERFACE_NAME))
            .execute();
    }

    private static void unionMembers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.TYPE_NAME, t.MEMBER_NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .set(t.MEMBER_NAME, excluded(t.MEMBER_NAME))
            .execute();
    }

    private static void directiveLocations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                            TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.LOCATION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.LOCATION, excluded(t.LOCATION))
            .execute();
    }

    private static void operationTypeDefinitions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                                  TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.OPERATION, t.TYPE_NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.OPERATION, excluded(t.OPERATION))
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .execute();
    }

    private static void typeDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }
    private static void fieldDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }
    /** All three input-value sites in one relation: the parent is a union whichever way this is cut. */
    private static void inputValueDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }
    private static void enumValueDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }
    private static void schemaDirectives(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void appliedArguments(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                          TypeDefinitionRegistry document) {
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.SOURCE_REF,
                t.TOUCHED_AT, t.PARENT_LINE, t.PARENT_COLUMN, t.NAME, t.VALUE_SDL)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.SOURCE_REF, excluded(t.SOURCE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARENT_LINE, excluded(t.PARENT_LINE))
            .set(t.PARENT_COLUMN, excluded(t.PARENT_COLUMN))
            .set(t.NAME, excluded(t.NAME))
            .set(t.VALUE_SDL, excluded(t.VALUE_SDL))
            .execute();
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
        return nodes.stream().filter(SdlEntries::written);
    }

    private static Stream<UnionTypeDefinition> unions(TypeDefinitionRegistry document) {
        List<UnionTypeDefinition> nodes = new ArrayList<>(document.getTypes(UnionTypeDefinition.class));
        nodes.addAll(flat(document.unionTypeExtensions()));
        return nodes.stream().filter(SdlEntries::written);
    }

    private static Stream<EnumTypeDefinition> enums(TypeDefinitionRegistry document) {
        List<EnumTypeDefinition> nodes = new ArrayList<>(document.getTypes(EnumTypeDefinition.class));
        nodes.addAll(flat(document.enumTypeExtensions()));
        return nodes.stream().filter(SdlEntries::written);
    }

    private static Stream<InputObjectTypeDefinition> inputObjects(TypeDefinitionRegistry document) {
        List<InputObjectTypeDefinition> nodes =
            new ArrayList<>(document.getTypes(InputObjectTypeDefinition.class));
        nodes.addAll(flat(document.inputObjectTypeExtensions()));
        return nodes.stream().filter(SdlEntries::written);
    }

    /**
     * The scalars the document declared. The accessor hands back the engine's built-ins alongside
     * them, and the filter is what leaves those out: a built-in has no file and no position, so it
     * has no row here, and that it exists at all is not a fact about any document.
     */
    private static Stream<ScalarTypeDefinition> scalars(TypeDefinitionRegistry document) {
        List<ScalarTypeDefinition> nodes = new ArrayList<>(document.scalars().values());
        nodes.addAll(flat(document.scalarTypeExtensions()));
        return nodes.stream().filter(SdlEntries::written);
    }

    private static Stream<DirectiveDefinition> directives(TypeDefinitionRegistry document) {
        return document.getDirectiveDefinitions().values().stream().filter(SdlEntries::written);
    }

    private static Stream<SchemaDefinition> schemas(TypeDefinitionRegistry document) {
        List<SchemaDefinition> nodes = new ArrayList<>();
        document.schemaDefinition().ifPresent(nodes::add);
        nodes.addAll(document.getSchemaExtensionDefinitions());
        return nodes.stream().filter(SdlEntries::written);
    }

    // ------------------------------------------------------- a node and the node it was written in

    /**
     * A node and the node it was written inside, which is what a row with a parent needs and what
     * the gatherers below hand back. Parent is graphql-java's own word for it and the columns are
     * named for it too. The three top-level kinds have no parent and stream the node alone.
     *
     * <p>Visible to the package because {@link GraphitronEntries} decodes the same walk this one
     * transcribes, and two walks over one document would be two answers to which nodes it holds.
     */
    record Nested<N extends Node<?>>(Node<?> parent, N node) {}

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
    private record NestedArgument(Node<?> parent, InputValueDefinition node, String typeName) {}

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

    static List<Nested<Directive>> directivesOnTypes(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        declarations(document).forEach(parent -> nest(nested, parent, parent.getDirectives()));
        return nested;
    }

    private static List<Nested<Directive>> directivesOnFields(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        fields(document).forEach(field -> nest(nested, field.node(), field.node().getDirectives()));
        return nested;
    }

    private static List<Nested<Directive>> directivesOnInputValues(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        inputValues(document).forEach(value -> nest(nested, value.node(), value.node().getDirectives()));
        return nested;
    }

    private static List<Nested<Directive>> directivesOnEnumValues(TypeDefinitionRegistry document) {
        List<Nested<Directive>> nested = new ArrayList<>();
        enumValues(document).forEach(value -> nest(nested, value.node(), value.node().getDirectives()));
        return nested;
    }

    private static List<Nested<Directive>> directivesOnSchemas(TypeDefinitionRegistry document) {
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
     * column. Visible to the package for the reason {@link Nested} is: {@link GraphitronEntries}
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
     * A value exactly as written, or null where none was written.
     *
     * <p>Not {@code printAstCompact} directly: it renders a missing node as the empty string, and
     * the column this feeds says NULL where none was. An empty default and an absent one are
     * different things to say about a declaration, and only one of them an author can write.
     */
    private static String written(Value<?> value) {
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
     * Whether a document wrote this node. A built-in the engine provides has no file and no position,
     * so it has no row here; that it exists at all is not a fact about any document.
     */
    private static boolean written(Node<?> node) {
        SourceLocation at = node.getSourceLocation();
        return at != null && at.getSourceName() != null;
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

    /** The expression with its outermost non-null removed, which is where a list shows itself. */
    private static Type<?> unwrapped(Type<?> type) {
        return type instanceof NonNullType wrapper ? wrapper.getType() : type;
    }
}
