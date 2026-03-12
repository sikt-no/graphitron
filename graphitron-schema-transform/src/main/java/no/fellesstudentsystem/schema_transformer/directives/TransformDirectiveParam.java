package no.fellesstudentsystem.schema_transformer.directives;

/**
 * Contains all the currently used directive parameters.
 * It is expected that this enum matches what is found in the schema.
 */
public enum TransformDirectiveParam {
    FOR("for"),
    CONNECTION_NAME("connectionName"),
    FIRST_DEFAULT("defaultFirstValue"),
    FLAGS("flags");

    private final String name;

    TransformDirectiveParam(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
