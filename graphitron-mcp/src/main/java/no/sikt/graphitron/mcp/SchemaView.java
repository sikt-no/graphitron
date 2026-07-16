package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code schema} read tool: current types / fields, classifications, backing
 * shapes, and definition locations off {@code Workspace.snapshot()}, joined with {@code @node}
 * metadata off {@code Workspace.catalog().nodeMetadata()} (the snapshot carries no {@code @node}
 * projection; the snapshot + catalog join is same-cadence, both written by one
 * {@code setBuildOutput}).
 *
 * <p>The classification / backing-shape / snapshot mappings are exhaustive switches over the
 * sealed permits with no {@code default}, mirroring {@code statusResult}: a new permit forces a
 * compile-time choice here rather than silently flattening.
 */
final class SchemaView {

    private SchemaView() {}

    /** Default page size when listing types (a {@code type} narrow returns the one type, unpaged). */
    static final int DEFAULT_LIMIT = 100;

    static McpSchema.CallToolResult schemaResult(
        LspSchemaSnapshot snapshot, Map<String, CompletionData.NodeMetadata> nodeMetadata, Map<String, Object> args
    ) {
        var fields = new LinkedHashMap<String, Object>();
        return switch (snapshot) {
            case LspSchemaSnapshot.Unavailable ignored -> {
                fields.put("availability", "Unavailable");
                fields.put("types", List.of());
                yield result("schema: snapshot Unavailable (no successful build yet).", fields);
            }
            case LspSchemaSnapshot.Built.Current c -> built(c, "Current", nodeMetadata, args, fields);
            case LspSchemaSnapshot.Built.Previous p -> built(p, "Previous", nodeMetadata, args, fields);
        };
    }

    private static McpSchema.CallToolResult built(
        LspSchemaSnapshot.Built b, String freshness,
        Map<String, CompletionData.NodeMetadata> nodeMetadata, Map<String, Object> args,
        LinkedHashMap<String, Object> fields
    ) {
        fields.put("availability", "Built");
        fields.put("freshness", freshness);

        Optional<String> typeFilter = McpWire.stringArg(args, "type");
        // Stable ordering over the classification map keys (Map.copyOf does not preserve order).
        var typeNames = new ArrayList<>(b.typeClassificationsByName().keySet());
        typeNames.sort(String::compareTo);

        if (typeFilter.isPresent()) {
            String name = typeFilter.get();
            if (!b.typeClassificationsByName().containsKey(name)) {
                fields.put("types", List.of());
                fields.put("notFound", name);
                return result("schema: type '" + name + "' not found in the current snapshot.", fields);
            }
            fields.put("types", List.of(typeEntry(name, b, nodeMetadata)));
            return result("schema: type '" + name + "'.", fields);
        }

        var paged = McpWire.page(typeNames, args, DEFAULT_LIMIT);
        var list = new ArrayList<Map<String, Object>>(paged.items().size());
        for (var name : paged.items()) {
            list.add(typeEntry(name, b, nodeMetadata));
        }
        fields.put("types", list);
        paged.nextCursor().ifPresent(c -> fields.put("nextCursor", c));
        String summary = "schema: " + typeNames.size() + " type(s); showing " + paged.items().size()
            + (paged.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return result(summary, fields);
    }

    private static Map<String, Object> typeEntry(
        String name, LspSchemaSnapshot.Built b, Map<String, CompletionData.NodeMetadata> nodeMetadata
    ) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("typeRef", name);
        var classification = b.typeClassificationsByName().get(name);
        if (classification != null) entry.put("typeClassification", mapTypeClassification(classification));
        b.typeBacking(name).ifPresent(backing -> entry.put("backingShape", mapBackingShape(backing)));

        var node = nodeMetadata.get(name);
        if (node != null) {
            var nm = new LinkedHashMap<String, Object>();
            McpWire.putIfNotNull(nm, "typeId", node.typeId());
            McpWire.putIfNotNull(nm, "keyColumns", node.keyColumns());
            entry.put("node", nm);
        }

        var fieldEntries = new ArrayList<Map<String, Object>>();
        String prefix = name + ".";
        var coords = new ArrayList<>(b.fieldClassificationsByCoord().keySet());
        coords.sort(String::compareTo);
        for (var coord : coords) {
            if (!coord.startsWith(prefix)) continue;
            var fc = new LinkedHashMap<String, Object>();
            fc.put("fieldRef", coord);
            fc.put("classification", mapFieldClassification(b.fieldClassificationsByCoord().get(coord)));
            fieldEntries.add(fc);
        }
        entry.put("fields", fieldEntries);

        b.typeDefinitionLocation(name).ifPresent(loc -> entry.put("definitionLocation", McpWire.location(loc)));
        return entry;
    }

