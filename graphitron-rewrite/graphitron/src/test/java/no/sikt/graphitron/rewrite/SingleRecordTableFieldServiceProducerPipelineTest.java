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
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(((SourceKey.Wrap.TableRecord) sk.wrap()).className())
            .isEqualTo(sk.target().recordClass());
        assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
        assertThat(sk.path()).isEmpty();
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
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
     * Mixed-producer carrier reject (DML-first registration): a DML mutation registers the
     * carrier coord with {@code Wrap.Record}; a sibling @service mutation returning the same
     * carrier with {@code Wrap.TableRecord} is rejected at classify time with a diagnostic
     * naming both producer mutations.
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

        // The DML mutation classified first (declaration order) and wrote Wrap.Record at the
        // (FilmListPayload, films) coord. The @service mutation sees the existing Wrap.Record
        // and rejects.
        var serviceMut = schema.field("Mutation", "runFilms");
        assertThat(serviceMut).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) serviceMut).rejection().message();
        assertThat(reason).contains("runFilms", "createFilms", "Wrap.Record", "Wrap.TableRecord");

        // The first producer's classification still stands: the data field carries the DML's
        // Wrap.Record registration.
        var dataField = schema.field("FilmListPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        assertThat(srtf.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.Record.class);
    }

    /**
     * Mixed-producer carrier reject (@service-first registration): an @service mutation
     * registers the carrier coord with {@code Wrap.TableRecord(FilmRecord)}; a sibling DML
     * mutation returning the same carrier with {@code Wrap.Record} is rejected at classify
     * time with a diagnostic naming both producer mutations. Order-independence pin.
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

        // The @service mutation classified first and wrote Wrap.TableRecord at the coord. The
        // DML mutation sees the existing Wrap.TableRecord and rejects.
        var dmlMut = schema.field("Mutation", "createFilms");
        assertThat(dmlMut).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) dmlMut).rejection().message();
        assertThat(reason).contains("createFilms", "runFilms", "Wrap.Record", "Wrap.TableRecord");

        // The first producer's classification still stands: the data field carries the
        // @service's Wrap.TableRecord registration.
        var dataField = schema.field("FilmListPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        assertThat(srtf.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
    }
}
