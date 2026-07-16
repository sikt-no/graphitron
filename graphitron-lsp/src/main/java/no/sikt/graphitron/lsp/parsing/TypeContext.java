package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;

import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.ARGUMENT;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.ARGUMENTS;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.ARGUMENTS_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.FIELD_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.INPUT_VALUE_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

/**
 * Resolves the surrounding GraphQL-type-declaration context for a node. Per-directive completion
 * providers use this to figure out which {@code @table}-bound type a {@code @field(name:)} or
 * {@code @reference(...)} sits inside, so column / FK suggestions can be filtered by the
 * relevant table.
 *
 * <p>The enclosing-declaration walk delegates to {@link DeclarationKind#enclosing(Node)}
 * so both {@code *_type_definition} ("type X { ... }") and {@code *_type_extension}
 * ("extend type X { ... }") nodes resolve uniformly. {@link #tableNameOf} is snapshot-routed
 * (asks the classifier's name-keyed projection rather than reading {@code @table(name:)} off the
 * AST node) so an extension whose definition lives in another file still resolves to the
 * authoritative table name.
 */
public final class TypeContext {

    private TypeContext() {}

    /**
     * Walks ancestors of {@code inner} until the {@code field_definition} node is found.
     * Returns empty if {@code inner} is not inside a field definition (e.g. directly on the
     * type's own directive list rather than on one of its fields).
     */
    public static Optional<Node> enclosingFieldDefinition(Node inner) {
        Node node = inner;
        while (node != null) {
            if (FIELD_DEFINITION.matches(node)) {
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
     * Walks ancestors of {@code inner} until the enclosing field-bearing node is found,
     * matching either {@code field_definition} (output type) or {@code input_value_definition}
     * (input type). Both kinds carry a {@code name} child compatible with {@link #fieldNameOf};
     * callers that need the SDL field name regardless of input/output orientation route through
     * this helper.
     */
    public static Optional<Node> enclosingFieldOrInputValueDefinition(Node inner) {
        Node node = inner;
        while (node != null) {
            if (FIELD_DEFINITION.matches(node) || INPUT_VALUE_DEFINITION.matches(node)) {
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
     * Names of the GraphQL arguments declared on a {@code field_definition}:
     * the {@code name} child of each {@code input_value_definition} under the
     * field's {@code arguments_definition}. Empty when the field has no
     * argument list. Used by the {@code argMapping} right-side completion /
     * diagnostics to resolve the candidate GraphQL arguments syntactically
     * (the LSP carries no projection of user field arguments).
     */
    public static java.util.List<String> fieldArgumentNames(Node fieldDef, byte[] source) {
        Node args = Nodes.childOfKind(fieldDef, ARGUMENTS_DEFINITION);
        if (args == null) return java.util.List.of();
        var out = new java.util.ArrayList<String>();
        for (int i = 0; i < args.getChildCount(); i++) {
            Node child = args.getChild(i).orElse(null);
            if (child == null || !INPUT_VALUE_DEFINITION.matches(child)) continue;
            Node nameNode = Nodes.childOfKind(child, NAME);
            if (nameNode != null) out.add(Nodes.text(nameNode, source));
        }
        return java.util.List.copyOf(out);
    }

    /**
     * Reads the field-name child of a {@code field_definition} node. Returns
     * {@link Optional#empty()} when the node has no {@code name} child (shouldn't happen for a
     * well-formed parse but defensive against partial-edit trees).
     */
    public static Optional<String> fieldNameOf(Node fieldDef, byte[] source) {
        Node nameNode = Nodes.childOfKind(fieldDef, NAME);
        if (nameNode == null) return Optional.empty();
        return Optional.of(Nodes.text(nameNode, source));
    }

    /**
     * Resolves the SQL table name for the type declared at {@code typeDecl} by routing
     * through the classifier's name-keyed projection on the snapshot. Returns empty when the
     * snapshot is unavailable, the declared name resolves to no classification, or the
     * classification has no {@code tableName} (e.g. plain object, scalar, enum). Works
     * uniformly for definition and extension nodes because the projection is keyed on the
     * declared type name, not on the AST node.
     */
    public static Optional<String> tableNameOf(
        Node typeDecl, byte[] source, LspSchemaSnapshot snapshot
    ) {
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return Optional.empty();
        return declaredNameOf(typeDecl, source)
            .map(name -> built.typeClassificationsByName().get(name))
            .flatMap(TypeContext::tableNameFromClassification);
    }

    /**
     * Switches over the {@link TypeClassification} arms that carry a {@code tableName}. The four
     * Table-bearing arms (Table, Node, TableInterface, TableInput) lift here so the inlay /
     * hover / completion / definition / diagnostic surfaces share a single switch.
     */
    public static Optional<String> tableNameFromClassification(TypeClassification classification) {
        return switch (classification) {
            case TypeClassification.Table t -> Optional.ofNullable(t.tableName());
            case TypeClassification.Node n -> Optional.ofNullable(n.tableName());
            case TypeClassification.TableInterface ti -> Optional.ofNullable(ti.tableName());
            case TypeClassification.TableInput ti -> Optional.ofNullable(ti.tableName());
            default -> Optional.empty();
        };
    }

    /**
     * Returns the declared name on {@code typeDecl} (e.g. the {@code "BigDecimal"} in
     * {@code scalar BigDecimal @scalarType(...)}, the {@code "Customer"} in
     * {@code extend type Customer { ... }}). Reads the first {@code name} child.
     */
    public static Optional<String> declaredNameOf(Node typeDecl, byte[] source) {
        Node nameNode = Nodes.childOfKind(typeDecl, NAME);
        if (nameNode == null) return Optional.empty();
        return Optional.of(Nodes.text(nameNode, source));
    }

    /**
     * Reads the string-literal value of {@code argName} from a {@code directive}
     * node. Returns {@code null} when the argument is absent or its value is
     * not a string literal.
     */
    public static String stringArg(Node directive, String argName, byte[] source) {
        Node arguments = Nodes.childOfKind(directive, ARGUMENTS);
        if (arguments == null) return null;
        for (int i = 0; i < arguments.getChildCount(); i++) {
            Node argNode = arguments.getChild(i).orElse(null);
            if (!ARGUMENT.matches(argNode)) continue;
            Node keyNode = Nodes.childOfKind(argNode, NAME);
            if (keyNode == null || !argName.equals(Nodes.text(keyNode, source))) continue;
            Node valueNode = Nodes.childOfKind(argNode, VALUE);
            if (valueNode == null) return null;
            return Nodes.unquote(Nodes.text(valueNode, source));
        }
        return null;
    }
}
