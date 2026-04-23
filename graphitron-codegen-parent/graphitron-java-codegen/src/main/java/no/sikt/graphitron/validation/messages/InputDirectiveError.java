package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.ErrorMessage;

import static no.sikt.graphql.directives.GenerationDirective.RECORD;
import static no.sikt.graphql.directives.GenerationDirective.TABLE;

public enum InputDirectiveError implements ErrorMessage {
    RECORD_TYPE_CONFLICT(String.format("Input types can only be mapped to one record category (either a jOOQ record " +
                    "via @%1$s or a Java record via @%2$s), but input type '%%s' has both directives. " +
                    "Remove @%1$s to preserve the existing behaviour.",
            TABLE.getName(), RECORD.getName()));

    private final String msg;

    InputDirectiveError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}