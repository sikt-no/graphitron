package no.fellesstudentsystem.graphql.directives;

/**
 * Contains all the available directive parameters.
 * This must match what is defined in the Graphitron directive schema.
 */
public enum GenerationDirectiveParam {
    KEY("key"),
    NAME("name"),
    TABLE("table"),
    CONDITION("condition"),
    OVERRIDE("override"),
    VIA("via"),
    TYPE("typeName");

    private final String name;

    GenerationDirectiveParam(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
