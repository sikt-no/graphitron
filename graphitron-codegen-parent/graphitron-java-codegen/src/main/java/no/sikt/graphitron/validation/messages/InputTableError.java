package no.sikt.graphitron.validation.messages;

import no.sikt.graphitron.validation.messages.interfaces.WarningMessage;

public enum InputTableError implements WarningMessage {
    MISSING_FIELD("Input type %s referencing table %s does not map all fields required by the database. Missing required fields: %s"),
    MISSING_NON_NULLABLE("Input type %s referencing table %s does not map all fields required by the database as non-nullable. Nullable required fields: %s");

    private final String msg;

    InputTableError(String msg) {
        this.msg = msg;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
