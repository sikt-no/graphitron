package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ComputedFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — no lift condition; valid when return type is not table-mapped",
            new ComputedField("Film", "fullTitle", null, new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)), List.of()),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method",
            new ComputedField("Film", "fullTitle", null, new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new JoinStep.ConditionJoin(new MethodRef("com.example.Conditions", "liftCondition", "org.jooq.Condition", List.of())))),
            List.of());

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
    void computedFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
