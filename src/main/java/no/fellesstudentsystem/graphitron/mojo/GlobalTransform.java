package no.fellesstudentsystem.graphitron.mojo;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;

/**
 * A transform that is applied to all records, or on all records in a subset of generated mutations.
 */
public class GlobalTransform {
    private TransformScope scope;
    private String name;

    public GlobalTransform() {}

    public GlobalTransform(String name, TransformScope scope) {
        this.scope = scope;
        this.name = name;
    }

    public TransformScope getScope() {
        return scope;
    }

    public String getName() {
        return name;
    }
}
