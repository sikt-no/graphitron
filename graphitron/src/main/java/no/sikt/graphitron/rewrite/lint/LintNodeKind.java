package no.sikt.graphitron.rewrite.lint;

/**
 * The graphql-java SDL node kinds the lint engine's single traversal can encounter, as an explicit
 * enum. Completeness is asserted over this declared partition (R398): every constant is either
 * subscribed by a rule ({@link LintRules#subscribedKinds()}) or in the deliberately-not-linted set
 * ({@link LintRules#NOT_LINTED}), and the two are disjoint. {@code LintRuleRegistryCoverageTest}
 * pins that, mirroring the {@code VariantCoverageTest} / {@code EdgeCoverageTest} no-silent-default
 * pattern, so a future node position cannot be silently skipped by a {@code default} arm.
 *
 * <p>The engine names the {@link LintNodeKind} explicitly at each dispatch site as it walks the
 * parsed types (see {@code LintEngine}), rather than deriving it from the node's runtime type; the
 * coverage test guards the enum against a rule subscribing to, or declaring not-linted, a kind
 * outside this set.
 */
public enum LintNodeKind {
    // Linted in v1 (subscribed by at least one engine visitor).
    OBJECT_TYPE,
    INTERFACE_TYPE,
    UNION_TYPE,
    ENUM_TYPE,
    INPUT_OBJECT_TYPE,
    SCALAR_TYPE,
    FIELD_DEFINITION,
    INPUT_FIELD_DEFINITION,
    ARGUMENT_DEFINITION,
    ENUM_VALUE_DEFINITION,
    APPLIED_DIRECTIVE,

    // Encountered but deliberately not linted in v1 (declared in LintRules.NOT_LINTED).
    DIRECTIVE_DEFINITION,
    SCHEMA_DEFINITION,
    OBJECT_TYPE_EXTENSION,
    INTERFACE_TYPE_EXTENSION,
    UNION_TYPE_EXTENSION,
    ENUM_TYPE_EXTENSION,
    INPUT_OBJECT_TYPE_EXTENSION,
    SCALAR_TYPE_EXTENSION,
    SCHEMA_EXTENSION,
    APPLIED_DIRECTIVE_ARGUMENT
}
