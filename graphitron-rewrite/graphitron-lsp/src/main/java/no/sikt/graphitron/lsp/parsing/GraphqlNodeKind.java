package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;

/**
 * R347 (Slice 1) — the closed set of tree-sitter-graphql <em>intra-declaration</em> node kinds the
 * LSP navigates: the structural children ({@code name}, {@code value}, {@code arguments}, ...) and
 * the value-shape nodes ({@code object_value}, {@code list_value}, {@code string_value}) that every
 * feature reaches for when walking inside a single declaration.
 *
 * <p>Sibling to {@link DeclarationKind}, which owns the declaration-level kinds
 * ({@code *_type_definition} / {@code *_type_extension}). The split is deliberate: DeclarationKind
 * carries declaration-only semantics (carrier / extension flags, {@code enclosing} / {@code walkAll}
 * ancestor and descendant walks); this enum is the vocabulary for the structural navigation helpers
 * on {@link Nodes}. Holding node-kind strings as constants turns a grammar rename from a
 * module-wide literal hunt into a one-line edit and gives every comparison a compile-time-checked
 * name.
 */
public enum GraphqlNodeKind {
    NAME("name"),
    VALUE("value"),
    NAMED_TYPE("named_type"),
    DIRECTIVE("directive"),
    DIRECTIVES("directives"),
    ARGUMENT("argument"),
    ARGUMENTS("arguments"),
    ARGUMENTS_DEFINITION("arguments_definition"),
    FIELD_DEFINITION("field_definition"),
    FIELDS_DEFINITION("fields_definition"),
    INPUT_FIELDS_DEFINITION("input_fields_definition"),
    INPUT_VALUE_DEFINITION("input_value_definition"),
    OBJECT_FIELD("object_field"),
    OBJECT_VALUE("object_value"),
    LIST_VALUE("list_value"),
    STRING_VALUE("string_value"),
    ENUM_VALUE("enum_value"),
    DESCRIPTION("description");

    private final String id;

    GraphqlNodeKind(String id) {
        this.id = id;
    }

    /** The tree-sitter-graphql node-type string (e.g. {@code "object_field"}). */
    public String id() {
        return id;
    }

    /** Null-safe kind test: {@code true} iff {@code node} is non-null and of this kind. */
    public boolean matches(Node node) {
        return node != null && id.equals(node.getType());
    }
}
