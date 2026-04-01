package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ParamInfo;
import no.sikt.graphitron.record.field.ReferencePathElement;
import no.sikt.graphitron.record.field.ServiceField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceFieldValidationTest {

    enum Case implements ValidatorCase {

        /** No {@code @reference} — no lift condition; valid when return type is not table-mapped. */
        NO_PATH {
            public GraphitronField field() {
                return new ServiceField("externalChild", null, List.of());
            }
            public List<String> errors() { return List.of(); }
        },

        /** Lift condition with a resolved method. */
        WITH_LIFT_CONDITION {
            public GraphitronField field() {
                return new ServiceField("externalChild", null, List.of(
                    new ReferencePathElement(null, null, Optional.of(
                        new MethodRef("com.example.Conditions.liftCondition", "org.jooq.Condition",
                            List.of(new ParamInfo("org.jooq.DSLContext", "ctx")))))));
            }
            public List<String> errors() { return List.of(); }
        },

        /** Lift condition method present but could not be resolved via reflection. */
        UNRESOLVED_CONDITION {
            public GraphitronField field() {
                return new ServiceField("externalChild", null, List.of(
                    new ReferencePathElement(null, null, Optional.of(
                        new MethodRef("com.example.Conditions.liftCondition", null, null)))));
            }
            public List<String> errors() {
                return List.of("Field 'externalChild': condition method 'com.example.Conditions.liftCondition' could not be resolved");
            }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void serviceFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "externalChild", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
