package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.FieldSourceSigil;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R159 per-aspect pipeline-tier coverage for the rejection messages produced by
 * {@link FieldSourceSigil}. Three synthetic schemas run end-to-end through classifier +
 * validator and assert {@link ValidationError#message()} contains the canonical
 * {@link FieldSourceSigil} message verbatim — pinning that the messages live on the
 * utility and are not duplicated.
 *
 * <p>Sibling to {@link RecordFieldAccessorValidationTest} for accessor resolution and
 * {@link ServiceFieldValidationTest} for service-directive resolution: each verifies that
 * a producer's classifier-side rejection reaches the validator over the established
 * {@code UnclassifiedField} route.
 */
@PipelineTier
class FieldSourceSigilValidationTest {

    private static List<ValidationError> validate(GraphitronSchema schema) {
        return new GraphitronSchemaValidator().validate(schema);
    }

    /**
     * Unknown sigil at the carrier data field surfaces the canonical
     * {@link FieldSourceSigil#unknownSigilMessage} via {@code ValidationReport.errors()}. The
     * carrier walk's parse-time arm fires before the forbidden-directive HardReject, and the
     * rejection propagates through {@code UnclassifiedField} to the validator.
     */
    @Test
    void unknownSigil_atCarrierDataField_surfacesCanonicalMessageOnValidationReport() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @field(name: "$bogus") }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var canonical = FieldSourceSigil.unknownSigilMessage("$bogus");
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains(canonical));
    }

    /**
     * {@code $source} type-mismatch at the carrier data field surfaces the canonical
     * {@link FieldSourceSigil#typeMismatchMessage} via {@code ValidationReport.errors()}. The
     * check fires at the {@code @service} mutation classifier site where the producer's
     * {@code MethodRef} is in scope; the rejection propagates through {@code UnclassifiedField}.
     */
    @Test
    void sourceSigilTypeMismatch_atServiceCarrier_surfacesCanonicalMessageOnValidationReport() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] @field(name: "$source") }
            type Query { x: String }
            type Mutation {
                runFilmsWrongType: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguages"})
            }
            """);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains(FieldSourceSigil.UPSTREAM_ROOT_LITERAL)
                && m.contains("getLanguages")
                && m.contains("LanguageRecord")
                && m.contains("FilmRecord"));
    }

    /**
     * {@code @field(name: "$source")} on a regular {@code @record}-backed field is NOT rewired
     * by R159; the validator surfaces today's generic accessor-mismatch rejection on the
     * literal string {@code $source}. The classifier intentionally leaves the non-carrier
     * paths structurally identical to today.
     */
    @Test
    void sourceSigil_atNonCarrierRecordSite_surfacesAccessorMismatchOnValidationReport() {
        var schema = TestSchemaHelper.buildSchema("""
            type Foo @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                renamed: String @field(name: "$source")
            }
            type Query { foo: Foo }
            """);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Foo.renamed") && m.contains(FieldSourceSigil.UPSTREAM_ROOT_LITERAL));
    }
}
