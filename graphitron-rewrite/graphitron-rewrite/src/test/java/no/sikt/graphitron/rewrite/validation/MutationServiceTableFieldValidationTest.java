package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class MutationServiceTableFieldValidationTest {

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN =
        new ReturnTypeRef.TableBoundReturnType("Film",
            new TableRef("film", "FILM", "Film", List.of()),
            new FieldWrapper.Single(true));

    enum Case implements ValidatorCase {

        STUBBED("service mutation field with resolved method — not yet implemented, produces stubbed-variant error",
            new MutationServiceTableField("Mutation", "externalMutation", null,
                FILM_RETURN,
                new MethodRef.Basic("com.example.Service", "method", "void", List.of())),
            List.of(stubbedError("Mutation.externalMutation", MutationServiceTableField.class)));

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
    void mutationServiceTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
