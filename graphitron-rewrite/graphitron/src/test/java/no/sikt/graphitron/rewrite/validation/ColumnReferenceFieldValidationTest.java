package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
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
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class ColumnReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name; path resolved via FK (stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(), TestFixtures.joinTarget("language"), List.of(), null, "")),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            List.of(stubbedError("Film.languageName", ColumnReferenceField.class))),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name; path resolved via FK (stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "language_name", new ColumnRef("NAME", "", ""),
                List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(), TestFixtures.joinTarget("language"), List.of(), null, "")),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            List.of(stubbedError("Film.languageName", ColumnReferenceField.class))),

        CONDITION_METHOD("path resolved via condition method instead of a FK (stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.ConditionJoin(new MethodRef.Basic("com.example.Conditions", "languageCondition", ClassName.get("org.jooq", "Condition"), List.of()), "")),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            List.of(stubbedError("Film.languageName", ColumnReferenceField.class))),

        MISSING_PATH("no @reference directive — path is empty (and stubbed)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            List.of("Field 'Film.languageName': @reference path is required",
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
