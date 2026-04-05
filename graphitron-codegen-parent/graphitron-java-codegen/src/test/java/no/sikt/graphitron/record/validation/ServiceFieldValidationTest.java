package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ParamInfo;
import no.sikt.graphitron.record.field.ChildField.ServiceField;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceFieldValidationTest {

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — no lift condition; valid when return type is not table-mapped",
            new ServiceField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), List.of(), null, List.of(), List.of()),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method",
            new ServiceField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new ConditionOnlyRef(new MethodRef("com.example.Conditions.liftCondition", "org.jooq.Condition",
                    List.of(new ParamInfo("org.jooq.DSLContext", "ctx"))))),
                null, List.of(), List.of()),
            List.of()),

        UNRESOLVED_CONDITION("lift condition method present but could not be resolved via reflection",
            new ServiceField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new UnresolvedConditionRef("com.example.Conditions.liftCondition")),
                null, List.of(), List.of()),
            List.of("Field 'externalChild': condition method 'com.example.Conditions.liftCondition' could not be resolved")),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog",
            new ServiceField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new UnresolvedKeyRef("FILM_ACTOR_FK")),
                null, List.of(), List.of()),
            List.of("Field 'externalChild': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_KEY_AND_CONDITION("both key and condition specified, neither could be resolved — two errors",
            new ServiceField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new UnresolvedKeyAndConditionRef("FILM_ACTOR_FK", "com.example.Conditions.liftCondition")),
                null, List.of(), List.of()),
            List.of(
                "Field 'externalChild': key 'FILM_ACTOR_FK' could not be resolved in the jOOQ catalog",
                "Field 'externalChild': condition method 'com.example.Conditions.liftCondition' could not be resolved"));

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
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
