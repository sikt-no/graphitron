package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.ErrorMessage;

import static no.sikt.graphql.directives.GenerationDirective.PROCEDURE_CALL;
import static no.sikt.graphql.directives.GenerationDirective.SPLIT_QUERY;

public enum ProcedureCallError implements ErrorMessage {
    UNKNOWN_ROUTINE("Field %s uses @" + PROCEDURE_CALL.getName() + " but no jOOQ routine was found with the name '%s'."),
    AMBIGUOUS_ROUTINE("Field %s uses @" + PROCEDURE_CALL.getName() + " with routine '%s' but that name exists in multiple schemas: [%s]. Qualify the name as 'schema.routine' to disambiguate."),
    NOT_A_FUNCTION("Field %s uses @" + PROCEDURE_CALL.getName() + " with routine '%s' but the routine is not a function (it has no return value, or it has OUT/INOUT parameters which are not supported)."),
    MISSING_TABLE("Field %s uses @" + PROCEDURE_CALL.getName() + " but the relevant type neither has a table nor inherits one from a previous type."),
    UNKNOWN_PARAMETER("Field %s uses @" + PROCEDURE_CALL.getName() + " with unknown routine parameter '%s' for routine '%s'. Known IN parameters: [%s]."),
    MISSING_PARAMETER("Field %s uses @" + PROCEDURE_CALL.getName() + " but does not map routine IN parameter '%s' (routine '%s'). All IN parameters must be supplied."),
    ARGUMENT_NOT_FOUND("Field %s uses @" + PROCEDURE_CALL.getName() + " mapping parameter '%s' to '%s' but no such argument source was found (%s)."),
    RETURN_TYPE_MISMATCH("Field %s uses @" + PROCEDURE_CALL.getName() + " but the routine '%s' returns '%s' which is not compatible with the GraphQL type '%s' (expected Java type '%s')."),
    ON_ROOT_OPERATION("Field %s uses @" + PROCEDURE_CALL.getName() + " on a %s root field. The directive is only valid on fields of types backed by a table."),
    ON_NON_SCALAR_FIELD_TYPE("Field %s uses @" + PROCEDURE_CALL.getName() + " but its type '%s' is not a scalar. @" + PROCEDURE_CALL.getName() + " only supports scalar-returning functions."),
    ON_INTERFACE_DECLARATION("Field %s is declared on interface '%s' with @" + PROCEDURE_CALL.getName() + ". Declare the directive on the implementing object types instead."),
    ILLEGAL_COMBINATION("Field %s uses @" + PROCEDURE_CALL.getName() + " together with @%s. These directives cannot be combined."),
    TARGET_MODE_REQUIRES_DATA_FETCHER("Field %s uses @" + PROCEDURE_CALL.getName() + " with target '%s' but the directive is not on a data-fetcher field. The 'target' parameter is only valid on root fields, fields with arguments, or @" + SPLIT_QUERY.getName() + " fields."),
    TARGET_MODE_REQUIRES_OBJECT_RETURN_TYPE("Field %s uses @" + PROCEDURE_CALL.getName() + " with target '%s' but its type '%s' is not an object type. Target mode requires the field to return an object or list of objects."),
    UNKNOWN_TARGET_FIELD("Field %s uses @" + PROCEDURE_CALL.getName() + " with target '%s' but no such field exists on the return type '%s'."),
    TARGET_FIELD_NOT_SCALAR("Field %s uses @" + PROCEDURE_CALL.getName() + " with target '%s' on return type '%s' but that field's type '%s' is not a scalar. @" + PROCEDURE_CALL.getName() + " only supports scalar-returning functions."),
    TARGET_FIELD_HAS_ILLEGAL_DIRECTIVE("Field %s uses @" + PROCEDURE_CALL.getName() + " with target '%s' on return type '%s' but the target field already carries @%s.");

    private final String msg;

    ProcedureCallError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
