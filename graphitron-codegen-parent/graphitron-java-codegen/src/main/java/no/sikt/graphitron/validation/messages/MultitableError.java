package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.ErrorMessage;

public enum MultitableError implements ErrorMessage {
    MISSING_TABLE_ON_MULTITABLE("Type(s) '%s' are used in a query %s returning multitable interface or union '%s', but do not have tables set. This is not supported.");

    private final String msg;

    MultitableError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
