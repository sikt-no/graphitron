package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.ErrorMessage;

import static no.sikt.graphql.directives.GenerationDirective.SPLIT_QUERY;

public enum SelfReferenceError implements ErrorMessage {
    SELF_REFERENCE_WITHOUT_SPLITQUERY("Field %s is a field generating a table self-reference. This is only supported if the field has the @" + SPLIT_QUERY.getName() + " directive.");

    private final String msg;

    SelfReferenceError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
