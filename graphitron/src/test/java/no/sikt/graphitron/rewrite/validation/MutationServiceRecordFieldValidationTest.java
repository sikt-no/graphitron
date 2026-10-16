package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class MutationServiceRecordFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("service mutation field with non-table return — passes validation (Invariants §1/§2 enforced at classifier time)",
            new MutationServiceRecordField("Mutation", "externalMutation", null,
                new ReturnTypeRef.ResultReturnType("Film", new FieldWrapper.Single(true), null, null),
                                TestFixtures.stubServiceCall(TestFixtures.staticServiceMethodRef("com.example.Service", "method", TypeName.VOID, List.of())),
                Optional.empty()),
            List.of()),

        LIST_OF_LISTS_OF_INPUT_OBJECTS_UNDER_VALIDATION(
            "list of lists of input objects under a VALIDATION channel → rejection naming the argument",
            gridField(List.of(new GraphitronType.ErrorType.ValidationHandler())),
            List.of("Field 'Mutation.externalMutation': argument 'grid' is a list of lists of input type "
                + "'GridInput', and the payload's error channel carries a VALIDATION handler; validation "
                + "of a list of lists of input objects is not supported. Flatten the argument to a "
                + "single list, or drop the VALIDATION handler from the payload's error types")),

        LIST_OF_LISTS_OF_INPUT_OBJECTS_WITHOUT_VALIDATION(
            "list of lists of input objects under a channel without VALIDATION → passes validation",
            gridField(List.of(new GraphitronType.ErrorType.SqlStateHandler(
                "23503", Optional.empty(), new GraphitronType.ErrorType.ClientMessage.FromSource()))),
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

    /**
     * {@code Mutation.externalMutation(grid: [[GridInput!]!]!)} whose payload's error channel maps
     * one {@code @error} type carrying {@code handlers}. The carrier is hand-built, since the
     * classifier refuses the VALIDATION variant before a field reaches the validator.
     */
    private static GraphitronField gridField(List<GraphitronType.ErrorType.Handler> handlers) {
        var carrier = new ServiceMethodCall.Static("com.example.Service", "method", List.of(),
            TypeName.VOID, ServiceMethodCall.ReturnContainer.Other.INSTANCE,
            List.of(new ServiceMethodCall.ValidatedArgument.InputObjectNestedList("grid", "GridInput")));
        var channel = new ErrorChannel.Mapped(
            List.of(new GraphitronType.ErrorType("GridErr", null, handlers, List.of())), "GRID_PAYLOAD");
        return new MutationServiceRecordField("Mutation", "externalMutation", null,
            new ReturnTypeRef.ResultReturnType("GridPayload", new FieldWrapper.Single(true), null, null),
            carrier, Optional.of(channel));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void mutationServiceRecordFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
