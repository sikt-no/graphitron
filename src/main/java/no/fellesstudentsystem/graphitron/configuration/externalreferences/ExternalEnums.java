package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import java.util.Map;

public class ExternalEnums extends ExternalReference<Class<?>> {
    private final static String EXPECTED_METHOD = "getEnumType";

    public ExternalEnums(String path) {
        super(path);
    }

    public ExternalEnums(Map<String, Class<?>> options) {
        super(options);
    }

    @Override
    protected String getExpectedMethodName() {
        return EXPECTED_METHOD;
    }
}
