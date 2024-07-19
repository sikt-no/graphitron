package no.fellesstudentsystem.graphitron.configuration.externalreferences;

/**
 * A transform that is applied to all records, or on all records in a subset of generated mutations.
 */
public class GlobalTransform {
    private TransformScope scope;
    private String fullyQualifiedClassName, method;

    public GlobalTransform() {}

    public GlobalTransform(String fullyQualifiedClassName, String method, TransformScope scope) {
        this.scope = scope;
        this.fullyQualifiedClassName = fullyQualifiedClassName;
        this.method = method;
    }

    public TransformScope getScope() {
        return scope;
    }

    public String getFullyQualifiedClassName() {
        return fullyQualifiedClassName;
    }

    public String getMethod() {
        return method;
    }
}
