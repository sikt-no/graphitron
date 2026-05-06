package no.sikt.graphitron.lsp.hover;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

/**
 * Hover content for known directive arguments. Cursor on a known
 * {@code @table(name:)} / {@code @field(name:)} / nested
 * {@code @reference(path: [{key:, table:}])} value reveals catalog
 * metadata: descriptions, column types, FK direction, and so on.
 *
 * <p>Mirrors the Rust LSP's {@code hover::generate} dispatch shape;
 * per-directive logic is rewritten against the Java catalog records.
 */
public final class Hovers {

    private Hovers() {}

    public static Optional<Hover> compute(WorkspaceFile file, CompletionData catalog, Point pos) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) return Optional.empty();
        var directive = directiveOpt.get();
        String name = Nodes.text(directive.nameNode(), file.source());
        return switch (name) {
            case "table" -> tableHover(directive, file, catalog, pos);
            case "field" -> fieldHover(directive, file, catalog, pos);
            case "reference" -> referenceHover(directive, file, catalog, pos);
            case "service" -> externalCodeReferenceHover(directive, file, catalog, pos, "service", true);
            case "condition" -> externalCodeReferenceHover(directive, file, catalog, pos, "condition", true);
            case "record" -> externalCodeReferenceHover(directive, file, catalog, pos, "record", false);
            default -> Optional.empty();
        };
    }

    /**
     * Hover content for the nested {@code ExternalCodeReference} object on
     * {@code @service} / {@code @condition} / {@code @record}. The cursor
     * position decides whether we surface class hover (cursor on
     * {@code <outer>.className}) or method hover (cursor on
     * {@code <outer>.method}); {@code @record} has no method field, so
     * {@code supportsMethod = false} skips the second branch.
     */
    private static Optional<Hover> externalCodeReferenceHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, Point pos,
        String outerArg, boolean supportsMethod
    ) {
        for (var arg : directive.arguments()) {
            if (!outerArg.equals(Nodes.text(arg.key(), file.source()))) continue;
            Node field = innermostObjectFieldContaining(arg.value(), pos);
            if (field == null) continue;
            Node nameNode = childOfKind(field, "name");
            Node valueNode = childOfKind(field, "value");
            if (nameNode == null || valueNode == null) continue;
            if (!Nodes.contains(valueNode, pos)) continue;
            String nestedField = Nodes.text(nameNode, file.source());
            if ("className".equals(nestedField)) {
                String fqn = Nodes.unquote(Nodes.text(valueNode, file.source()));
                return findExternal(catalog, fqn)
                    .map(ref -> hover(file, valueNode, formatClass(ref)));
            }
            if (supportsMethod && "method".equals(nestedField)) {
                return methodHoverContent(arg.value(), file, catalog, valueNode);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Looks up the resolved method for the cursor position by reading
     * {@code className} off the same nested object.
     */
    private static Optional<Hover> methodHoverContent(
        Node outerValue, WorkspaceFile file, CompletionData catalog, Node methodValue
    ) {
        String methodName = Nodes.unquote(Nodes.text(methodValue, file.source()));
        if (methodName.isEmpty()) return Optional.empty();
        String classFqn = readNestedString(outerValue, "className", file.source());
        if (classFqn == null || classFqn.isEmpty()) return Optional.empty();
        var refOpt = findExternal(catalog, classFqn);
        if (refOpt.isEmpty()) return Optional.empty();
        return refOpt.get().methods().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .map(method -> hover(file, methodValue, formatMethod(refOpt.get(), method)));
    }

    private static String readNestedString(Node outerValue, String fieldName, byte[] source) {
        if (outerValue == null) return null;
        if ("object_field".equals(outerValue.getType())) {
            Node nameNode = childOfKind(outerValue, "name");
            Node valueNode = childOfKind(outerValue, "value");
            if (nameNode != null && valueNode != null
                && fieldName.equals(Nodes.text(nameNode, source))) {
                return Nodes.unquote(Nodes.text(valueNode, source));
            }
        }
        for (int i = 0; i < outerValue.getChildCount(); i++) {
            Node child = outerValue.getChild(i).orElse(null);
            if (child == null) continue;
            String found = readNestedString(child, fieldName, source);
            if (found != null) return found;
        }
        return null;
    }

    private static String formatMethod(CompletionData.ExternalReference ref, CompletionData.Method method) {
        var sb = new StringBuilder();
        sb.append("**Method** `").append(method.name()).append("`")
          .append(" on `").append(ref.className()).append("`")
          .append("\n\n```\n")
          .append(method.returnType()).append(' ').append(method.name()).append('(');
        boolean missingNames = false;
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            var p = method.parameters().get(i);
            sb.append(p.type()).append(' ');
            if (p.name() != null) {
                sb.append(p.name());
            } else {
                sb.append("arg").append(i);
                missingNames = true;
            }
        }
        sb.append(")\n```");
        if (missingNames) {
            // The detection signal mirrors the build-time
            // ServiceCatalog.emitParametersWarning path: a null name on
            // any parameter means the class was compiled without
            // -parameters. The LSP also emits this as a workspace
            // diagnostic; surfacing it on hover is the immediate
            // editor-side hint.
            sb.append("\n\n_Parameter names are unavailable; recompile with the `-parameters` flag to surface them._");
        }
        return sb.toString();
    }

    private static Optional<CompletionData.ExternalReference> findExternal(CompletionData catalog, String fqn) {
        return catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
    }

    private static String formatClass(CompletionData.ExternalReference ref) {
        // Hover payload: just the FQN. Method list and Javadoc surfacing
        // are follow-on work.
        return "**Class** `" + ref.className() + "`";
    }

    private static Optional<Hover> tableHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, Point pos
    ) {
        var argValue = stringArgValueAt(directive, "name", pos, file.source());
        if (argValue == null) return Optional.empty();
        String name = Nodes.unquote(Nodes.text(argValue, file.source()));
        return catalog.getTable(name).map(table -> hover(file, argValue, formatTable(table)));
    }

    private static Optional<Hover> fieldHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, Point pos
    ) {
        var argValue = stringArgValueAt(directive, "name", pos, file.source());
        if (argValue == null) return Optional.empty();
        String columnName = Nodes.unquote(Nodes.text(argValue, file.source()));

        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) return Optional.empty();
        var tableName = TypeContext.tableNameOf(typeDef.get(), file.source());
        if (tableName.isEmpty()) return Optional.empty();
        var tableOpt = catalog.getTable(tableName.get());
        if (tableOpt.isEmpty()) return Optional.empty();
        return tableOpt.get().columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst()
            .map(column -> hover(file, argValue, formatColumn(tableName.get(), column)));
    }

    private static Optional<Hover> referenceHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, Point pos
    ) {
        // Find the innermost object_field containing the cursor; then
        // produce hover content keyed on the nested-field name.
        for (var arg : directive.arguments()) {
            if (!"path".equals(Nodes.text(arg.key(), file.source()))) continue;
            Node field = innermostObjectFieldContaining(arg.value(), pos);
            if (field == null) continue;
            Node nameNode = childOfKind(field, "name");
            Node valueNode = childOfKind(field, "value");
            if (nameNode == null || valueNode == null) continue;
            if (!Nodes.contains(valueNode, pos)) continue;
            String fieldName = Nodes.text(nameNode, file.source());
            String value = Nodes.unquote(Nodes.text(valueNode, file.source()));
            return switch (fieldName) {
                case "key" -> referenceKeyHover(file, valueNode, value, catalog);
                case "table" -> referenceTableHover(file, valueNode, value, catalog);
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static Optional<Hover> referenceKeyHover(
        WorkspaceFile file, Node valueNode, String fkName, CompletionData catalog
    ) {
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                if (!ref.keyName().equals(fkName)) continue;
                String arrow = ref.inverse() ? "←" : "→";
                String content = "**Foreign key** `" + fkName + "`\n\n"
                    + "`" + table.name() + "` " + arrow + " `" + ref.targetTable() + "`";
                return Optional.of(hover(file, valueNode, content));
            }
        }
        return Optional.empty();
    }

    private static Optional<Hover> referenceTableHover(
        WorkspaceFile file, Node valueNode, String tableName, CompletionData catalog
    ) {
        return catalog.getTable(tableName).map(t -> hover(file, valueNode, formatTable(t)));
    }

    private static String formatTable(CompletionData.Table table) {
        var sb = new StringBuilder();
        sb.append("**Table** `").append(table.name()).append("`");
        if (!table.description().isEmpty()) {
            sb.append("\n\n").append(table.description());
        }
        sb.append("\n\n").append(table.columns().size()).append(" column")
            .append(table.columns().size() == 1 ? "" : "s")
            .append(", ").append(table.references().size()).append(" reference")
            .append(table.references().size() == 1 ? "" : "s").append(".");
        return sb.toString();
    }

    private static String formatColumn(String tableName, CompletionData.Column column) {
        var sb = new StringBuilder();
        sb.append("**Column** `").append(column.name()).append("`")
          .append(" on `").append(tableName).append("`")
          .append("\n\nType: `").append(column.graphqlType()).append("`")
          .append(column.nullable() ? " (nullable)" : " (not null)");
        if (!column.description().isEmpty()) {
            sb.append("\n\n").append(column.description());
        }
        return sb.toString();
    }

    /**
     * Returns the directive's argument value node for {@code argName} when
     * the cursor is inside that value, otherwise {@code null}.
     */
    private static Node stringArgValueAt(
        Directives.Directive directive, String argName, Point pos, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            if (!argName.equals(Nodes.text(arg.key(), source))) continue;
            if (!Nodes.contains(arg.value(), pos)) continue;
            return arg.value();
        }
        return null;
    }

    private static Hover hover(WorkspaceFile file, Node rangeNode, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), rangeNode.getStartByte());
        var end = Positions.toLspPosition(file.source(), rangeNode.getEndByte());
        return new Hover(content, new Range(start, end));
    }

    private static Node innermostObjectFieldContaining(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = null;
        if ("object_field".equals(node.getType())) best = node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostObjectFieldContaining(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (kind.equals(child.getType())) return child;
        }
        return null;
    }
}
