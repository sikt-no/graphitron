package no.sikt.graphitron.rewrite.model;

public enum ErrorHandlerType {
    DATABASE,
    GENERIC,
    VALIDATION;

    public String toCamelCaseString() {
        var lower = this.toString().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
