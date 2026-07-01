package no.sikt.graphitron.rewrite.lint;

import java.util.Set;

/**
 * One built-in lint rule as an independent visitor, the ESLint /
 * graphql-schema-linter contract: it declares its {@link LintRule}, subscribes to the
 * {@link LintNodeKind}s it cares about, and inspects each matching node, reporting findings through
 * the {@link LintContext} sink. Rules are registered in {@link LintRules}; the engine's single
 * traversal dispatches each node to the visitors subscribed to its kind. Adding a rule is
 * registering a visitor, not editing a central switch.
 */
public interface LintVisitor {

    /** The rule this visitor implements; every finding it reports is tagged with this. */
    LintRule rule();

    /** The node kinds this visitor subscribes to. The engine dispatches only matching nodes here. */
    Set<LintNodeKind> kinds();

    /** Inspect one subscribed node and report any finding through {@code ctx}. */
    void inspect(LintTarget target, LintContext ctx);
}
