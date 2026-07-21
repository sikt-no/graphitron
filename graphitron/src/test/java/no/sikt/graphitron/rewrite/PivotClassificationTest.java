package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.ReachableSourceShape;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified model for {@code @pivot} (the dimensional verdict itself is pinned by the
 * {@code pivot} entry in {@code ClassifiedCorpus}; these cases pin the leaf payloads and the
 * type-level facts the corpus's {@code @classified} dimensions cannot express).
 */
@PipelineTier
class PivotClassificationTest {

    private static final String VOCABULARY_SCHEMA = """
        enum Sprak {
          nn @field(name: "nno")
          nb @field(name: "nob")
        }
        type TranslatedTexts { nn: String nb: String }
        type Film @table(name: "film") {
          titleTexts: TranslatedTexts
            @reference(path: [{table: "film_translation"}])
            @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
        }
        type Query { film: Film }
        """;

    @Test
    void pivotField_carriesResolvedSpecWithVocabularyTokenMap() {
        var schema = TestSchemaHelper.buildSchema(VOCABULARY_SCHEMA);
        var pf = (ChildField.PivotField) schema.field("Film", "titleTexts");
        var spec = pf.spec();
        assertThat(spec.pivotTable().tableName()).isEqualTo("film_translation");
        assertThat(spec.discriminator().sqlName()).isEqualTo("lang_code");
        assertThat(spec.value().sqlName()).isEqualTo("title_txt");
        assertThat(spec.projectionTypeName()).isEqualTo("TranslatedTexts");
        assertThat(spec.tokenBySlot())
            .containsExactlyInAnyOrderEntriesOf(Map.of("nn", "nno", "nb", "nob"));
        assertThat(spec.slots())
            .extracting(ChildField.PivotSlotField::name, ChildField.PivotSlotField::readName)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("nn", "nn"),
                org.assertj.core.groups.Tuple.tuple("nb", "nb"));
        assertThat(pf.sourceShape()).isEqualTo(SourceShape.Table);
    }

    @Test
    void pivotProjectionType_registersAsOrdinaryNestingType() {
        var schema = TestSchemaHelper.buildSchema(VOCABULARY_SCHEMA);
        assertThat(schema.type("TranslatedTexts")).isInstanceOf(GraphitronType.NestingType.class);
    }

    @Test
    void identityMapping_whenVocabularyOmitted() {
        var schema = TestSchemaHelper.buildSchema("""
            type PriceByCurrency { NOK: Float EUR: Float }
            type Film @table(name: "film") {
              prices: PriceByCurrency
                @reference(path: [{table: "film_price"}])
                @pivot(on: "currency_code", value: "amount")
            }
            type Query { film: Film }
            """);
        var pf = (ChildField.PivotField) schema.field("Film", "prices");
        assertThat(pf.spec().tokenBySlot())
            .containsExactlyInAnyOrderEntriesOf(Map.of("NOK", "NOK", "EUR", "EUR"));
    }

    @Test
    void splitQuery_classifiesBatchedPivotFieldWithDerivedSourceKey() {
        var schema = TestSchemaHelper.buildSchema("""
            type TranslatedTexts { nob: String nno: String }
            type Film @table(name: "film") {
              titleTexts: TranslatedTexts @splitQuery
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            type Query { film: Film }
            """);
        var bpf = (ChildField.BatchedPivotField) schema.field("Film", "titleTexts");
        // The batch keys on the FK source-side column (the parent's film_id), lifted by column
        // projection off the table row and dispatched LOAD_ONE — the same triple the batched
        // table shape derives.
        assertThat(bpf.sourceKey().columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
        assertThat(bpf.lift()).isInstanceOf(KeyLift.FkColumns.class);
        assertThat(bpf.loaderRegistration().dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(bpf.emitsSingleRecordPerKey()).isTrue();
        assertThat(bpf.spec().tokenBySlot())
            .containsExactlyInAnyOrderEntriesOf(Map.of("nob", "nob", "nno", "nno"));
    }

    @Test
    void compositeKeyReference_carriesArityTwoCorrelation() {
        var schema = TestSchemaHelper.buildSchema("""
            type TranslatedTexts { nob: String eng: String }
            type FilmActor @table(name: "film_actor") {
              notes: TranslatedTexts
                @reference(path: [{table: "film_actor_note"}])
                @pivot(on: "lang_code", value: "note_txt")
            }
            type Query { filmActor: FilmActor }
            """);
        var pf = (ChildField.PivotField) schema.field("FilmActor", "notes");
        // The correlation is arity-generic: the composite FK contributes one column pair per
        // key column, and the projection itself never touches the key.
        assertThat(pf.spec().pairs().slotCount()).isEqualTo(2);
        assertThat(pf.spec().pairs().sourceSideColumns())
            .extracting(c -> c.sqlName()).containsExactlyInAnyOrder("actor_id", "film_id");
    }

    /**
     * Dual use in one schema: reached by a {@code @pivot} field the type is a pivot record;
     * reached by a plain field on a compatible {@code @table} parent it is an ordinary nesting
     * object. Both edges classify with no conflict — the pivot sibling of
     * {@code GraphitronSchemaBuilderTest.SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE}.
     */
    @Test
    void pivotAndNestingEdges_coexistOnOneProjectionType() {
        var schema = TestSchemaHelper.buildSchema("""
            type TranslatedTexts { nn: String nb: String }
            type Film @table(name: "film") {
              titleTexts: TranslatedTexts
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            type PivotNestingHost @table(name: "pivot_nesting_host") {
              texts: TranslatedTexts
            }
            type Query { film: Film host: PivotNestingHost }
            """);
        assertThat(schema.field("Film", "titleTexts")).isInstanceOf(ChildField.PivotField.class);
        var nesting = (ChildField.NestingField) schema.field("PivotNestingHost", "texts");
        assertThat(nesting.nestedFields()).allMatch(f -> f instanceof ChildField.ColumnBackedField);
        assertThat(schema.type("TranslatedTexts")).isInstanceOf(GraphitronType.NestingType.class);
        assertThat(no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate(schema)).isEmpty();
    }

    /**
     * Class-backed coexistence: a {@code @service} producer also reaches the projection type, so
     * each slot coordinate's classifier-known shape set is the two-shape union (generic Record +
     * class-backed accessor), in both registration orders — the first-wins registry entry does
     * not drive the arm set.
     */
    @Test
    void classBackedProducerCoexistence_unionsShapeSet_bothOrders() {
        String pivotFirst = """
            type TranslatedTexts { nn: String nb: String }
            type Film @table(name: "film") {
              titleTexts: TranslatedTexts
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            type Query {
              film: Film
              serviceTexts: TranslatedTexts
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeTranslatedTexts"})
            }
            """;
        String producerFirst = """
            type TranslatedTexts { nn: String nb: String }
            type Query {
              serviceTexts: TranslatedTexts
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeTranslatedTexts"})
              film: Film
            }
            type Film @table(name: "film") {
              titleTexts: TranslatedTexts
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            """;
        for (String sdl : new String[] {pivotFirst, producerFirst}) {
            var schema = TestSchemaHelper.buildSchema(sdl);
            assertThat(schema.field("Film", "titleTexts")).isInstanceOf(ChildField.PivotField.class);
            for (String slot : new String[] {"nn", "nb"}) {
                assertThat(schema.reachableSourceShapes("TranslatedTexts", slot))
                    .as("slot coordinate TranslatedTexts.%s carries the two-shape union", slot)
                    .containsExactlyInAnyOrder(
                        ReachableSourceShape.NESTING_RECORD,
                        ReachableSourceShape.CLASS_BACKED_ACCESSOR);
            }
            assertThat(no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate(schema)).isEmpty();
        }
    }
}
