package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
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
