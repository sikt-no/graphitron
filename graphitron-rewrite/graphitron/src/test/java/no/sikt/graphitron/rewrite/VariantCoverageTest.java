package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Meta-test: every sealed leaf of {@link GraphitronField} and {@link GraphitronType}
 * must have at least one classification case whose {@link ClassificationCase#variants()}
 * includes it, or a documented entry in {@link #NO_CASE_REQUIRED}.
 *
 * <p>Phase 2 of {@code plan-variant-coverage-meta-test.md}. Complements
 * {@link GeneratorCoverageTest#everyGraphitronFieldLeafHasAKnownDispatchStatus} (generator
 * dispatch coverage) by asserting that classification itself is demonstrated for every leaf.
 */
@UnitTier
class VariantCoverageTest {

    /**
     * Leaves that legitimately need no classification test case. Each entry carries a
     * one-line reason. The goal is for this map to stay small — every schema-reachable
     * leaf should have a case showing the classifier lands there.
     */
    private static final Map<Class<?>, String> NO_CASE_REQUIRED = Map.of(
        GraphitronType.JooqRecordType.class,
            "No plain jOOQ Record<?> (non-TableRecord) fixture class in the test classpath; "
            + "add a case when a suitable fixture is introduced.",
        GraphitronType.JooqRecordInputType.class,
            "No plain jOOQ Record<?> (non-TableRecord) input fixture class in the test classpath; "
            + "add a case when a suitable fixture is introduced.",
        ChildField.ErrorsField.class,
            "Permit added in R12 (error-handling-parity) C2 alongside the ErrorChannel slot; "
            + "the classifier doesn't produce it until C3 lifts the five PolymorphicReturnType "
            + "rejection sites in FieldBuilder. Add a GraphitronSchemaBuilderTest case when "
            + "that lift lands.",
        ChildField.CompositeColumnField.class,
            "Covered by NodeIdPipelineTest.OutputCase (the composite-PK NodeId path requires the "
            + "nodeid fixture's `bar` table with two key columns, not available in the standard "
            + "sakila catalog); add a GraphitronSchemaBuilderTest case when a composite-PK "
            + "fixture is made available there.",
        ChildField.CompositeColumnReferenceField.class,
            "Composite-key NodeId reference (rooted-at-parent or non-FK-mirror): the only schema "
            + "shape that produces it is a child table whose FK references a parent NodeType "
            + "with multiple key columns AND the FK's target columns differ from those keys. The "
            + "standard sakila catalog has no such shape; add a GraphitronSchemaBuilderTest case "
            + "when a composite-PK rooted-at-parent fixture exists.",
        InputField.CompositeColumnField.class,
            "Covered by NodeIdPipelineTest.InputCase (composite-PK same-table NodeId filter, "
            + "arity > 1) and the synthesized-shim composite path. Lives in NodeIdPipelineTest "
            + "because the standard sakila catalog has no composite-PK NodeType.",
        InputField.CompositeColumnReferenceField.class,
            "Composite-key input reference (arity > 1 FK target). The canonical and synthesis-"
            + "shim cases land via the same buildInputNodeIdReference helper for the "
            + "[ID!] @nodeId(typeName: T) branch, but no test fixture exercises an arity > 1 FK "
            + "target yet (the fixtures use `bar` (composite PK) only for same-table NodeId "
            + "paths). Add a NodeIdPipelineTest case when a composite-PK FK target lands."
    );

    private static final List<Class<?>> ROOTS = List.of(
        GraphitronField.class, GraphitronType.class);

    @Test
    void everySealedLeafHasAClassificationCase() {
        var leaves = ROOTS.stream()
            .flatMap(r -> GeneratorCoverageTest.sealedLeaves(r).stream())
            .collect(toSet());
        var covered = allClassificationCases().stream()
            .flatMap(c -> c.variants().stream())
            .collect(toSet());
        var missing = leaves.stream()
            .filter(l -> !covered.contains(l))
            .filter(l -> !NO_CASE_REQUIRED.containsKey(l))
            .map(c -> c.getSimpleName())
            .sorted()
            .toList();
        assertThat(missing)
            .as("every sealed leaf must have at least one classification case "
                + "(or a documented entry in NO_CASE_REQUIRED)")
            .isEmpty();
    }

    @Test
    void allowlistEntriesAreStillLeaves() {
        var leaves = ROOTS.stream()
            .flatMap(r -> GeneratorCoverageTest.sealedLeaves(r).stream())
            .collect(toSet());
        var stale = NO_CASE_REQUIRED.keySet().stream()
            .filter(c -> !leaves.contains(c))
            .map(c -> c.getSimpleName())
            .sorted()
            .toList();
        assertThat(stale)
            .as("NO_CASE_REQUIRED must not contain stale (non-leaf) classes")
            .isEmpty();
    }

    /** All ClassificationCase constants across all enum types declared in GraphitronSchemaBuilderTest. */
    private static List<ClassificationCase> allClassificationCases() {
        return Arrays.stream(GraphitronSchemaBuilderTest.class.getDeclaredClasses())
            .filter(Class::isEnum)
            .filter(ClassificationCase.class::isAssignableFrom)
            .flatMap(c -> Arrays.stream(c.getEnumConstants()))
            .map(ClassificationCase.class::cast)
            .toList();
    }
}
