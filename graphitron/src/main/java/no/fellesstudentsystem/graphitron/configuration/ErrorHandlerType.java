package no.fellesstudentsystem.graphitron.configuration;

import static org.apache.commons.lang3.StringUtils.capitalize;

public enum ErrorHandlerType {
    DATABASE,
    GENERIC;

    public String toCamelCaseString() {
        return capitalize(this.toString().toLowerCase());
    }
}
