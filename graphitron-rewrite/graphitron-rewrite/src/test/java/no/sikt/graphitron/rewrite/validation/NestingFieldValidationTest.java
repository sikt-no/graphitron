package no.sikt.graphitron.rewrite.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NestingFieldValidationTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());
    private static final TableRef ADVERTISEMENT_TABLE = new TableRef("advertisement", "ADVERTISEMENT", "Advertisement", List.of());

    private static NestingField nestingField(String parent, String name, List<ChildField> nested, TableRef parentTable) {
        return new NestingField(parent, name, null,
            new ReturnTypeRef.TableBoundReturnType("FilmDetails", parentTable, new FieldWrapper.Single(true)),
            nested);
    }

    private static ColumnField titleOn(String parent, String column, String javaType) {
        return new ColumnField(parent, "title", null, "title",
            new ColumnRef(column, "TITLE", javaType), false);
    }

    enum Case implements ValidatorCase {

        EMPTY("nesting field with no nested fields — no error",
            new NestingField("Film", "nested", null,
                new ReturnTypeRef.TableBoundReturnType("Film",
                    new TableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of()),
            List.of()),

        LIST_CARDINALITY_REJECTED("list cardinality on a NestingField → error",
            new NestingField("Film", "tags", null,
                new ReturnTypeRef.TableBoundReturnType("Tag",
                    new TableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.List(false, false)),
                List.of()),
            List.of("Field 'Film.tags': list cardinality on a plain-object nesting field is not supported")),

        STUBBED_NESTED_LEAF_ROLLS_UP("a stubbed variant (ColumnReferenceField) at nested depth surfaces the stubbed error at the nested leaf",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    new TableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(new ColumnReferenceField("FilmDetails", "languageName", null, "languageName",
                    new ColumnRef("NAME", "", ""),
                    List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(),
                        new TableRef("language", "", "", List.of()), List.of(), null, "")),
                    false))),
            List.of(stubbedError("FilmDetails.languageName", ColumnReferenceField.class))),

        STUBBED_NESTED_LEAF_INSIDE_NESTED_NESTING("stubbed variant inside a NestingField inside a NestingField → recursive walk surfaces it",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    new TableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(new NestingField("FilmDetails", "meta", null,
                    new ReturnTypeRef.TableBoundReturnType("FilmMeta",
                        new TableRef("film", "FILM", "Film", List.of()),
                        new FieldWrapper.Single(true)),
                    List.of(new ColumnReferenceField("FilmMeta", "languageName", null, "languageName",
                        new ColumnRef("NAME", "", ""),
                        List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(),
                            new TableRef("language", "", "", List.of()), List.of(), null, "")),
                        false))))),
            List.of(stubbedError("FilmMeta.languageName", ColumnReferenceField.class)));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nestingFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }

    // ===== Multi-parent compatibility (schema-level) =====

    /**
     * Build a {@link GraphitronSchema} with two {@code @table} parents each declaring a
     * {@code details: FilmDetails} nesting field. Mirrors the production grouping: fields()
     * keyed by parent coordinates, types() with both parent TableTypes.
     */
    private static GraphitronSchema twoParentSchema(List<ChildField> filmNested, List<ChildField> advertisementNested) {
        var types = new java.util.LinkedHashMap<String, GraphitronType>();
        types.put("Film", new TableType("Film", null, FILM_TABLE));
        types.put("Advertisement", new TableType("Advertisement", null, ADVERTISEMENT_TABLE));
        var fields = new java.util.LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Film", "details"),
            nestingField("Film", "details", filmNested, FILM_TABLE));
        fields.put(FieldCoordinates.coordinates("Advertisement", "details"),
            nestingField("Advertisement", "details", advertisementNested, ADVERTISEMENT_TABLE));
        return new GraphitronSchema(types, fields);
    }

    @Test
    void multiParentCompat_matchingShapes_noError() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(titleOn("FilmDetails", "title", "java.lang.String")));
        assertThat(validate(schema)).extracting(ValidationError::message).isEmpty();
    }

    @Test
    void multiParentCompat_missingFieldOnSecondParent_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of());
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'title' exists on the first but not the second");
    }

    @Test
    void multiParentCompat_extraFieldOnSecondParent_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(
                titleOn("FilmDetails", "title", "java.lang.String"),
                new ColumnField("FilmDetails", "extra", null, "extra",
                    new ColumnRef("extra", "EXTRA", "java.lang.String"), false)));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'extra' exists on the second but not the first");
    }

    @Test
    void multiParentCompat_differentColumnName_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(titleOn("FilmDetails", "headline", "java.lang.String")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'title' resolves to column 'title' on the first but 'headline' on the second");
    }

    @Test
    void multiParentCompat_differentJavaType_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(titleOn("FilmDetails", "title", "java.lang.Integer")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'title' has Java type 'java.lang.String' on the first but 'java.lang.Integer' on the second");
    }

    @Test
    void multiParentCompat_nestedNestingWithDivergentInnerColumn_reported() {
        // Two parents share a nested type FilmDetails whose `meta` is itself a NestingField.
        // The inner FilmMeta has one field `label` that resolves to different columns on each
        // parent. The shape check must recurse into the inner NestingField and flag the mismatch;
        // without recursion, class equality (NestingField == NestingField) hides the divergence.
        var filmMeta = new NestingField("FilmDetails", "meta", null,
            new ReturnTypeRef.TableBoundReturnType("FilmMeta", FILM_TABLE, new FieldWrapper.Single(true)),
            List.of(new ColumnField("FilmMeta", "label", null, "label",
                new ColumnRef("title", "TITLE", "java.lang.String"), false)));
        var adMeta = new NestingField("FilmDetails", "meta", null,
            new ReturnTypeRef.TableBoundReturnType("FilmMeta", ADVERTISEMENT_TABLE, new FieldWrapper.Single(true)),
            List.of(new ColumnField("FilmMeta", "label", null, "label",
                new ColumnRef("headline", "HEADLINE", "java.lang.String"), false)));
        var schema = twoParentSchema(List.of(filmMeta), List.of(adMeta));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .contains(
                "Nested type 'FilmMeta' shared across 'Film' and 'Advertisement': field 'label' resolves to column 'title' on the first but 'headline' on the second");
    }

    @Test
    void multiParentCompat_nonColumnLeaf_rejectedAcrossParents() {
        var columnRefFirst = new ColumnReferenceField("FilmDetails", "langName", null, "langName",
            new ColumnRef("NAME", "", ""),
            List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(),
                new TableRef("language", "", "", List.of()), List.of(), null, "")),
            false);
        var columnRefSecond = new ColumnReferenceField("FilmDetails", "langName", null, "langName",
            new ColumnRef("NAME", "", ""),
            List.of(new JoinStep.FkJoin("advertisement_language_id_fkey", "", null, List.of(),
                new TableRef("language", "", "", List.of()), List.of(), null, "")),
            false);
        var schema = twoParentSchema(List.of(columnRefFirst), List.of(columnRefSecond));
        // Shape check reports "not yet supported" for the shared non-column leaf. The per-field
        // stubbed-variant walk fires twice — once per parent — surfacing the ColumnReferenceField
        // stubbed reason at each nested location.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .contains(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'langName' classifies as ColumnReferenceField which is not yet supported across multiple parents — see rewrite-roadmap.md #8");
    }
}
