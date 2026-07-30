package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.ExemptionRegistry;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus.Example;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-by-example corpus. Each fixture is an annotated schema; the harness classifies
 * it with today's classifier and checks every {@code @classified} / {@code @classifiedType} coordinate
 * against its declared dimensional verdict (read off the field model's {@code source()} /
 * {@code operation()} / {@code target()} accessors).
 *
 * <p>The meta-tests pin the coverage obligations over the {@code (source, operation, target)} axes:
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
        for (var sc : result.synthesises()) {
            assertThat(sc.produced())
                .as("%s.%s mints exactly its declared synthesis set (declared vs the "
                    + "connection-synthesis relation's produced row)", sc.parentType(), sc.fieldName())
                .isEqualTo(sc.declared());
        }
    }

    /**
     * Source wrapper arms the model declares but no fixture reaches, each with the reason. Empty:
     * every source wrapper arm ({@code Query} / {@code Mutation} / {@code OnlyChild} /
     * {@code Child}) is exercised by a corpus fixture. An arm that becomes unreachable rejoins
     * this list with its reason; an entry a fixture exercises must be removed (asserted below).
     */
    private static final Map<String, String> SOURCE_KNOWN_GAPS = Map.of();

    /**
     * One corpus coordinate's classified position on the five axes; {@code sourceShape} is null
     * on Root rows (the shape exists only on the nested source arms). Extracted once and shared
     * by {@link #everyDimensionValueIsExercised()} and {@link #axisPairCensusIsDerivable()} so
     * the two instruments cannot drift on what an axis value is.
     */
    private record CoordinateAxes(
        String source, SourceShape sourceShape, String operation, String targetWrapper, String targetShape) {}

    private static List<CoordinateAxes> corpusAxes;

    private static List<CoordinateAxes> corpusAxes() {
        if (corpusAxes == null) {
            var rows = new ArrayList<CoordinateAxes>();
            for (var example : ClassifiedCorpus.examples()) {
                for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                    Source source = fc.actual().source();
                    SourceShape shape = switch (source) {
                        case Source.OnlyChild(var s) -> s;
                        case Source.Child(var s) -> s;
                        case Source.Root ignored -> null;
                    };
                    rows.add(new CoordinateAxes(
                        source.getClass().getSimpleName(),
                        shape,
                        fc.actual().operation().getSimpleName(),
                        fc.actual().target().wrapper().getSimpleName(),
                        fc.actual().target().shape().getSimpleName()));
                }
            }
            corpusAxes = List.copyOf(rows);
        }
        return corpusAxes;
    }

    @Test
    void everyDimensionValueIsExercised() {
        var sourceArms = new HashSet<String>();
        var sourceShapes = EnumSet.noneOf(SourceShape.class);
        var targetWrappers = new HashSet<String>();
        var targetShapes = new HashSet<String>();

        for (var row : corpusAxes()) {
            sourceArms.add(row.source());
            if (row.sourceShape() != null) {
                sourceShapes.add(row.sourceShape());
            }
            targetWrappers.add(row.targetWrapper());
            targetShapes.add(row.targetShape());
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

        // Operation arms: every arm exercised or exempt with a typed reason. The known-gap map
        // (ExemptionRegistry.OPERATION_KNOWN_GAPS) is one registry obligation; the shared
        // assertion carries the exercised-must-be-removed ratchet this test used to state inline.
        ExemptionRegistry.assertHonoured(ExemptionRegistry.OPERATION_ARMS);
    }

    /**
     * The axis-pair census: for each pair of classification axes, is the cross product of the
     * corpus-observed values populated, or only a diagonal? A populated product is measured
     * independence, so the families must separate; a diagonal is measured co-variation, so they
     * stay fused and the machinery is saved. The census turns "which families are real" from a
     * judgment call into a measurement, and its consumers are the grain worklist and the
     * split-on-measured-independence rule.
     *
     * <p>Stated so the instrument is not mistaken for a different one: this measures co-variation
     * between classification axes at the coordinate grain. It cannot falsify the projection
     * command's contribution-arm split, which is decided by counted downstream consumers of the
     * distinction, not by provenance and not by this census.
     *
     * <p>Mechanics: denominators use corpus-observed values only, so a known-gap arm (never
     * observed) cannot inflate a product. Pairs involving the source shape exclude Root rows,
     * where no shape exists; source-wrapper-by-source-shape is skipped outright because the
     * shape is a component of the nested wrapper arms, a containment rather than two independent
     * axes. The assertion is non-vacuity; the measured matrix prints to the test output as the
     * re-derivable figure.
     */
    @Test
    void axisPairCensusIsDerivable() {
        Map<String, Function<CoordinateAxes, String>> axes = new LinkedHashMap<>();
        axes.put("source", CoordinateAxes::source);
        axes.put("sourceShape", row -> row.sourceShape() == null ? null : row.sourceShape().name());
        axes.put("operation", CoordinateAxes::operation);
        axes.put("targetWrapper", CoordinateAxes::targetWrapper);
        axes.put("targetShape", CoordinateAxes::targetShape);

        var rows = corpusAxes();
        assertThat(rows.size()).as("corpus coordinates measured (census must not be vacuous)")
            .isGreaterThan(50);

        List<String> names = List.copyOf(axes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (names.get(i).equals("source") && names.get(j).equals("sourceShape")) {
                    continue; // containment, not a pair of independent axes
                }
                var first = axes.get(names.get(i));
                var second = axes.get(names.get(j));
                var firstValues = new TreeSet<String>();
                var secondValues = new TreeSet<String>();
                var observedPairs = new TreeSet<String>();
                for (var row : rows) {
                    String a = first.apply(row);
                    String b = second.apply(row);
                    if (a == null || b == null) continue;
                    firstValues.add(a);
                    secondValues.add(b);
                    observedPairs.add(a + "*" + b);
                }
                assertThat(observedPairs)
                    .as("axis pair %s x %s must be observable over the corpus", names.get(i), names.get(j))
                    .isNotEmpty();
                var missing = new ArrayList<String>();
                for (String a : firstValues) {
                    for (String b : secondValues) {
                        if (!observedPairs.contains(a + "*" + b)) missing.add(a + "*" + b);
                    }
                }
                System.out.printf("PAIR %s x %s: observed %d of %d (%d x %d), missing %s%n",
                    names.get(i), names.get(j), observedPairs.size(),
                    firstValues.size() * secondValues.size(),
                    firstValues.size(), secondValues.size(), missing);
            }
        }
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
    void synthesisedTypeMirrorsTheRelationsMintedArmVocabulary() {
        assertThat(ClassifiedHarness.synthesisedTypeEnumConstants())
            .as("the SDL SynthesisedType enum must mirror the connection-synthesis relation's "
                + "declared minted-arm vocabulary; widening one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(
                no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MINTED_ARM_VOCABULARY.stream()
                    .map(Class::getSimpleName)
                    .collect(Collectors.toSet()));
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
