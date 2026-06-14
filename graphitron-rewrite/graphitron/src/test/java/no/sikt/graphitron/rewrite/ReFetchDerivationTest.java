package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R290 — the re-fetch derivation ({@link OutputField#requiresReFetch()}) is the single home of the
 * service/DML -&gt; {@code @table} follow-up SELECT predicate, derived from {@code intent x mapping}
 * rather than re-decided per leaf. This test pins that the derivation forks on {@code mapping}
 * (a {@code @service}-table field re-fetches; a {@code @service}-record field does not) and that the
 * {@code GraphitronSchemaValidator} mirror agrees with the generator's dispatch (no re-fetch drift),
 * which is what makes the materialised slot earn its keep: a production consumer pulls on it.
 */
@PipelineTier
class ReFetchDerivationTest {

    private static final String SERVICE_FIXTURE = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
          rating: String
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
          language: Language
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
        }
        type Query {
          film: Film
          externalFilm: Film
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
        }
        """;

    @Test
    void serviceTableReFetches_serviceRecordDoesNot() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(SERVICE_FIXTURE);

        // @service returning the Language @table: QueryService x Table -> re-fetch.
        OutputField language = (OutputField) schema.field("Film", "language");
        assertThat(language.requiresReFetch())
            .as("a @service field mapping to a catalog @table re-queries (QueryService x Table)")
            .isTrue();

        // @service returning a scalar/record: QueryService x Record -> no re-fetch.
        OutputField rating = (OutputField) schema.field("Film", "rating");
        assertThat(rating.requiresReFetch())
            .as("a @service field mapping to a Record hands back the consumed shape, no re-query")
            .isFalse();

        // Root @service returning the Film @table: QueryService x Table -> re-fetch.
        OutputField externalFilm = (OutputField) schema.field("Query", "externalFilm");
        assertThat(externalFilm.requiresReFetch()).isTrue();

        // A plain catalog Fetch reads the table directly, no producer round-trip.
        OutputField film = (OutputField) schema.field("Query", "film");
        assertThat(film.requiresReFetch())
            .as("a catalog Fetch is not a re-fetch")
            .isFalse();
    }

    @Test
    void validatorMirrorAgreesWithDispatch() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(SERVICE_FIXTURE);

        // The GraphitronSchemaValidator re-fetch mirror fails the build if the intent x mapping
        // derivation and the generator's re-fetch dispatch disagree; a clean validation proves the
        // single-homed predicate matches what the emitter actually does.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("re-fetch derivation"));
    }
}
