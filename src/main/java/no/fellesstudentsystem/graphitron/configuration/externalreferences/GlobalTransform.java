package no.fellesstudentsystem.graphitron.configuration.externalreferences;

/**
 * A transform that is applied to all records, or on all records in a subset of generated mutations.
 */
public class GlobalTransform {
    private TransformScope scope;
    private String name, method;

    public GlobalTransform() {}

    public GlobalTransform(String name, String method, TransformScope scope) {
        this.scope = scope;
        this.name = name;
        this.method = method;
    }

    public TransformScope getScope() {
        return scope;
    }

    public String getName() {
        return name;
    }

    public String getMethod() {
        return method;
    }
}
