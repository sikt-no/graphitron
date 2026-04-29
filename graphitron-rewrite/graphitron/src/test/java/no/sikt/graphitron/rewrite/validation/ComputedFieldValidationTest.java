package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ParamSource;
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

    private static final MethodRef DUMMY_METHOD = new MethodRef.Basic(
        "com.example.Ext", "fullTitle",
        ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), ClassName.get(String.class)),
        List.of(new MethodRef.Param.Typed("table", "com.example.tables.Film", new ParamSource.Table())));

    enum Case implements ValidatorCase {

        NO_PATH("no @reference — no lift condition: passes validation now that ComputedField is implemented",
            new ComputedField("Film", "fullTitle", null,
                new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)),
                List.of(),
                DUMMY_METHOD),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method — DEFERRED until the lift form ships",
            new ComputedField("Film", "fullTitle", null,
                new ReturnTypeRef.ScalarReturnType("Film", new FieldWrapper.Single(true)),
                List.of(new JoinStep.ConditionJoin(
                    new MethodRef.Basic("com.example.Conditions", "liftCondition",
                        ClassName.get("org.jooq", "Condition"), List.of()),
                    "")),
                DUMMY_METHOD),
            List.of("Field 'Film.fullTitle': @externalField with a @reference path "
                + "(condition-join lift form) is not yet supported — see "
                + "graphitron-rewrite/roadmap/computed-field-with-reference.md"));

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
