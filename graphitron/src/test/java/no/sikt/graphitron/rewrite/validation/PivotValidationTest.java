package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.PivotError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @pivot} negatives: every rejection in the pivot admission surface fails the build with
 * its typed {@link PivotError} arm. The classify-time arms surface via {@link UnclassifiedField}
 * (asserted on the rejection's type and message); the validate-time duplicate-token arm surfaces
 * through {@code GraphitronSchemaValidator.validatePivotSpec}.
 */
@PipelineTier
class PivotValidationTest {

    private static Rejection rejectionOf(GraphitronSchema schema, String type, String field) {
        var classified = schema.field(type, field);
        assertThat(classified).isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) classified).rejection();
    }

    private static String pivotFilmSchema(String projectionType, String fieldDef) {
        return projectionType + "\n"
            + "type Film @table(name: \"film\") {\n" + fieldDef + "\n}\n"
            + "type Query { film: Film }\n";
    }

    @Test
    void nonNullSlot_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String! nno: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        var rejection = rejectionOf(schema, "Film", "texts");
        assertThat(rejection).isInstanceOf(PivotError.NonNullSlot.class);
        assertThat(rejection.message()).contains("Texts.nob").contains("nullable");
    }

    @Test
    void nonScalarSlot_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Inner { x: String }\ntype Texts { nob: Inner nno: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.NonScalarSlot.class);
    }

    @Test
    void listSlot_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: [String] nno: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.NonScalarSlot.class);
    }

    @Test
    void divergentSlotTypes_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String nno: Int }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        var rejection = rejectionOf(schema, "Film", "texts");
        assertThat(rejection).isInstanceOf(PivotError.DivergentSlotType.class);
        assertThat(rejection.message()).contains("nno").contains("String").contains("Int");
    }

    @Test
    void vocabularyNotAnEnum_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt", vocabulary: "Texts")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.VocabularyNotTextEnum.class);
    }

    @Test
    void slotMissingFromVocabulary_rejected_namingSlotAndEnum() {
        var schema = TestSchemaHelper.buildSchema(
            "enum Sprak { nb @field(name: \"nob\") }\n"
            + pivotFilmSchema(
                "type Texts { nb: String se: String }",
                """
                texts: Texts @reference(path: [{table: "film_translation"}])
                    @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
                """));
        var rejection = rejectionOf(schema, "Film", "texts");
        assertThat(rejection).isInstanceOf(PivotError.SlotMissingFromVocabulary.class);
        assertThat(rejection.message()).contains("se").contains("Sprak").contains("nb");
    }

    @Test
    void duplicateSlotToken_rejectedAtValidateTime() {
        var schema = TestSchemaHelper.buildSchema(
            "enum Sprak { nb @field(name: \"nob\") bokmaal @field(name: \"nob\") }\n"
            + pivotFilmSchema(
                "type Texts { nb: String bokmaal: String }",
                """
                texts: Texts @reference(path: [{table: "film_translation"}])
                    @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
                """));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("nob") && m.contains("distinct token"));
    }

    @Test
    void unresolvedOnColumn_rejected_withCandidates() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "no_such_column", value: "title_txt")
            """));
        var rejection = rejectionOf(schema, "Film", "texts");
        assertThat(rejection).isInstanceOf(PivotError.ColumnUnresolved.class);
        assertThat(rejection.message())
            .contains("no_such_column").contains("film_translation").contains("lang_code");
    }

    @Test
    void unresolvedValueColumn_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "no_such_column")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.ColumnUnresolved.class);
    }

    @Test
    void valueTypeMismatch_rejected() {
        // amount is numeric; a String-slotted projection cannot be produced from it.
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { NOK: String }",
            """
            texts: Texts @reference(path: [{table: "film_price"}])
                @pivot(on: "currency_code", value: "amount")
            """));
        var rejection = rejectionOf(schema, "Film", "texts");
        assertThat(rejection).isInstanceOf(PivotError.ValueTypeMismatch.class);
        assertThat(rejection.message()).contains("amount").contains("String");
    }

    @Test
    void listReturn_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String }",
            """
            texts: [Texts] @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.ListReturn.class);
    }

    @Test
    void multiHopReferencePath_rejected() {
        // film_actor -> film -> film_translation: two chained FK hops; the batched delivery
        // cannot key-preserve the chain (bridging hops are inner joins).
        var schema = TestSchemaHelper.buildSchema("""
            type Texts { nob: String }
            type FilmActor @table(name: "film_actor") {
              texts: Texts
                @reference(path: [{key: "film_actor_film_id_fkey"}, {table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            type Query { filmActor: FilmActor }
            """);
        var rejection = rejectionOf(schema, "FilmActor", "texts");
        assertThat(rejection).isInstanceOf(PivotError.UnsupportedReferencePath.class);
        assertThat(rejection.message()).contains("2 hops");
    }

    @Test
    void missingReferencePath_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String }",
            """
            texts: Texts @pivot(on: "lang_code", value: "title_txt")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.UnsupportedReferencePath.class);
    }

    @Test
    void tableBackedReturnType_rejected() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts @table(name: \"language\") { name: String }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        assertThat(rejectionOf(schema, "Film", "texts"))
            .isInstanceOf(PivotError.InvalidProjectionType.class);
    }

    @Test
    void recordBackedParent_rejected_withoutSuggestingSplitQuery() {
        var schema = TestSchemaHelper.buildSchema("""
            type Texts { nob: String }
            type Holder {
              texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            type Query {
              holder: Holder
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makePivotHolder"})
            }
            """);
        var rejection = rejectionOf(schema, "Holder", "texts");
        assertThat(rejection).isInstanceOf(PivotError.RecordBackedParent.class);
        assertThat(rejection.message())
            .contains("Holder")
            .doesNotContain("@splitQuery");
    }

    @Test
    void rootField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Texts { nob: String }
            type Query {
              texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            """);
        assertThat(rejectionOf(schema, "Query", "texts"))
            .isInstanceOf(PivotError.RecordBackedParent.class);
    }

    @Test
    void classifyTimeRejections_surfaceThroughValidate() {
        var schema = TestSchemaHelper.buildSchema(pivotFilmSchema(
            "type Texts { nob: String! }",
            """
            texts: Texts @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            """));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Texts.nob") && m.contains("nullable"));
    }
}
