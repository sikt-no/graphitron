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
import graphql.language.SourceLocation;
import graphql.language.UnionTypeDefinition;
import graphql.language.UnionTypeExtensionDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
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
import static org.jooq.impl.DSL.excluded;
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
 * declaring one name are two rows. That leaves the writing with nothing to decide, and so with no
 * claim, no first-wins and no ordinals.
 *
 * <p>Mark and sweep, because a store is not always empty. Every row a reading writes carries that
 * reading's instant, and the reading finishes by deleting its file's rows that still carry an older
 * one. An upsert alone would handle a file read twice and would miss the case that matters: a
 * declaration the author removed has no incoming row to be matched against, so nothing updates it
 * and nothing deletes it. The sweep is what finds those, and it is the only part of this class that
 * looks at rows it did not just write.
 *
 * <p>What this does not do is anything about a corpus. Which declaration won, whether a name was
 * declared twice, and what a written type expression resolves to are questions across documents, and
 * they are queries over these rows.
 */
public final class SdlEntries {

    private SdlEntries() {}

    /**
     * Makes {@code source}'s rows under {@code graph} be exactly what {@code document} declares.
     *
     * <p>The file is a parameter rather than read off the declarations, because a document whose
     * every declaration was deleted still has rows to sweep and its definitions can no longer say
     * which file they were in. The instant is the caller's for the same kind of reason: the twelve
     * relations have to agree on which reading is the current one.
     *
     * <p>Whether to call this at all is the caller's question. Re-reading a file whose bytes have not
     * changed rewrites identical rows, which is wasteful and not wrong; skipping it is what
     * {@code store_source.stamp} is for, one level up.
     */
    public static void write(DSLContext dsl, String graph, String source,
                             TypeDefinitionRegistry document, LocalDateTime touchedAt) {
        objectTypes(dsl, graph, touchedAt, document.getTypes(ObjectTypeDefinition.class));
        interfaceTypes(dsl, graph, touchedAt, document.getTypes(InterfaceTypeDefinition.class));
        unionTypes(dsl, graph, touchedAt, document.getTypes(UnionTypeDefinition.class));
        enumTypes(dsl, graph, touchedAt, document.getTypes(EnumTypeDefinition.class));
        inputObjectTypes(dsl, graph, touchedAt, document.getTypes(InputObjectTypeDefinition.class));
        scalarTypes(dsl, graph, touchedAt, List.copyOf(document.scalars().values()));
        objectTypeExtensions(dsl, graph, touchedAt, flat(document.objectTypeExtensions()));
        interfaceTypeExtensions(dsl, graph, touchedAt, flat(document.interfaceTypeExtensions()));
        unionTypeExtensions(dsl, graph, touchedAt, flat(document.unionTypeExtensions()));
        enumTypeExtensions(dsl, graph, touchedAt, flat(document.enumTypeExtensions()));
        inputObjectTypeExtensions(dsl, graph, touchedAt, flat(document.inputObjectTypeExtensions()));
        scalarTypeExtensions(dsl, graph, touchedAt, flat(document.scalarTypeExtensions()));
        sweep(dsl, graph, source, touchedAt);
    }

    /**
     * Every relation this reader writes, so the sweep can reach all of them. Listed rather than
     * found by prefix: a relation added above and not here would keep its stale rows silently, and a
     * list that has to be edited alongside is the cheapest way to make that visible.
     */
    private static final List<Table<?>> RELATIONS = List.of(
        GRAPHQL_OBJECT_TYPE_ENTRY, GRAPHQL_INTERFACE_TYPE_ENTRY,
        GRAPHQL_UNION_TYPE_ENTRY, GRAPHQL_ENUM_TYPE_ENTRY,
        GRAPHQL_INPUT_OBJECT_TYPE_ENTRY, GRAPHQL_SCALAR_TYPE_ENTRY,
        GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY, GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY,
        GRAPHQL_UNION_TYPE_EXTENSION_ENTRY, GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY,
        GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY, GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY);

    /**
     * Deletes this file's rows that this reading did not touch, which are the declarations the
     * author removed. Scoped to the file, so a reading of one document says nothing about another's
     * rows.
     */
    private static void sweep(DSLContext dsl, String graph, String source, LocalDateTime touchedAt) {
        for (Table<?> table : RELATIONS) {
            dsl.deleteFrom(table)
                .where(table.field(GRAPHQL_OBJECT_TYPE_ENTRY.GRAPH_NAME).eq(graph))
                .and(table.field(GRAPHQL_OBJECT_TYPE_ENTRY.SOURCE_NAME).eq(source))
                .and(table.field(GRAPHQL_OBJECT_TYPE_ENTRY.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    /** One accessor's extensions, whose map holds them per name and several per name. */
    private static <N> List<N> flat(Map<String, List<N>> byName) {
        return byName.values().stream().flatMap(List::stream).toList();
    }

    private static void objectTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<ObjectTypeDefinition> nodes) {
        var t = GRAPHQL_OBJECT_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void interfaceTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<InterfaceTypeDefinition> nodes) {
        var t = GRAPHQL_INTERFACE_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void unionTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<UnionTypeDefinition> nodes) {
        var t = GRAPHQL_UNION_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void enumTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<EnumTypeDefinition> nodes) {
        var t = GRAPHQL_ENUM_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void inputObjectTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<InputObjectTypeDefinition> nodes) {
        var t = GRAPHQL_INPUT_OBJECT_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void scalarTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<ScalarTypeDefinition> nodes) {
        var t = GRAPHQL_SCALAR_TYPE_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME),
                node -> val(text(node.getDescription()), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void objectTypeExtensions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<ObjectTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_OBJECT_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void interfaceTypeExtensions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<InterfaceTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_INTERFACE_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void unionTypeExtensions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<UnionTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_UNION_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void enumTypeExtensions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<EnumTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_ENUM_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void inputObjectTypeExtensions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<InputObjectTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_INPUT_OBJECT_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void scalarTypeExtensions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             List<ScalarTypeExtensionDefinition> nodes) {
        var t = GRAPHQL_SCALAR_TYPE_EXTENSION_ENTRY;
        var rows = nodes.stream()
            .filter(SdlEntries::written)
            .collect(Rows.toRowList(
                node -> val(graph, t.GRAPH_NAME),
                node -> val(node.getSourceLocation().getSourceName(), t.SOURCE_NAME),
                node -> val(node.getSourceLocation().getLine(), t.SOURCE_LINE),
                node -> val(node.getSourceLocation().getColumn(), t.SOURCE_COLUMN),
                node -> val(touchedAt, t.TOUCHED_AT),
                node -> val(node.getName(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
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
