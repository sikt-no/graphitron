package no.sikt.graphql.directives;

/**
 * Contains all the available directive parameters.
 * This must match what is defined in the Graphitron directive schema.
 */
public enum GenerationDirectiveParam {
    KEY("key"),
    NAME("name"),
    JAVA_NAME("javaName"),
    TABLE("table"),
    CONDITION("condition"),
    OVERRIDE("override"),
    REFERENCES("references"),
    TYPE("typeName"),
    ENUM("enumReference"),
    SERVICE("service"),
    ERROR("error"),
    CLASSNAME("className"),
    METHOD("method"),

    RECORD("record"),
    HANDLERS("handlers"),
    HANDLER("handler"),
    CLASS_NAME("className"),
    CODE("code"),
    MATCHES("matches"),
    DESCRIPTION("description"),
    ON("on"),
    VALUE("value");

    private final String name;

    GenerationDirectiveParam(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
