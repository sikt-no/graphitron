package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R290 / R316 — the re-fetch derivation ({@link OutputField#requiresReFetch()}) is the single home of
 * the service/DML -&gt; {@code @table} follow-up SELECT predicate, derived from the {@code (source,
 * operation, target)} coordinate rather than re-decided per leaf. This test pins that the derivation
 * forks on a bare {@code TargetShape.Table} target combined with holds-records (a {@code @service}-table
 * field re-fetches; a {@code @service}-record field does not) and that the
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

        // @service returning the Language @table: ServiceCall producing a Table target -> re-fetch.
        OutputField language = (OutputField) schema.field("Film", "language");
        assertThat(language.requiresReFetch())
            .as("a @service field projecting a catalog @table re-queries (ServiceCall x Table target)")
            .isTrue();

        // @service returning a scalar/record: ServiceCall with a Record/Field target -> no re-fetch.
        OutputField rating = (OutputField) schema.field("Film", "rating");
        assertThat(rating.requiresReFetch())
            .as("a @service field projecting a Record hands back the consumed shape, no re-query")
            .isFalse();

        // Root @service returning the Film @table: ServiceCall producing a Table target -> re-fetch.
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

        // The GraphitronSchemaValidator re-fetch mirror fails the build if the (source, operation,
        // target) derivation and the generator's re-fetch dispatch disagree; a clean validation proves
        // the single-homed predicate matches what the emitter actually does.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("re-fetch derivation"));
    }

    private static final String RECORD_SOURCE_SINGLE = """
        type Film @table(name: "film") { title: String }
        type FilmPayload { film: Film }
        type Query { x: String }
        type Mutation {
          runFilm: FilmPayload
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
        }
        """;

    private static final String RECORD_SOURCE_LIST = """
        type Film @table(name: "film") { title: String }
        type FilmListPayload { films: [Film!] }
        type Query { x: String }
        type Mutation {
          runFilms: FilmListPayload
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
        }
        """;

    @Test
    void recordSourceCarrier_reFetches() {
        // R305: the @service payload carrier's @table data field collapsed into RecordTableField —
        // a Source.Child(Record) field projecting a Table target, so holds-records x Table target ->
        // re-fetch. Both the single and the list (bulk) carrier re-fetch (the source=target re-projection).
        GraphitronSchema single = TestSchemaHelper.buildSchema(RECORD_SOURCE_SINGLE);
        OutputField film = (OutputField) single.field("FilmPayload", "film");
        assertThat(film).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.RecordTableField.class);
        assertThat(film.requiresReFetch())
            .as("a Source.Child(Record) x Table-target carrier re-fetches (holds a produced record, R305)")
            .isTrue();

        GraphitronSchema list = TestSchemaHelper.buildSchema(RECORD_SOURCE_LIST);
        OutputField films = (OutputField) list.field("FilmListPayload", "films");
        assertThat(films).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.RecordTableField.class);
        assertThat(films.requiresReFetch()).isTrue();

        // Mirror agreement across the Record-source family: the validator's dispatchPerformsReFetch
        // must match requiresReFetch, or the build fails with a re-fetch-derivation drift.
        assertThat(validate(single)).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("re-fetch derivation"));
        assertThat(validate(list)).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("re-fetch derivation"));
    }
}
