package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.model.diagnostics.ServiceCarrierShapeError;
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
    void listArrival_classifiesPayloadResultAndDataField() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
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

        var mut = schema.field("Mutation", "createFilms");
        assertThat(mut).isInstanceOf(MutationField.MutationServiceRecordField.class);
        assertThat(((MutationField.MutationServiceRecordField) mut).errorChannel()).isPresent();

        // The payload classifies to the per-element composite class (the element-naming convention).
        assertThat(schema.type("CreateFilmsPayload")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("CreateFilmsPayload")).fqClassName())
            .isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(schema.type("CreateFilmsResult")).isInstanceOf(GraphitronType.JavaRecordType.class);

        var data = schema.field("CreateFilmsPayload", "results");
        assertThat(data).isInstanceOf(ChildField.RecordCompositeField.class);
        var rc = (ChildField.RecordCompositeField) data;
        assertThat(rc.returnType().wrapper().isList()).isTrue();
        assertThat(rc.returnType().fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(rc.envelope()).isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.OUTCOME_SUCCESS);

        var errors = schema.field("CreateFilmsPayload", "errors");
        assertThat(errors).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errors).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);

        // The @field-mapped @table children read the composite's filmRecord() / actorRecords() accessors.
        var film = schema.field("CreateFilmsResult", "film");
        assertThat(film).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(((ChildField.BatchedTableField) film).returnType().table().tableName()).isEqualTo("film");
        var actors = schema.field("CreateFilmsResult", "actors");
        assertThat(actors).isInstanceOf(ChildField.BatchedTableField.class);
        var actorsBtf = (ChildField.BatchedTableField) actors;
        assertThat(actorsBtf.returnType().table().tableName()).isEqualTo("actor");
        assertThat(actorsBtf.returnType().wrapper().isList()).isTrue();

        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void singleArrival_classifiesDataFieldAsRecordComposite() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmPayload {
                result: CreateFilmsResult
                errors: [CreateError]
            }
            type Query { x: String }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmWithActors"})
            }
            """);

        assertThat(schema.field("Mutation", "createFilm"))
            .isInstanceOf(MutationField.MutationServiceRecordField.class);
        assertThat(schema.type("CreateFilmPayload")).isInstanceOf(GraphitronType.JavaRecordType.class);
        var data = schema.field("CreateFilmPayload", "result");
        assertThat(data).isInstanceOf(ChildField.RecordCompositeField.class);
        var rc = (ChildField.RecordCompositeField) data;
        assertThat(rc.returnType().wrapper().isList()).isFalse();
        assertThat(rc.envelope()).isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.OUTCOME_SUCCESS);
        assertThat(schema.diagnostics()).isEmpty();
    }

    /**
     * A <em>list</em> carrier ({@code [CreateFilmsPayload]}) over a class-backed composite payload,
     * produced by a <em>single</em> composite ({@code createFilmWithActors}): carrier arrival
     * disagrees with producer arrival, so the shape verdict rejects with the typed
     * {@link ServiceCarrierShapeError.ProducerArrivalMismatch} (the same arm as the
     * {@code @table}-data-field case), naming the {@code List<…>} fix.
     */
    @Test
    void listCarrier_classBacked_singleProducer_rejectsProducerArrivalMismatch() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsPayload {
                results: [CreateFilmsResult]
                errors: [CreateError]
            }
            type Query { x: String }
            type Mutation {
                createFilms: [CreateFilmsPayload]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmWithActors"})
            }
            """);

        var mut = schema.field("Mutation", "createFilms");
        assertThat(mut).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) mut).rejection();
        assertThat(rejection).isInstanceOf(ServiceCarrierShapeError.ProducerArrivalMismatch.class);
        assertThat(rejection.message())
            .contains("[CreateFilmsPayload]", "single value", "List<…>", "createFilmWithActors");
    }

    /**
     * The coherent class-backed list carrier: a <em>list</em> carrier ({@code [CreateFilmsPayload]})
     * with a <em>single</em> composite data field ({@code result: CreateFilmsResult}), produced by a
     * <em>collection</em> ({@code createFilmsWithActors} returning {@code List<TestFilmWithActorsDto>}).
     * Carrier arrival {@code MANY} agrees with producer arrival {@code MANY}: graphql-java iterates
     * the producer's list into the carrier, one composite per payload element, and each payload's
     * single {@code result} projects that one composite. This is the only coherent list-carrier data
     * shape (the list-data-field variant is the
     * {@link #listCarrier_classBacked_listDataField_rejectsDataFieldArrivalConflict} reject) and the
     * class-backed sibling of the {@code @table}-element coherent list carrier
     * {@code SingleRecordTableFieldServiceProducerPipelineTest#serviceProducer_listCarrier_singleTableDataField_admitsBatchedLoadOne}.
     * The payload/data-field model matches the single-carrier sibling
     * {@link #singleArrival_classifiesDataFieldAsRecordComposite}.
     */
    @Test
    void listCarrier_classBacked_collectionProducer_admitsCoherentComposite() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsPayload {
                result: CreateFilmsResult
                errors: [CreateError]
            }
            type Query { x: String }
            type Mutation {
                createFilms: [CreateFilmsPayload]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        var mut = schema.field("Mutation", "createFilms");
        assertThat(mut).isInstanceOf(MutationField.MutationServiceRecordField.class);
        assertThat(((MutationField.MutationServiceRecordField) mut).errorChannel()).isPresent();

        assertThat(schema.type("CreateFilmsPayload")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("CreateFilmsPayload")).fqClassName())
            .isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(schema.type("CreateFilmsResult")).isInstanceOf(GraphitronType.JavaRecordType.class);

        var data = schema.field("CreateFilmsPayload", "result");
        assertThat(data).isInstanceOf(ChildField.RecordCompositeField.class);
        var rc = (ChildField.RecordCompositeField) data;
        assertThat(rc.returnType().wrapper().isList()).isFalse();
        assertThat(rc.returnType().fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(rc.envelope()).isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.OUTCOME_SUCCESS);

        var film = schema.field("CreateFilmsResult", "film");
        assertThat(film).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(((ChildField.BatchedTableField) film).returnType().table().tableName()).isEqualTo("film");
        var actors = schema.field("CreateFilmsResult", "actors");
        assertThat(actors).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(((ChildField.BatchedTableField) actors).returnType().wrapper().isList()).isTrue();

        assertThat(schema.diagnostics()).isEmpty();
    }

    /**
     * The class-backed sibling of the {@code @table} reject
     * ({@code SingleRecordTableFieldServiceProducerPipelineTest#serviceProducer_listCarrier_listDataField_rejectsDataFieldArrivalConflict}):
     * a <em>list</em> carrier ({@code [CreateFilmsPayload]}) whose class-backed composite data field is
     * <em>itself</em> a list ({@code results: [CreateFilmsResult]}), produced by a flat
     * {@code List<TestFilmWithActorsDto>}. graphql-java iterates that flat list into the carrier, so
     * one composite reaches each payload; the source-passthrough data fetcher would cast that single
     * composite to {@code List<Composite>} ({@code FetcherEmitter.buildRecordCompositeFetcherValue})
     * and {@code ClassCastException} on every request. Filling the shape would need a
     * {@code List<List<Dto>>} producer the model does not have, so the verdict rejects with the typed
     * {@link ServiceCarrierShapeError.DataFieldArrivalConflict}, not a silent admit. (Contrast the
     * coherent single-data-field
     * {@link #listCarrier_classBacked_collectionProducer_admitsCoherentComposite} above, and the coherent
     * <em>single</em>-carrier list data field {@link #listArrival_classifiesPayloadResultAndDataField},
     * whose one payload's list projects the whole producer list.)
     */
    @Test
    void listCarrier_classBacked_listDataField_rejectsDataFieldArrivalConflict() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsPayload {
                results: [CreateFilmsResult]
                errors: [CreateError]
            }
            type Query { x: String }
            type Mutation {
                createFilms: [CreateFilmsPayload]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        var mut = schema.field("Mutation", "createFilms");
        assertThat(mut).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) mut).rejection();
        assertThat(rejection).isInstanceOf(ServiceCarrierShapeError.DataFieldArrivalConflict.class);
        assertThat(rejection.message())
            .contains("[CreateFilmsPayload]", "results", "element-by-element", "CreateFilmsResult");
    }

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
