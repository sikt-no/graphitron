package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ConditionOnlyStep;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ParamInfo;
import no.sikt.graphitron.record.field.ServiceField;
import no.sikt.graphitron.record.field.UnresolvedConditionStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — no lift condition; valid when return type is not table-mapped",
            new ServiceField("externalChild", null, List.of()),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method",
            new ServiceField("externalChild", null, List.of(
                new ConditionOnlyStep(new MethodRef("com.example.Conditions.liftCondition", "org.jooq.Condition",
                    List.of(new ParamInfo("org.jooq.DSLContext", "ctx")))))),
            List.of()),

        UNRESOLVED_CONDITION("lift condition method present but could not be resolved via reflection",
            new ServiceField("externalChild", null, List.of(
                new UnresolvedConditionStep("com.example.Conditions.liftCondition"))),
            List.of("Field 'externalChild': condition method 'com.example.Conditions.liftCondition' could not be resolved"));

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
    void serviceFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "externalChild", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
