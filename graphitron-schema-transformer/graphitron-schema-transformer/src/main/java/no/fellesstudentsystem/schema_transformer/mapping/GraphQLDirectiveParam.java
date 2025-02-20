package no.fellesstudentsystem.schema_transformer.mapping;

/**
 * Contains all the currently used directive parameters.
 * It is expected that this enum matches what is found in the schema.
 */
public enum GraphQLDirectiveParam {
    FOR("for"),
    FLAGS("flags");

    private final String name;

    GraphQLDirectiveParam(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
