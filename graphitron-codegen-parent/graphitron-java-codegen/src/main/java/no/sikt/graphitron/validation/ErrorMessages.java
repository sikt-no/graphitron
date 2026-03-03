package no.sikt.graphitron.validation;

public enum ErrorMessages {
    MISSING_FIELD("Input type %s referencing table %s does not map all fields required by the database. Missing required fields: %s"),
    MISSING_NON_NULLABLE("Input type %s referencing table %s does not map all fields required by the database as non-nullable. Nullable required fields: %s"),
    MISSING_TABLE_ON_MULTITABLE("Type(s) '%s' are used in a query %s returning multitable interface or union '%s', but do not have tables set. This is not supported.");

    private final String msg;

    ErrorMessages(String msg) {
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public String format(Object ...input) {
        return String.format(msg, input);
    }
}
