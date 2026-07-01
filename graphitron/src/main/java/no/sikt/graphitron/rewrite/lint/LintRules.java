package no.sikt.graphitron.rewrite.lint;

import no.sikt.graphitron.rewrite.lint.rules.DeprecationsHaveAReasonVisitor;
import no.sikt.graphitron.rewrite.lint.rules.EnumValuesScreamingSnakeCaseVisitor;
import no.sikt.graphitron.rewrite.lint.rules.FieldNamesCamelCaseVisitor;
import no.sikt.graphitron.rewrite.lint.rules.InputAndArgumentNamesCamelCaseVisitor;
import no.sikt.graphitron.rewrite.lint.rules.InputObjectNameSuffixVisitor;
import no.sikt.graphitron.rewrite.lint.rules.NoDeprecatedDirectiveUsageVisitor;
import no.sikt.graphitron.rewrite.lint.rules.NoTypenamePrefixVisitor;
import no.sikt.graphitron.rewrite.lint.rules.TypeNamesPascalCaseVisitor;
import no.sikt.graphitron.rewrite.lint.rules.TypesAndFieldsHaveDescriptionsVisitor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The built-in lint rule registry (R398): every {@link LintRule.Source#ENGINE} rule paired with its
 * visitor, plus the explicit set of node kinds the engine deliberately does not lint. The engine
 * dispatches each node to the visitors subscribed to its kind. Adding a rule is appending to
 * {@link #builtIn()} and adding its enum constant, not editing a central switch.
 *
 * <p>{@link #NOT_LINTED} and {@link #subscribedKinds()} together must partition
 * {@link LintNodeKind} with no overlap and no gap. {@code LintRuleRegistryCoverageTest} asserts that,
 * mirroring the {@code VariantCoverageTest} / {@code EdgeCoverageTest} no-silent-default pattern.
 */
public final class LintRules {

    private LintRules() {}

    /** Node kinds encountered by the traversal but deliberately not linted in v1. */
    public static final Set<LintNodeKind> NOT_LINTED = Set.of(
        LintNodeKind.DIRECTIVE_DEFINITION,
        LintNodeKind.SCHEMA_DEFINITION,
        LintNodeKind.OBJECT_TYPE_EXTENSION,
        LintNodeKind.INTERFACE_TYPE_EXTENSION,
        LintNodeKind.UNION_TYPE_EXTENSION,
        LintNodeKind.ENUM_TYPE_EXTENSION,
        LintNodeKind.INPUT_OBJECT_TYPE_EXTENSION,
        LintNodeKind.SCALAR_TYPE_EXTENSION,
        LintNodeKind.SCHEMA_EXTENSION,
        LintNodeKind.APPLIED_DIRECTIVE_ARGUMENT);

    /** The built-in visitors, one per {@link LintRule.Source#ENGINE} rule. */
    public static List<LintVisitor> builtIn() {
        return List.of(
            new TypeNamesPascalCaseVisitor(),
            new FieldNamesCamelCaseVisitor(),
            new InputAndArgumentNamesCamelCaseVisitor(),
            new EnumValuesScreamingSnakeCaseVisitor(),
            new DeprecationsHaveAReasonVisitor(),
            new TypesAndFieldsHaveDescriptionsVisitor(),
            new InputObjectNameSuffixVisitor(),
            new NoDeprecatedDirectiveUsageVisitor(),
            new NoTypenamePrefixVisitor());
    }

    /** Union of the node kinds the built-in visitors subscribe to. */
    public static Set<LintNodeKind> subscribedKinds() {
        var kinds = EnumSet.noneOf(LintNodeKind.class);
        for (LintVisitor visitor : builtIn()) {
            kinds.addAll(visitor.kinds());
        }
        return kinds;
    }
}
