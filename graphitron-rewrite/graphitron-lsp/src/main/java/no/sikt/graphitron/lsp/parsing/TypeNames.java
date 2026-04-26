package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

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

    private static final TSQuery QUERY =
        new TSQuery(GraphqlLanguage.get(), DECLARATION_QUERY);

    private TypeNames() {}

    public record Extracted(Set<String> declared, Set<String> referenced) {}

    public static Extracted extract(TSNode root, byte[] source) {
        var declared = new LinkedHashSet<String>();
        var referenced = new LinkedHashSet<String>();

        var cursor = new TSQueryCursor();
        cursor.exec(QUERY, root);
        var match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                String name = QUERY.getCaptureNameForId(capture.getIndex());
                String text = Nodes.text(capture.getNode(), source);
                switch (name) {
                    case "decl" -> declared.add(text);
                    case "ref" -> referenced.add(text);
                    default -> { /* ignore */ }
                }
            }
        }
        return new Extracted(declared, referenced);
    }
}
