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
    PATH("path"),
    TYPE("typeName"),
    ENUM("enumReference"),
    SERVICE("service"),
    TABLE_METHOD_REFERENCE("tableMethodReference"),
    ROUTES("routes"),
    ERROR("error"),
    METHOD("method"),

    RECORD("record"),
    HANDLERS("handlers"),
    HANDLER("handler"),
    CLASS_NAME("className"),
    CONTEXT_ARGUMENTS("contextArguments"),
    CODE("code"),
    SQL_STATE("sqlState"),
    MATCHES("matches"),
    DESCRIPTION("description"),
    ON("on"),
    VALUE("value"),
    TYPE_ID("typeId"),
    KEY_COLUMNS("keyColumns"),
    TYPE_NAME("typeName"),
    INDEX("index"),
    FIELDS("fields"),
    PRIMARY_KEY("primaryKey"),
    COLLATE("collate"),
    DIRECTION("direction"),
    SELECTION("selection"),
    PROCEDURE("procedure"),
    ARGUMENTS("arguments");

    private final String name;

    GenerationDirectiveParam(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
