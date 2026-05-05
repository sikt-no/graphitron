package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.sikt.graphql.directives.GenerationDirective.EXTERNAL_FIELD;
import static no.sikt.graphql.directives.GenerationDirective.FIELD;
import static no.sikt.graphql.directives.GenerationDirective.PROCEDURE_CALL;
import static no.sikt.graphql.directives.GenerationDirective.REFERENCE;

@DisplayName("Schema validation - @experimental_procedureCall directive")
public class ProcedureCallValidationTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "procedureCall";
    }

    @Test
    @DisplayName("Procedure call with unknown routine name")
    void procedureCallUnknownRoutine() {
        assertErrorsContain("procedureCallUnknownRoutine",
                String.format(
                        "Field %s uses @%s but no jOOQ routine was found with the name '%s'.",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        "no_such_routine"
                )
        );
    }

    @Test
    @DisplayName("Procedure call with a routine name that exists in multiple schemas requires qualification")
    void procedureCallAmbiguousRoutine() {
        assertErrorsContain("procedureCallAmbiguousRoutine",
                String.format(
                        "Field %s uses @%s with routine '%s' but that name exists in multiple schemas: [%s].",
                        "'Customer.lastDay'",
                        PROCEDURE_CALL.getName(),
                        "last_day",
                        "public, utils"
                )
        );
    }

    @Test
    @DisplayName("Procedure call targeting a stored procedure (no return value) is rejected")
    void procedureCallNotAFunction() {
        assertErrorsContain("procedureCallNotAFunction",
                String.format(
                        "Field %s uses @%s with routine '%s' but the routine is not a function",
                        "'Film.reset'",
                        PROCEDURE_CALL.getName(),
                        "reset_film_rental_duration"
                )
        );
    }

    @Test
    @DisplayName("Procedure call missing a required IN parameter")
    void procedureCallMissingParam() {
        assertErrorsContain("procedureCallMissingParam",
                String.format(
                        "Field %s uses @%s but does not map routine IN parameter '%s'",
                        "'Customer.balance'",
                        PROCEDURE_CALL.getName(),
                        "p_effective_date"
                )
        );
    }

    @Test
    @DisplayName("Procedure call with an unknown parameter key")
    void procedureCallUnknownParam() {
        assertErrorsContain("procedureCallUnknownParam",
                String.format(
                        "Field %s uses @%s with unknown routine parameter '%s'",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        "fake"
                )
        );
    }

    @Test
    @DisplayName("Procedure call mapping a column that does not exist on the surrounding table")
    void procedureCallNonexistentColumn() {
        assertErrorsContain("procedureCallNonexistentColumn", Set.of(CUSTOMER_QUERY),
                String.format(
                        "Field %s uses @%s mapping parameter '%s' to '%s' but no such argument source was found (no such column on table '%s').",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        "p_inventory_id",
                        "NONEXISTENT_COLUMN",
                        "CUSTOMER"
                )
        );
    }

    @Test
    @DisplayName("Procedure call with mismatched return type")
    void procedureCallReturnTypeMismatch() {
        assertErrorsContain("procedureCallReturnTypeMismatch",
                String.format(
                        "Field %s uses @%s but the routine '%s' returns",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        "inventory_held_by_customer"
                )
        );
    }

    @Test
    @DisplayName("Procedure call on Query root field is rejected")
    void procedureCallOnQueryRoot() {
        assertErrorsContain("procedureCallOnQueryRoot",
                String.format(
                        "Field %s uses @%s on a %s root field.",
                        "'Query.heldBy'",
                        PROCEDURE_CALL.getName(),
                        "Query"
                )
        );
    }

    @Test
    @DisplayName("Procedure call on Mutation root field is rejected")
    void procedureCallOnMutationRoot() {
        assertErrorsContain("procedureCallOnMutationRoot",
                String.format(
                        "Field %s uses @%s on a %s root field.",
                        "'Mutation.heldBy'",
                        PROCEDURE_CALL.getName(),
                        "Mutation"
                )
        );
    }

    @Test
    @DisplayName("Procedure call on a type with no resolvable table is rejected")
    void procedureCallOnTypeWithoutTable() {
        assertErrorsContain("procedureCallOnTypeWithoutTable",
                String.format(
                        "Field %s uses @%s but the relevant type neither has a table nor inherits one from a previous type.",
                        "'Container.heldBy'",
                        PROCEDURE_CALL.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call on a field whose type is an interface is rejected")
    void procedureCallOnInterfaceTypedField() {
        assertErrorsContain("procedureCallOnInterfaceTypedField",
                String.format(
                        "Field %s uses @%s but its type '%s' is not a scalar.",
                        "'Customer.held'",
                        PROCEDURE_CALL.getName(),
                        "SomeInterface"
                )
        );
    }

    @Test
    @DisplayName("Procedure call on a field whose type is a union is rejected")
    void procedureCallOnUnionTypedField() {
        assertErrorsContain("procedureCallOnUnionTypedField",
                String.format(
                        "Field %s uses @%s but its type '%s' is not a scalar.",
                        "'Customer.held'",
                        PROCEDURE_CALL.getName(),
                        "SomeUnion"
                )
        );
    }

    @Test
    @DisplayName("Procedure call on a field whose type is an object type is rejected")
    void procedureCallOnObjectTypedField() {
        assertErrorsContain("procedureCallOnObjectTypedField",
                String.format(
                        "Field %s uses @%s but its type '%s' is not a scalar.",
                        "'Customer.held'",
                        PROCEDURE_CALL.getName(),
                        "Other"
                )
        );
    }

    @Test
    @DisplayName("Procedure call on an interface declaration is rejected")
    void procedureCallOnInterfaceDeclaration() {
        assertErrorsContain("procedureCallOnInterfaceDeclaration",
                String.format(
                        "Field %s is declared on interface '%s' with @%s.",
                        "'SomeInterface.heldBy'",
                        "SomeInterface",
                        PROCEDURE_CALL.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call combined with @field is rejected")
    void procedureCallWithFieldDirective() {
        assertErrorsContain("procedureCallWithFieldDirective",
                String.format(
                        "Field %s uses @%s together with @%s.",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        FIELD.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call combined with @externalField is rejected")
    void procedureCallWithExternalFieldDirective() {
        assertErrorsContain("procedureCallWithExternalFieldDirective",
                String.format(
                        "Field %s uses @%s together with @%s.",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        EXTERNAL_FIELD.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call combined with @reference is rejected")
    void procedureCallWithReferenceDirective() {
        assertErrorsContain("procedureCallWithReferenceDirective",
                String.format(
                        "Field %s uses @%s together with @%s.",
                        "'Customer.heldBy'",
                        PROCEDURE_CALL.getName(),
                        REFERENCE.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode with unknown target field is rejected")
    void procedureCallTargetUnknownField() {
        assertErrorsContain("procedureCallTargetUnknownField",
                String.format(
                        "Field %s uses @%s with target '%s' but no such field exists on the return type '%s'.",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "doesNotExist",
                        "Customer"
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode with non-scalar target field is rejected")
    void procedureCallTargetNotScalar() {
        assertErrorsContain("procedureCallTargetNotScalar",
                String.format(
                        "Field %s uses @%s with target '%s' on return type '%s' but that field's type '%s' is not a scalar.",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "nested",
                        "Customer",
                        "Other"
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode where the target field carries @field is rejected")
    void procedureCallTargetHasFieldDirective() {
        assertErrorsContain("procedureCallTargetHasFieldDirective",
                String.format(
                        "Field %s uses @%s with target '%s' on return type '%s' but the target field already carries @%s.",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "heldBy",
                        "Customer",
                        FIELD.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode where the target field carries @reference is rejected")
    void procedureCallTargetHasReferenceDirective() {
        assertErrorsContain("procedureCallTargetHasReferenceDirective",
                String.format(
                        "Field %s uses @%s with target '%s' on return type '%s' but the target field already carries @%s.",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "heldBy",
                        "Customer",
                        REFERENCE.getName()
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode on a non-data-fetcher field is rejected")
    void procedureCallTargetMissingDataFetcher() {
        assertErrorsContain("procedureCallTargetMissingDataFetcher",
                String.format(
                        "Field %s uses @%s with target '%s' but the directive is not on a data-fetcher field.",
                        "'Store.buddy'",
                        PROCEDURE_CALL.getName(),
                        "heldBy"
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode mapping a value that is not a GraphQL argument on the field")
    void procedureCallTargetUnknownArgument() {
        assertErrorsContain("procedureCallTargetUnknownArgument",
                String.format(
                        "Field %s uses @%s mapping parameter '%s' to '%s' but no such argument source was found (no such GraphQL argument on field '%s').",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "p_inventory_id",
                        "fakeArg",
                        "someInventory"
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode with mismatched return type")
    void procedureCallTargetReturnTypeMismatch() {
        assertErrorsContain("procedureCallTargetReturnTypeMismatch",
                String.format(
                        "Field %s uses @%s but the routine '%s' returns",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "inventory_held_by_customer"
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode on a field whose return type is not an object is rejected")
    void procedureCallTargetNonObjectReturnType() {
        assertErrorsContain("procedureCallTargetNonObjectReturnType",
                String.format(
                        "Field %s uses @%s with target '%s' but its type '%s' is not an object type.",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName(),
                        "heldBy",
                        "ID"
                )
        );
    }

    @Test
    @DisplayName("Procedure call target mode on a return type with no resolvable table is rejected")
    void procedureCallTargetMissingTable() {
        assertErrorsContain("procedureCallTargetMissingTable",
                String.format(
                        "Field %s uses @%s but the relevant type neither has a table nor inherits one from a previous type.",
                        "'Query.someInventory'",
                        PROCEDURE_CALL.getName()
                )
        );
    }
}
