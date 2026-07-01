package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema pipeline tests for the R105 record-backed parent multi-table
 * polymorphic ChildField classifier arm. Three permits become reachable through
 * {@code FieldBuilder.classifyChildFieldOnResultType}'s {@code PolymorphicReturnType} case:
 *
 * <ul>
 *   <li>{@link SourceKey.Reader.ColumnRead} on a {@link no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType}
 *       parent (hub = parent's mapped table).</li>
 *   <li>{@link SourceKey.Reader.AccessorCall} on a {@link no.sikt.graphitron.rewrite.model.GraphitronType.PojoResultType}
 *       parent with a single-cardinality typed accessor
 *       ({@link SourceKey.Cardinality#ONE}, hub = accessor's element-Record table).</li>
 *   <li>{@link SourceKey.Reader.AccessorCall} on a Pojo parent with a list / set typed accessor
 *       ({@link SourceKey.Cardinality#MANY}, hub = accessor's element-Record table).</li>
 * </ul>
 *
 * <p>Driven through the full SDL → classifier pipeline so the new arm is exercised, not bypassed
 * (fixture-helper construction of {@code InterfaceField} / {@code UnionField} would skip the arm
 * under test). Loader-dispatch shape is read off
 * {@code field.loaderRegistration().dispatch()} and key arity off
 * {@code field.parentSourceKey().columns().size()}; for the accessor-call permits the hub
 * identity is implicit in the {@code LiftedHop}'s {@code targetTable}.
 *
 * <p>Per the rewrite-design-principles, body-shape assertions on emitted method bodies are not
 * used; the {@code ColumnRead} variant pins TypeSpec equivalence with a table-backed parent
 * fixture so any drift across the two producers fails fast.
 */
@PipelineTier
class RecordParentMultiTablePolymorphicPipelineTest {

    /**
     * Two single-PK participants that both FK to {@code film}. {@code inventory.film_id →
     * film.film_id}; {@code content.film_id → film.film_id}. Uniform single-column PK arity
     * keeps {@code validateMultiTableParticipants} clean (composite-PK participants would fail
     * the arity-uniformity rule under the current validator).
     */
    private static final String UNION_PARTICIPANTS = """
        type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
        type Content @table(name: "content") { contentId: Int! @field(name: "content_id") }
        union FilmReferrer = Inventory | Content
        """;

    private static final String INTERFACE_PARTICIPANTS = """
        interface FilmReferrer { rowId: Int }
        type Inventory implements FilmReferrer @table(name: "inventory") {
          rowId: Int @field(name: "inventory_id")
          inventoryId: Int! @field(name: "inventory_id")
        }
        type Content implements FilmReferrer @table(name: "content") {
          rowId: Int @field(name: "content_id")
          contentId: Int! @field(name: "content_id")
        }
        """;

    // ===== InterfaceField siblings =====

    @Test
    void childInterfaceField_recordParent_rowKeyed() {
        // JooqTableRecordType-backed parent → hub = parent's mapped table = film →
        // SourceKey (Wrap.Row + ColumnRead) off film's PK (single column, film_id). The
        // classifier arm produced here is byte-for-byte equivalent to the table-backed branch's
        // construction when the table-backed parent is the same hub; pin TypeSpec equivalence to
        // surface any drift across the two producers.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type FilmInfo {
              referrers: [FilmReferrer!]!
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query {
              film: Film
              filmInfo: FilmInfo @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var field = (ChildField.InterfaceField) schema.field("FilmInfo", "referrers");
        assertThat(field.parentSourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(field.parentSourceKey().cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
        assertThat(field.parentSourceKey().columns()).hasSize(1);
        assertThat(field.parentSourceKey().columns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
    }

    @Test
    void childInterfaceField_recordParent_rowKeyed_typeSpecEqualsTableBacked() {
        // Two SDLs differing only in how Film acquires its film backing: a @service producer
        // returning FilmRecord (-> JooqTableRecordType) vs. @table(name: "film"). Both classify to
        // the same RowKeyed permit on the same hub, so the emitted FilmFetchers TypeSpec for the
        // polymorphic child field should be identical. Drift in either producer fails this
        // comparison.
        var recordParentSchema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film {
              referrers: [FilmReferrer!]!
            }
            type Query {
              film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var tableParentSchema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film @table(name: "film") {
              referrers: [FilmReferrer!]!
            }
            type Query { film: Film }
            """);
        var recordSpec = TypeFetcherGenerator.generate(recordParentSchema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();
        var tableSpec = TypeFetcherGenerator.generate(tableParentSchema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();
        var recordReferrers = recordSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("referrers")).findFirst().orElseThrow();
        var tableReferrers = tableSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("referrers")).findFirst().orElseThrow();
        assertThat(recordReferrers.toString()).isEqualTo(tableReferrers.toString());
        var recordRows = recordSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("rowsReferrers")).findFirst().orElseThrow();
        var tableRows = tableSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("rowsReferrers")).findFirst().orElseThrow();
        assertThat(recordRows.toString()).isEqualTo(tableRows.toString());
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedSingle() {
        // R367: Pojo parent (AccessorPayloads.SinglePayload) exposes `FilmRecord film()`. The
        // single-cardinality polymorphic child named `film` resolves to AccessorKeyedSingle on
        // the hub `film`; the scalar per-parent fetcher binds parentRecord to the accessor's
        // returned hub record (rather than casting env.getSource() to a jOOQ Record) and reads
        // the hub FK columns off it inline. Was previously deferred because the scalar fetcher
        // had no record-backed-parent arm.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type SinglePayloadType {
              film: FilmReferrer
            }
            type Query {
              sp: SinglePayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSinglePayload"})
            }
            """);
        var field = (ChildField.InterfaceField) schema.field("SinglePayloadType", "film");
        var psk = field.parentSourceKey();
        assertThat(psk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(psk.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
        assertThat(psk.target().tableName()).isEqualTo("film");
        assertThat(psk.columns()).hasSize(1);
        assertThat(psk.columns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(((SourceKey.Reader.AccessorCall) psk.reader()).accessor().methodName()).isEqualTo("film");
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedMany() {
        // Pojo parent (AccessorPayloads.ListPayload) exposes `List<FilmRecord> films()`. The
        // list-cardinality polymorphic child named `films` resolves to AccessorKeyedMany;
        // dispatch is LOAD_MANY (loader.loadMany returns one Record per element key).
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type ListPayloadType {
              films: [FilmReferrer!]!
            }
            type Query {
              lp: ListPayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorListPayload"})
            }
            """);
        var field = (ChildField.InterfaceField) schema.field("ListPayloadType", "films");
        var psk = field.parentSourceKey();
        assertThat(psk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(psk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(psk.target().tableName()).isEqualTo("film");
        assertThat(psk.columns()).hasSize(1);
        assertThat(psk.columns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(((SourceKey.Reader.AccessorCall) psk.reader()).accessor().methodName()).isEqualTo("films");
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedMany_fieldNameRemapsAccessor() {
        // R191: @field(name:) on a free-form record-backed parent remaps the accessor base name. The
        // Pojo parent (AccessorPayloads.ListPayload) exposes `List<FilmRecord> films()`; the SDL
        // field is named `referrers` and uses @field(name: "films") to bridge the divergence.
        // Without the directive-honored remap, the matcher would search for an accessor named
        // `referrers` / `getReferrers` / `isReferrers` and fall through to UnclassifiedField.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type ListPayloadType {
              referrers: [FilmReferrer!]! @field(name: "films")
            }
            type Query {
              lp: ListPayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorListPayload"})
            }
            """);
        var field = (ChildField.InterfaceField) schema.field("ListPayloadType", "referrers");
        var psk = field.parentSourceKey();
        assertThat(psk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(psk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(psk.target().tableName()).isEqualTo("film");
        // The carried method name is the actual accessor name (the directive value), not the SDL
        // field name.
        assertThat(((SourceKey.Reader.AccessorCall) psk.reader()).accessor().methodName())
            .isEqualTo("films");
    }

    // ===== UnionField siblings (mirror Interface; pin shape parity rather than re-verifying body) =====

    @Test
    void childUnionField_recordParent_rowKeyed() {
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type FilmInfo {
              referrers: [FilmReferrer!]!
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query {
              film: Film
              filmInfo: FilmInfo @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var field = (ChildField.UnionField) schema.field("FilmInfo", "referrers");
        assertThat(field.parentSourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(field.parentSourceKey().columns()).hasSize(1);
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
    }

    @Test
    void childUnionField_recordParent_accessorKeyedSingle() {
        // R367: same single-cardinality accessor resolution as the InterfaceField sibling.
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type SinglePayloadType {
              film: FilmReferrer
            }
            type Query {
              sp: SinglePayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSinglePayload"})
            }
            """);
        var field = (ChildField.UnionField) schema.field("SinglePayloadType", "film");
        var psk = field.parentSourceKey();
        assertThat(psk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(psk.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
        assertThat(psk.target().tableName()).isEqualTo("film");
        assertThat(((SourceKey.Reader.AccessorCall) psk.reader()).accessor().methodName()).isEqualTo("film");
    }

    @Test
    void childUnionField_recordParent_accessorKeyedMany() {
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type ListPayloadType {
              films: [FilmReferrer!]!
            }
            type Query {
              lp: ListPayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorListPayload"})
            }
            """);
        var field = (ChildField.UnionField) schema.field("ListPayloadType", "films");
        var psk = field.parentSourceKey();
        assertThat(psk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
        assertThat(psk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(psk.target().tableName()).isEqualTo("film");
    }

    // ===== Rejection arms =====

    @Test
    void recordParentPolymorphic_pojoWithoutMatchingAccessor_classifiesAsUnclassifiedField() {
        // DummyRecord exposes no typed TableRecord-returning accessor. The polymorphic child
        // field cannot derive a hub, so the classifier produces UnclassifiedField with the
        // three-option AUTHOR_ERROR (typed accessor / typed JooqTableRecord parent; the
        // @sourceRow route is currently deferred for polymorphic returns per spec Out of
        // scope).
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type DummyRecordType {
              films: [FilmReferrer!]!
            }
            type Query {
              dr: DummyRecordType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);
        var field = schema.field("DummyRecordType", "films");
        assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
        assertThat(unc.rejection().message())
            .contains("polymorphic child field 'films'")
            .contains("requires a typed accessor")
            .contains("HubRecord")
            .contains("@sourceRow is not yet supported for polymorphic returns");
    }

    // ===== R108: asymmetric-fragment fixture =====

    @Test
    void unionParticipants_sharedFieldNameBackedByDifferentColumns_classifiesAndGeneratesStage2Helpers() {
        // R108 asymmetric-fragment fixture: union participants Inventory and Content both expose
        // a `filmId` field, backed by their respective `film_id` columns on different tables.
        // The classifier produces a JooqTableRecordType-backed parent with a single-PK
        // hub on film; the generator emits Stage-2 per-typename helpers
        // (selectInventoryForReferrers, selectContentForReferrers) that each thread
        // env.getSelectionSet() through PolymorphicSelectionSet.restrictTo with their own
        // typename. The pin in PolymorphicProjectionFilterPinTest counts the source-level
        // emit; this pipeline-tier test confirms the full SDL → classify → emit path
        // produces both helpers from an asymmetric-fragment fixture.
        String asymmetricParticipants = """
            type Inventory @table(name: "inventory") {
              inventoryId: Int! @field(name: "inventory_id")
              filmId: Int! @field(name: "film_id")
            }
            type Content @table(name: "content") {
              contentId: Int! @field(name: "content_id")
              filmId: Int @field(name: "film_id")
            }
            union FilmReferrer = Inventory | Content
            """;
        var schema = TestSchemaHelper.buildSchema(asymmetricParticipants + """
            type Film {
              referrers: [FilmReferrer!]!
            }
            type Query {
              film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        // Classifier: the asymmetric fixture produces a UnionField with both participants
        // routed to a single-PK hub on film.
        var field = (ChildField.UnionField) schema.field("Film", "referrers");
        assertThat(field.participantJoinPaths().keySet())
            .containsExactlyInAnyOrder("Inventory", "Content");

        // Emit: each per-typename Stage-2 helper exists. The R108 PolymorphicSelectionSet wrap
        // is asserted at the source-emitter level by PolymorphicProjectionFilterPinTest; here
        // we pin that the full classify → generate pipeline yields both helpers for the
        // asymmetric fixture so the wrap reaches both branches.
        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();
        var helperNames = filmFetchers.methodSpecs().stream()
            .map(no.sikt.graphitron.javapoet.MethodSpec::name)
            .toList();
        assertThat(helperNames)
            .contains("selectInventoryForReferrers", "selectContentForReferrers");
    }

}
