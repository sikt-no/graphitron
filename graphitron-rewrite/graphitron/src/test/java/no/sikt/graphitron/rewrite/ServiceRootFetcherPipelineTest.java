package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * End-to-end check that classifier rejections from the root {@code @service} / {@code @tableMethod}
 * invariants (Connection wrapper, {@code Sources} param at root, {@code @tableMethod} strict-class
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

    @Test
    void serviceAtRootWithConnectionReturn_surfacesAsValidationError() {
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type FilmConnection {
                edges: [FilmEdge]
                pageInfo: PageInfo!
            }
            type FilmEdge {
                node: Film
                cursor: String!
            }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                externalFilms: FilmConnection
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """);

        assertThat(messages(errors))
            .anyMatch(m -> m.contains("Field 'Query.externalFilms'")
                && m.contains("@service at the root does not support Connection return types"));
    }

    @Test
    void tableMethodWithWiderReturnType_surfacesAsValidationError() {
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type Query {
                wider: [Film!]!
                    @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get"})
            }
            """);

        assertThat(messages(errors))
            .anyMatch(m -> m.contains("Field 'Query.wider'")
                && m.contains("must return the generated jOOQ table class"));
    }

    @Test
    void serviceWithMismatchedReturnType_surfacesAsValidationError() {
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type Query {
                wrongReturn: Film
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """);

        assertThat(messages(errors))
            .anyMatch(m -> m.contains("Field 'Query.wrongReturn'")
                && m.contains("must return")
                && m.contains("FilmRecord"));
    }

    private static List<ValidationError> validate(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return new GraphitronSchemaValidator().validate(schema);
    }

    private static List<String> messages(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::message).toList();
    }
}
