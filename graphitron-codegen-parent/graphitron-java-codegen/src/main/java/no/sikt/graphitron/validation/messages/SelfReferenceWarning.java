package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.WarningMessage;

public enum SelfReferenceWarning implements WarningMessage {
    SELF_REFERENCE_IMPLICITLY_REVERSE("Field %s is a listed field generating a table self-reference through a key, implying you may want a reverse reference. " +
            "Reverse self reference is not currently supported. Possible workaround is defining a custom reference condition.");

    private final String msg;

    SelfReferenceWarning(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
