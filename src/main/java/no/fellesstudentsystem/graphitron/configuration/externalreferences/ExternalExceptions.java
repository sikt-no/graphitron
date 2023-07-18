package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import java.util.Map;

public class ExternalExceptions extends ExternalReference<Class<?>> {
    private final static String EXPECTED_METHOD = "getExceptionType";

    public ExternalExceptions(String path) {
        super(path);
    }

    public ExternalExceptions(Map<String, Class<?>> options) {
        super(options);
    }

    @Override
    protected String getExpectedMethodName() {
        return EXPECTED_METHOD;
    }
}
