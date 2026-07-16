package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code services} / {@code conditions} / {@code records} read tools over the
 * consumer Java the schema wires to. Reads {@code Workspace.catalog().externalReferences()} (the
 * flat {@link CompletionData.ExternalReference} scan) joined with {@code Workspace.sourceIndex()}
 * for source locations.
 *
 * <p>The service / condition / record split is a <em>derivation these tools perform</em> over two
 * orthogonal axes already on the model, not a per-class bucket: a class can legitimately appear
 * under more than one tool (a service interface that also exposes a {@code Condition}-returning
 * method is both a service host and a condition host). {@code records} lists references with a
 * non-empty {@code recordComponents}; {@code conditions} lists the methods whose typed
 * {@code returnsCondition} fact is set; {@code services} lists references with their callable
 * methods (condition methods included, since the same class is both).
 */
final class CodeTools {

    private CodeTools() {}

    /** Default page size for the code tools: well under MCP response limits, paged by cursor. */
    static final int DEFAULT_LIMIT = 100;

    // ---- services ----

    static McpSchema.CallToolResult servicesResult(
        List<CompletionData.ExternalReference> refs, SourceWalker.Index index, Map<String, Object> args
    ) {
        Optional<String> name = McpWire.stringArg(args, "name");
        // A reference with no public methods (e.g. a pure record/POJO backing) is surfaced by the
        // records tool, not here; services lists references that carry callable methods.
        var filtered = refs.stream()
            .filter(r -> !r.methods().isEmpty())
            .filter(r -> matches(r.className(), name))
            .toList();
        var paged = McpWire.page(filtered, args, DEFAULT_LIMIT);

        var list = new ArrayList<Map<String, Object>>(paged.items().size());
        for (var ref : paged.items()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("classRef", ref.className());
            entry.put("className", ref.className());
            if (!ref.description().isBlank()) entry.put("description", ref.description());
            var methods = new ArrayList<Map<String, Object>>(ref.methods().size());
            for (var m : ref.methods()) {
                methods.add(methodEntry(ref.className(), m));
            }
            entry.put("methods", methods);
            McpWire.writeLocation(entry, McpWire.joinClass(index, ref.className()));
            list.add(entry);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.put("services", list);
        paged.nextCursor().ifPresent(c -> fields.put("nextCursor", c));
        String summary = "services: " + filtered.size() + " class(es)"
            + name.map(n -> " matching '" + n + "'").orElse("")
            + "; showing " + paged.items().size()
            + (paged.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return result(summary, fields);
    }

    /** Full method projection for the {@code services} tool: ref ID, signature, parameters, doc. */
    private static Map<String, Object> methodEntry(String className, CompletionData.Method m) {
        var mm = new LinkedHashMap<String, Object>();
        mm.put("methodRef", McpWire.methodRef(className, m.name(), m.parameters().size()));
        mm.put("name", m.name());
        mm.put("returnType", m.returnType());
        mm.put("parameters", parameters(m));
        if (!m.description().isBlank()) mm.put("description", m.description());
        return mm;
    }

    // ---- conditions ----

    static McpSchema.CallToolResult conditionsResult(
        List<CompletionData.ExternalReference> refs, SourceWalker.Index index, Map<String, Object> args
    ) {
        Optional<String> name = McpWire.stringArg(args, "name");
        var all = new ArrayList<ConditionMethod>();
        for (var ref : refs) {
            if (!matches(ref.className(), name)) continue;
            for (var m : ref.methods()) {
                if (m.returnsCondition()) all.add(new ConditionMethod(ref.className(), m));
            }
        }
        var paged = McpWire.page(all, args, DEFAULT_LIMIT);

        var list = new ArrayList<Map<String, Object>>(paged.items().size());
        for (var c : paged.items()) {
            var m = c.method();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("methodRef", McpWire.methodRef(c.className(), m.name(), m.parameters().size()));
            entry.put("className", c.className());
            entry.put("name", m.name());
            entry.put("parameters", parameters(m));
            if (!m.description().isBlank()) entry.put("description", m.description());
            McpWire.writeLocation(entry, McpWire.joinMethod(index, c.className(), m.name(), m.parameters().size()));
            list.add(entry);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.put("conditions", list);
        paged.nextCursor().ifPresent(c -> fields.put("nextCursor", c));
        String summary = "conditions: " + all.size() + " condition method(s)"
            + name.map(n -> " in classes matching '" + n + "'").orElse("")
            + "; showing " + paged.items().size()
            + (paged.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return result(summary, fields);
    }

    /** A jOOQ-condition method paired with the FQN of the class that declares it. */
    private record ConditionMethod(String className, CompletionData.Method method) {}

    // ---- records ----

    static McpSchema.CallToolResult recordsResult(
        List<CompletionData.ExternalReference> refs, SourceWalker.Index index, Map<String, Object> args
    ) {
        Optional<String> name = McpWire.stringArg(args, "name");
        var filtered = refs.stream()
            .filter(r -> !r.recordComponents().isEmpty())
            .filter(r -> matches(r.className(), name))
            .toList();
        var paged = McpWire.page(filtered, args, DEFAULT_LIMIT);

        var list = new ArrayList<Map<String, Object>>(paged.items().size());
        for (var ref : paged.items()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("classRef", ref.className());
            entry.put("className", ref.className());
            if (!ref.description().isBlank()) entry.put("description", ref.description());
            var components = new ArrayList<Map<String, Object>>(ref.recordComponents().size());
            for (var rc : ref.recordComponents()) {
                var cm = new LinkedHashMap<String, Object>();
                cm.put("name", rc.name());
                cm.put("displayType", rc.displayType());
                components.add(cm);
            }
            entry.put("components", components);
            McpWire.writeLocation(entry, McpWire.joinClass(index, ref.className()));
            list.add(entry);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.put("records", list);
        paged.nextCursor().ifPresent(c -> fields.put("nextCursor", c));
        String summary = "records: " + filtered.size() + " record class(es)"
            + name.map(n -> " matching '" + n + "'").orElse("")
            + "; showing " + paged.items().size()
            + (paged.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return result(summary, fields);
    }

    // ---- shared ----

    private static List<Map<String, Object>> parameters(CompletionData.Method m) {
        var params = new ArrayList<Map<String, Object>>(m.parameters().size());
        for (var p : m.parameters()) {
            var pm = new LinkedHashMap<String, Object>();
            // name is null when the consumer compiled without -parameters; omit rather than synthesise.
            McpWire.putIfNotNull(pm, "name", p.name());
            pm.put("type", p.type());
            McpWire.putIfNotNull(pm, "source", p.source());
            params.add(pm);
        }
        return params;
    }

    private static boolean matches(String className, Optional<String> name) {
        return name.map(n -> className.toLowerCase().contains(n.toLowerCase())).orElse(true);
    }

    private static McpSchema.CallToolResult result(String summary, Map<String, Object> fields) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }
}
