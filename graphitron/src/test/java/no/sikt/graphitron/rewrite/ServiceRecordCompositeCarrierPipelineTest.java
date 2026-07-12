package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R329 pipeline-tier coverage for the {@code @service} record-composite payload carrier: an
 * {@code @service} mutation whose method returns a list (or single) of a consumer-authored composite
 * (one {@code FilmRecord} plus a {@code List<ActorRecord>}), expressed as a two-level carrier
 * {@code Payload { results: [Result], errors }} / {@code Result { film: Film, actors: [Actor] }}.
 *
 * <p>Pins the positive classification: the intermediate result type binds to the composite class
 * (a {@code JavaRecordType}) and its {@code @field}-mapped {@code @table} children resolve through the
 * record-backed accessor path ({@code RecordTableField}); the payload no longer dangles (it classifies
 * as a class-backed {@code JavaRecordType} naming the per-element composite, the carrier recognition
 * "closing the seam"); the data field is the source-passthrough {@code RecordCompositeField} carrying
 * the {@code OUTCOME_SUCCESS} envelope when the payload has an errors field; and the errors field rides
 * the {@code WrapperArm} transport. The driving shape reduces to a Sakila-catalog analog (FilmRecord +
 * List&lt;ActorRecord&gt;) returned by {@link TestServiceStub#createFilmsWithActors} /
 * {@link TestServiceStub#createFilmWithActors}.
 *
 * <p>R357 adds the casing-mismatch sibling ({@link #caseMismatchedTableName_classifiesCompositeChildrenAsRecordTableField}):
 * the same carrier whose {@code @table} children declare {@code @table(name:)} in a case that differs from
 * the lowercase jOOQ catalog name still resolves both children through the accessor path.
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

        // The mutation field is a service-record field with the typed Outcome (Mapped) channel.
        var mut = schema.field("Mutation", "createFilms");
        assertThat(mut).isInstanceOf(MutationField.MutationServiceRecordField.class);
        assertThat(((MutationField.MutationServiceRecordField) mut).errorChannel()).isPresent();

        // The payload no longer dangles: it classifies as the class-backed JavaRecordType naming the
        // per-element composite (the element-naming convention), and the intermediate result type
        // binds to the same composite.
        assertThat(schema.type("CreateFilmsPayload")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("CreateFilmsPayload")).fqClassName())
            .isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(schema.type("CreateFilmsResult")).isInstanceOf(GraphitronType.JavaRecordType.class);

        // The data field is the source-passthrough RecordCompositeField: list arrival, the composite
        // element class on its return type, OUTCOME_SUCCESS envelope (the payload has an errors field).
        var data = schema.field("CreateFilmsPayload", "results");
        assertThat(data).isInstanceOf(ChildField.RecordCompositeField.class);
        var rc = (ChildField.RecordCompositeField) data;
        assertThat(rc.returnType().wrapper().isList()).isTrue();
        assertThat(rc.returnType().fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(rc.envelope()).isEqualTo(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS);

        // The errors field rides the Outcome WrapperArm transport.
        var errors = schema.field("CreateFilmsPayload", "errors");
        assertThat(errors).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errors).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);

        // The composite's @field-mapped @table children resolve through the record-backed accessor
        // path (RecordTableField reading the composite's filmRecord() / actorRecords() accessors).
        var film = schema.field("CreateFilmsResult", "film");
        assertThat(film).isInstanceOf(ChildField.RecordTableField.class);
        assertThat(((ChildField.RecordTableField) film).sourceKey().target().tableName()).isEqualTo("film");
        var actors = schema.field("CreateFilmsResult", "actors");
        assertThat(actors).isInstanceOf(ChildField.RecordTableField.class);
        var actorsRtf = (ChildField.RecordTableField) actors;
        assertThat(actorsRtf.sourceKey().target().tableName()).isEqualTo("actor");
        assertThat(actorsRtf.sourceKey().cardinality()).isEqualTo(SourceKey.Cardinality.MANY);

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
        assertThat(rc.envelope()).isEqualTo(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS);
        assertThat(schema.diagnostics()).isEmpty();
    }

    /**
     * R308 — the class-backed list-carrier arrival mismatch (formerly a misleading rejection): a
     * <em>list</em> carrier ({@code [CreateFilmsPayload]}) over a class-backed composite payload,
     * produced by a <em>single</em> composite ({@code createFilmWithActors}). Before R308 the
     * {@code NoBind}-silent-drop left the payload unbacked and only the generic dangling-type-reference
     * rule rejected it (never naming the cardinality cause). The shape verdict now rejects at the seat
     * with the typed {@link ServiceCarrierShapeError.ProducerArrivalMismatch} — the same arm the
     * {@code @table}-data-field a1 case lands, since both are the one fact "carrier arrival disagrees
     * with producer arrival" — naming the {@code List<…>} fix.
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
     * R308 — the coherent class-backed list carrier (the working sub-shape the rework pins). A
     * <em>list</em> carrier ({@code [CreateFilmsPayload]}) whose payload has a <em>single</em>
     * composite data field ({@code result: CreateFilmsResult}), produced by a <em>collection</em>
     * ({@code createFilmsWithActors} returning {@code List<TestFilmWithActorsDto>}): carrier arrival
     * {@code MANY} agrees with producer arrival {@code MANY}, so graphql-java iterates the producer's
     * list into the {@code [CreateFilmsPayload]} carrier, one composite per payload element, and each
     * payload's single {@code result} projects that one composite. This is the class-backed sibling of
     * the {@code @table}-element coherent list carrier
     * {@code SingleRecordTableFieldServiceProducerPipelineTest#serviceProducer_listCarrier_singleTableDataField_admitsBatchedLoadOne}
     * ({@code [FilmPayload] { film: Film }} over {@code List<FilmRecord>}): both are a list carrier over
     * a collection producer with a single per-element data field, the only coherent list-carrier data
     * shape (the list-data-field variant is the a2 {@code DataFieldArrivalConflict} reject).
     *
     * <p>The shape verdict's {@code Coherent} arm admits it and the payload/data-field model is
     * byte-for-byte the single-carrier sibling {@link #singleArrival_classifiesDataFieldAsRecordComposite}:
     * a class-backed {@code JavaRecordType} payload and a single source-passthrough
     * {@code RecordCompositeField} data field, no diagnostics. Before this rework the shape false-rejected:
     * {@code checkServiceReturnMatchesPayload} re-levelled the expected producer cardinality onto the
     * data-field wrapper alone (an R329 read that predates list carriers), so a single data field made it
     * expect a single-value producer and reject the {@code List<…>} producer. Commit 0803628 fixed the
     * mirror-image false reject for the {@code @table} variant, which only surfaced because that variant
     * had a fixture; this is the missing class-backed tripwire.
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

        // The field classifies (no rejection) as a service-record field with the typed Outcome channel.
        var mut = schema.field("Mutation", "createFilms");
        assertThat(mut).isInstanceOf(MutationField.MutationServiceRecordField.class);
        assertThat(((MutationField.MutationServiceRecordField) mut).errorChannel()).isPresent();

        // The payload/data-field model is unchanged from the single-carrier sibling: a class-backed
        // JavaRecordType naming the composite, a single RecordCompositeField with the OUTCOME_SUCCESS
        // envelope.
        assertThat(schema.type("CreateFilmsPayload")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("CreateFilmsPayload")).fqClassName())
            .isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(schema.type("CreateFilmsResult")).isInstanceOf(GraphitronType.JavaRecordType.class);

        var data = schema.field("CreateFilmsPayload", "result");
        assertThat(data).isInstanceOf(ChildField.RecordCompositeField.class);
        var rc = (ChildField.RecordCompositeField) data;
        assertThat(rc.returnType().wrapper().isList()).isFalse();
        assertThat(rc.returnType().fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestFilmWithActorsDto");
        assertThat(rc.envelope()).isEqualTo(SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS);

        // The composite's @field-mapped @table children still resolve through the record-backed
        // accessor path, unchanged by the list carrier.
        var film = schema.field("CreateFilmsResult", "film");
        assertThat(film).isInstanceOf(ChildField.RecordTableField.class);
        assertThat(((ChildField.RecordTableField) film).sourceKey().target().tableName()).isEqualTo("film");
        var actors = schema.field("CreateFilmsResult", "actors");
        assertThat(actors).isInstanceOf(ChildField.RecordTableField.class);
        assertThat(((ChildField.RecordTableField) actors).sourceKey().cardinality())
            .isEqualTo(SourceKey.Cardinality.MANY);

        assertThat(schema.diagnostics()).isEmpty();
    }

    /**
     * R308 — the class-backed sibling of the {@code @table} a2 reject
     * ({@code SingleRecordTableFieldServiceProducerPipelineTest#serviceProducer_listCarrier_listDataField_rejectsDataFieldArrivalConflict}):
     * a <em>list</em> carrier ({@code [CreateFilmsPayload]}) whose class-backed composite data field is
     * <em>itself</em> a list ({@code results: [CreateFilmsResult]}), produced by a flat
     * {@code List<TestFilmWithActorsDto>}. graphql-java iterates that flat list into the
     * {@code [CreateFilmsPayload]} carrier, so one composite reaches each payload; the source-passthrough
     * data fetcher then casts that single composite to {@code List<Composite>}
     * ({@code FetcherEmitter.buildRecordCompositeFetcherValue}) and {@code ClassCastException}s on every
     * request — the acceptance axiom's forbidden shape. Filling it would need a {@code List<List<Dto>>}
     * producer the model does not have. This is the same semantic hole as the {@code @table} twin, so the
     * verdict rejects with the typed {@link ServiceCarrierShapeError.DataFieldArrivalConflict}, not a
     * silent admit; the {@code DataFieldArrivalConflict} arm is no longer scoped to the {@code @table}
     * element kind. (Contrast the coherent single-data-field
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
        // so the passthrough reads env.getSource() directly (DIRECT envelope) — the same envelope
        // split the @table-element / ID-element @service carriers carry.
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
            .isEqualTo(SourceKey.Reader.SourceEnvelope.DIRECT);
    }

    /**
     * R357 — the casing-mismatch sibling of {@link #listArrival_classifiesPayloadResultAndDataField}. The
     * driving utdanningsregisteret schema writes every {@code @table(name:)} in legacy Oracle-style
     * UPPERCASE while the Postgres jOOQ catalog is lowercase. The same record-composite carrier, with the
     * result type's {@code @table} children declared {@code @table(name: "FILM")} / {@code @table(name:
     * "ACTOR")} against the lowercase Sakila {@code film} / {@code actor} tables, must still resolve both
     * children through the record-backed accessor path. A single case-sensitive table-name comparison in
     * {@code collectAccessorMatches} dropped the matched accessor under a casing mismatch (the verbatim
     * {@code @table(name:)} casing on one operand, the jOOQ {@code Table.getName()} casing on the other),
     * falling the field through to the misleading three-option {@code resolveRecordParentSource} author
     * error; the comparison is now case-insensitive, the idiom the codebase uses for table-name comparison
     * everywhere else.
     *
     * <p>Pre-fix both children classify as {@code UnclassifiedField}; post-fix both are
     * {@code RecordTableField} (to-one {@code ONE}, to-many {@code MANY}) with empty diagnostics. Asserts
     * the classification verdict, not the case-insensitivity mechanism (an implementation detail); the
     * verdict under a casing mismatch is the behaviour.
     */
    @Test
    void caseMismatchedTableName_classifiesCompositeChildrenAsRecordTableField() {
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

        // The to-one child resolves through the record-backed accessor path despite the @table(name:)
        // casing ("FILM") differing from the lowercase jOOQ catalog name ("film").
        var film = schema.field("CreateFilmsResult", "film");
        assertThat(film).isInstanceOf(ChildField.RecordTableField.class);
        var filmRtf = (ChildField.RecordTableField) film;
        assertThat(filmRtf.sourceKey().target().tableName()).isEqualToIgnoringCase("film");
        assertThat(filmRtf.sourceKey().cardinality()).isEqualTo(SourceKey.Cardinality.ONE);

        // The to-many child likewise resolves (list cardinality), not dropped to UnclassifiedField.
        var actors = schema.field("CreateFilmsResult", "actors");
        assertThat(actors).isInstanceOf(ChildField.RecordTableField.class);
        var actorsRtf = (ChildField.RecordTableField) actors;
        assertThat(actorsRtf.sourceKey().target().tableName()).isEqualToIgnoringCase("actor");
        assertThat(actorsRtf.sourceKey().cardinality()).isEqualTo(SourceKey.Cardinality.MANY);

        assertThat(schema.diagnostics()).isEmpty();
    }
}
