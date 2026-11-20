package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import no.sikt.graphitron.model.diagnostics.ReflectionError;
import no.sikt.graphitron.model.diagnostics.ServiceMethodCallError;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * End-to-end check that classifier rejections from the root {@code @service}
 * invariants (Connection wrapper, {@code Sources} param at root, strict-return-class
 * return type, and the strict {@code @service} return-type comparison) surface as
 * {@link ValidationError}s through the full SDL → classifier → validator path.
 *
 * <p>{@link GraphitronSchemaBuilderTest.UnclassifiedFieldCase} pins that the field is
 * classified as {@code UnclassifiedField} with the right reason; this test pins that the
 * reason is then surfaced by {@code validateUnclassifiedField} as a build-time
 * {@link ValidationError}, matching the pattern in {@link StubbedVariantPipelineTest} for
 * stubbed-variant rejections.
 */
@PipelineTier
class ServiceRootFetcherPipelineTest {

    /**
 * A root {@code @service} field with a {@code [Film!]!} return type accepts a
     * developer method that returns {@code java.util.List<FilmRecord>} as well as the
     * historical {@code org.jooq.Result<FilmRecord>}. Pipeline-tier positive witness for
     * the looser pair check in
     * {@code ServiceDirectiveResolver.validateRootListTableBoundReturnPair}.
     */
    @Test
    void serviceWithListOfRecordReturn_isAccepted() {
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type Query {
                acceptsListShape: [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        assertThat(messages(errors))
            .noneMatch(m -> m.contains("Field 'Query.acceptsListShape'"));
    }

    private static List<ValidationError> validate(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return new GraphitronSchemaValidator().validate(schema);
    }

    private static List<String> messages(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::message).toList();
    }
}
