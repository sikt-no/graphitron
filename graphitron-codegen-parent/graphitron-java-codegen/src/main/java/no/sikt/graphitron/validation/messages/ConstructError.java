package no.sikt.graphitron.validation.messages;

import static no.sikt.graphql.directives.GenerationDirective.CONSTRUCT_TYPE;
import static no.sikt.graphql.directives.GenerationDirective.TABLE;

import no.sikt.graphitron.validation.messages.interfaces.ErrorMessage;

public enum ConstructError implements ErrorMessage {
    CONSTRUCT_FIELD_IS_NOT_TYPE("Field %s uses @" + CONSTRUCT_TYPE.getName() + " but its target type '%s' is not an object type."),
    CONSTRUCT_TYPE_HAS_TABLE("Field %s uses @" + CONSTRUCT_TYPE.getName() + " but the target type '%s' has a @" + TABLE.getName() + " directive. The target type must not have its own table."),
    CONSTRUCT_MISSING_TABLE("Field %s uses @" + CONSTRUCT_TYPE.getName() + " but the containing type has no resolvable table."),
    CONSTRUCT_FIELD_HAS_ILLEGAL_DIRECTIVE("Field %s can not have both @" + CONSTRUCT_TYPE.getName() + " and @%s directives."),
    CONSTRUCT_NONEXISTENT_FIELD("Field %s uses @" + CONSTRUCT_TYPE.getName() + " with selection field '%s' which does not exist in target type '%s'."),
    CONSTRUCT_CONTAINS_NESTED_TYPE("Field %s uses @" + CONSTRUCT_TYPE.getName() + " with selection field '%s' which is an object type. Only scalar and enum fields are allowed in construct selections."),
    CONSTRUCT_NONEXISTENT_COLUMN("Field %s uses @" + CONSTRUCT_TYPE.getName() + " with selection column '%s' which does not exist in table '%s'.");

    private final String msg;

    ConstructError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
