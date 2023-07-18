package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import java.lang.reflect.Method;
import java.util.Map;

public class ExternalConditions extends ExternalReference<Method> {
    private final static String EXPECTED_METHOD = "getConditionType";

    public ExternalConditions(String path) {
        super(path);
    }

    public ExternalConditions(Map<String, Method> options) {
        super(options);
    }

    @Override
    protected String getExpectedMethodName() {
        return EXPECTED_METHOD;
    }
}
