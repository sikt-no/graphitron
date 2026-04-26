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
import org.treesitter.TSNode;
import org.treesitter.TSPoint;

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

    public static Optional<Hover> compute(WorkspaceFile file, CompletionData catalog, TSPoint pos) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) return Optional.empty();
        var directive = directiveOpt.get();
        String name = Nodes.text(directive.nameNode(), file.source());
        return switch (name) {
            case "table" -> tableHover(directive, file, catalog, pos);
            case "field" -> fieldHover(directive, file, catalog, pos);
            case "reference" -> referenceHover(directive, file, catalog, pos);
            default -> Optional.empty();
        };
    }

    private static Optional<Hover> tableHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, TSPoint pos
    ) {
        var argValue = stringArgValueAt(directive, "name", pos, file.source());
        if (argValue == null) return Optional.empty();
        String name = Nodes.unquote(Nodes.text(argValue, file.source()));
        return catalog.getTable(name).map(table -> hover(file, argValue, formatTable(table)));
    }

    private static Optional<Hover> fieldHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, TSPoint pos
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
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, TSPoint pos
    ) {
        // Find the innermost object_field containing the cursor; then
        // produce hover content keyed on the nested-field name.
        for (var arg : directive.arguments()) {
            if (!"path".equals(Nodes.text(arg.key(), file.source()))) continue;
            TSNode field = innermostObjectFieldContaining(arg.value(), pos);
            if (field == null) continue;
            TSNode nameNode = childOfKind(field, "name");
            TSNode valueNode = childOfKind(field, "value");
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
        WorkspaceFile file, TSNode valueNode, String fkName, CompletionData catalog
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
        WorkspaceFile file, TSNode valueNode, String tableName, CompletionData catalog
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
    private static TSNode stringArgValueAt(
        Directives.Directive directive, String argName, TSPoint pos, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            if (!argName.equals(Nodes.text(arg.key(), source))) continue;
            if (!Nodes.contains(arg.value(), pos)) continue;
            return arg.value();
        }
        return null;
    }

    private static Hover hover(WorkspaceFile file, TSNode rangeNode, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), rangeNode.getStartByte());
        var end = Positions.toLspPosition(file.source(), rangeNode.getEndByte());
        return new Hover(content, new Range(start, end));
    }

    private static TSNode innermostObjectFieldContaining(TSNode node, TSPoint pos) {
        if (node == null || node.isNull() || !Nodes.contains(node, pos)) return null;
        TSNode best = null;
        if ("object_field".equals(node.getType())) best = node;
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode descendant = innermostObjectFieldContaining(node.getChild(i), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static TSNode childOfKind(TSNode parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            TSNode child = parent.getChild(i);
            if (kind.equals(child.getType())) return child;
        }
        return null;
    }
}
