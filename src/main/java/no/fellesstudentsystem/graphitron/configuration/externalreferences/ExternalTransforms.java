package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import java.lang.reflect.Method;
import java.util.Map;

public class ExternalTransforms extends ExternalReference<Method> {
    private final static String EXPECTED_METHOD = "getMethod";

    public ExternalTransforms(String path) {
        super(path);
    }

    public ExternalTransforms(Map<String, Method> options) {
        super(options);
    }

    @Override
    protected String getExpectedMethodName() {
        return EXPECTED_METHOD;
    }
}
