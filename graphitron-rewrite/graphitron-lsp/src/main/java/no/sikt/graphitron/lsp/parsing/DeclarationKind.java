package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * R216 — closed family of tree-sitter-graphql declaration kinds that the LSP cares about,
 * spanning both {@code *_type_definition} ("type Foo { ... }") and {@code *_type_extension}
 * ("extend type Foo { ... }") families.
 *
 * <p>The split between definition and extension is a syntactic boundary in tree-sitter-graphql,
 * not a semantic one: a multi-file schema author who lays out root fields under
 * {@code extend type Query { ... }} expects the LSP surfaces (classification labels, inferred
 * directives, completions, go-to-def, member-validation diagnostics) to behave identically on
 * either family. {@link #enclosing(Node)} and {@link #walkAll(Node, Consumer)} centralise the
 * kind-set so every consumer treats the two families uniformly.
 *
 * <p>{@link #isCarrier()} distinguishes kinds that admit field declarations (object / interface /
 * input-object) from those that don't (union / scalar / enum) — the former participate in
 * {@code @field(name:)} hovers and member-validation walks.
 */
public enum DeclarationKind {
    OBJECT_DEF       ("object_type_definition",        true,  false),
    INTERFACE_DEF    ("interface_type_definition",     true,  false),
    INPUT_OBJECT_DEF ("input_object_type_definition",  true,  false),
    UNION_DEF        ("union_type_definition",         false, false),
    SCALAR_DEF       ("scalar_type_definition",        false, false),
    ENUM_DEF         ("enum_type_definition",          false, false),
    OBJECT_EXT       ("object_type_extension",         true,  true),
    INTERFACE_EXT    ("interface_type_extension",      true,  true),
    INPUT_OBJECT_EXT ("input_object_type_extension",   true,  true),
    UNION_EXT        ("union_type_extension",          false, true),
    SCALAR_EXT       ("scalar_type_extension",         false, true),
    ENUM_EXT         ("enum_type_extension",           false, true);

    private static final Map<String, DeclarationKind> BY_TREE_SITTER_KIND;
    static {
        var map = new java.util.HashMap<String, DeclarationKind>();
        for (var kind : values()) map.put(kind.treeSitterKind, kind);
        BY_TREE_SITTER_KIND = Map.copyOf(map);
    }

    private final String treeSitterKind;
    private final boolean carrier;
    private final boolean extension;

    DeclarationKind(String treeSitterKind, boolean carrier, boolean extension) {
        this.treeSitterKind = treeSitterKind;
        this.carrier = carrier;
        this.extension = extension;
    }

    /** The tree-sitter-graphql node-type string (e.g. {@code "object_type_definition"}). */
    public String treeSitterKind() {
        return treeSitterKind;
    }

    /**
     * True for kinds that admit a {@code fields_definition} or {@code input_fields_definition}
     * child (object / interface / input-object definitions and extensions). Used by the
     * field-hover ancestor walk to filter to coordinates where {@code Parent.fieldName} is
     * meaningful.
     */
    public boolean isCarrier() {
        return carrier;
    }

    /** True for the {@code *_type_extension} family. */
    public boolean isExtension() {
        return extension;
    }

    public static Optional<DeclarationKind> of(String treeSitterKind) {
        return Optional.ofNullable(BY_TREE_SITTER_KIND.get(treeSitterKind));
    }

    public static Optional<DeclarationKind> of(Node node) {
        return node == null ? Optional.empty() : of(node.getType());
    }

    /**
     * Walks ancestors of {@code inner} (inclusive) until a node whose kind resolves to a
     * {@link DeclarationKind} is found. Replaces the per-consumer "find the enclosing type"
     * walks that used to hard-code the definition-only kind set.
     */
    public static Optional<Node> enclosing(Node inner) {
        Node node = inner;
        while (node != null) {
            if (of(node).isPresent()) {
                return Optional.of(node);
            }
            Node parent = node.getParent().orElse(null);
            if (parent == null || parent.equals(node)) {
                return Optional.empty();
            }
            node = parent;
        }
        return Optional.empty();
    }

    /**
     * Visits every descendant of {@code root} (inclusive) whose kind resolves to a
     * {@link DeclarationKind}.
     */
    public static void walkAll(Node root, Consumer<Node> sink) {
        if (of(root).isPresent()) sink.accept(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            Node child = root.getChild(i).orElse(null);
            if (child != null) walkAll(child, sink);
        }
    }
}
