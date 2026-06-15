package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus.Example;
import no.sikt.graphitron.rewrite.model.Carrier;
import no.sikt.graphitron.rewrite.model.Intent;
import no.sikt.graphitron.rewrite.model.Mapping;
import no.sikt.graphitron.rewrite.model.SourceCardinality;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R281 slice 1: the spec-by-example corpus running alongside the legacy enum truth table
 * ({@code GraphitronSchemaBuilderTest}). Each fixture is an annotated schema; the harness classifies
 * it with today's classifier and checks every {@code @classified} / {@code @classifiedType}
 * coordinate against its declared dimensional verdict (read off the field model's
 * {@code carrier()} / {@code intent()} / {@code mapping()} accessors).
 *
 * <p>Slice 1's primary deliverable is the <em>validated value sets</em>, not full leaf coverage. The
 * meta-tests here pin three of the four coverage obligations from the Spec
 * (§"Validating the axis set"):
 * <ul>
 *   <li><b>Verdict totality</b> is compiler-enforced: the {@code intent()} / {@code mapping()}
 *       accessors on each carrier root ({@code QueryField} / {@code MutationField} / {@code ChildField})
 *       switch exhaustively over that carrier's sealed leaves, so a new leaf without a verdict fails
 *       the build. No runtime test can strengthen that, so none is written.</li>
 *   <li><b>Value exercise</b>, {@link #everyDimensionValueIsExercised()}: every {@link Carrier},
 *       {@link Intent}, and {@link Mapping} value is either produced by some fixture or, for the
 *       modeled-but-unpopulated intents, on an explicit known-gap list with a stated reason.</li>
 *   <li><b>Carrier / Intent mirror</b>, {@link #carrierMirrorsAdapterValues()} /
 *       {@link #intentMirrorsAdapterValues()}: the SDL {@code Carrier} / {@code Intent} enums equal
 *       the Java {@link Carrier} / {@link Intent} value sets the field model produces.</li>
 *   <li><b>TypeVerdict mirror</b>, {@link #typeVerdictMirrorsGraphitronTypeLeaves()}: the SDL
 *       {@code TypeVerdict} enum equals the non-failure {@code GraphitronType} leaf set. Its
 *       soundness rests on {@link #graphitronTypeLeafSimpleNamesAreUnique()}, since the mirror
 *       compares by simple name.</li>
 * </ul>
 * Per-leaf corpus coverage (the fourth obligation) is slice 3's sweep, not slice 1's.
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
                + "@classifiedType type); some slice-3 coverage fixtures assert only a type verdict", example)
            .isFalse();

        for (var fc : result.fields()) {
            assertThat(fc.actual())
                .as("%s.%s classifies to its declared (carrier, intent, mapping)", fc.parentType(), fc.fieldName())
                .isEqualTo(fc.expected());
        }
        for (var tc : result.types()) {
            assertThat(tc.actualVerdict())
                .as("type %s classifies to its declared TypeVerdict", tc.typeName())
                .isEqualTo(tc.expectedVerdict());
        }
    }

    /**
     * Intents the model declares but the current leaf set cannot populate, each with the reason no
     * fixture lands on it. Five are R222's model-completeness gaps (no classified leaf exists yet);
     * {@code Upsert} is the sixth shape, a leaf that exists but is upstream-rejected, following the
     * {@code VariantCoverageTest.NO_CASE_REQUIRED} precedent for {@code MutationUpsertTableField}. A
     * value leaves this list the moment a fixture exercises it; an unexercised value not listed here
     * fails {@link #everyDimensionValueIsExercised()}.
     */
    private static final Map<Intent, String> INTENT_KNOWN_GAPS = Map.of(
        Intent.EntityResolve, "Federation _entities is not a classified leaf yet (separate item).",
        Intent.Count, "Connection totalCount is generator-only emit behind the ConnectionType quarantine.",
        Intent.Facet, "Connection facets are generator-only emit behind the ConnectionType quarantine.",
        Intent.UpdateMatching, "Condition-matched UPDATE is unimplemented.",
        Intent.DeleteMatching, "Condition-matched DELETE is unimplemented.",
        Intent.Upsert, "R144 retires UPSERT generation pending R145; the classifier rejects every "
            + "UPSERT mutation at MutationInputResolver, so no schema-reachable fixture lands on it "
            + "(mirrors VariantCoverageTest.NO_CASE_REQUIRED for MutationUpsertTableField).");

    @Test
    void everyDimensionValueIsExercised() {
        var carrierArms = new java.util.HashSet<String>();
        var intents = EnumSet.noneOf(Intent.class);
        var mappings = EnumSet.noneOf(Mapping.class);
        var sourceShapes = EnumSet.noneOf(SourceShape.class);
        var sourceCardinalities = EnumSet.noneOf(SourceCardinality.class);

        for (var example : ClassifiedCorpus.examples()) {
            for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                Carrier carrier = fc.actual().carrier();
                carrierArms.add(carrier.getClass().getSimpleName());
                if (carrier instanceof Carrier.Source source) {
                    sourceShapes.add(source.shape());
                    sourceCardinalities.add(source.cardinality());
                }
                intents.add(fc.actual().intent());
                mappings.add(fc.actual().mapping());
            }
        }

        assertThat(carrierArms)
            .as("every Carrier arm must be exercised by the corpus")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.carrierArmNames());
        assertThat(mappings)
            .as("every Mapping value must be exercised by the corpus")
            .containsExactlyInAnyOrder(Mapping.values());
        assertThat(sourceShapes)
            .as("both source-shape values must be exercised by Source-carried rows")
            .containsExactlyInAnyOrder(SourceShape.values());
        assertThat(sourceCardinalities)
            .as("R305 builds only the One source-cardinality path; the Many arrival is R308 "
                + "(service-list-payload-arrival), so no corpus row produces Many yet")
            .containsExactly(SourceCardinality.One);

        var unexercisedAndUnexplained = EnumSet.allOf(Intent.class);
        unexercisedAndUnexplained.removeAll(intents);
        unexercisedAndUnexplained.removeAll(INTENT_KNOWN_GAPS.keySet());
        assertThat(unexercisedAndUnexplained)
            .as("every Intent value must be exercised by a fixture or listed in INTENT_KNOWN_GAPS "
                + "with a stated reason; these are neither")
            .isEmpty();

        assertThat(EnumSet.copyOf(INTENT_KNOWN_GAPS.keySet()))
            .as("a known-gap intent that a fixture now exercises must be removed from INTENT_KNOWN_GAPS")
            .doesNotContainAnyElementsOf(intents);
    }

    @Test
    void carrierMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.carrierEnumConstants())
            .as("the SDL Carrier enum must mirror the Java Carrier sealed-arm set the adapter produces; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.carrierArmNames());
    }

    @Test
    void sourceShapeMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.sourceShapeEnumConstants())
            .as("the SDL SourceShape enum must mirror the Java SourceShape value set; "
                + "adding a value to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(enumNames(SourceShape.values()));
    }

    @Test
    void sourceCardinalityMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.sourceCardinalityEnumConstants())
            .as("the SDL SourceCardinality enum must mirror the Java SourceCardinality value set; "
                + "adding a value to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(enumNames(SourceCardinality.values()));
    }

    @Test
    void intentMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.intentEnumConstants())
            .as("the SDL Intent enum must mirror the Java Intent value set the adapter produces; "
                + "adding a value to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(enumNames(Intent.values()));
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
    void graphitronTypeLeafSimpleNamesAreUnique() {
        assertThat(ClassifiedHarness.graphitronTypeNonFailureLeafSimpleNames())
            .as("GraphitronType's sealed leaves must have unique simple names: the TypeVerdict mirror "
                + "compares by simple name, so a future nested leaf reusing a name (e.g. a second "
                + "`Backed`) would silently conflate two leaves and let the mirror pass while a real "
                + "leaf goes unmirrored")
            .doesNotHaveDuplicates();
    }
}
