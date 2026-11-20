package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * The re-fetch derivation ({@link OutputField#requiresReFetch()}) is the single home of
 * the service/DML -&gt; {@code @table} follow-up SELECT predicate, derived from the {@code (source,
 * operation, target)} coordinate rather than re-decided per leaf. This test pins that the derivation
 * forks on a bare {@code TargetShape.Table} target combined with holds-records (a {@code @service}-table
 * field re-fetches; a {@code @service}-record field does not) and that the
 * {@code GraphitronSchemaValidator} reentry implementedness guard (the replacement for the
 * retired dispatch mirror) stays quiet on every implemented reentry shape, which is
 * what makes the materialised slot earn its keep: a production consumer pulls on it.
 */
@PipelineTier
class ReFetchDerivationTest {

    private static final String SERVICE_FIXTURE = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
          rating: String
            @service(service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRatingMapped"})
          language: Language
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguageByKey"})
        }
        type Query {
          film: Film
          externalFilm: Film
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
        }
        """;

    @Test
    void reentryImplementednessGuardStaysQuietOnTheCorpus() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(SERVICE_FIXTURE);

        // The dispatchPerformsReFetch mirror was retired (the reentry emit routes on the
        // model facts, so the per-leaf enumeration became a second derivation of the same facts);
        // its replacement is the implementedness guard — a site-level reentry classification on a
        // leaf outside the implemented shapes fails at validate time. No current leaf can fire it
        // (the sealed hierarchy admits no such combination), so the corpus assertion is that the
        // guard stays quiet on every implemented reentry shape.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("carries no reentry emit"));
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
        // The @service payload carrier's @table data field collapsed into BatchedTableField —
        // a Source.Child(Record) field projecting a Table target, so holds-records x Table target ->
        // re-fetch. Both the single and the list (bulk) carrier re-fetch (the source=target re-projection).
        GraphitronSchema single = TestSchemaHelper.buildSchema(RECORD_SOURCE_SINGLE);
        OutputField film = (OutputField) single.field("FilmPayload", "film");
        assertThat(film).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField.class);
        assertThat(film.requiresReFetch())
            .as("a Source.Child(Record) x Table-target carrier re-fetches (holds a produced record, R305)")
            .isTrue();

        GraphitronSchema list = TestSchemaHelper.buildSchema(RECORD_SOURCE_LIST);
        OutputField films = (OutputField) list.field("FilmListPayload", "films");
        assertThat(films).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField.class);
        assertThat(films.requiresReFetch()).isTrue();

        // Record-sourced carriers own their keyed re-query (the rows-method), so the site-level
        // fact agrees with the value-level one on this family.
        assertThat(film.emitsKeyedReQuery()).isTrue();
        assertThat(films.emitsKeyedReQuery()).isTrue();

        // The reentry implementedness guard (the retired mirror's replacement) stays quiet on
        // the Record-source family: both carriers are BatchKeyField-backed implemented shapes.
        assertThat(validate(single)).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("carries no reentry emit"));
        assertThat(validate(list)).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("carries no reentry emit"));
    }
}
