package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class MutationServiceRecordFieldValidationTest {

    enum Case implements ValidatorCase {

        STUBBED("service mutation field with non-table return — not yet implemented, produces stubbed-variant error",
            new MutationServiceRecordField("Mutation", "externalMutation", null,
                new ReturnTypeRef.ResultReturnType("Film", new FieldWrapper.Single(true), null),
                new MethodRef.Basic("com.example.Service", "method", TypeName.VOID, List.of()),
                Optional.empty(), Optional.empty()),
            List.of(stubbedError("Mutation.externalMutation", MutationServiceRecordField.class)));

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
    void mutationServiceRecordFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
