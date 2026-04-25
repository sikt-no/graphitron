package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.MutationField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that the stubbed-variant validator fires through the full SDL → classifier →
 * validator path, not only when fed a hand-constructed fixture (the per-variant
 * {@code *ValidationTest} classes). Complements
 * {@code GraphitronSchemaValidator.validateVariantIsImplemented} and the
 * {@link TypeFetcherGenerator#NOT_IMPLEMENTED_REASONS} map.
 */
class StubbedVariantPipelineTest {

    @Test
    void mutationInsertOnATableType_surfacesStubbedError() {
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm: Film @mutation(typeName: INSERT) }
            """);

        assertThat(messages(errors))
            .contains("Field 'Mutation.createFilm': "
                + TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.get(MutationField.MutationInsertTableField.class));
    }

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
