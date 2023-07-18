package no.fellesstudentsystem.graphql.directives;

/**
 * Contains all the currently used directive parameters.
 * It is expected that this enum matches what is found in the schema.
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
