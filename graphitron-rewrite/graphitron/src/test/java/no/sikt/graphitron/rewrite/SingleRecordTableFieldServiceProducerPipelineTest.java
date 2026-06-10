package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R158 pipeline-tier coverage for the {@code @service}-backed producer admit for single-record
 * DML carrier data fields. Verifies that an {@code @service} mutation returning {@code XRecord}
 * (single-record carrier) or {@code List<XRecord>} (list-record carrier) lands a
 * {@link ChildField.SingleRecordTableField} on the carrier's data field with the producer-kind
 * discrimination wired into {@link SourceKey.Wrap.TableRecord} (typed against the data table's
 * record class) — distinct from the DML producer's {@link SourceKey.Wrap.Record} arm. Rejection
 * cases pin the strict-return predicate and the single-producer-kind invariant.
 */
@PipelineTier
class SingleRecordTableFieldServiceProducerPipelineTest {

    // ===== Admission cases =====

    /** ONE / single-PK admission: @service returning {@code FilmRecord} for a non-list data field. */
    @Test
    void serviceProducer_one_singlePk_admitsAsWrapTableRecord() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            type Mutation {
                runFilm: FilmPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationServiceRecordField.class);

        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        var sk = srtf.sourceKey();
        assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.ResultRowWalk.class);
        // No errors field on the payload -> the producer returns the record bare, so the source
        // envelope is DIRECT (env.getSource() is the FilmRecord, not an Outcome).
        assertThat(((SourceKey.Reader.ResultRowWalk) sk.reader()).envelope())
            .isEqualTo(SourceKey.Reader.SourceEnvelope.DIRECT);
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(((SourceKey.Wrap.TableRecord) sk.wrap()).className())
            .isEqualTo(sk.target().recordClass());
        assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
        assertThat(sk.path()).isEmpty();
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    /**
     * R275: the source-record carrier with an error channel. Adding an {@code errors} field to the
     * payload routes the {@code @service} producer through the typed {@code Outcome} wrapper, so the
     * data field's {@link SourceKey.Reader.ResultRowWalk} records the {@code OUTCOME_SUCCESS}
     * envelope (the fetcher narrows {@code Outcome.Success} and reads off {@code success.value()})
     * and the sibling errors field is the {@code WrapperArm} transport. This is the opptak
     * {@code { sak: Sak, errors: [...] }} shape that buckets B/D failed on before R275.
     */
    @Test
    void serviceProducer_withErrorsField_recordsOutcomeSuccessEnvelope() {
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
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var sk = ((ChildField.SingleRecordTableField) dataField).sourceKey();
        assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.ResultRowWalk.class);
        assertThat(((SourceKey.Reader.ResultRowWalk) sk.reader()).envelope())
            .isEqualTo(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS);
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);

        // The sibling errors field rides the WrapperArm transport (the Outcome.ErrorList arm).
        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
    }

    /**
     * R275 reopened scope: the {@code @splitQuery}-list source-record carrier (the opptak
     * {@code leggTilTagger -> { saker: [Sak!] @splitQuery, errors }} shape). {@code @splitQuery}
     * on the carrier data field is tolerated by the {@code @service}-carrier scan (redundant:
     * the carrier emit already runs a PK-keyed follow-up SELECT off the producer's records), so
     * the payload promotes and the data field projects over the {@code OUTCOME_SUCCESS} list
     * path. A redundancy advisory fires for the ignored directive. Pre-fix the forbidden-
     * directive check dropped the payload type entirely, producing an invalid assembled schema
     * (dangling {@code typeRef}).
     */
    @Test
    void serviceProducer_splitQueryListCarrier_withErrors_admitsAndWarnsRedundantDirective() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union FilmError = DbErr
            type FilmListPayload {
                films: [Film!] @splitQuery
                errors: [FilmError]
            }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        assertThat(schema.field("Mutation", "runFilms"))
            .isInstanceOf(MutationField.MutationServiceRecordField.class);
        var dataField = schema.field("FilmListPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var sk = ((ChildField.SingleRecordTableField) dataField).sourceKey();
        assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(((SourceKey.Reader.ResultRowWalk) sk.reader()).envelope())
            .isEqualTo(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS);
        var errorsField = schema.field("FilmListPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmListPayload.films")
                && m.contains("@splitQuery is redundant on a @record-parent field"));
    }

    /**
     * R275 reopened scope: an {@code [ID!] @nodeId} data field is the DELETE-only PK-echo permit;
     * on an {@code @service} mutation no producer binding grounds it, so the payload cannot
     * classify. The orphan-carrier guard rejects the mutation field loudly at classification
     * time. Pre-fix the field classified as {@code MutationServiceRecordField} over the dropped
     * payload type, producing an invalid assembled schema (dangling {@code typeRef}). When
     * {@code @service} support for encoding node ids off the service result lands (R275's
     * follow-up slice), this rejection flips to classification.
     */
    @Test
    void serviceProducer_idElementCarrier_rejectsLoudly() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union FilmError = DbErr
            type FilmIdsPayload {
                filmIds: [ID!] @nodeId(typeName: "Film")
                errors: [FilmError]
            }
            type Query { x: String }
            type Mutation {
                deleteFilms: FilmIdsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        var mutField = schema.field("Mutation", "deleteFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) mutField).rejection().message())
            .contains("FilmIdsPayload", "ID-element", "DELETE", "R275");
    }

    /** MANY / single-PK admission: @service returning {@code List<FilmRecord>}. */
    @Test
    void serviceProducer_many_singlePk_admitsAsWrapTableRecord() {
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
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        var sk = srtf.sourceKey();
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(((SourceKey.Wrap.TableRecord) sk.wrap()).className())
            .isEqualTo(sk.target().recordClass());
        assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
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
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        var sk = srtf.sourceKey();
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(((SourceKey.Wrap.TableRecord) sk.wrap()).className())
            .isEqualTo(sk.target().recordClass());
        assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        // Composite PK: both columns in declaration order from the catalog.
        assertThat(sk.columns()).extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
    }

    // ===== Rejection cases =====

    /**
     * Wrong element type: @service returns {@code List<LanguageRecord>} for a carrier whose
     * data field is {@code [Film!]}. The service-producer-strict-return check rejects naming the
     * expected {@code List<FilmRecord>}.
     */
    @Test
    void serviceProducer_wrongElementType_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguagesAsList"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("getLanguagesAsList", "FilmRecord");
    }

    /** {@code Set<XRecord>} return: structural rejection via the strict predicate. */
    @Test
    void serviceProducer_setReturn_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsSet"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("getFilmsAsSet", "List<");
    }

    /** {@code Iterable<XRecord>} return: structural rejection via the strict predicate. */
    @Test
    void serviceProducer_iterableReturn_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsIterable"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("getFilmsAsIterable", "List<");
    }

    /**
     * R204 mixed-producer carrier reject (DML-first declaration order). Pins that the validator
     * detects the conflict regardless of declaration order; the sibling test pins the other
     * direction.
     *
     * <p>Both producer mutations demote to {@link UnclassifiedField} carrying a
     * {@code MultiProducerDomainTypeDisagreement} rejection; the message names the payload SDL
     * type, both producer coords, and both {@code DomainReturnType} arms ({@code Record(film)}
     * and {@code TableRecord(FilmRecord)}).
     */
    @Test
    void serviceProducer_mixedWithDml_dmlFirst_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilms(in: [FilmInput!]!): FilmListPayload @mutation(typeName: INSERT)
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        var dmlMut = schema.field("Mutation", "createFilms");
        var serviceMut = schema.field("Mutation", "runFilms");
        assertThat(dmlMut).isInstanceOf(UnclassifiedField.class);
        assertThat(serviceMut).isInstanceOf(UnclassifiedField.class);

        var dmlReason = ((UnclassifiedField) dmlMut).rejection().message();
        var serviceReason = ((UnclassifiedField) serviceMut).rejection().message();
        // Both surfaces carry the same conflict payload; assert one and let the symmetry pin
        // hold the other arm.
        assertThat(dmlReason)
            .contains("FilmListPayload", "createFilms", "runFilms", "Record(film)", "TableRecord(FilmRecord)");
        assertThat(serviceReason)
            .contains("FilmListPayload", "createFilms", "runFilms", "Record(film)", "TableRecord(FilmRecord)");
    }

    /**
     * R204 mixed-producer carrier reject (@service-first declaration order). Pins that the
     * validator detects the conflict regardless of declaration order; the sibling test pins
     * the other direction.
     */
    @Test
    void serviceProducer_mixedWithDml_serviceFirst_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
                createFilms(in: [FilmInput!]!): FilmListPayload @mutation(typeName: INSERT)
            }
            """);

        var dmlMut = schema.field("Mutation", "createFilms");
        var serviceMut = schema.field("Mutation", "runFilms");
        assertThat(dmlMut).isInstanceOf(UnclassifiedField.class);
        assertThat(serviceMut).isInstanceOf(UnclassifiedField.class);

        var dmlReason = ((UnclassifiedField) dmlMut).rejection().message();
        assertThat(dmlReason)
            .contains("FilmListPayload", "createFilms", "runFilms", "Record(film)", "TableRecord(FilmRecord)");
    }
}
