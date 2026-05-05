package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCapture;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extracts declared and referenced type names from a parsed schema. Used by
 * {@code WorkspaceFile} to populate its {@code dependsOnDeclarations} set,
 * so the workspace can decide which files need re-analysis when another
 * file's declarations change. Mirrors the type-extraction pass in the Rust
 * LSP's {@code state/file.rs}.
 */
public final class TypeNames {

    /** Built-in GraphQL scalars; never count as declarations to depend on. */
    public static final Set<String> BUILTIN_SCALARS =
        Set.of("Int", "Float", "String", "Boolean", "ID");

    private static final String DECLARATION_QUERY = """
        (object_type_definition (name) @decl)
        (interface_type_definition (name) @decl)
        (union_type_definition (name) @decl)
        (enum_type_definition (name) @decl)
        (input_object_type_definition (name) @decl)
        (scalar_type_definition (name) @decl)
        (named_type (name) @ref)
        """;

    private static final Query QUERY =
        new Query(GraphqlLanguage.get(), DECLARATION_QUERY);

    private TypeNames() {}

    public record Extracted(Set<String> declared, Set<String> referenced) {}

    public static Extracted extract(Node root, byte[] source) {
        var declared = new LinkedHashSet<String>();
        var referenced = new LinkedHashSet<String>();

        // We only read each capture's text inside the stream consumer, so a confined
        // arena scoped to this method is enough — no Node escapes the try block.
        try (var cursor = new QueryCursor(QUERY);
             java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            cursor.findMatches(root, arena, null).forEach(match -> {
                for (QueryCapture capture : match.captures()) {
                    String text = Nodes.text(capture.node(), source);
                    switch (capture.name()) {
                        case "decl" -> declared.add(text);
                        case "ref" -> referenced.add(text);
                        default -> { /* ignore */ }
                    }
                }
            });
        }
        return new Extracted(declared, referenced);
    }
}