    // ---- type classification (exhaustive over TypeClassification permits) ----

    private static Map<String, Object> mapTypeClassification(TypeClassification c) {
        var m = new LinkedHashMap<String, Object>();
        switch (c) {
            case TypeClassification.Table t -> {
                m.put("kind", "Table");
                McpWire.putIfNotNull(m, "tableName", t.tableName());
            }
            case TypeClassification.Node n -> {
                m.put("kind", "Node");
                McpWire.putIfNotNull(m, "tableName", n.tableName());
                McpWire.putIfNotNull(m, "typeId", n.typeId());
                m.put("keyColumnNames", n.keyColumnNames());
            }
            case TypeClassification.TableInterface ti -> {
                m.put("kind", "TableInterface");
                McpWire.putIfNotNull(m, "tableName", ti.tableName());
                McpWire.putIfNotNull(m, "discriminatorColumn", ti.discriminatorColumn());
                m.put("participantTypeNames", ti.participantTypeNames());
            }
            case TypeClassification.Interface i -> {
                m.put("kind", "Interface");
                m.put("participantTypeNames", i.participantTypeNames());
            }
            case TypeClassification.Union u -> {
                m.put("kind", "Union");
                m.put("participantTypeNames", u.participantTypeNames());
            }
            case TypeClassification.JavaRecord r -> {
                m.put("kind", "JavaRecord");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
            }
            case TypeClassification.JavaRecordInput r -> {
                m.put("kind", "JavaRecordInput");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
            }
            case TypeClassification.JooqRecord r -> {
                m.put("kind", "JooqRecord");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
            }
            case TypeClassification.JooqRecordInput r -> {
                m.put("kind", "JooqRecordInput");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
            }
            case TypeClassification.JooqTableRecord r -> {
                m.put("kind", "JooqTableRecord");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
                McpWire.putIfNotNull(m, "tableName", r.tableName());
            }
            case TypeClassification.JooqTableRecordInput r -> {
                m.put("kind", "JooqTableRecordInput");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
                McpWire.putIfNotNull(m, "tableName", r.tableName());
            }
            case TypeClassification.PojoResult r -> {
                m.put("kind", "PojoResult");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
            }
            case TypeClassification.PojoInput r -> {
                m.put("kind", "PojoInput");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
            }
            case TypeClassification.TableInput t -> {
                m.put("kind", "TableInput");
                McpWire.putIfNotNull(m, "tableName", t.tableName());
            }
            case TypeClassification.Root r -> {
                m.put("kind", "Root");
                m.put("operation", r.operation());
            }
            case TypeClassification.Connection conn -> {
                m.put("kind", "Connection");
                m.put("elementTypeName", conn.elementTypeName());
                m.put("edgeTypeName", conn.edgeTypeName());
            }
            case TypeClassification.Edge e -> {
                m.put("kind", "Edge");
                m.put("elementTypeName", e.elementTypeName());
            }
            case TypeClassification.PageInfo ignored -> m.put("kind", "PageInfo");
            case TypeClassification.Error e -> {
                m.put("kind", "Error");
                m.put("handlerKinds", e.handlerKinds());
            }
            case TypeClassification.Enum ignored -> m.put("kind", "Enum");
            case TypeClassification.Scalar s -> {
                m.put("kind", "Scalar");
                McpWire.putIfNotNull(m, "javaType", s.javaType());
            }
            case TypeClassification.PlainObject ignored -> m.put("kind", "PlainObject");
            case TypeClassification.Unclassified u -> {
                m.put("kind", "Unclassified");
                McpWire.putIfNotNull(m, "reason", u.reason());
            }
        }
        return m;
    }

