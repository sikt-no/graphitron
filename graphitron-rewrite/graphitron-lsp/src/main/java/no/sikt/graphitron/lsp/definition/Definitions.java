package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.treesitter.TSNode;
import org.treesitter.TSPoint;

import java.util.Optional;

/**
 * Resolves cursor positions on known directive arguments to source
 * locations in the jOOQ-generated Java tree, so the editor's
 * "go-to-definition" jumps to the table class, column declaration, or
 * FK constant. Phase 4 scope: file-level URIs only (line range is
 * always 0:0); per-column line refinement waits until JavaParser is
 * adopted in Phase 5.
 *
 * <p>Returns {@link Optional#empty()} when the cursor is not on a
 * known directive arg, when the arg value does not resolve in the
 * catalog, or when the catalog entry has no source location (the file
 * is not on disk under the conventional path).
 */
public final class Definitions {

    private Definitions() {}

    public static Optional<Location> compute(WorkspaceFile file, CompletionData catalog, TSPoint pos) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) return Optional.empty();
        var directive = directiveOpt.get();
        String name = Nodes.text(directive.nameNode(), file.source());
        return switch (name) {
            case "table" -> tableDefinition(directive, file, catalog, pos);
            case "field" -> fieldDefinition(directive, file, catalog, pos);
            case "reference" -> referenceDefinition(directive, file, catalog, pos);
            default -> Optional.empty();
        };
    }

    private static Optional<Location> tableDefinition(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, TSPoint pos
    ) {
        TSNode argValue = stringArgValueAt(directive, "name", pos, file.source());
        if (argValue == null) return Optional.empty();
        String tableName = Nodes.unquote(Nodes.text(argValue, file.source()));
        return catalog.getTable(tableName)
            .map(t -> t.definition())
            .flatMap(Definitions::asLocation);
    }

    private static Optional<Location> fieldDefinition(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, TSPoint pos
    ) {
        TSNode argValue = stringArgValueAt(directive, "name", pos, file.source());
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
            .map(CompletionData.Column::definition)
            .flatMap(Definitions::asLocation);
    }

    private static Optional<Location> referenceDefinition(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, TSPoint pos
    ) {
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
                case "key" -> referenceKeyDefinition(catalog, value);
                case "table" -> catalog.getTable(value)
                    .map(CompletionData.Table::definition)
                    .flatMap(Definitions::asLocation);
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static Optional<Location> referenceKeyDefinition(CompletionData catalog, String fkName) {
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                if (ref.keyName().equals(fkName)) {
                    return asLocation(ref.definition());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Location> asLocation(CompletionData.SourceLocation source) {
        if (source == null || source.uri().isEmpty()) {
            return Optional.empty();
        }
        var pos = new Position(source.line(), source.column());
        return Optional.of(new Location(source.uri(), new Range(pos, pos)));
    }

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
