package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.FkStep;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.NodeIdReferenceField;
import no.sikt.graphitron.record.field.UnresolvedConditionStep;
import no.sikt.graphitron.record.field.UnresolvedKeyStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        WITH_PATH("reference path resolves successfully",
            new NodeIdReferenceField("languageId", null, List.of(
                new FkStep(Keys.FILM__FILM_LANGUAGE_ID_FKEY))),
            List.of()),

        MISSING_PATH("no @reference directive — path is empty",
            new NodeIdReferenceField("languageId", null, List.of()),
            List.of("Field 'languageId': @reference path is required")),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog",
            new NodeIdReferenceField("languageId", null,
                List.of(new UnresolvedKeyStep("FILM_LANGUAGE_FK"))),
            List.of("Field 'languageId': key 'FILM_LANGUAGE_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection",
            new NodeIdReferenceField("languageId", null,
                List.of(new UnresolvedConditionStep("com.example.Conditions.languageCondition"))),
            List.of("Field 'languageId': condition method 'com.example.Conditions.languageCondition' could not be resolved"));

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
    void nodeIdReferenceFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "languageId", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
