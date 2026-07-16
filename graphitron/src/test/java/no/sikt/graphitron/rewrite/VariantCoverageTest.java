package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Meta-test: every sealed leaf of {@link GraphitronField} and {@link GraphitronType}
 * must have at least one classification case whose {@link ClassificationCase#variants()}
 * includes it, <em>or</em> a leaf the spec-by-example corpus demonstrates
 * ({@link ClassifiedCorpus#coveredLeaves()}), or a documented entry in {@link #NO_CASE_REQUIRED}.
 *
 * <p>Phase 2 of {@code plan-variant-coverage-meta-test.md}. Complements
 * {@link GeneratorCoverageTest#everyGraphitronFieldLeafHasAKnownDispatchStatus} (generator
 * dispatch coverage) by asserting that classification itself is demonstrated for every leaf.
 *
 * <p>Coverage is split by who owns the verdict truth, retiring the earlier union net:
 *
 * <ul>
 *   <li><b>Output-field and type leaves</b> ({@link OutputField} leaves and every non-failure
 *       {@link GraphitronType} leaf) are owned by the spec-by-example corpus as the
 *       <em>single source of truth</em>; {@link #everyOutputFieldAndTypeLeafIsDemonstratedByTheCorpus()}
 *       requires each to be demonstrated by a {@link ClassifiedCorpus} fixture (the corpus is classified
 *       and its per-coordinate leaves collected), not by an enum row. This is the milestone:
 *       full successful output-field and type corpus coverage.</li>
 *   <li><b>Input-field leaves</b> ({@link InputField}) stay covered by the
 *       {@code GraphitronSchemaBuilderTest} enum truth table, their own game and out of scope for the
 *       corpus; {@link #everyInputFieldLeafHasAnEnumClassificationCase()} keeps that obligation.</li>
 *   <li>The failure leaves ({@code UnclassifiedField} / {@code UnclassifiedType}) are out of scope for
 *       both: the corpus asserts successful classification only, and the failure path gets a separate
 *       mechanism later.</li>
 * </ul>
 *
 * <p>{@link #NO_CASE_REQUIRED} remains the documented escape hatch shared by both obligations.
 */
@PipelineTier
class VariantCoverageTest {

    /**
     * Leaves that legitimately need no classification test case. Each entry carries a
     * one-line reason. The goal is for this map to stay small — every schema-reachable
     * leaf should have a case showing the classifier lands there.
     */
    private static final Map<Class<?>, String> NO_CASE_REQUIRED = Map.ofEntries(
        Map.entry(no.sikt.graphitron.rewrite.model.MutationField.MutationUpsertTableField.class,
            "R144 retires UPSERT generation pending R145 (mutation-cardinality-safety-upsert); "
            + "the classifier rejects every UPSERT mutation at MutationInputResolver, so no "
            + "schema-reachable case lands on this leaf. Add a fresh case when R145 lifts the "
            + "upstream rejection."),
        Map.entry(no.sikt.graphitron.rewrite.model.MutationField.MutationDeletePayloadField.class,
            "R287: a payload-returning DELETE's only admissible data field is an ID-element (the "
            + "@table-element projection is rejected — the row is gone, RETURNING carries only the "
            + "PK), which requires the synthesised __NODE_TYPE_ID metadata absent from the corpus "
            + "catalog. Covered by MutationDmlNodeIdClassificationTest under the nodeidfixture."),
        Map.entry(no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDeletePayloadField.class,
            "R287: bulk sibling of MutationDeletePayloadField — same ID-element-only constraint and "
            + "same nodeidfixture coverage (MutationDmlNodeIdClassificationTest)."),
        Map.entry(GraphitronType.FacetsType.class,
            "R13: synthesised-only (the @asFacet expansion on a directive-driven @asConnection "
            + "carrier); no SDL declaration exists to carry @classifiedType, so the corpus cannot "
            + "demonstrate it (the corpus's connection example uses the structural form, which "
            + "facets have no analogue of). Covered by FacetedConnectionPipelineTest and "
            + "ConnectionPromoterTest."),
        Map.entry(GraphitronType.FacetValueType.class,
            "R13: synthesised-only sibling of FacetsType (one reusable entry per (scalar, "
            + "nullability) pair); same no-SDL-declaration constraint. Covered by "
            + "FacetedConnectionPipelineTest and ConnectionPromoterTest."),
        Map.entry(GraphitronType.JooqRecordType.class,
            "No plain jOOQ Record<?> (non-TableRecord) fixture class in the test classpath; "
            + "add a corpus example when a suitable fixture is introduced."),
        Map.entry(GraphitronType.JooqRecordInputType.class,
            "No plain jOOQ Record<?> (non-TableRecord) input fixture class in the test classpath; "
            + "add a corpus example when a suitable fixture is introduced."),
        Map.entry(ChildField.ErrorsField.class,
            "Permit added in R12 (error-handling-parity) C2 alongside the ErrorChannel slot; "
            + "the classifier doesn't produce it until C3 lifts the five PolymorphicReturnType "
            + "rejection sites in FieldBuilder. Add a corpus example when that lift lands."),
        Map.entry(ChildField.CompositeColumnField.class,
            "Covered by NodeIdPipelineTest.OutputCase (the composite-PK NodeId path requires the "
            + "nodeid fixture's `bar` table with two key columns, not available in the standard "
            + "sakila catalog); add a GraphitronSchemaBuilderTest case when a composite-PK "
            + "fixture is made available there."),
        Map.entry(ChildField.CompositeColumnReferenceField.class,
            "Composite-key NodeId reference (rooted-at-parent or non-FK-mirror): the only schema "
            + "shape that produces it is a child table whose FK references a parent NodeType "
            + "with multiple key columns AND the FK's target columns differ from those keys. The "
            + "standard sakila catalog has no such shape; add a GraphitronSchemaBuilderTest case "
            + "when a composite-PK rooted-at-parent fixture exists."),
        Map.entry(InputField.CompositeColumnField.class,
            "Covered by NodeIdPipelineTest.InputCase (composite-PK same-table NodeId filter, "
            + "arity > 1) and the synthesized-shim composite path. Lives in NodeIdPipelineTest "
            + "because the standard sakila catalog has no composite-PK NodeType."),
        Map.entry(InputField.CompositeColumnReferenceField.class,
            "Composite-key input reference (arity > 1 FK target). The canonical and synthesis-"
            + "shim cases land via the same buildInputNodeIdReference helper for the "
            + "[ID!] @nodeId(typeName: T) branch, but no test fixture exercises an arity > 1 FK "
            + "target yet (the fixtures use `bar` (composite PK) only for same-table NodeId "
            + "paths). Add a NodeIdPipelineTest case when a composite-PK FK target lands."),
        Map.entry(ChildField.SingleRecordIdFieldFromReturning.class,
            "R156: produced by the @mutation classifier for @mutation(typeName: DELETE) carriers "
            + "with an ID-typed data field. Covered structurally by the four "
            + "MutationDmlNodeIdClassificationTest admission cells (bulk/single × implicit/"
            + "explicit @nodeId on the nodeidfixture catalog) and end-to-end by "
            + "DmlBulkMutationsExecutionTest#deleteFilmsIdCarrier_returnsEncodedNodeIdsOfDeletedRows. "
            + "The corpus runs against the default Sakila catalog (no synthesized "
            + "__NODE_TYPE_ID metadata), so the Id admission case lives in the pipeline-tier "
            + "test that can swap to the nodeidfixture RewriteContext."),
        Map.entry(InputField.UnboundField.class,
            "R215: input field with no column binding. Covers @condition(override: true) "
            + "with or without a matching column (the §5 collapse of R210's ConditionOnlyField "
            + "plus ColumnField + override:true), and the cascade-admitted bare-field case where "
            + "the consumer's enclosing @condition(override: true) suppresses the implicit "
            + "predicate. Covered by GraphitronSchemaBuilderTest's "
            + "plainInput_overrideTrueWithoutMatchingColumn_classifiesAsUnboundField "
            + "and tableInput_overrideTrueWithoutMatchingColumn_classifiesAsUnboundField "
            + "@Test methods (one carries @ProjectionFor(UnboundField.class)), which "
            + "land outside the enum-style ClassificationCase shape this coverage walker reads.")
    );

    private static final List<Class<?>> ROOTS = List.of(
        GraphitronField.class, GraphitronType.class);

    /**
     * The leaves the corpus owns as single source of truth: every {@link OutputField} leaf and every
     * {@link GraphitronType} leaf except the failure leaf {@code UnclassifiedType}. Input-field leaves
     * and {@code UnclassifiedField} are deliberately excluded (see the class Javadoc).
     */
    private static Set<Class<?>> corpusOwnedLeaves() {
        var leaves = new HashSet<>(GeneratorCoverageTest.sealedLeaves(OutputField.class));
        GeneratorCoverageTest.sealedLeaves(GraphitronType.class).stream()
            .filter(l -> l != GraphitronType.UnclassifiedType.class)
            .forEach(leaves::add);
        return leaves;
    }

    @Test
    void everyOutputFieldAndTypeLeafIsDemonstratedByTheCorpus() {
        var covered = ClassifiedCorpus.coveredLeaves();
        var missing = corpusOwnedLeaves().stream()
            .filter(l -> !covered.contains(l))
            .filter(l -> !NO_CASE_REQUIRED.containsKey(l))
            .map(Class::getSimpleName)
            .sorted()
            .toList();
        assertThat(missing)
            .as("every output-field and type leaf must be demonstrated by a ClassifiedCorpus fixture "
                + "(the corpus is the single source of truth for these verdicts), or carry a documented "
                + "entry in NO_CASE_REQUIRED")
            .isEmpty();
    }

    @Test
    void everyInputFieldLeafHasAnEnumClassificationCase() {
        var covered = new HashSet<Class<?>>();
        allClassificationCases().stream()
            .flatMap(c -> c.variants().stream())
            .forEach(covered::add);
        var missing = GeneratorCoverageTest.sealedLeaves(InputField.class).stream()
            .filter(l -> !covered.contains(l))
            .filter(l -> !NO_CASE_REQUIRED.containsKey(l))
            .map(Class::getSimpleName)
            .sorted()
            .toList();
        assertThat(missing)
            .as("every input-field leaf must have at least one GraphitronSchemaBuilderTest "
                + "classification case (input-side classification stays on the enum truth table), "
                + "or a documented entry in NO_CASE_REQUIRED")
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
