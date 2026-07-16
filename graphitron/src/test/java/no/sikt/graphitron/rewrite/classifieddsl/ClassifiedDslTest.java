package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus.Example;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-by-example corpus. Each fixture is an annotated schema; the harness classifies
 * it with today's classifier and checks every {@code @classified} / {@code @classifiedType} coordinate
 * against its declared dimensional verdict (read off the field model's {@code source()} /
 * {@code operation()} / {@code target()} accessors).
 *
 * <p>The meta-tests pin the coverage obligations from the Spec (§"Validating the axis set"), now over the
 * {@code (source, operation, target)} axes:
 * <ul>
 *   <li><b>Verdict totality</b> is compiler-enforced: the {@code source()} / {@code operation()} /
 *       {@code target()} producers on each root ({@code QueryField} / {@code MutationField} /
 *       {@code ChildField}) switch exhaustively over that root's sealed leaves, so a new leaf without a
 *       verdict fails the build. No runtime test can strengthen that, so none is written.</li>
 *   <li><b>Value exercise</b>, {@link #everyDimensionValueIsExercised()}: every {@link Source} wrapper
 *       arm, {@link Operation} arm, {@link no.sikt.graphitron.rewrite.model.Target} wrapper arm,
 *       {@link no.sikt.graphitron.rewrite.model.TargetShape} arm, and {@link SourceShape} value is either
 *       produced by some fixture or, for the modeled-but-unpopulated arms, on an explicit known-gap list
 *       with a stated reason.</li>
 *   <li><b>SDL-vs-Java mirrors</b>: the SDL {@code SourceWrapper} / {@code Operation} /
 *       {@code TargetWrapper} / {@code TargetShape} / {@code SourceShape} enums equal the sealed-arm sets
 *       the field model produces.</li>
 *   <li><b>TypeVerdict mirror</b>: the SDL {@code TypeVerdict} enum equals the non-failure
 *       {@code GraphitronType} leaf set. Its soundness, like every name-based mirror here, rests on the
 *       arm simple names being unique ({@link #sealedAxisLeafSimpleNamesAreUnique()}).</li>
 * </ul>
 */
@PipelineTier
class ClassifiedDslTest {

    static Stream<Example> corpus() {
        return ClassifiedCorpus.examples().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void corpusClassifiesToDeclaredDimensions(Example example) {
        var result = ClassifiedHarness.classify(example.sdl());

        assertThat(result.fields().isEmpty() && result.types().isEmpty())
            .as("fixture %s must annotate at least one coordinate (@classified field or "
                + "@classifiedType type); some coverage fixtures assert only a type verdict", example)
            .isFalse();

        for (var fc : result.fields()) {
            assertThat(fc.actual())
                .as("%s.%s classifies to its declared (source, operation, target)", fc.parentType(), fc.fieldName())
                .isEqualTo(fc.expected());
        }
        for (var tc : result.types()) {
            assertThat(tc.actualVerdict())
                .as("type %s classifies to its declared TypeVerdict", tc.typeName())
                .isEqualTo(tc.expectedVerdict());
        }
    }

    /**
     * Source wrapper arms the model declares but no fixture reaches, each with the reason. Empty since
 * The ancestor-product arrival fold makes {@link Source.OnlyChild} ({@code One} arrival)
     * reachable, so every source wrapper arm ({@code Query} / {@code Mutation} / {@code OnlyChild} /
     * {@code Child}) is now exercised by a corpus fixture. An arm that later becomes unreachable rejoins
     * this list with its reason; an entry a fixture exercises must be removed (asserted below).
     */
    private static final Map<String, String> SOURCE_KNOWN_GAPS = Map.of();

    /**
     * Operation arms the model declares but the current leaf set cannot populate, each with the reason no
     * fixture lands on it. Five are R222's model-completeness gaps (no classified leaf exists yet);
     * {@code Upsert} is the sixth shape, a leaf that exists but is upstream-rejected, following the
     * {@code VariantCoverageTest.NO_CASE_REQUIRED} precedent for {@code MutationUpsertTableField}. An arm
     * leaves this list the moment a fixture exercises it; an unexercised arm not listed here fails
     * {@link #everyDimensionValueIsExercised()}.
     */
    private static final Map<Class<? extends Operation>, String> OPERATION_KNOWN_GAPS = Map.of(
        Operation.EntityResolve.class, "Federation _entities is not a classified leaf yet (separate item).",
        Operation.Count.class, "Connection totalCount is generator-only emit behind the ConnectionType quarantine.",
        Operation.Facet.class, "Connection facets are generator-only emit behind the ConnectionType quarantine.",
        Operation.UpdateMatching.class, "Condition-matched UPDATE is unimplemented.",
        Operation.DeleteMatching.class, "Condition-matched DELETE is unimplemented.",
        Operation.Upsert.class, "R144 retires UPSERT generation pending R145; the classifier rejects every "
            + "UPSERT mutation at MutationInputResolver, so no schema-reachable fixture lands on it "
            + "(mirrors VariantCoverageTest.NO_CASE_REQUIRED for MutationUpsertTableField).");

    @Test
    void everyDimensionValueIsExercised() {
        var sourceArms = new HashSet<String>();
        var sourceShapes = EnumSet.noneOf(SourceShape.class);
        var operations = new HashSet<String>();
        var targetWrappers = new HashSet<String>();
        var targetShapes = new HashSet<String>();

        for (var example : ClassifiedCorpus.examples()) {
            for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                Source source = fc.actual().source();
                sourceArms.add(source.getClass().getSimpleName());
                switch (source) {
                    case Source.OnlyChild(var shape) -> sourceShapes.add(shape);
                    case Source.Child(var shape) -> sourceShapes.add(shape);
                    case Source.Root ignored -> { }
                }
                operations.add(fc.actual().operation().getSimpleName());
                targetWrappers.add(fc.actual().target().wrapper().getSimpleName());
                targetShapes.add(fc.actual().target().shape().getSimpleName());
            }
        }

        // Target wrapper and shape arms are fully exercised (no declared gaps).
        assertThat(targetWrappers)
            .as("every Target wrapper arm must be exercised by the corpus")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetWrapperArmSimpleNames());
        assertThat(targetShapes)
            .as("every TargetShape arm must be exercised by the corpus")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetShapeArmSimpleNames());

        // Both source shapes are exercised on the nested (Child / OnlyChild) arms.
        assertThat(sourceShapes)
            .as("both source-shape values must be exercised by nested-source rows")
            .containsExactlyInAnyOrder(SourceShape.values());

        // Source wrapper arms: every arm exercised or on the known-gap list.
        var unexercisedSource = new HashSet<>(ClassifiedHarness.sourceWrapperArmSimpleNames());
        unexercisedSource.removeAll(sourceArms);
        unexercisedSource.removeAll(SOURCE_KNOWN_GAPS.keySet());
        assertThat(unexercisedSource)
            .as("every source wrapper arm must be exercised by a fixture or listed in SOURCE_KNOWN_GAPS "
                + "with a stated reason; these are neither")
            .isEmpty();
        assertThat(SOURCE_KNOWN_GAPS.keySet())
            .as("a known-gap source arm that a fixture now exercises must be removed from SOURCE_KNOWN_GAPS")
            .doesNotContainAnyElementsOf(sourceArms);

        // Operation arms: every arm exercised or on the known-gap list.
        Set<String> operationGapNames = OPERATION_KNOWN_GAPS.keySet().stream()
            .map(Class::getSimpleName).collect(Collectors.toSet());
        var unexercisedOps = new HashSet<>(ClassifiedHarness.operationArmSimpleNames());
        unexercisedOps.removeAll(operations);
        unexercisedOps.removeAll(operationGapNames);
        assertThat(unexercisedOps)
            .as("every Operation arm must be exercised by a fixture or listed in OPERATION_KNOWN_GAPS "
                + "with a stated reason; these are neither")
            .isEmpty();
        assertThat(operationGapNames)
            .as("a known-gap operation that a fixture now exercises must be removed from OPERATION_KNOWN_GAPS")
            .doesNotContainAnyElementsOf(operations);
    }

    @Test
    void sourceWrapperMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.sourceWrapperEnumConstants())
            .as("the SDL SourceWrapper enum must mirror the sealed Source leaf arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.sourceWrapperArmSimpleNames());
    }

    @Test
    void operationMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.operationEnumConstants())
            .as("the SDL Operation enum must mirror the sealed Operation arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.operationArmSimpleNames());
    }

    @Test
    void targetWrapperMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.targetWrapperEnumConstants())
            .as("the SDL TargetWrapper enum must mirror the sealed Target wrapper arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetWrapperArmSimpleNames());
    }

    @Test
    void targetShapeMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.targetShapeEnumConstants())
            .as("the SDL TargetShape enum must mirror the sealed TargetShape arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetShapeArmSimpleNames());
    }

    @Test
    void sourceShapeMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.sourceShapeEnumConstants())
            .as("the SDL SourceShape enum must mirror the Java SourceShape value set; "
                + "adding a value to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(enumNames(SourceShape.values()));
    }

    private static <E extends Enum<E>> Set<String> enumNames(E[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    void typeVerdictMirrorsGraphitronTypeLeaves() {
        assertThat(ClassifiedHarness.typeVerdictEnumConstants())
            .as("the SDL TypeVerdict enum must mirror GraphitronType's non-failure sealed leaves; "
                + "adding a type leaf without a matching TypeVerdict constant (or vice versa) fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.graphitronTypeNonFailureLeafNames());
    }

    @Test
    void sealedAxisLeafSimpleNamesAreUnique() {
        // Every name-based mirror above compares the SDL enum against sealed-leaf simple names, so a future
        // nested leaf reusing a name within one seal would silently conflate two leaves and let the mirror
        // pass while a real leaf goes unmirrored. (Table / Record / Interface reused *across* SourceShape
        // and TargetShape is safe: the two seals are never folded into one name set.)
        assertThat(ClassifiedHarness.sourceWrapperArmSimpleNames()).as("Source arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.operationArmSimpleNames()).as("Operation arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.targetWrapperArmSimpleNames()).as("Target wrapper arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.targetShapeArmSimpleNames()).as("TargetShape arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.graphitronTypeNonFailureLeafSimpleNames())
            .as("GraphitronType's sealed leaves must have unique simple names: the TypeVerdict mirror "
                + "compares by simple name, so a future nested leaf reusing a name would silently conflate two")
            .doesNotHaveDuplicates();
    }
}
