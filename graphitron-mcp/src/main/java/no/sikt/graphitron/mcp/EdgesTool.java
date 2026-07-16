package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code edges} tool: takes one node selector + a direction and returns that
 * node's typed neighbours, the traversal layer over the frozen structured tools. One
 * dedicated tool, not a {@code neighbours} field retrofitted onto every result, so the eight
 * existing contracts stay frozen and both directions (forward, and the reverse-index impact
 * analysis) live behind one contract.
 *
 * <p>Reads the live projections off the {@code Workspace} handle on every call: the snapshot
 * (field / type classifications), the catalog facts (table / column resolution + FK structure), and
 * the external-reference scan (method-arity reconciliation). The reverse direction is served by a
 * lazily-built, snapshot-memoised {@link ReverseEdgeIndex} held by the server.
 *
 * <p>Input is named node-selector arguments (exactly one of {@code field} / {@code type} /
 * {@code table} / {@code column} (+{@code table}) / {@code method} / {@code class}) rather than a
 * single flat {@code node} string, because {@code Type.field} (a schema coordinate) and
 * {@code schema.table} (a catalog ID) are both a dotted pair and would be ambiguous. Result
 * endpoints are the canonical {@link NodeRef#wireId()} strings with their {@link NodeRef#kind()}, so
 * an agent reads a neighbour's ID off one result and re-selects it by kind on the next call.
 */
final class EdgesTool {

    private EdgesTool() {}

    static McpSchema.CallToolResult edgesResult(
        LspSchemaSnapshot snapshot, CatalogFacts facts,
        List<CompletionData.ExternalReference> externalReferences,
        ReverseEdgeIndex.Cache reverseIndexCache, Map<String, Object> args
    ) {
        String direction = direction(args);
        boolean wantOut = !direction.equals("in");
        boolean wantIn = !direction.equals("out");

        var fields = new LinkedHashMap<String, Object>();
        var ctx = new EdgeProducer.Context(facts, externalReferences);

        var selected = selectNode(args, facts);
        if (selected instanceof Selection.Missing missing) {
            fields.put("edges", List.of());
            McpWire.writeSnapshotAxes(fields, snapshot);
            return result(missing.message(), fields);
        }
        if (selected instanceof Selection.Degraded degraded) {
            fields.put("node", nodeWire(degraded.id(), degraded.kind()));
            fields.put("resolution", degraded.resolution());
            degraded.schemas().ifPresent(s -> fields.put("schemas", s));
            fields.put("edges", List.of());
            McpWire.writeSnapshotAxes(fields, snapshot);
            return result("edges: " + degraded.summary(), fields);
        }

        var node = ((Selection.Resolved) selected).node();
        fields.put("node", nodeWire(node.wireId(), node.kind()));

        var edgeEntries = new ArrayList<Map<String, Object>>();
        if (wantOut) {
            for (var edge : forwardEdges(node, snapshot, ctx)) {
                edgeEntries.add(edgeWire(edge, "out"));
            }
        }
        if (wantIn) {
            for (var edge : reverseEdges(node, snapshot, facts, externalReferences, reverseIndexCache)) {
                edgeEntries.add(edgeWire(edge, "in"));
            }
        }
        fields.put("edges", edgeEntries);
        McpWire.writeSnapshotAxes(fields, snapshot);

        String summary = "edges: " + node.kind() + " '" + node.wireId() + "' (" + direction + "); "
            + edgeEntries.size() + " edge(s).";
        return result(summary, fields);
    }

    // ---- forward edges ----

    private static List<Edge> forwardEdges(NodeRef node, LspSchemaSnapshot snapshot, EdgeProducer.Context ctx) {
        return switch (node) {
            case NodeRef.FieldNode f -> snapshot instanceof LspSchemaSnapshot.Built b
                ? Optional.ofNullable(b.fieldClassificationsByCoord().get(f.coordinate()))
                    .map(c -> EdgeProducer.fieldEdges(c, ctx)).orElseGet(List::of)
                : List.of();
            case NodeRef.TypeNode t -> snapshot instanceof LspSchemaSnapshot.Built b
                ? Optional.ofNullable(b.typeClassificationsByName().get(t.typeName()))
                    .map(c -> EdgeProducer.typeEdges(c, ctx)).orElseGet(List::of)
                : List.of();
            case NodeRef.TableNode tn -> tableByWireId(ctx.facts(), tn.wireId())
                .map(EdgeProducer::outgoingFkEdges).orElseGet(List::of);
            // A column / method / class has no stage-1 forward edge; its value is the reverse index.
            case NodeRef.ColumnNode ignored -> List.of();
            case NodeRef.MethodNode ignored -> List.of();
            case NodeRef.ClassNode ignored -> List.of();
        };
    }

    // ---- reverse edges (the impact-analysis deliverable) ----

    private static List<Edge> reverseEdges(
        NodeRef node, LspSchemaSnapshot snapshot, CatalogFacts facts,
        List<CompletionData.ExternalReference> externalReferences, ReverseEdgeIndex.Cache cache
    ) {
        var index = cache.get(snapshot, facts, externalReferences);
        return switch (node) {
            case NodeRef.ColumnNode c -> index.reverseEdges(c.wireId());
            case NodeRef.MethodNode m -> index.reverseEdges(m.wireId());
            case NodeRef.TableNode tn -> {
                var out = new ArrayList<>(index.reverseEdges(tn.wireId()));
                // The inbound FK direction is read straight off the catalog (table -> table).
                tableByWireId(facts, tn.wireId()).ifPresent(t -> out.addAll(EdgeProducer.incomingFkEdges(t)));
                yield out;
            }
            // Fields / types / classes are not reverse-index targets (PARTICIPATES is forward-only).
            case NodeRef.FieldNode ignored -> List.of();
            case NodeRef.TypeNode ignored -> List.of();
            case NodeRef.ClassNode ignored -> List.of();
        };
    }

    // ---- node selection / bare-name reconciliation on the queried node ----

    private sealed interface Selection {
        record Resolved(NodeRef node) implements Selection {}

        /** A bare table name that resolved to 0 / >1 catalog tables; never a hard failure. */
        record Degraded(String id, String kind, String resolution,
                        Optional<List<String>> schemas, String summary) implements Selection {}

        /** No selector, or a malformed combination; degrades to an empty edge list with a hint. */
        record Missing(String message) implements Selection {}
    }

    private static Selection selectNode(Map<String, Object> args, CatalogFacts facts) {
        Optional<String> field = McpWire.stringArg(args, "field");
        Optional<String> type = McpWire.stringArg(args, "type");
        Optional<String> table = McpWire.stringArg(args, "table");
        Optional<String> column = McpWire.stringArg(args, "column");
        Optional<String> method = McpWire.stringArg(args, "method");
        Optional<String> clazz = McpWire.stringArg(args, "class");

        long primaries = java.util.stream.Stream.of(field, type, table, method, clazz)
            .filter(Optional::isPresent).count();
        if (primaries == 0) {
            return new Selection.Missing("edges: provide exactly one node selector "
                + "(field / type / table / column+table / method / class).");
        }
        if (primaries > 1) {
            return new Selection.Missing("edges: pass exactly one node selector; "
                + "got more than one of field / type / table / method / class.");
        }
        if (field.isPresent()) return new Selection.Resolved(new NodeRef.FieldNode(field.get()));
        if (type.isPresent()) return new Selection.Resolved(new NodeRef.TypeNode(type.get()));
        if (method.isPresent()) return new Selection.Resolved(parseMethod(method.get()));
        if (clazz.isPresent()) return new Selection.Resolved(new NodeRef.ClassNode(clazz.get()));

        // table (optionally with column): the queried node carries a bare/qualified table name the
        // catalog resolves; the ambiguous / not-found arms reuse CatalogFacts.resolve's sub-taxonomy.
        String tableArg = table.get();
        return switch (facts.resolve(tableArg, Optional.empty())) {
            case CatalogFacts.TableResolution.Resolved r -> {
                var tableNode = new NodeRef.TableNode(r.table().schema(), r.table().name());
                yield column.<Selection>map(col ->
                        new Selection.Resolved(new NodeRef.ColumnNode(tableNode, col)))
                    .orElseGet(() -> new Selection.Resolved(tableNode));
            }
            case CatalogFacts.TableResolution.Ambiguous a -> new Selection.Degraded(
                tableArg, column.isPresent() ? "column" : "table", "ambiguous",
                Optional.of(a.schemas()),
                "table '" + tableArg + "' is ambiguous, carried by schemas " + a.schemas()
                    + "; re-call qualified (e.g. \"" + a.schemas().get(0) + "." + tableArg + "\").");
            case CatalogFacts.TableResolution.NotFound ignored -> new Selection.Degraded(
                tableArg, column.isPresent() ? "column" : "table", "notFound",
                Optional.empty(), "table '" + tableArg + "' was not found in the catalog.");
        };
    }

    /** Parses a {@code fqcn#method/arity} ref back into its structured {@link NodeRef.MethodNode}. */
    private static NodeRef parseMethod(String ref) {
        int hash = ref.indexOf('#');
        int slash = ref.lastIndexOf('/');
        if (hash < 0 || slash < hash) {
            // Not the expected grammar; carry it through as a class FQN so the call still resolves.
            return new NodeRef.ClassNode(ref);
        }
        String fqcn = ref.substring(0, hash);
        String method = ref.substring(hash + 1, slash);
        int arity;
        try {
            arity = Integer.parseInt(ref.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            arity = 0;
        }
        return new NodeRef.MethodNode(fqcn, method, arity);
    }

    private static Optional<CatalogFacts.Table> tableByWireId(CatalogFacts facts, String qualified) {
        return facts.resolve(qualified, Optional.empty()) instanceof CatalogFacts.TableResolution.Resolved r
            ? Optional.of(r.table()) : Optional.empty();
    }

    // ---- wire shaping ----

    private static Map<String, Object> nodeWire(String id, String kind) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("kind", kind);
        return m;
    }

    private static Map<String, Object> edgeWire(Edge edge, String direction) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", edge.kind().name());
        m.put("direction", direction);
        m.put("target", nodeWire(edge.target().wireId(), edge.target().kind()));
        if (!edge.joinPath().isEmpty()) {
            var hops = new ArrayList<Map<String, Object>>(edge.joinPath().size());
            for (FieldClassification.FkStep step : edge.joinPath()) {
                var hop = new LinkedHashMap<String, Object>();
                hop.put("targetTableName", step.targetTableName());
                hop.put("fkName", step.fkName());
                hops.add(hop);
            }
            m.put("joinPath", hops);
        }
        return m;
    }

    private static String direction(Map<String, Object> args) {
        String d = McpWire.stringArg(args, "direction").map(s -> s.toLowerCase()).orElse("both");
        return switch (d) {
            case "out", "in", "both" -> d;
            default -> "both";
        };
    }

    private static McpSchema.CallToolResult result(String summary, Map<String, Object> fields) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }
}
