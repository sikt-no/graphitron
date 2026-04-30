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

/**
 * Meta-test: every sealed leaf of {@link GraphitronField} and {@link GraphitronType}
 * must have at least one classification case whose {@link ClassificationCase#variants()}
 * includes it, or a documented entry in {@link #NO_CASE_REQUIRED}.
 *
 * <p>Phase 2 of {@code plan-variant-coverage-meta-test.md}. Complements
 * {@link GeneratorCoverageTest#everyGraphitronFieldLeafHasAKnownDispatchStatus} (generator
 * dispatch coverage) by asserting that classification itself is demonstrated for every leaf.
 */
class VariantCoverageTest {

    /**
     * Leaves that legitimately need no classification test case. Each entry carries a
     * one-line reason. The goal is for this map to stay small — every schema-reachable
     * leaf should have a case showing the classifier lands there.
     */
    private static final Map<Class<?>, String> NO_CASE_REQUIRED = Map.of(
        InputField.NodeIdField.class,
            "Covered by NodeIdPipelineTest.InputCase (requires nodeid fixture with "
            + "__NODE_TYPE_ID/__NODE_KEY_COLUMNS metadata not available in the standard sakila catalog); "
            + "add a GraphitronSchemaBuilderTest case when the nodeid fixture is made available there.",
        InputField.NodeIdReferenceField.class,
            "Covered by NodeIdPipelineTest.InputReferenceCase (requires nodeid fixture with "
            + "FK-linked NodeType table not available in the standard sakila catalog); "
            + "add a GraphitronSchemaBuilderTest case when the nodeid fixture is made available there.",
        InputField.NodeIdInFilterField.class,
            "Covered by NodeIdPipelineTest.InputSameTableNodeIdCase (requires nodeid fixture with "
            + "__NODE_TYPE_ID/__NODE_KEY_COLUMNS metadata not available in the standard sakila catalog); "
            + "add a GraphitronSchemaBuilderTest case when the nodeid fixture is made available there.",
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
            + "standard sakila catalog has no such shape; the rooted-at-parent fixture lands in "
            + "phase (g) of R50. Add a GraphitronSchemaBuilderTest case when that fixture exists."
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
