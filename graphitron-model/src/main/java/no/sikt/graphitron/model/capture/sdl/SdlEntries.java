package no.sikt.graphitron.model.capture.sdl;

import graphql.language.Description;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumTypeExtensionDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputObjectTypeExtensionDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.InterfaceTypeExtensionDefinition;
import graphql.language.NamedNode;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.ScalarTypeExtensionDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.language.UnionTypeExtensionDefinition;
import graphql.language.SourceLocation;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INPUT_OBJECT_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INTERFACE_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_OBJECT_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_TYPE_EXTENSION_ENTRY;
import static org.jooq.impl.DSL.val;

/**
 * Writes one parsed document into the store, as written.
 *
 * <p>One relation per SDL node kind, mirroring graphql-java's own: this family is the document in
 * graphql-java's vocabulary, so the shape here is that API's rather than a paraphrase of it. An
 * extension is a different node kind and gets a different relation, which is why nothing here
 * carries a flag saying which it is, and why there is no kind discriminator to compute.
 *
 * <p>Every row is keyed by the position it was written at, so nothing can collide: two documents
 * declaring one name are two rows. That leaves this with nothing to decide, and so with no claim, no
 * first-wins, no ordinals and no state. Call it once per document, in any order.
 *
 * <p>One statement per relation, every value bound to the column it is going into, so the column
 * list and the rows are checked against each other by the compiler rather than by a failing insert.
 *
 * <p>What this does not do is anything about a corpus. Which declaration won, whether a name was
 * declared twice, and what a written type expression resolves to are questions across documents, and
 * they are queries over these rows.
 */
public final class SdlEntries {

    private SdlEntries() {}

    /** Writes {@code document}'s declarations under {@code graph}. */
    public static void write(DSLContext dsl, String graph, TypeDefinitionRegistry document) {
        objectTypes(dsl, graph, document.getTypes(ObjectTypeDefinition.class));
        interfaceTypes(dsl, graph, document.getTypes(InterfaceTypeDefinition.class));
        unionTypes(dsl, graph, document.getTypes(UnionTypeDefinition.class));
        enumTypes(dsl, graph, document.getTypes(EnumTypeDefinition.class));
        inputObjectTypes(dsl, graph, document.getTypes(InputObjectTypeDefinition.class));
        scalarTypes(dsl, graph, List.copyOf(document.scalars().values()));
        objectTypeExtensions(dsl, graph, flat(document.objectTypeExtensions()));
        interfaceTypeExtensions(dsl, graph, flat(document.interfaceTypeExtensions()));
        unionTypeExtensions(dsl, graph, flat(document.unionTypeExtensions()));
        enumTypeExtensions(dsl, graph, flat(document.enumTypeExtensions()));
        inputObjectTypeExtensions(dsl, graph, flat(document.inputObjectTypeExtensions()));
        scalarTypeExtensions(dsl, graph, flat(document.scalarTypeExtensions()));
    }

    /** One accessor's extensions, whose map holds them per name and several per name. */
    private static <N> List<N> flat(Map<String, List<N>> byName) {
        return byName.values().stream().flatMap(List::stream).toList();
    }

    private static void objectTypes(DSLContext dsl, String graph, List<ObjectTypeDefinition> nodes) {
        var t = GRAPHQL_OBJECT_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME, t.DESCRIPTION)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void interfaceTypes(DSLContext dsl, String graph,
                                       List<InterfaceTypeDefinition> nodes) {
        var t = GRAPHQL_INTERFACE_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME, t.DESCRIPTION)
                .valuesOfRows(rows)
                .execute();
        }
    }

    /**
     * An extension is its own node kind, so its own relation, and it carries no description: the
     * grammar puts one on a base declaration only.
     */
    private static void objectTypeExtensions(DSLContext dsl, String graph,
                                             List<ObjectTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.NAME)
                .valuesOfRows(rows)
                .execute();
        }
    }


    private static void unionTypes(DSLContext dsl, String graph, List<UnionTypeDefinition> nodes) {
        var t = GRAPHQL_UNION_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME, t.DESCRIPTION)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void enumTypes(DSLContext dsl, String graph, List<EnumTypeDefinition> nodes) {
        var t = GRAPHQL_ENUM_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME, t.DESCRIPTION)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void inputObjectTypes(DSLContext dsl, String graph, List<InputObjectTypeDefinition> nodes) {
        var t = GRAPHQL_INPUT_OBJECT_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME, t.DESCRIPTION)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void scalarTypes(DSLContext dsl, String graph, List<ScalarTypeDefinition> nodes) {
        var t = GRAPHQL_SCALAR_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME, t.DESCRIPTION)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void interfaceTypeExtensions(DSLContext dsl, String graph, List<InterfaceTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void unionTypeExtensions(DSLContext dsl, String graph, List<UnionTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_UNION_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void enumTypeExtensions(DSLContext dsl, String graph, List<EnumTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void inputObjectTypeExtensions(DSLContext dsl, String graph, List<InputObjectTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME)
                .valuesOfRows(rows)
                .execute();
        }
    }

    private static void scalarTypeExtensions(DSLContext dsl, String graph, List<ScalarTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(node.getName(), t.NAME)));
        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.NAME)
                .valuesOfRows(rows)
                .execute();
        }
    }

    /**
     * Whether a document wrote this node. A built-in the engine provides has no file and no position,
     * so it has no row here; that it exists at all is not a fact about any document.
     */
    private static boolean written(NamedNode<?> node) {
        SourceLocation at = node.getSourceLocation();
        return at != null && at.getSourceName() != null;
    }

    private static String text(Description description) {
        return description == null ? null : description.getContent();
    }
}
