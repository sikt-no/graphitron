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
 * <p>R22 closed all six mutation-leaf stubs (DELETE, INSERT, UPDATE, UPSERT, plus both service
 * variants). The previous {@code mutationUpsertOnATableType_surfacesStubbedError} (and its
 * UPDATE / INSERT predecessors) is therefore retired: there is no DML stub left to ratchet
 * through the pipeline. The negative-direction test below remains and continues to guard
 * against regressions where an implemented variant accidentally emits a "not yet implemented"
 * message.
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

    // SplitTableField / SplitLookupTableField intra-variant stub paths are exercised via the
    // per-variant SplitTableFieldValidationTest / SplitLookupTableFieldValidationTest with
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
