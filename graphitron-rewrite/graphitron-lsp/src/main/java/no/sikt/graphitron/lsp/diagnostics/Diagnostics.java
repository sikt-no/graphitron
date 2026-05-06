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
import io.github.treesitter.jtreesitter.Node;

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
                case "service" -> validateExternalCodeReference(directive, file, catalog, out, "service", true);
                case "condition" -> validateExternalCodeReference(directive, file, catalog, out, "condition", true);
                case "record" -> validateExternalCodeReference(directive, file, catalog, out, "record", false);
                default -> { /* no validation yet */ }
            }
        }
        return out;
    }

    private static void validateTable(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        Node argValue = stringArgValueNode(directive, "name", file.source());
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
        Node argValue = stringArgValueNode(directive, "name", file.source());
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
        var matched = table.get().columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst();
        if (matched.isEmpty()) {
            out.add(diagnostic(file, argValue, DiagnosticSeverity.Error,
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
                Node nameNode = childOfKind(field, "name");
                Node valueNode = childOfKind(field, "value");
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
        Node valueNode, String fkName,
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
        Node valueNode, String tableName,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        if (catalog.getTable(tableName).isEmpty()) {
            out.add(diagnostic(file, valueNode,
                "Unknown table '" + tableName + "'. The jOOQ catalog does not contain a table with this name."));
        }
    }

    /**
     * Validates the nested {@code ExternalCodeReference} object on
     * {@code @service} / {@code @condition} / {@code @record}. The schema
     * directive surface is uniform: each carries one outer arg whose key
     * matches the directive name and whose value is an object with
     * {@code className} / {@code method} / {@code argMapping} fields.
     *
     * @param outerArg     the outer-arg key to descend into ({@code service},
     *                     {@code condition}, or {@code record}).
     * @param validateMethod whether to also validate {@code method} and
     *                       emit the {@code -parameters}-missing warning.
     *                       {@code @record} has no {@code method} field.
     */
    private static void validateExternalCodeReference(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog,
        List<Diagnostic> out, String outerArg, boolean validateMethod
    ) {
        // Empty `externalReferences` means the classpath scan saw nothing
        // (typically: consumer hasn't run `mvn compile` yet). Reporting
        // every reference as unknown in that state would be noise; defer
        // until the scan has at least one entry to match against.
        if (catalog.externalReferences().isEmpty()) return;
        Node outerValue = stringArgValueNode(directive, outerArg, file.source());
        if (outerValue == null) return;

        Node classNameValue = nestedFieldValue(outerValue, "className", file.source());
        if (classNameValue == null) return;
        String fqn = Nodes.unquote(Nodes.text(classNameValue, file.source()));
        if (fqn.isEmpty()) return;

        var refOpt = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
        if (refOpt.isEmpty()) {
            out.add(diagnostic(file, classNameValue,
                "Unknown class '" + fqn + "'. Not found in compiled target/classes."));
            return;
        }
        if (!validateMethod) return;

        Node methodValue = nestedFieldValue(outerValue, "method", file.source());
        if (methodValue == null) return;
        String methodName = Nodes.unquote(Nodes.text(methodValue, file.source()));
        if (methodName.isEmpty()) return;
        var methodOpt = refOpt.get().methods().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst();
        if (methodOpt.isEmpty()) {
            out.add(diagnostic(file, methodValue,
                "Unknown method '" + methodName + "' on class '" + fqn + "'."));
            return;
        }
        // The method resolved. If it takes parameters but the consumer
        // compiled the class without -parameters, our parameter names
        // are unknown (null on every Parameter record). Surface the
        // same warning the rewrite generator emits at build time
        // (ServiceCatalog.emitParametersWarning), but as a per-
        // reference Warning so the schema author sees it inline next
        // to the affected directive.
        var method = methodOpt.get();
        if (!method.parameters().isEmpty()
                && method.parameters().stream().allMatch(p -> p.name() == null)) {
            out.add(diagnostic(file, methodValue, DiagnosticSeverity.Warning,
                "Class '" + fqn + "' was compiled without `-parameters`; "
                + "parameter help on '" + methodName + "' is unavailable. "
                + "Set `<parameters>true</parameters>` on maven-compiler-plugin "
                + "to surface parameter names."));
        }
    }

    /**
     * Returns the {@code value} node of the first nested {@code object_field}
     * named {@code fieldName} found under {@code outerValue}, or {@code null}
     * if no such field is present.
     */
    private static Node nestedFieldValue(Node outerValue, String fieldName, byte[] source) {
        if (outerValue == null) return null;
        if ("object_field".equals(outerValue.getType())) {
            Node nameNode = childOfKind(outerValue, "name");
            Node valueNode = childOfKind(outerValue, "value");
            if (nameNode != null && valueNode != null
                && fieldName.equals(Nodes.text(nameNode, source))) {
                return valueNode;
            }
        }
        for (int i = 0; i < outerValue.getChildCount(); i++) {
            Node child = outerValue.getChild(i).orElse(null);
            if (child == null) continue;
            Node found = nestedFieldValue(child, fieldName, source);
            if (found != null) return found;
        }
        return null;
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

    private static Node stringArgValueNode(Directives.Directive directive, String argName, byte[] source) {
        for (var arg : directive.arguments()) {
            if (argName.equals(Nodes.text(arg.key(), source))) {
                return arg.value();
            }
        }
        return null;
    }

    private static Diagnostic diagnostic(WorkspaceFile file, Node node, DiagnosticSeverity severity, String message) {
        var start = Positions.toLspPosition(file.source(), node.getStartByte());
        var end = Positions.toLspPosition(file.source(), node.getEndByte());
        var d = new Diagnostic(new Range(start, end), message);
        d.setSeverity(severity);
        d.setSource(SOURCE);
        return d;
    }

    private static Diagnostic diagnostic(WorkspaceFile file, Node node, String message) {
        return diagnostic(file, node, DiagnosticSeverity.Error, message);
    }

    @FunctionalInterface
    private interface NodeConsumer {
        void accept(Node node);
    }

    private static void forEachObjectField(Node root, NodeConsumer consumer) {
        if (root == null) return;
        if ("object_field".equals(root.getType())) {
            consumer.accept(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            forEachObjectField(root.getChild(i).orElse(null), consumer);
        }
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (kind.equals(child.getType())) return child;
        }
        return null;
    }
}
