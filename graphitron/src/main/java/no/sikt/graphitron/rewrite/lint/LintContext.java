package no.sikt.graphitron.rewrite.lint;

import graphql.language.SourceLocation;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * The context a {@link LintVisitor} receives for one {@link LintTarget}: a {@code report} sink that
 * records a finding for the visitor's rule, plus read access to the parsed schema (the
 * {@link TypeDefinitionRegistry} and a {@link DeprecationRecognizer} over it) for the rules that
 * need more than the single node.
 *
 * <p>The engine binds a fresh context to the current (visitor, node) pair, so {@code report}
 * attributes the finding to the right {@link LintRule} and defaults its location to the node's,
 * without the visitor threading either through.
 */
public interface LintContext {

    /** Record a finding for the current rule at the target node's location. */
    void report(String message);

    /** Record a finding for the current rule at the target node's location, carrying a suggested fix. */
    void report(String message, LintFix fix);

    /** Record a finding for the current rule at an explicit location (for a sub-node of the target). */
    void reportAt(SourceLocation location, String message);

    /** The full parsed schema registry (consumer SDL plus the merged graphitron directive surface). */
    TypeDefinitionRegistry registry();

    /** A deprecation recognizer over {@link #registry()}, shared across the run. */
    DeprecationRecognizer deprecation();
}
