package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.facts.SdlDeclarations;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;
import java.util.Set;

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
 * resolves into the jOOQ-generated Java tree, this provider keys on a
 * {@code named_type} reference name outside any directive and resolves into a
 * workspace declaration. The two key off disjoint syntax (a {@code named_type}
 * never appears inside a directive argument), so the definition handler chains them
 * with {@code .or()} rather than classifying up front.
 *
 * <p>When an open buffer declares the type, the returned range is the real
 * declaration-name span from the tree-sitter parse. Otherwise resolution falls back to
 * the graph's captured declaration sites, through {@link SdlDeclarations}, which cover
 * every type in every schema file regardless of which buffers are open. The
 * open-buffer scan stays first and authoritative: a type being edited resolves to its
 * live tree-sitter span, not the position the last capture recorded.
 *
 * <p>The store is optional here, and not for the reason it is optional elsewhere. The
 * providers that resolve into the Java tree have nothing to say without it and decline
 * at the top; this one's authoritative arm is the buffer, so a session outside a build
 * still resolves every reference the workspace declares and loses only the on-disk
 * fallback. What that fallback additionally needs is for the cursor's own document to
 * name a captured source, since it is the document that decides which graph answers.
 */
public final class IntraSchemaDefinitions {

    /**
     * The GraphQL spec's own scalars, which nothing declares and no jump can land on. Held here
     * because this is the one surface that has to recognise them: a reference to {@code Int} is a
     * reference to a type the language defines, not to a missing declaration.
     */
    private static final Set<String> BUILTIN_SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");

    private IntraSchemaDefinitions() {}

    public static Optional<Location> compute(
        Workspace workspace, Optional<StoreHandle> store, String cursorUri, Point pos
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
            if (BUILTIN_SCALARS.contains(typeName)) return Optional.<Location>empty();

            for (var entry : views.entrySet()) {
                var file = entry.getValue();
                // findDefinition returns empty for a file that does not declare the
                // type, so it doubles as the per-file guard; no pre-filter is needed
                // because the walk is cheap at LSP open-file counts.
                var nameNode = DeclarationKind.findDefinition(file.tree().getRootNode(), file.source(), typeName);
                if (nameNode.isPresent()) {
                    return Optional.of(locationOf(entry.getKey(), nameNode.get(), file.source()));
                }
            }
            // Workspace-wide fallback: no open buffer declares the type, so the graph's own
            // captured declaration sites answer. Empty for a name the graph does not declare,
            // and for one it declares only where an editor cannot follow.
            return store.flatMap(handle -> SdlDeclarations.typeLocation(handle, typeName));
        });
    }

    private static Location locationOf(String uri, Node nameNode, byte[] source) {
        var start = Positions.toLspPosition(source, nameNode.getStartByte());
        var end = Positions.toLspPosition(source, nameNode.getEndByte());
        return new Location(uri, new Range(start, end));
    }
}
