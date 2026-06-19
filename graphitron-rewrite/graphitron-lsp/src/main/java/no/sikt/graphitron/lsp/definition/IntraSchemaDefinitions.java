package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeNames;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

/**
 * Goto-definition for intra-schema type references: the cursor sits on a GraphQL
 * type reference (the {@code Film} in {@code films: [Film!]!}, an {@code implements}
 * interface, or a union member) and the editor jumps to that type's
 * {@code type Film { ... }} declaration in whichever open file declares it.
 *
 * <p>Parallel to {@code hover/DeclarationHovers} beside {@code hover/Hovers}: where
 * {@link Definitions} keys on the cursor sitting inside a directive argument and
 * resolves into the jOOQ-generated Java tree, this provider keys on the cursor
 * sitting on a {@code named_type} reference name outside any directive and resolves
 * into a workspace declaration. The two key off disjoint syntax (a {@code named_type}
 * never appears inside a directive argument), so the definition handler chains them
 * with {@code .or()} rather than classifying up front.
 *
 * <p>Targets here live in the tree-sitter-parsed workspace files, so the returned
 * range is the real declaration-name span, not the {@code 0:0} placeholder the jOOQ
 * path is stuck with pending JavaParser (see R90).
 */
public final class IntraSchemaDefinitions {

    private IntraSchemaDefinitions() {}

    public static Optional<Location> compute(Workspace workspace, String cursorUri, Point pos) {
        var cursorFile = workspace.get(cursorUri).orElse(null);
        if (cursorFile == null || cursorFile.tree() == null) return Optional.empty();

        Node leaf = cursorFile.tree().getRootNode().getDescendant(pos, pos).orElse(null);
        if (leaf == null || !"name".equals(leaf.getType())) return Optional.empty();
        Node parent = leaf.getParent().orElse(null);
        if (parent == null || !"named_type".equals(parent.getType())) return Optional.empty();

        String typeName = Nodes.text(leaf, cursorFile.source());
        if (TypeNames.BUILTIN_SCALARS.contains(typeName)) return Optional.empty();

        for (var uri : workspace.openUris()) {
            var file = workspace.get(uri).orElse(null);
            if (file == null || file.tree() == null) continue;
            if (!file.declaredTypes().contains(typeName)) continue;
            var nameNode = DeclarationKind.findDefinition(file.tree().getRootNode(), file.source(), typeName);
            if (nameNode.isPresent()) {
                return Optional.of(locationOf(uri, nameNode.get(), file.source()));
            }
        }
        return Optional.empty();
    }

    private static Location locationOf(String uri, Node nameNode, byte[] source) {
        var start = Positions.toLspPosition(source, nameNode.getStartByte());
        var end = Positions.toLspPosition(source, nameNode.getEndByte());
        return new Location(uri, new Range(start, end));
    }
}
