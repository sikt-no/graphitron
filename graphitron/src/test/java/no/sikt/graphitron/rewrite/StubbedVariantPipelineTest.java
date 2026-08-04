package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * End-to-end check that the stubbed-variant validator fires through the full SDL → classifier →
 * validator path, not only when fed a hand-constructed fixture (the per-variant
 * {@code *ValidationTest} classes). Complements
 * {@code GraphitronSchemaValidator.validateVariantIsImplemented} and the
 * {@link TypeFetcherGenerator#STUBBED_VARIANTS} map.
 *
 * <p>The stub map is empty: every reachable leaf has a real or projected arm, so no
 * positive-direction pipeline case exists to ratchet. The gate stays armed all the same
 * (the validator reads the map dynamically), and the negative-direction test below guards
 * against an implemented variant accidentally emitting a "not yet implemented" message.
 * If a stub is ever minted again, add the positive pipeline case beside it.
 */
@PipelineTier
class StubbedVariantPipelineTest {

    @Test
    void implementedVariantOnly_producesNoStubbedError() {
        // QueryTableField is implemented, not stubbed — validator emits nothing for variant status.
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] }
            """);

        assertThat(messages(errors))
            .noneMatch(m -> m.contains("not yet implemented"));
    }

    // BatchedTableField intra-variant stub paths (lookup-keyed or not) are exercised via
    // BatchedTableFieldValidationTest / BatchedLookupValidationTest with
    // hand-constructed fixtures. A pipeline-level test here is blocked by unrelated jOOQ-catalog
    // infrastructure on the test schemas (same failure surface as other pre-existing
    // pipeline tests against @table types).

    private static List<ValidationError> validate(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return new GraphitronSchemaValidator().validate(schema);
    }

    private static List<String> messages(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::message).toList();
    }
}
