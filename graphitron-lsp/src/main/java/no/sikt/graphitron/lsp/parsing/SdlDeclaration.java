package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.FIELD_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.INPUT_VALUE_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;

/**
 * The shared leaf-walk-and-classify primitive for an SDL <em>declaration name</em>
 * token: the cursor sits on a type-declaration name (the {@code Customer} in
 * {@code type Customer @table(...)}) or a field / input-value declaration name
 * (the {@code firstName} in {@code firstName: String!}), outside any directive.
 *
 * <p>Two features key off this exact trigger and must not drift: the
 * classification {@code hover} arm ({@code DeclarationHovers}) and the
 * declaration-name {@code goto-definition} arm ({@code DeclarationDefinitions}).
 * Both call {@link #findContaining} so "is this leaf a declaration name, and of
 * which kind?" is decided in exactly one place; each feature then maps the
 * result to its own target (hover content vs. a Java {@code Location}). This is
 * the orthogonal axis to {@link Behavior} (directive-argument bindings) and to a
 * {@code named_type} reference (an intra-schema type use, never a declaration).
 */
public sealed interface SdlDeclaration
    permits SdlDeclaration.TypeName, SdlDeclaration.FieldName {

    /** The tree-sitter {@code name} token the cursor resolved onto. */
    Node nameNode();

    /** A type-declaration name ({@code object_type_definition} and friends). */
    record TypeName(Node nameNode, String typeName) implements SdlDeclaration {}

    /**
     * A field- or input-value-declaration name. {@code parentTypeName} is the
     * enclosing carrier type's name; {@code fieldName} is the declared field
     * name. The {@code field_definition} / {@code input_value_definition} node is
     * {@code nameNode().getParent()}, so consumers needing it (e.g. to read an
     * {@code @field(name:)} override) reach it from there.
     */
    record FieldName(Node nameNode, String parentTypeName, String fieldName) implements SdlDeclaration {}

    /**
     * Walks from the leaf {@code pos} sits inside outward and classifies it as a
     * declaration name, or returns empty when the cursor is not on one (not a
     * {@code name} token, a {@code name} whose parent is neither a field/input
     * declaration nor a type declaration, or a field declaration with no
     * resolvable carrier type).
     */
    static Optional<SdlDeclaration> findContaining(Node root, Point pos, byte[] source) {
        Node node = root.getDescendant(pos, pos).orElse(null);
        if (node == null || !NAME.matches(node)) return Optional.empty();
        Node parent = node.getParent().orElse(null);
        if (parent == null) return Optional.empty();
        if (FIELD_DEFINITION.matches(parent) || INPUT_VALUE_DEFINITION.matches(parent)) {
            return fieldDeclaration(node, parent, source);
        }
        if (DeclarationKind.of(parent).isPresent()) {
            return Optional.of(new TypeName(node, Nodes.text(node, source)));
        }
        return Optional.empty();
    }

    private static Optional<SdlDeclaration> fieldDeclaration(Node nameNode, Node fieldDef, byte[] source) {
        Node parent = fieldDef.getParent().orElse(null);
        while (parent != null) {
            if (DeclarationKind.of(parent).filter(DeclarationKind::isCarrier).isPresent()) {
                Node typeName = Nodes.childOfKind(parent, NAME);
                if (typeName == null) return Optional.empty();
                return Optional.of(new FieldName(
                    nameNode, Nodes.text(typeName, source), Nodes.text(nameNode, source)));
            }
            Node grandparent = parent.getParent().orElse(null);
            if (grandparent == null || grandparent.equals(parent)) return Optional.empty();
            parent = grandparent;
        }
        return Optional.empty();
    }
}
