package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for the {@code @service} record-composite payload carrier: an
 * {@code @service} mutation whose method returns a list (or single) of a consumer-authored composite
 * (one {@code FilmRecord} plus a {@code List<ActorRecord>}), expressed as a two-level carrier
 * {@code Payload { results: [Result], errors }} / {@code Result { film: Film, actors: [Actor] }}.
 * The driving shape reduces to a Sakila-catalog analog (FilmRecord + List&lt;ActorRecord&gt;)
 * returned by {@link TestServiceStub#createFilmsWithActors} / {@link TestServiceStub#createFilmWithActors}.
 */
@PipelineTier
class ServiceRecordCompositeCarrierPipelineTest {

    private static final String TABLES = """
        type Film @table(name: "film") { title: String }
        type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]!  message: String! }
        union CreateError = DbErr
        type CreateFilmsResult {
            film: Film! @field(name: "filmRecord")
            actors: [Actor] @field(name: "actorRecords")
        }
        """;

    @Test
    void noErrorsField_dataFieldUsesDirectEnvelope() {
        // Without an errors field the producer returns the composite list bare (no Outcome wrapper),
        // so the passthrough reads env.getSource() directly: the DIRECT envelope.
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsPayload {
                results: [CreateFilmsResult]
            }
            type Query { x: String }
            type Mutation {
                createFilms: CreateFilmsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        var data = schema.field("CreateFilmsPayload", "results");
        assertThat(data).isInstanceOf(ChildField.RecordCompositeField.class);
        assertThat(((ChildField.RecordCompositeField) data).envelope())
            .isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.DIRECT);
    }

    /**
     * The casing-mismatch sibling of {@link #listArrival_classifiesPayloadResultAndDataField}: the
     * same record-composite carrier with the result type's {@code @table} children declared
     * {@code @table(name: "FILM")} / {@code @table(name: "ACTOR")} against the lowercase Sakila
     * {@code film} / {@code actor} catalog names (a schema written in legacy Oracle-style UPPERCASE
     * against a lowercase Postgres jOOQ catalog). The table-name comparison in
     * {@code FieldBuilder.collectAccessorMatches} is case-insensitive, so both children still resolve
     * through the record-backed accessor path. Asserts the classification verdict, not the
     * case-insensitivity mechanism.
     */
    @Test
    void caseMismatchedTableName_classifiesCompositeChildrenAsBatchedTableField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "FILM") { title: String }
            type Actor @table(name: "ACTOR") { firstName: String @field(name: "first_name") }
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]!  message: String! }
            union CreateError = DbErr
            type CreateFilmsResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
            }
            type CreateFilmsPayload {
                results: [CreateFilmsResult]
                errors: [CreateError]
            }
            type Query { x: String }
            type Mutation {
                createFilms: CreateFilmsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        var film = schema.field("CreateFilmsResult", "film");
        assertThat(film).isInstanceOf(ChildField.BatchedTableField.class);
        var filmBtf = (ChildField.BatchedTableField) film;
        assertThat(filmBtf.returnType().table().tableName()).isEqualToIgnoringCase("film");
        assertThat(filmBtf.returnType().wrapper().isList()).isFalse();

        var actors = schema.field("CreateFilmsResult", "actors");
        assertThat(actors).isInstanceOf(ChildField.BatchedTableField.class);
        var actorsBtf = (ChildField.BatchedTableField) actors;
        assertThat(actorsBtf.returnType().table().tableName()).isEqualToIgnoringCase("actor");
        assertThat(actorsBtf.returnType().wrapper().isList()).isTrue();

        assertThat(schema.diagnostics()).isEmpty();
    }
}
