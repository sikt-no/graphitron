package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name; path resolved via FK",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "language", null)), false),
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name; path resolved via FK",
            new ColumnReferenceField("Film", "languageName", null, "language_name", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "language", null)), false),
            List.of()),

        CONDITION_METHOD("path resolved via condition method instead of a FK",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.ConditionJoin(new MethodRef("com.example.Conditions", "languageCondition", "org.jooq.Condition", List.of()))), false),
            List.of()),

        JAVA_NAME_PRESENT("@field(javaName:) is not supported — validation error",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "language", null)), true),
            List.of("Field 'languageName': @field(javaName:) is not supported in record-based output")),

        MISSING_PATH("no @reference directive — path is empty",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(), false),
            List.of("Field 'languageName': @reference path is required")),

        JAVA_NAME_AND_MISSING_PATH("@field(javaName:) present AND path is empty — both validators fire independently",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(), true),
            List.of(
                "Field 'languageName': @field(javaName:) is not supported in record-based output",
                "Field 'languageName': @reference path is required"));

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
    void columnReferenceFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
