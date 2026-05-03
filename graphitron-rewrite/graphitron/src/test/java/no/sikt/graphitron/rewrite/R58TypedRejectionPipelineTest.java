package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R58 Phase D: end-to-end coverage for migrated producer sites that now emit
 * {@link Rejection.AuthorError.UnknownName} carrying a non-empty {@code candidates()} list,
 * not just rendered prose. Each test exercises one factory by building a representative SDL
 * fragment whose classifier path resolves through the migrated producer; the assertion is that
 * the rejection pattern-matches {@code UnknownName} with the expected {@code attemptKind} and a
 * populated candidate list. This pins the typed-shape contract that R18 (LSP fix-its) and other
 * downstream consumers consume.
 *
 * <p>Out of scope: producer sites whose carrier widening is deferred to a Phase D follow-up
 * ({@code ArgumentRef.ScalarArg.UnboundArg.reason}, {@code ParsedPath.errorMessage},
 * {@code InputFieldResolution.Unresolved.reason}, {@code EnumValidation.Mismatch} message-list,
 * {@code keyColumnErrors} list); those still flatten to {@link Rejection.AuthorError.Structural}.
 */
@PipelineTier
class R58TypedRejectionPipelineTest {

    @Test
    void unknownColumn_onScalarFieldFiltersTypedShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film] }
            type Film @table(name: "film") {
                title: String
                bogusColumn: String @field(name: "bogus_column")
            }
            """);

        var field = schema.field("Film", "bogusColumn");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
        assertThat(unknown.attempt()).isEqualTo("bogus_column");
        assertThat(unknown.candidates()).isNotEmpty();
        assertThat(unknown.candidates()).anyMatch(c -> c.equalsIgnoreCase("title"));
    }

    @Test
    void unknownTypeName_onNodeIdReferenceTypedShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film] }
            type Film @table(name: "film") {
                title: String
                refToBogus: ID @nodeId(typeName: "BogusNonExistentType")
            }
            """);

        var field = schema.field("Film", "refToBogus");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.TYPE_NAME);
        assertThat(unknown.attempt()).isEqualTo("BogusNonExistentType");
        assertThat(unknown.candidates()).isNotEmpty();
    }

    @Test
    void unknownServiceMethod_typedShapeSurvivesWrapperPrefix() {
        // The wrapper site in ServiceDirectiveResolver prefixes "service method could not be
        // resolved — " onto the producer's typed UnknownName via Rejection.prefixedWith;
        // assert the typed components survive that wrap.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                noSuch: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "bogusNonExistentMethod"})
            }
            """);

        var field = schema.field("Query", "noSuch");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.SERVICE_METHOD);
        assertThat(unknown.attempt()).isEqualTo("bogusNonExistentMethod");
        assertThat(unknown.candidates()).isNotEmpty();
        assertThat(unknown.message()).startsWith("service method could not be resolved — ");
    }
}
