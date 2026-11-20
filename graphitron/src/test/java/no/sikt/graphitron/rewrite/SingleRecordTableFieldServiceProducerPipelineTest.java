package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ServiceCarrierShapeError;
import no.sikt.graphitron.model.diagnostics.Arity;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for the {@code @service}-backed producer admit for single-record
 * DML carrier data fields. Verifies that an {@code @service} mutation returning {@code XRecord}
 * (single-record carrier) or {@code List<XRecord>} (list-record carrier) lands a
 * {@link ChildField.BatchedTableField} on the carrier's data field, keyed on a source=target re-fetch key
 * ({@link KeyLift.ProducedRecords} + {@link SourceKey.Wrap.Row}, the PK read off the
 * produced record(s)). The generator derives the {@code DIRECT} / {@code OUTCOME_SUCCESS}
 * source envelope at the type level; the SourceKey does not carry it. Rejection cases pin the
 * strict-return predicate and the single-producer-kind invariant.
 */
@PipelineTier
class SingleRecordTableFieldServiceProducerPipelineTest {

    // ===== Admission cases =====

    /**
     * The source-record carrier with an error channel. Adding an {@code errors} field to
     * the payload routes the {@code @service} producer through the typed {@code Outcome} wrapper;
     * the data field collapses into {@link ChildField.BatchedTableField} with a
     * {@link KeyLift.ProducedRecords} source=target re-fetch, and the sibling errors field is the
     * {@code WrapperArm} transport. This is the opptak {@code { sak: Sak, errors: [...] }} shape.
     */
    @Test
    void serviceProducer_withErrorsField_collapsesToBatchedTableField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union FilmError = DbErr
            type FilmPayload {
                film: Film
                errors: [FilmError]
            }
            type Query { x: String }
            type Mutation {
                runFilm: FilmPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
            }
            """);

        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var sk = ((ChildField.BatchedTableField) dataField).sourceKey();
        assertThat(((ChildField.BatchedTableField) dataField).lift())
            .isInstanceOf(KeyLift.ProducedRecords.class);
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);

        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField)).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
    }

    /**
     * Errors-less sibling: with no errors field the producer returns the
     * record bare, so the envelope is {@code DIRECT}, the same envelope split the
     * {@code @table}-element sibling carries.
     */
    @Test
    void serviceProducer_idElementCarrier_noErrors_recordsDirectEnvelope() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type FilmIdPayload {
                filmId: ID @nodeId(typeName: "Film")
            }
            type Query { x: String }
            type Mutation {
                deleteFilm: FilmIdPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        var dataField = schema.field("FilmIdPayload", "filmId");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordIdField.class);
        assertThat(((ChildField.SingleRecordIdField) dataField).envelope())
            .isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.DIRECT);
    }

    /**
     * Encoder failure at the field edge: {@code @nodeId(typeName:)} names
     * a type that is {@code @table}-bound (so the binding grounds and the payload promotes)
     * but not {@code @node}-registered; the data field rejects with the unknown-node
     * diagnostic while the payload type itself survives (no dangling {@code typeRef}).
     */
    @Test
    void serviceProducer_idElementCarrier_targetNotNode_rejectsDataField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union FilmError = DbErr
            type FilmIdsPayload {
                filmIds: [ID] @nodeId(typeName: "Film")
                errors: [FilmError]
            }
            type Query { x: String }
            type Mutation {
                deleteFilms: FilmIdsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        var dataField = schema.field("FilmIdsPayload", "filmIds");
        assertThat(dataField).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) dataField).rejection().message())
            .contains("FilmIdsPayload.filmIds", "no @node type");
    }

    /** MANY / single-PK admission: @service returning {@code List<FilmRecord>}. */
    @Test
    void serviceProducer_many_singlePk_admitsAsBatchedTableField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        var dataField = schema.field("FilmListPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var btf = (ChildField.BatchedTableField) dataField;
        var sk = btf.sourceKey();
        assertThat(btf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.MANY));
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    /**
     * MANY / composite-PK admission: @service returning {@code List<FilmActorRecord>}. The
     * data table's PK is composite ({@code (actor_id, film_id)}); the registered
     * {@link SourceKey#columns()} must carry both columns in declaration order.
     */
    @Test
    void serviceProducer_many_compositePk_admitsAsWrapTableRecord() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type FilmActorListPayload { filmActors: [FilmActor!] }
            type Query { x: String }
            type Mutation {
                runFilmActors: FilmActorListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmActorsAsList"})
            }
            """);

        var dataField = schema.field("FilmActorListPayload", "filmActors");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var btf = (ChildField.BatchedTableField) dataField;
        var sk = btf.sourceKey();
        assertThat(btf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.MANY));
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(sk.columns()).extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
    }

    // ===== Rejection cases =====

}
