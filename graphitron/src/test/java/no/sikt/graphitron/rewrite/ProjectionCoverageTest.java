package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.ProjectionFor;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-prevention meta-test that pairs with
 * {@link CatalogBuilder#projectFieldClassification}'s compile-time exhaustive switch. The
 * switch enforces "every leaf has a projection arm"; this meta-test enforces "every leaf
 * has a payload-asserting test under {@link GraphitronSchemaBuilderTest}", so a future
 * contributor adding a new sealed leaf cannot land it without an accompanying sibling
 * projection assertion (or a documented exception in {@link #NO_PROJECTION_REQUIRED}).
 *
 * <p>Sibling to {@link no.sikt.graphitron.rewrite.VariantCoverageTest} on the classifier
 * side; that one walks {@link ClassificationCase#variants()}, this one walks methods
 * annotated {@link ProjectionFor}.
 */
@UnitTier
class ProjectionCoverageTest {

    /**
     * Leaves that legitimately need no projection-asserting test. Each entry carries a
     * one-line reason. The goal is for this map to stay small; the allowlist mostly
     * mirrors {@code VariantCoverageTest.NO_CASE_REQUIRED} (leaves with no
     * schema-reachable case under the default sakila catalog) plus a few input-side
     * type permits whose model leaves classify the parent type but never produce a
     * standalone snapshot entry the projection arm would assert on.
     */
    private static final Map<Class<?>, String> NO_PROJECTION_REQUIRED = Map.ofEntries(
        Map.entry(MutationField.MutationUpsertTableField.class,
            "R144 retires UPSERT generation pending R145; classifier rejects all UPSERT "
            + "mutations, so no schema-reachable fixture lands on this leaf. Mirrors "
            + "VariantCoverageTest's allowlist."),
        Map.entry(GraphitronType.JooqRecordType.class,
            "No plain jOOQ Record<?> (non-TableRecord) fixture class in the test classpath. "
            + "Mirrors VariantCoverageTest's allowlist."),
        Map.entry(GraphitronType.JooqRecordInputType.class,
            "No plain jOOQ Record<?> (non-TableRecord) input fixture class in the test "
            + "classpath. Mirrors VariantCoverageTest's allowlist."),
        Map.entry(GraphitronType.JavaRecordInputType.class,
            "Input-side Java record fixture lands via the same TestRecordDto class as "
            + "JavaRecordType; the projector arm covers both sides, but no @input-only "
            + "fixture exercises the input-permit in isolation under the default catalog."),
        Map.entry(GraphitronType.JooqTableRecordInputType.class,
            "Input-side jOOQ TableRecord fixture; covered structurally by the codegen "
            + "tier but not as a standalone classification-test fixture under the default "
            + "catalog."),
        Map.entry(ChildField.ErrorsField.class,
            "Permit added in R12 (error-handling-parity) C2 alongside the ErrorChannel "
            + "slot; classifier doesn't produce it until C3 lifts the five "
            + "PolymorphicReturnType rejection sites. Mirrors VariantCoverageTest."),
        Map.entry(ChildField.CompositeColumnField.class,
            "Covered by NodeIdPipelineTest.OutputCase (composite-PK NodeId path requires "
            + "the nodeid fixture's `bar` table). Mirrors VariantCoverageTest."),
        Map.entry(ChildField.CompositeColumnReferenceField.class,
            "Composite-key NodeId reference; the only schema shape that produces it needs "
            + "a composite-PK rooted-at-parent fixture not available under the default "
            + "sakila catalog. Mirrors VariantCoverageTest."),
        Map.entry(InputField.CompositeColumnField.class,
            "Covered by NodeIdPipelineTest.InputCase (composite-PK same-table NodeId "
            + "filter). Mirrors VariantCoverageTest."),
        Map.entry(InputField.CompositeColumnReferenceField.class,
            "Composite-key input reference; arity > 1 FK target case not exercised "
            + "in pipeline-tier fixtures yet. Mirrors VariantCoverageTest."),
        Map.entry(ChildField.SingleRecordIdFieldFromReturning.class,
            "R156: DELETE carrier with ID-typed data field; the GraphitronSchemaBuilderTest "
            + "classification enum runs against the default sakila catalog which has no "
            + "synthesised __NODE_TYPE_ID metadata, so this case lives in "
            + "MutationDmlNodeIdClassificationTest under the nodeidfixture catalog. "
            + "Mirrors VariantCoverageTest.")
    );

    private static final List<Class<?>> ROOTS = List.of(
        GraphitronField.class, GraphitronType.class);

    @Test
    void everySealedLeafHasAProjectionAssertion() {
        var leaves = ROOTS.stream()
            .flatMap(r -> GeneratorCoverageTest.sealedLeaves(r).stream())
            .collect(toSet());
        var covered = projectionCoverage();
        var missing = leaves.stream()
            .filter(l -> !covered.contains(l))
            .filter(l -> !NO_PROJECTION_REQUIRED.containsKey(l))
            .map(Class::getSimpleName)
            .sorted()
            .toList();
        assertThat(missing)
            .as("every sealed leaf must have at least one @ProjectionFor-annotated test in "
                + "GraphitronSchemaBuilderTest (or a documented entry in NO_PROJECTION_REQUIRED)")
            .isEmpty();
    }

    @Test
    void allowlistEntriesAreStillLeaves() {
        var leaves = ROOTS.stream()
            .flatMap(r -> GeneratorCoverageTest.sealedLeaves(r).stream())
            .collect(toSet());
        var stale = NO_PROJECTION_REQUIRED.keySet().stream()
            .filter(c -> !leaves.contains(c))
            .map(Class::getSimpleName)
            .sorted()
            .toList();
        assertThat(stale)
            .as("NO_PROJECTION_REQUIRED must not contain stale (non-leaf) classes")
            .isEmpty();
    }

    /** All sealed-leaf classes named by any {@link ProjectionFor} annotation in the test file. */
    private static Set<Class<?>> projectionCoverage() {
        var covered = new LinkedHashSet<Class<?>>();
        for (var method : GraphitronSchemaBuilderTest.class.getDeclaredMethods()) {
            ProjectionFor pf = method.getAnnotation(ProjectionFor.class);
            if (pf != null) {
                covered.addAll(Arrays.asList(pf.value()));
            }
        }
        return covered;
    }
}
