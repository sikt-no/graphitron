package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.generators.ParentSourceBinding;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema pipeline tests for the record-backed parent multi-table
 * polymorphic ChildField classifier arm. Three permits are reachable through
 * {@code FieldBuilder.classifyChildFieldOnResultType}'s {@code PolymorphicReturnType} case:
 *
 * <ul>
 *   <li>{@link KeyLift.FkColumns} on a {@link no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType}
 *       parent (hub = parent's mapped table).</li>
 *   <li>{@link KeyLift.Accessor} on a {@link no.sikt.graphitron.rewrite.model.GraphitronType.PojoResultType}
 *       parent with a single-cardinality typed accessor ({@link Arity#ONE}).</li>
 *   <li>{@link KeyLift.Accessor} on a Pojo parent with a list / set typed accessor
 *       ({@link Arity#MANY}).</li>
 * </ul>
 *
 * <p>Driven through the full SDL → classifier pipeline so the arm under test is exercised, not
 * bypassed (fixture-helper construction of {@code InterfaceField} / {@code UnionField} would skip
 * it). Body-shape assertions on emitted method bodies are avoided per the development principles;
 * the {@link KeyLift.FkColumns} variant instead pins TypeSpec equivalence against a table-backed
 * parent fixture so any drift across the two producers fails fast.
 */
@PipelineTier
class RecordParentMultiTablePolymorphicPipelineTest {

    private static final ClassName COMPLETABLE_FUTURE = ClassName.get(CompletableFuture.class);

    /**
     * The given code as javapoet renders it inside a method body: emitted through the same
     * {@link MethodSpec} renderer a fetcher goes through, then stripped of the signature line and
     * the closing brace. Deriving the indented form is what lets a caller compare a producer's
     * output against an emitted method without transcribing either.
     */
    private static String asRenderedMethodBody(CodeBlock code) {
        var rendered = MethodSpec.methodBuilder("body").addCode(code).build().toString().lines().toList();
        return String.join("\n", rendered.subList(1, rendered.size() - 1)) + "\n";
    }

    /**
     * Two single-PK participants that both FK to {@code film}. Uniform single-column PK arity
     * keeps {@code validateMultiTableParticipants} clean (composite-PK participants would fail
     * its arity-uniformity rule).
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
        // JooqTableRecordType-backed parent: hub = parent's mapped table (film), so the classifier
        // lifts a SourceKey (Wrap.Row) via KeyLift.FkColumns off film's single-column PK.
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
        var field = (ChildField.BatchedInterfaceField) schema.field("FilmInfo", "referrers");
        assertThat(field.parentKeyLift()).isInstanceOf(KeyLift.FkColumns.class);
        assertThat(field.sourceKey().columns()).hasSize(1);
        assertThat(field.sourceKey().columns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
        // The row-keyed lift is one key per parent row, so the minted dispatch is LOAD_ONE.
        assertThat(field.loaderRegistration().dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(field.loaderRegistration().container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
    }

    @Test
    void childInterfaceField_recordParent_rowKeyed_typeSpecEqualsTableBacked() {
        // Two SDLs differing only in how Film acquires its film backing: a @service producer
        // returning FilmRecord (JooqTableRecordType) vs. @table(name: "film"). Both classify to
        // the same permit on the same hub, so the emitted FilmFetchers methods for the polymorphic
        // child field must be identical up to the parent-source binding: the record parent is
        // producer-handed, so its fetcher additionally carries the DirectRecord null-source guard
        // (the LocalContext errors transport fires data fetchers with data(null)); the table
        // parent's own projected row is never null mid-query, so its prelude is empty. Everything
        // else must match; drift in either producer fails this comparison.
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
        // Derived from the production producer rather than transcribed: the batched list fetcher
        // asks the same DirectRecord arm for a prelude over the same subject and escape, so this
        // compares two generated artifacts. A source literal here would instead pin javapoet's
        // rendering choices (indent width, qualified-vs-imported type name, statement form), none
        // of which is what this test claims.
        String nullSourceGuard = asRenderedMethodBody(new ParentSourceBinding.DirectRecord()
            .prelude(CodeBlock.of("env.getSource()"),
                     CodeBlock.of("$T.completedFuture(null)", COMPLETABLE_FUTURE)));
        assertThat(nullSourceGuard)
            .as("the DirectRecord arm mints a guard, so the containment below is not vacuous")
            .isNotBlank();
        assertThat(recordReferrers.toString())
            .as("the record parent's fetcher carries the DirectRecord null-source guard")
            .contains(nullSourceGuard);
        assertThat(recordReferrers.toString().replace(nullSourceGuard, ""))
            .as("modulo the parent-source binding's guard, the two producers must emit the same fetcher")
            .isEqualTo(tableReferrers.toString());
        var recordRows = recordSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("rowsReferrers")).findFirst().orElseThrow();
        var tableRows = tableSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("rowsReferrers")).findFirst().orElseThrow();
        assertThat(recordRows.toString()).isEqualTo(tableRows.toString());
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedSingle() {
        // Pojo parent (AccessorPayloads.SinglePayload) exposes `FilmRecord film()`. The
        // single-cardinality polymorphic child named `film` resolves to KeyLift.Accessor
        // (Arity.ONE) on the hub `film`; the scalar per-parent fetcher binds parentRecord to the
        // accessor's returned hub record rather than casting env.getSource() to a jOOQ Record.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type SinglePayloadType {
              film: FilmReferrer
            }
            type Query {
              sp: SinglePayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSinglePayload"})
            }
            """);
        var field = (ChildField.InterfaceField) schema.field("SinglePayloadType", "film");
        var psk = field.sourceKey();
        var lift = field.parentKeyLift();
        assertThat(lift).isInstanceOf(KeyLift.Accessor.class);
        assertThat(((KeyLift.Accessor) lift).arity()).isEqualTo(Arity.ONE);
        assertThat(field.parentKeyOwnerTable().tableName()).isEqualTo("film");
        assertThat(psk.columns()).hasSize(1);
        assertThat(psk.columns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(((KeyLift.Accessor) lift).accessor().methodName()).isEqualTo("film");
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedMany() {
        // Pojo parent (AccessorPayloads.ListPayload) exposes `List<FilmRecord> films()`; the
        // list-cardinality polymorphic child named `films` resolves to KeyLift.Accessor (Arity.MANY).
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type ListPayloadType {
              films: [FilmReferrer!]!
            }
            type Query {
              lp: ListPayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorListPayload"})
            }
            """);
        var field = (ChildField.BatchedInterfaceField) schema.field("ListPayloadType", "films");
        var psk = field.sourceKey();
        var lift = field.parentKeyLift();
        assertThat(lift).isInstanceOf(KeyLift.Accessor.class);
        assertThat(((KeyLift.Accessor) lift).arity()).isEqualTo(Arity.MANY);
        assertThat(field.parentKeyOwnerTable().tableName()).isEqualTo("film");
        assertThat(psk.columns()).hasSize(1);
        assertThat(psk.columns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(((KeyLift.Accessor) lift).accessor().methodName()).isEqualTo("films");
        // The accessor-many arm is the loader.loadMany dispatch, minted where the arity is
        // decided rather than re-derived at the emitter's load site.
        assertThat(field.loaderRegistration().dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_MANY);
    }

    @Test
    void childInterfaceField_recordParent_accessorKeyedMany_fieldNameRemapsAccessor() {
        // @field(name:) on a record-backed parent remaps the accessor base name: the SDL field
        // `referrers` bridges to the Pojo parent's `films()` accessor via @field(name: "films").
        // Without the remap the matcher would search for an accessor named `referrers` /
        // `getReferrers` / `isReferrers` and fall through to UnclassifiedField.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type ListPayloadType {
              referrers: [FilmReferrer!]! @field(name: "films")
            }
            type Query {
              lp: ListPayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorListPayload"})
            }
            """);
        var field = (ChildField.BatchedInterfaceField) schema.field("ListPayloadType", "referrers");
        var psk = field.sourceKey();
        var lift = field.parentKeyLift();
        assertThat(lift).isInstanceOf(KeyLift.Accessor.class);
        assertThat(((KeyLift.Accessor) lift).arity()).isEqualTo(Arity.MANY);
        assertThat(field.parentKeyOwnerTable().tableName()).isEqualTo("film");
        // The carried method name is the actual accessor name (the directive value), not the SDL
        // field name.
        assertThat(((KeyLift.Accessor) lift).accessor().methodName())
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
        var field = (ChildField.BatchedUnionField) schema.field("FilmInfo", "referrers");
        assertThat(field.parentKeyLift()).isInstanceOf(KeyLift.FkColumns.class);
        assertThat(field.sourceKey().columns()).hasSize(1);
        assertThat(field.participantJoinPaths().keySet()).containsExactlyInAnyOrder("Inventory", "Content");
    }

    @Test
    void childUnionField_recordParent_accessorKeyedSingle() {
        // Same single-cardinality accessor resolution as the InterfaceField sibling.
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type SinglePayloadType {
              film: FilmReferrer
            }
            type Query {
              sp: SinglePayloadType @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSinglePayload"})
            }
            """);
        var field = (ChildField.UnionField) schema.field("SinglePayloadType", "film");
        var psk = field.sourceKey();
        var lift = field.parentKeyLift();
        assertThat(lift).isInstanceOf(KeyLift.Accessor.class);
        assertThat(((KeyLift.Accessor) lift).arity()).isEqualTo(Arity.ONE);
        assertThat(field.parentKeyOwnerTable().tableName()).isEqualTo("film");
        assertThat(((KeyLift.Accessor) lift).accessor().methodName()).isEqualTo("film");
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
        var field = (ChildField.BatchedUnionField) schema.field("ListPayloadType", "films");
        var psk = field.sourceKey();
        var lift = field.parentKeyLift();
        assertThat(lift).isInstanceOf(KeyLift.Accessor.class);
        assertThat(((KeyLift.Accessor) lift).arity()).isEqualTo(Arity.MANY);
        assertThat(field.parentKeyOwnerTable().tableName()).isEqualTo("film");
        assertThat(field.loaderRegistration().dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_MANY);
    }

    // ===== Rejection arms =====

    @Test
    void recordParentPolymorphic_pojoWithoutMatchingAccessor_classifiesAsUnclassifiedField() {
        // DummyRecord exposes no typed TableRecord-returning accessor, so the polymorphic child
        // field cannot derive a hub and classifies as UnclassifiedField with the three-option
        // AUTHOR_ERROR.
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

    // ===== Asymmetric-fragment fixture =====

    @Test
    void unionParticipants_sharedFieldNameBackedByDifferentColumns_classifiesAndGeneratesStage2Helpers() {
        // Asymmetric-fragment fixture: union participants Inventory and Content both expose a
        // `filmId` field, backed by `film_id` columns on different tables. The generator emits
        // Stage-2 per-typename helpers (selectInventoryForReferrers, selectContentForReferrers)
        // that each thread env.getSelectionSet() through PolymorphicSelectionSet.restrictTo with
        // their own typename.
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

        var field = (ChildField.BatchedUnionField) schema.field("Film", "referrers");
        assertThat(field.participantJoinPaths().keySet())
            .containsExactlyInAnyOrder("Inventory", "Content");

        // The PolymorphicSelectionSet wrap itself is asserted at the source-emitter level by
        // PolymorphicProjectionFilterPinTest; here we pin that the full classify → generate
        // pipeline yields both helpers so the wrap reaches both branches.
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
