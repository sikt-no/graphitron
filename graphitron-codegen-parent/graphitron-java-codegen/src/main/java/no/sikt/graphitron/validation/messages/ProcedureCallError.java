package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.ErrorMessage;

import static no.sikt.graphql.directives.GenerationDirective.PROCEDURE_CALL;

public enum ProcedureCallError implements ErrorMessage {
    UNKNOWN_ROUTINE("Field %s uses @" + PROCEDURE_CALL.getName() + " but no jOOQ routine was found with the name '%s'."),
    AMBIGUOUS_ROUTINE("Field %s uses @" + PROCEDURE_CALL.getName() + " with routine '%s' but that name exists in multiple schemas: [%s]. Qualify the name as 'schema.routine' to disambiguate."),
    NOT_A_FUNCTION("Field %s uses @" + PROCEDURE_CALL.getName() + " with routine '%s' but the routine is not a function (it has no return value, or it has OUT/INOUT parameters which are not supported)."),
    MISSING_TABLE("Field %s uses @" + PROCEDURE_CALL.getName() + " but the containing type neither has a table nor inherits one from a previous type."),
    UNKNOWN_PARAMETER("Field %s uses @" + PROCEDURE_CALL.getName() + " with unknown routine parameter '%s' for routine '%s'. Known IN parameters: [%s]."),
    MISSING_PARAMETER("Field %s uses @" + PROCEDURE_CALL.getName() + " but does not map routine IN parameter '%s' (routine '%s'). All IN parameters must be supplied."),
    NONEXISTENT_COLUMN("Field %s uses @" + PROCEDURE_CALL.getName() + " mapping parameter '%s' to column '%s' which does not exist in table '%s'."),
    RETURN_TYPE_MISMATCH("Field %s uses @" + PROCEDURE_CALL.getName() + " but the routine '%s' returns '%s' which is not compatible with the field's GraphQL type '%s' (expected Java type '%s')."),
    ON_ROOT_OPERATION("Field %s uses @" + PROCEDURE_CALL.getName() + " on a %s root field. The directive is only valid on fields of types backed by a table."),
    ON_NON_SCALAR_FIELD_TYPE("Field %s uses @" + PROCEDURE_CALL.getName() + " but its type '%s' is not a scalar. Procedure calls only support scalar-returning functions."),
    ON_INTERFACE_DECLARATION("Field %s is declared on interface '%s' with @" + PROCEDURE_CALL.getName() + ". Declare the directive on the implementing object types instead."),
    ILLEGAL_COMBINATION("Field %s uses @" + PROCEDURE_CALL.getName() + " together with @%s. These directives cannot be combined.");

    private final String msg;

    ProcedureCallError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
