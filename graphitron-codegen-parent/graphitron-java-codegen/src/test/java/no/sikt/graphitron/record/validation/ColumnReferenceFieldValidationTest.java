package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ColumnReferenceField;
import no.sikt.graphitron.record.field.ConditionOnlyStep;
import no.sikt.graphitron.record.field.FkStep;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ResolvedColumn;
import no.sikt.graphitron.record.field.UnresolvedColumn;
import no.sikt.graphitron.record.field.UnresolvedConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyAndConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name; path resolved via FK",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null),
                List.of(new FkStep(Keys.FILM__FILM_LANGUAGE_ID_FKEY)), false),
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name; path resolved via FK",
            new ColumnReferenceField("languageName", null, "language_name", new ResolvedColumn("NAME", null),
                List.of(new FkStep(Keys.FILM__FILM_LANGUAGE_ID_FKEY)), false),
            List.of()),

        CONDITION_METHOD("path resolved via condition method instead of a FK",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null),
                List.of(new ConditionOnlyStep(new MethodRef("com.example.Conditions.languageCondition", "org.jooq.Condition", List.of()))), false),
            List.of()),

        UNRESOLVED_COLUMN("column name could not be matched to a jOOQ field in the joined table",
            new ColumnReferenceField("languageName", null, "languageName", new UnresolvedColumn(),
                List.of(new FkStep(Keys.FILM__FILM_LANGUAGE_ID_FKEY)), false),
            List.of("Field 'languageName': column 'languageName' could not be resolved in the jOOQ table")),

        JAVA_NAME_PRESENT("@field(javaName:) is not supported — validation error",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null),
                List.of(new FkStep(Keys.FILM__FILM_LANGUAGE_ID_FKEY)), true),
            List.of("Field 'languageName': @field(javaName:) is not supported in record-based output")),

        MISSING_PATH("no @reference directive — path is empty",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null), List.of(), false),
            List.of("Field 'languageName': @reference path is required")),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null),
                List.of(new UnresolvedKeyStep("FILM_LANGUAGE_FK")), false),
            List.of("Field 'languageName': key 'FILM_LANGUAGE_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null),
                List.of(new UnresolvedConditionStep("com.example.Conditions.languageCondition")), false),
            List.of("Field 'languageName': condition method 'com.example.Conditions.languageCondition' could not be resolved")),

        UNRESOLVED_KEY_AND_CONDITION("both key and condition specified, neither could be resolved — two errors",
            new ColumnReferenceField("languageName", null, "languageName", new ResolvedColumn("NAME", null),
                List.of(new UnresolvedKeyAndConditionStep("FILM_LANGUAGE_FK", "com.example.Conditions.languageCondition")), false),
            List.of(
                "Field 'languageName': key 'FILM_LANGUAGE_FK' could not be resolved in the jOOQ catalog",
                "Field 'languageName': condition method 'com.example.Conditions.languageCondition' could not be resolved"));

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
        assertThat(validate(inTableTypeSchema("Film", "languageName", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
