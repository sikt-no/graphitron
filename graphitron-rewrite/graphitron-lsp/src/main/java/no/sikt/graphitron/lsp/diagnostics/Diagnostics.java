package no.sikt.graphitron.lsp.diagnostics;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates known directive arguments against the catalog and emits LSP
 * diagnostics for arguments that do not resolve. Slice-3 scope:
 * {@code @table(name:)}, {@code @field(name:)}, and the nested
 * {@code @reference(path: [{key:, table:}])} positions. All errors;
 * other severities and quick-fixes can layer on later.
 *
 * <p>Mirrors the Rust LSP's {@code diagnostics::validate} entry point at
 * the dispatch level, but per-directive logic is rewritten against the
 * Java catalog records.
 */
public final class Diagnostics {

    private Diagnostics() {}

    private static final String SOURCE = "graphitron-lsp";

    public static List<Diagnostic> compute(WorkspaceFile file, CompletionData catalog) {
        var out = new ArrayList<Diagnostic>();
        var directives = Directives.findAll(file.tree().getRootNode());
        for (var directive : directives) {
            String name = Nodes.text(directive.nameNode(), file.source());
            switch (name) {
                case "table" -> validateTable(directive, file, catalog, out);
                case "field" -> validateField(directive, file, catalog, out);
                case "reference" -> validateReference(directive, file, catalog, out);
                default -> { /* no validation yet */ }
            }
        }
        return out;
    }

    private static void validateTable(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        TSNode argValue = stringArgValueNode(directive, "name", file.source());
        if (argValue == null) return;
        String tableName = Nodes.unquote(Nodes.text(argValue, file.source()));
        if (tableName.isEmpty()) return;
        if (catalog.getTable(tableName).isEmpty()) {
            out.add(diagnostic(file, argValue,
                "Unknown table '" + tableName + "'. The jOOQ catalog does not contain a table with this name."));
        }
    }

    private static void validateField(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        TSNode argValue = stringArgValueNode(directive, "name", file.source());
        if (argValue == null) return;
        String columnName = Nodes.unquote(Nodes.text(argValue, file.source()));
        if (columnName.isEmpty()) return;

        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) return;
        var tableName = TypeContext.tableNameOf(typeDef.get(), file.source());
        if (tableName.isEmpty()) return;
        var table = catalog.getTable(tableName.get());
        if (table.isEmpty()) {
            // The enclosing @table is itself a typo; the @table validation
            // already flagged it. Skip the duplicate here.
            return;
        }
        boolean found = table.get().columns().stream()
            .anyMatch(c -> c.name().equalsIgnoreCase(columnName));
        if (!found) {
            out.add(diagnostic(file, argValue,
                "Unknown column '" + columnName + "' on table '" + tableName.get() + "'."));
        }
    }

    private static void validateReference(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        // path: [{key: ..., table: ...}, ...]; iterate every nested
        // object_field and validate per nested-key.
        for (var arg : directive.arguments()) {
            if (!"path".equals(Nodes.text(arg.key(), file.source()))) continue;
            forEachObjectField(arg.value(), (field) -> {
                TSNode nameNode = childOfKind(field, "name");
                TSNode valueNode = childOfKind(field, "value");
                if (nameNode == null || valueNode == null) return;
                String fieldName = Nodes.text(nameNode, file.source());
                String value = Nodes.unquote(Nodes.text(valueNode, file.source()));
                if (value.isEmpty()) return;
                switch (fieldName) {
                    case "key" -> validateReferenceKey(valueNode, value, file, catalog, out);
                    case "table" -> validateReferenceTable(valueNode, value, file, catalog, out);
                    default -> { /* condition: deferred to Phase 5 */ }
                }
            });
        }
    }

    private static void validateReferenceKey(
        TSNode valueNode, String fkName,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        // Look across every table's references for a matching FK name.
        // Path-step refinement (which step's table we are on) is deferred
        // along with the path-aware completion.
        if (!collectAllFkNames(catalog).contains(fkName)) {
            out.add(diagnostic(file, valueNode,
                "Unknown foreign key '" + fkName + "'. Not present in the jOOQ catalog."));
        }
    }

    private static void validateReferenceTable(
        TSNode valueNode, String tableName,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        if (catalog.getTable(tableName).isEmpty()) {
            out.add(diagnostic(file, valueNode,
                "Unknown table '" + tableName + "'. The jOOQ catalog does not contain a table with this name."));
        }
    }

    private static Set<String> collectAllFkNames(CompletionData catalog) {
        var names = new LinkedHashSet<String>();
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                names.add(ref.keyName());
            }
        }
        return names;
    }

    private static TSNode stringArgValueNode(Directives.Directive directive, String argName, byte[] source) {
        for (var arg : directive.arguments()) {
            if (argName.equals(Nodes.text(arg.key(), source))) {
                return arg.value();
            }
        }
        return null;
    }

    private static Diagnostic diagnostic(WorkspaceFile file, TSNode node, String message) {
        var start = Positions.toLspPosition(file.source(), node.getStartByte());
        var end = Positions.toLspPosition(file.source(), node.getEndByte());
        var d = new Diagnostic(new Range(start, end), message);
        d.setSeverity(DiagnosticSeverity.Error);
        d.setSource(SOURCE);
        return d;
    }

    @FunctionalInterface
    private interface NodeConsumer {
        void accept(TSNode node);
    }

    private static void forEachObjectField(TSNode root, NodeConsumer consumer) {
        if (root == null || root.isNull()) return;
        if ("object_field".equals(root.getType())) {
            consumer.accept(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            forEachObjectField(root.getChild(i), consumer);
        }
    }

    private static TSNode childOfKind(TSNode parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            TSNode child = parent.getChild(i);
            if (kind.equals(child.getType())) return child;
        }
        return null;
    }
}
