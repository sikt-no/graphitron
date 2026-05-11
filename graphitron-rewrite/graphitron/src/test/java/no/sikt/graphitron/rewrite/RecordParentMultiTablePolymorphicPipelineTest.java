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
 * SDL → classified schema pipeline tests for the R105 {@code @record}-parent multi-table
 * polymorphic ChildField classifier arm. Three permits become reachable through
 * {@code FieldBuilder.classifyChildFieldOnResultType}'s {@code PolymorphicReturnType} case:
 *
 * <ul>
 *   <li>{@link BatchKey.RowKeyed} on a {@link no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType}
 *       parent (hub = parent's mapped table).</li>
 *   <li>{@link BatchKey.AccessorKeyedSingle} on a {@link no.sikt.graphitron.rewrite.model.GraphitronType.PojoResultType}
 *       parent with a single-cardinality typed accessor (hub = accessor's element-Record table).</li>
 *   <li>{@link BatchKey.AccessorKeyedMany} on a Pojo parent with a list / set typed accessor
 *       (hub = accessor's element-Record table).</li>
 * </ul>
 *
 * <p>Driven through the full SDL → classifier pipeline so the new arm is exercised, not bypassed
 * (fixture-helper construction of {@code InterfaceField} / {@code UnionField} would skip the arm
 * under test). Loader-dispatch shape is read off {@code field.parentKey().dispatch()} and key
 * arity off {@code field.parentKey().preludeKeyColumns().size()}; for the accessor-keyed
 * permits the hub identity is implicit in the {@code LiftedHop}'s {@code targetTable}.
 *
 * <p>Per the rewrite-design-principles, body-shape assertions on emitted method bodies are not
 * used; the {@code RowKeyed} variant pins TypeSpec equivalence with a table-backed parent
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
        // JooqTableRecordType-backed @record parent → hub = parent's mapped table = film →
        // BatchKey.RowKeyed off film's PK (single column, film_id). The classifier arm produced
        // here is byte-for-byte equivalent to the table-backed branch's RowKeyed construction
        // when the table-backed parent is the same hub; pin TypeSpec equivalence to surface any
        // drift across the two producers.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type FilmInfo @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              referrers: [FilmReferrer!]!
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
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
        // Two SDLs differing only in the parent declaration: @record(record: FilmRecord) vs.
        // @table(name: "film"). Both classify to the same RowKeyed permit on the same hub, so
        // the emitted FilmFetchers TypeSpec for the polymorphic child field should be
        // identical. Drift in either producer fails this comparison.
        var recordParentSchema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              referrers: [FilmReferrer!]!
            }
            type Query { film: Film }
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
    void childInterfaceField_recordParent_accessorKeyedSingle_deferred() {
        // Pojo parent (AccessorPayloads.SinglePayload) with a single-cardinality polymorphic
        // child is currently deferred at the classifier: MultiTablePolymorphicEmitter's
        // single-cardinality arm (buildScalarPerParentFetcher) reads parent context as a jOOQ
        // Record (env.getSource() cast) and has no @record-Pojo arm; producing
        // AccessorKeyedSingle here would generate code that ClassCastExceptions on a Pojo
        // source. The follow-up to lift this restriction is to widen
        // buildScalarPerParentFetcher analogously to the list-cardinality arm. List cardinality
        // (AccessorKeyedMany) is reachable because the list arm is DataLoader-batched and
        // already routes through the parentKey-aware buildBatchedListFetcher.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type SinglePayloadType @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$SinglePayload"}) {
              film: FilmReferrer
            }
            type Query { sp: SinglePayloadType }
            """);
        var field = schema.field("SinglePayloadType", "film");
        assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
        assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.DEFERRED);
        assertThat(unc.reason())
            .contains("single-cardinality polymorphic child field 'film'")
            .contains("@record (Pojo / JavaRecord) parent")
            .contains("not yet supported");
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedMany() {
        // Pojo parent (AccessorPayloads.ListPayload) exposes `List<FilmRecord> films()`. The
        // list-cardinality polymorphic child named `films` resolves to AccessorKeyedMany;
        // dispatch is LOAD_MANY (loader.loadMany returns one Record per element key).
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type ListPayloadType @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$ListPayload"}) {
              films: [FilmReferrer!]!
            }
            type Query { lp: ListPayloadType }
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

    // ===== UnionField siblings (mirror Interface; pin shape parity rather than re-verifying body) =====

    @Test
    void childUnionField_recordParent_rowKeyed() {
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type FilmInfo @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              referrers: [FilmReferrer!]!
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);
        var field = (ChildField.UnionField) schema.field("FilmInfo", "referrers");
        assertThat(field.parentSourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(field.parentSourceKey().columns()).hasSize(1);
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
    }

    @Test
    void childUnionField_recordParent_accessorKeyedSingle_deferred() {
        // Same single-cardinality deferral as the InterfaceField sibling.
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type SinglePayloadType @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$SinglePayload"}) {
              film: FilmReferrer
            }
            type Query { sp: SinglePayloadType }
            """);
        var field = schema.field("SinglePayloadType", "film");
        assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
        assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.DEFERRED);
    }

    @Test
    void childUnionField_recordParent_accessorKeyedMany() {
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type ListPayloadType @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$ListPayload"}) {
              films: [FilmReferrer!]!
            }
            type Query { lp: ListPayloadType }
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
            type DummyRecordType @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              films: [FilmReferrer!]!
            }
            type Query { dr: DummyRecordType }
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

}
