package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for the {@code @service}-backed producer admit for single-record
 * DML carrier data fields. Verifies that an {@code @service} mutation returning {@code XRecord}
 * (single-record carrier) or {@code List<XRecord>} (list-record carrier) lands a
 * {@link ChildField.BatchedTableField} on the carrier's data field (the former
 * {@code SingleRecordTableField} collapsed into it) keyed on a source=target re-fetch key
 * ({@link SourceKey.Reader.ProducedRecordRead} + {@link SourceKey.Wrap.Row}, the PK read off the
 * produced record(s)). The {@code DIRECT} / {@code OUTCOME_SUCCESS} source envelope is no longer
 * carried on the SourceKey; the generator derives it at the type level. Rejection cases pin the
 * strict-return predicate and the single-producer-kind invariant.
 */
@PipelineTier
class SingleRecordTableFieldServiceProducerPipelineTest {

    // ===== Admission cases =====

    /** ONE / single-PK admission: @service returning {@code FilmRecord} for a non-list data field. */
    @Test
    void serviceProducer_one_singlePk_admitsAsRecordTableField() {
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
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var rtf = (ChildField.BatchedTableField) dataField;
        var sk = rtf.sourceKey();
        // Source=target re-fetch key — ProducedRecordRead reads the PK off the produced
        // record, Wrap.Row carries the PK tuple. The DIRECT/OUTCOME_SUCCESS envelope is no longer
        // on the SourceKey (the generator derives it at the type level).
        assertThat(rtf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.ONE));
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(rtf.joinPath()).isEmpty();
        assertThat(rtf.parentCorrelation())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots.class);
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    /**
 * The source-record carrier with an error channel. Adding an {@code errors} field to
     * the payload routes the {@code @service} producer through the typed {@code Outcome} wrapper; the
     * data field collapses into {@link ChildField.BatchedTableField} (ProducedRecordRead source=target
     * re-fetch) and the sibling errors field is the {@code WrapperArm} transport. The
     * {@code OUTCOME_SUCCESS} envelope is no longer recorded on the data field's SourceKey — the
     * generator derives it from the payload's error channel at the type level. This is the opptak
     * {@code { sak: Sak, errors: [...] }} shape that buckets B/D failed on before service-carrier
     * error channels were supported.
     */
    @Test
    void serviceProducer_withErrorsField_collapsesToRecordTableField() {
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

        // The sibling errors field rides the WrapperArm transport (the Outcome.ErrorList arm).
        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
    }

    /**
     * The {@code @splitQuery}-list source-record carrier (the opptak
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
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(((ChildField.BatchedTableField) dataField).lift())
            .isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
                pr -> assertThat(pr.arity()).isEqualTo(Arity.MANY));
        var errorsField = schema.field("FilmListPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmListPayload.films")
                && m.contains("@splitQuery is redundant on a record-backed parent field"));
    }

    /**
     * Formerly {@code serviceProducer_idElementCarrier_rejectsLoudly},
     * which pinned the interim loud rejection: the {@code [ID] @nodeId} data field on an
     * errors-bearing {@code @service} carrier classifies as {@code SingleRecordIdField} —
     * MANY cardinality, {@code OUTCOME_SUCCESS} envelope, the Film node encoder on the
     * compaction — encoding node ids straight off the producer's in-memory records with no
     * re-fetch. Note the list-of-nullable {@code [ID]} wrapper (the real opptak
     * {@code fjernSakTagger} contract): the DML DELETE scan rejects it, the @service-carrier
     * scan admits it.
     */
    @Test
    void serviceProducer_idElementCarrier_list_encodesFromRecords() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
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

        assertThat(schema.field("Mutation", "deleteFilms"))
            .isInstanceOf(MutationField.MutationServiceRecordField.class);
        var dataField = schema.field("FilmIdsPayload", "filmIds");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordIdField.class);
        var idField = (ChildField.SingleRecordIdField) dataField;
        var sk = idField.sourceKey();
        assertThat(idField.returnType().wrapper().isList()).isTrue();
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.TableRecord.class);
        assertThat(idField.envelope())
            .isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.OUTCOME_SUCCESS);
        assertThat(idField.table().tableName()).isEqualTo("film");
        assertThat(idField.encode().encodeMethod()).isNotNull();
        var errorsField = schema.field("FilmIdsPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
    }

    /**
     * Single arm (the opptak {@code fjernSakTagg -> { taggId: ID @nodeId,
     * errors }} shape): a single-record producer into an {@code ID @nodeId} data field
     * classifies with ONE cardinality and the {@code OUTCOME_SUCCESS} envelope.
     */
    @Test
    void serviceProducer_idElementCarrier_single_encodesFromRecord() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union FilmError = DbErr
            type FilmIdPayload {
                filmId: ID @nodeId(typeName: "Film")
                errors: [FilmError]
            }
            type Query { x: String }
            type Mutation {
                deleteFilm: FilmIdPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        assertThat(schema.field("Mutation", "deleteFilm"))
            .isInstanceOf(MutationField.MutationServiceRecordField.class);
        var dataField = schema.field("FilmIdPayload", "filmId");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordIdField.class);
        var idField = (ChildField.SingleRecordIdField) dataField;
        assertThat(idField.returnType().wrapper().isList()).isFalse();
        assertThat(idField.envelope())
            .isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.OUTCOME_SUCCESS);
    }

    /**
     * Errors-less sibling: with no errors field the producer returns the
     * record bare, so the envelope is {@code DIRECT} — the same envelope split the
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
     * Grounding failure stays a loud author error: the producer's record
     * class ({@code LanguageRecord}) does not match {@code @nodeId(typeName: "Film")}'s table
     * record, so no {@code ServiceEmitted} binding grounds, the payload never promotes, and
     * the orphan-carrier guard rejects the mutation field with the ID-element guidance.
     */
    @Test
    void serviceProducer_idElementCarrier_recordClassMismatch_rejectsLoudly() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
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
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguagesAsList"})
            }
            """);

        var mutField = schema.field("Mutation", "deleteFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) mutField).rejection().message())
            .contains("FilmIdsPayload", "ID-element", "@nodeId(typeName: T)");
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
    void serviceProducer_many_singlePk_admitsAsRecordTableField() {
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
        var rtf = (ChildField.BatchedTableField) dataField;
        var sk = rtf.sourceKey();
        assertThat(rtf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
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
        var rtf = (ChildField.BatchedTableField) dataField;
        var sk = rtf.sourceKey();
        assertThat(rtf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.MANY));
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        // Composite PK: both columns in declaration order from the catalog.
        assertThat(sk.columns()).extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
    }

    /**
 * The coherent <em>list</em> carrier ({@code @service...: [FilmPayload]}) whose payload has
     * a single {@code @table} data field, produced by {@code List<FilmRecord>}. graphql-java iterates
     * the producer list into the {@code [FilmPayload]} list, so each element is one payload whose
     * single {@code film} resolves through a {@code LOAD_ONE} that coalesces into one batched
     * rows-method query. This shape worked by accident before the shape verdict existed (no test
     * exercised it at any tier); the shape verdict now admits it explicitly and this test pins the
     * BatchedTableField model +
     * LOAD_ONE dispatch. Contrast the single-carrier {@code FilmListPayload { films: [Film!] }} above,
     * whose list data field is filled by the same producer list.
     */
    @Test
    void serviceProducer_listCarrier_singleTableDataField_admitsBatchedLoadOne() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            type Mutation {
                runFilms: [FilmPayload]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        assertThat(schema.field("Mutation", "runFilms"))
            .isInstanceOf(MutationField.MutationServiceRecordField.class);
        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var rtf = (ChildField.BatchedTableField) dataField;
        assertThat(rtf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.ONE));
        // Each payload element's single film re-fetches through LOAD_ONE; graphql-java coalesces the
        // per-element loads into one batched rows-method query (proven end-to-end at the execution tier).
        assertThat(rtf.loaderRegistration().dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(schema.diagnostics()).isEmpty();
    }

    // ===== Rejection cases =====

    /**
     * Case a1 (formerly a silent admit): a <em>list</em> carrier ({@code [FilmPayload]}) with a single
     * {@code @table} data field, produced by a <em>single</em> {@code FilmRecord}. graphql-java cannot
     * iterate a single record into the {@code [FilmPayload]} list, so list coercion fails at runtime.
     * The shape verdict rejects at classify time with the typed
     * {@link ServiceCarrierShapeError.ProducerArrivalMismatch}, naming the carrier-vs-producer arrival
     * mismatch and the {@code List<…>} fix (before the shape verdict the return-match gate
     * short-circuited on the null {@code fqClassName} and this shape built green).
     */
    @Test
    void serviceProducer_listCarrier_singleProducer_rejectsProducerArrivalMismatch() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            type Mutation {
                runFilms: [FilmPayload]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) mutField).rejection();
        assertThat(rejection).isInstanceOf(ServiceCarrierShapeError.ProducerArrivalMismatch.class);
        assertThat(rejection.message())
            .contains("[FilmPayload]", "single value", "List<…>", "runFilm");
    }

    /**
     * Case a2 (formerly a silent admit broken at runtime): a <em>list</em> carrier
     * ({@code [FilmListPayload]}) whose {@code @table} data field is <em>itself</em> a list
     * ({@code films: [Film!]}), produced by a flat {@code List<FilmRecord>}. The producer list is
     * consumed element-by-element into the {@code [FilmListPayload]} carrier, so a single record reaches
     * each payload and cannot populate the list-valued {@code films} — the per-element
     * {@code ClassCastException} the acceptance axiom forbids. The shape verdict rejects with the typed
     * {@link ServiceCarrierShapeError.DataFieldArrivalConflict}.
     */
    @Test
    void serviceProducer_listCarrier_listDataField_rejectsDataFieldArrivalConflict() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                runFilms: [FilmListPayload]
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) mutField).rejection();
        assertThat(rejection).isInstanceOf(ServiceCarrierShapeError.DataFieldArrivalConflict.class);
        assertThat(rejection.message())
            .contains("[FilmListPayload]", "films", "element-by-element");
    }

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
     * Mixed-producer carrier reject (DML-first declaration order). Pins that the
     * conflict surfaces regardless of declaration order; the sibling test pins the other direction.
     *
     * <p>The builder-side demote-to-{@link UnclassifiedField} post-pass was retired: the two
     * producer mutations now stay classified as their producer leaves, and the disagreement rides on
     * the model as a {@code MultiProducerDomainTypeDisagreement} the validator surfaces as a single
     * {@link no.sikt.graphitron.rewrite.ValidationError}. The message names the payload SDL type, both
     * producer coords, and both {@code DomainReturnType} arms ({@code Record(film)} and
     * {@code TableRecord(FilmRecord)}).
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

        // The builder no longer demotes the producers; they stay classified and the conflict is
        // carried on the model for the validator to surface.
        assertThat(schema.field("Mutation", "createFilms")).isInstanceOf(OutputField.class);
        assertThat(schema.field("Mutation", "runFilms")).isInstanceOf(OutputField.class);

        var conflicts = new GraphitronSchemaValidator().validate(schema).stream()
            .filter(e -> e.rejection() instanceof Rejection.AuthorError.MultiProducerDomainTypeDisagreement)
            .toList();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).message())
            .contains("FilmListPayload", "createFilms", "runFilms", "Record(film)", "TableRecord(FilmRecord)");
    }

    /**
     * Mixed-producer carrier reject (@service-first declaration order). Pins that
     * the conflict surfaces regardless of declaration order; the sibling test pins the other
     * direction.
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

        assertThat(schema.field("Mutation", "createFilms")).isInstanceOf(OutputField.class);
        assertThat(schema.field("Mutation", "runFilms")).isInstanceOf(OutputField.class);

        var conflicts = new GraphitronSchemaValidator().validate(schema).stream()
            .filter(e -> e.rejection() instanceof Rejection.AuthorError.MultiProducerDomainTypeDisagreement)
            .toList();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).message())
            .contains("FilmListPayload", "createFilms", "runFilms", "Record(film)", "TableRecord(FilmRecord)");
    }
}