    // ---- backing shape (exhaustive over TypeBackingShape permits, leaves matched directly) ----

    private static Map<String, Object> mapBackingShape(TypeBackingShape s) {
        var m = new LinkedHashMap<String, Object>();
        switch (s) {
            case TypeBackingShape.RecordBacking r -> {
                m.put("kind", "RecordBacking");
                McpWire.putIfNotNull(m, "fqClassName", r.fqClassName());
                m.put("members", members(r.components()));
            }
            case TypeBackingShape.PojoBacking p -> {
                m.put("kind", "PojoBacking");
                McpWire.putIfNotNull(m, "fqClassName", p.fqClassName());
                m.put("members", members(p.accessors()));
            }
            case TypeBackingShape.JooqRecordBacking.WithTable w -> {
                m.put("kind", "JooqRecordBacking.WithTable");
                McpWire.putIfNotNull(m, "fqClassName", w.fqClassName());
                McpWire.putIfNotNull(m, "tableName", w.tableName());
            }
            case TypeBackingShape.JooqRecordBacking.Standalone st -> {
                m.put("kind", "JooqRecordBacking.Standalone");
                McpWire.putIfNotNull(m, "fqClassName", st.fqClassName());
            }
            case TypeBackingShape.TableBacking t -> {
                m.put("kind", "TableBacking");
                McpWire.putIfNotNull(m, "tableName", t.tableName());
            }
            case TypeBackingShape.NoBacking.Root ignored -> m.put("kind", "NoBacking.Root");
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> m.put("kind", "NoBacking.UnbackedResult");
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored ->
                m.put("kind", "NoBacking.UnclassifiedInterface");
        }
        return m;
    }

    private static List<Map<String, Object>> members(List<TypeBackingShape.MemberSlot> slots) {
        var out = new ArrayList<Map<String, Object>>(slots.size());
        for (var slot : slots) {
            var sm = new LinkedHashMap<String, Object>();
            sm.put("name", slot.name());
            sm.put("displayType", slot.displayType());
            sm.put("accessorMethodName", slot.accessorMethodName());
            out.add(sm);
        }
        return out;
    }

    // ---- field classification (exhaustive over FieldClassification permits) ----

