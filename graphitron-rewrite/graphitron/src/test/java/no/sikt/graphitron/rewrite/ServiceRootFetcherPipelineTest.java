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
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get")
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

    /**
     * R127: a root {@code @service} field with a {@code [Film!]!} return type accepts a
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

    /**
     * R127: a root {@code @service} on a {@code [Film!]!} return whose method returns
     * neither {@code Result<FilmRecord>} nor {@code List<FilmRecord>} rejects with both
     * accepted shapes named, the actual mismatched shape named, and the same
     * {@code "service method could not be resolved — "} prefix the Single-arm rejection
     * carries. Locks in the error-quality contract the looser pair check is most likely
     * to regress.
     */
    @Test
    void serviceWithWrongInnerGenericOnList_surfacesAsValidationErrorWithPairedShapes() {
        var errors = validate("""
            type Film @table(name: "film") { title: String }
            type Query {
                wrongInnerGenericOnList: [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguages"})
            }
            """);

        assertThat(messages(errors))
            .anyMatch(m -> m.contains("Field 'Query.wrongInnerGenericOnList'")
                && m.contains("service method could not be resolved")
                && m.contains("must return")
                && m.contains("Result<FilmRecord>")
                && m.contains("List<FilmRecord>")
                && m.contains("Result<LanguageRecord>"));
    }

    /**
     * R238: a {@code @service} method with two {@code DSLContext} params in the same round
     * surfaces the walker's typed {@code ServiceMethodCallError.MultipleDslContextSlots} arm
     * end-to-end through the validator. The {@link ValidationError#rejection} preserves the
     * typed arm (rather than collapsing to {@code Rejection.AuthorError.Structural}) so the
     * LSP {@code Diagnostics.lspCodeOf} projector can read its stable wire code.
     */
    @Test
    void serviceWithTwoDslContextParams_surfacesAsTypedServiceMethodCallError() {
        var errors = validate("""
            type Query {
                twoDsls: String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getWithTwoDsls"})
            }
            """);

        var typed = errors.stream()
            .map(ValidationError::rejection)
            .filter(r -> r instanceof no.sikt.graphitron.rewrite.model.ServiceMethodCallError.MultipleDslContextSlots)
            .map(r -> (no.sikt.graphitron.rewrite.model.ServiceMethodCallError.MultipleDslContextSlots) r)
            .findFirst();
        assertThat(typed)
            .as("Walker's MultipleDslContextSlots arm must reach ValidationError as a typed rejection")
            .isPresent();
        assertThat(typed.get().lspCode())
            .isEqualTo("graphitron.service-method-call.multiple-dsl-context-slots");
    }

    private static List<ValidationError> validate(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return new GraphitronSchemaValidator().validate(schema);
    }

    private static List<String> messages(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::message).toList();
    }
}
