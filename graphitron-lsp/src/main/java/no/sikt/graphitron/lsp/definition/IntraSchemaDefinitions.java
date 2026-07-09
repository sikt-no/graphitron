package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeNames;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAMED_TYPE;

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
 * <p>Targets here live in the tree-sitter-parsed workspace files, so when an open
 * buffer declares the type the returned range is the real declaration-name span, not
 * the {@code 0:0} placeholder the jOOQ path is stuck with pending JavaParser (see R90).
 *
 * <p>When no open buffer declares the type (R350), the resolution falls back to the
 * build snapshot's type-definition-location map ({@link LspSchemaSnapshot.Built#typeDefinitionLocations()},
 * projected from the {@code TypeDefinitionRegistry} in {@code CatalogBuilder.buildSnapshot}),
 * which covers every type in every schema file regardless of which buffers are open. The
 * open-buffer scan stays first and authoritative: a type being edited resolves to its live
 * tree-sitter span, not the last-built on-disk position. The snapshot is taken as an explicit
 * parameter (not read off the {@link Workspace}) so the fallback is unit-testable without a
 * full build; the production call site in {@code GraphitronTextDocumentService} passes
 * {@code workspace.snapshot()}.
 */
public final class IntraSchemaDefinitions {

    private IntraSchemaDefinitions() {}

    public static Optional<Location> compute(
        Workspace workspace, LspSchemaSnapshot snapshot, String cursorUri, Point pos
    ) {
        // withAllViews so the cursor-file leaf resolution and the workspace-wide
        // declaration scan read one consistent generation of every open file; the
        // views are closed for us when the lambda returns.
        return workspace.withAllViews(views -> {
            var cursorFile = views.get(cursorUri);
            if (cursorFile == null) return Optional.<Location>empty();

            Node leaf = cursorFile.tree().getRootNode().getDescendant(pos, pos).orElse(null);
            if (leaf == null || !NAME.matches(leaf)) return Optional.<Location>empty();
            Node parent = leaf.getParent().orElse(null);
            if (parent == null || !NAMED_TYPE.matches(parent)) return Optional.<Location>empty();

            String typeName = Nodes.text(leaf, cursorFile.source());
            if (TypeNames.BUILTIN_SCALARS.contains(typeName)) return Optional.<Location>empty();

            for (var entry : views.entrySet()) {
                var file = entry.getValue();
                // findDefinition returns empty for a file that does not declare the
                // type, so it doubles as the per-file guard; the snapshot no longer
                // carries declaredTypes to pre-filter (it exists only for the
                // workspace's own under-lock mutators), and the walk is cheap at LSP
                // open-file counts.
                var nameNode = DeclarationKind.findDefinition(file.tree().getRootNode(), file.source(), typeName);
                if (nameNode.isPresent()) {
                    return Optional.of(locationOf(entry.getKey(), nameNode.get(), file.source()));
                }
            }
            return snapshotFallback(snapshot, typeName);
        });
    }

    /**
     * Workspace-wide fallback: resolve the type to its declaration position recorded in the
     * build snapshot when no open buffer declares it. Returns empty for an
     * {@link LspSchemaSnapshot.Unavailable} snapshot or a type the map does not carry
     * (built-in scalar, bundled-directive type, or a name the schema does not declare),
     * preserving the prior no-op for the genuine no-target case.
     */
    private static Optional<Location> snapshotFallback(LspSchemaSnapshot snapshot, String typeName) {
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return Optional.empty();
        return built.typeDefinitionLocation(typeName).map(IntraSchemaDefinitions::locationOf);
    }

    private static Location locationOf(CompletionData.SourceLocation loc) {
        // The snapshot map already holds 0-based LSP coordinates (CatalogBuilder reduces the
        // registry's 1-based SourceLocation). The fallback points at the declaration's start
        // (the type/scalar keyword); the precise name span is only available from the
        // open-buffer tree-sitter path above.
        var pos = new Position(loc.line(), loc.column());
        return new Location(loc.uri(), new Range(pos, pos));
    }

    private static Location locationOf(String uri, Node nameNode, byte[] source) {
        var start = Positions.toLspPosition(source, nameNode.getStartByte());
        var end = Positions.toLspPosition(source, nameNode.getEndByte());
        return new Location(uri, new Range(start, end));
    }
}