    private static Map<String, Object> mapFieldClassification(FieldClassification c) {
        var m = new LinkedHashMap<String, Object>();
        switch (c) {
            case FieldClassification.Column f -> {
                m.put("kind", "Column");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "columnName", f.columnName());
            }
            case FieldClassification.ColumnReference f -> {
                m.put("kind", "ColumnReference");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "columnName", f.columnName());
                m.put("joinPath", joinPath(f.joinPath()));
            }
            case FieldClassification.CompositeColumn f -> {
                m.put("kind", "CompositeColumn");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                m.put("columnNames", f.columnNames());
            }
            case FieldClassification.CompositeColumnReference f -> {
                m.put("kind", "CompositeColumnReference");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                m.put("columnNames", f.columnNames());
                m.put("joinPath", joinPath(f.joinPath()));
            }
            case FieldClassification.ParticipantCrossTable f -> {
                m.put("kind", "ParticipantCrossTable");
                McpWire.putIfNotNull(m, "targetTableName", f.targetTableName());
                McpWire.putIfNotNull(m, "columnName", f.columnName());
                McpWire.putIfNotNull(m, "fkName", f.fkName());
                McpWire.putIfNotNull(m, "alias", f.alias());
            }
            case FieldClassification.TableTarget f -> {
                m.put("kind", "TableTarget");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                m.put("joinPath", joinPath(f.joinPath()));
                m.put("splitBatched", f.splitBatched());
                m.put("hasLookupKey", f.hasLookupKey());
            }
            case FieldClassification.RecordTableTarget f -> {
                m.put("kind", "RecordTableTarget");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                m.put("joinPath", joinPath(f.joinPath()));
                m.put("hasLookupKey", f.hasLookupKey());
            }
            case FieldClassification.TableMethod f -> {
                m.put("kind", "TableMethod");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
                m.put("recordParent", f.recordParent());
            }
            case FieldClassification.TableInterface f -> {
                m.put("kind", "TableInterface");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "discriminatorColumn", f.discriminatorColumn());
                m.put("participantTypeNames", f.participantTypeNames());
            }
            case FieldClassification.Polymorphic f -> {
                m.put("kind", "Polymorphic");
                m.put("participantTypeNames", f.participantTypeNames());
            }
            case FieldClassification.Nesting ignored -> m.put("kind", "Nesting");
            case FieldClassification.ServiceBacked f -> {
                m.put("kind", "ServiceBacked");
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
                m.put("tableBound", f.tableBound());
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "errorChannelMappingName", f.errorChannelMappingName());
            }
            case FieldClassification.RecordOrProperty f -> {
                m.put("kind", "RecordOrProperty");
                McpWire.putIfNotNull(m, "columnName", f.columnName());
                McpWire.putIfNotNull(m, "accessorName", f.accessorName());
            }
            case FieldClassification.Computed f -> {
                m.put("kind", "Computed");
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
            }
            case FieldClassification.InputUnbound f -> {
                m.put("kind", "InputUnbound");
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
                m.put("override", f.override());
            }
            case FieldClassification.Errors f -> {
                m.put("kind", "Errors");
                m.put("errorTypeNames", f.errorTypeNames());
            }
            case FieldClassification.SingleRecordId f -> {
                m.put("kind", "SingleRecordId");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
            }
            case FieldClassification.SingleRecordIdFromReturning ignored ->
                m.put("kind", "SingleRecordIdFromReturning");
            case FieldClassification.QueryTable f -> {
                m.put("kind", "QueryTable");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                m.put("isLookup", f.isLookup());
            }
            case FieldClassification.QueryTableMethod f -> {
                m.put("kind", "QueryTableMethod");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
            }
            case FieldClassification.QueryNode f -> {
                m.put("kind", "QueryNode");
                m.put("isList", f.isList());
            }
            case FieldClassification.QueryTableInterface f -> {
                m.put("kind", "QueryTableInterface");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "discriminatorColumn", f.discriminatorColumn());
                m.put("participantTypeNames", f.participantTypeNames());
            }
            case FieldClassification.QueryPolymorphic f -> {
                m.put("kind", "QueryPolymorphic");
                m.put("participantTypeNames", f.participantTypeNames());
            }
            case FieldClassification.QueryService f -> {
                m.put("kind", "QueryService");
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
                m.put("tableBound", f.tableBound());
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "errorChannelMappingName", f.errorChannelMappingName());
            }
            case FieldClassification.DmlMutation f -> {
                m.put("kind", "DmlMutation");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "inputTypeName", f.inputTypeName());
                m.put("dmlKind", f.kind().name());
                McpWire.putIfNotNull(m, "errorChannelMappingName", f.errorChannelMappingName());
            }
            case FieldClassification.MutationService f -> {
                m.put("kind", "MutationService");
                McpWire.putIfNotNull(m, "methodClassName", f.methodClassName());
                McpWire.putIfNotNull(m, "methodName", f.methodName());
                m.put("tableBound", f.tableBound());
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "errorChannelMappingName", f.errorChannelMappingName());
            }
            case FieldClassification.DmlRecord f -> {
                m.put("kind", "DmlRecord");
                McpWire.putIfNotNull(m, "tableName", f.tableName());
                McpWire.putIfNotNull(m, "inputTypeName", f.inputTypeName());
                m.put("dmlKind", f.kind().name());
                m.put("bulk", f.bulk());
                McpWire.putIfNotNull(m, "errorChannelMappingName", f.errorChannelMappingName());
            }
            case FieldClassification.Unclassified f -> {
                m.put("kind", "Unclassified");
                McpWire.putIfNotNull(m, "reason", f.reason());
            }
        }
        return m;
    }

    private static List<Map<String, Object>> joinPath(List<FieldClassification.FkStep> steps) {
        var out = new ArrayList<Map<String, Object>>(steps.size());
        for (var step : steps) {
            var sm = new LinkedHashMap<String, Object>();
            sm.put("targetTableName", step.targetTableName());
            sm.put("fkName", step.fkName());
            out.add(sm);
        }
        return out;
    }

    private static McpSchema.CallToolResult result(String summary, Map<String, Object> fields) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }
}
