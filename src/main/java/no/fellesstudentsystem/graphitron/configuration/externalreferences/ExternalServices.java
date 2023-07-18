package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import java.util.Map;

public class ExternalServices extends ExternalReference<Class<?>> {
    private final static String EXPECTED_METHOD = "getServiceType";

    public ExternalServices(String path) {
        super(path);
    }

    public ExternalServices(Map<String, Class<?>> options) {
        super(options);
    }

    @Override
    protected String getExpectedMethodName() {
        return EXPECTED_METHOD;
    }
}
