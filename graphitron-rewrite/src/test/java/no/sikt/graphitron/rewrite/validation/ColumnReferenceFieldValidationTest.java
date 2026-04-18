package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name; path resolved via FK (stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(), new TableRef("language", "", "", List.of()), List.of(), null, "")), false),
            List.of(stubbedError("Film.languageName", ColumnReferenceField.class))),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name; path resolved via FK (stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "language_name", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(), new TableRef("language", "", "", List.of()), List.of(), null, "")), false),
            List.of(stubbedError("Film.languageName", ColumnReferenceField.class))),

        CONDITION_METHOD("path resolved via condition method instead of a FK (stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "languageCondition", "org.jooq.Condition", List.of()), "")), false),
            List.of(stubbedError("Film.languageName", ColumnReferenceField.class))),

        JAVA_NAME_PRESENT("@field(javaName:) is not supported — validation error (and stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(), new TableRef("language", "", "", List.of()), List.of(), null, "")), true),
            List.of("Field 'Film.languageName': @field(javaName:) is not supported in record-based output",
                stubbedError("Film.languageName", ColumnReferenceField.class))),

        MISSING_PATH("no @reference directive — path is empty (and stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(), false),
            List.of("Field 'Film.languageName': @reference path is required",
                stubbedError("Film.languageName", ColumnReferenceField.class))),

        JAVA_NAME_AND_MISSING_PATH("@field(javaName:) present AND path is empty — both validators fire independently (and stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(), true),
            List.of(
                "Field 'Film.languageName': @field(javaName:) is not supported in record-based output",
                "Field 'Film.languageName': @reference path is required",
                stubbedError("Film.languageName", ColumnReferenceField.class)));

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
