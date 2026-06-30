package no.sikt.graphitron.rewrite.lint;

import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.SourceLocation;

/**
 * One node the engine's traversal dispatches to a subscribed visitor: its {@link LintNodeKind}, the
 * graphql-java AST {@link Node} itself, and the enclosing-type context a rule may need (the parent
 * type name, and whether that type is a root operation type, for the description-scope rule).
 */
public record LintTarget(
    LintNodeKind kind,
    Node<?> node,
    String enclosingTypeName,
    boolean enclosingTypeIsRootOperation
) {

    /** The node's source location (1-based line/column), used as the default finding range. */
    public SourceLocation location() {
        return node.getSourceLocation();
    }

    /** The node's name when it is a {@link NamedNode}, else {@code null}. */
    public String name() {
        return node instanceof NamedNode<?> named ? named.getName() : null;
    }
}
