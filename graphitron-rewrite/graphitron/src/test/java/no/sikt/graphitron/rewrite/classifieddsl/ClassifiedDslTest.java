package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus.Example;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R281 slice 1: the spec-by-example corpus running alongside the legacy enum truth table
 * ({@code GraphitronSchemaBuilderTest}). Each fixture is an annotated schema; the harness classifies
 * it with today's classifier and checks every {@code @classified} / {@code @classifiedType}
 * coordinate against its declared dimensional verdict (via {@link LeafTupleAdapter}).
 *
 * <p>Slice 1's primary deliverable is the <em>validated value sets</em>, not full leaf coverage. The
 * meta-tests here pin three of the four coverage obligations from the Spec
 * (§"Validating the axis set"):
 * <ul>
 *   <li><b>Adapter totality</b> is compiler-enforced: {@link LeafTupleAdapter#toTuple} switches
 *       exhaustively over the sealed {@code OutputField} hierarchy, so a new leaf without a verdict
 *       fails the build. No runtime test can strengthen that, so none is written.</li>
 *   <li><b>Value exercise</b>, {@link #everyDimensionValueIsExercised()}: every {@link ProducerStep}
 *       and {@link Mapping} value (and the inline empty producer) is produced by some fixture.</li>
 *   <li><b>TypeVerdict mirror</b>, {@link #typeVerdictMirrorsGraphitronTypeLeaves()}: the SDL
 *       {@code TypeVerdict} enum equals the non-failure {@code GraphitronType} leaf set.</li>
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

        assertThat(result.fields())
            .as("fixture %s must annotate at least one output field", example)
            .isNotEmpty();

        for (var fc : result.fields()) {
            assertThat(fc.actual())
                .as("%s.%s classifies to its declared (producer, mapping)", fc.parentType(), fc.fieldName())
                .isEqualTo(fc.expected());
        }
        for (var tc : result.types()) {
            assertThat(tc.actualVerdict())
                .as("type %s classifies to its declared TypeVerdict", tc.typeName())
                .isEqualTo(tc.expectedVerdict());
        }
    }

    @Test
    void everyDimensionValueIsExercised() {
        var producers = EnumSet.noneOf(ProducerStep.class);
        var mappings = EnumSet.noneOf(Mapping.class);
        boolean inlineSeen = false;

        for (var example : ClassifiedCorpus.examples()) {
            for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                producers.addAll(fc.actual().producer());
                mappings.add(fc.actual().mapping());
                inlineSeen |= fc.actual().producer().isEmpty();
            }
        }

        assertThat(producers)
            .as("every ProducerStep value must be exercised by the corpus")
            .containsExactlyInAnyOrder(ProducerStep.values());
        assertThat(mappings)
            .as("every Mapping value must be exercised by the corpus")
            .containsExactlyInAnyOrder(Mapping.values());
        assertThat(inlineSeen)
            .as("the inline (empty) producer must be exercised by the corpus")
            .isTrue();
    }

    @Test
    void typeVerdictMirrorsGraphitronTypeLeaves() {
        assertThat(ClassifiedHarness.typeVerdictEnumConstants())
            .as("the SDL TypeVerdict enum must mirror GraphitronType's non-failure sealed leaves; "
                + "adding a type leaf without a matching TypeVerdict constant (or vice versa) fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.graphitronTypeNonFailureLeafNames());
    }
}
