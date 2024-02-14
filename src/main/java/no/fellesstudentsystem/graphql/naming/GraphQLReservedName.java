package no.fellesstudentsystem.graphql.naming;

/**
 * Enum of various names or substrings that have special meanings in the schema.
 * These should always be used rather than specifying them as Strings in various places throughout the code.
 */
public enum GraphQLReservedName {
    PAGINATION_FIRST("first"),
    PAGINATION_AFTER("after"),
    PAGINATION_LAST("last"),
    PAGINATION_BEFORE("before"),

    CONNECTION_NODE_FIELD("node"),
    CONNECTION_NODES_FIELD("nodes"),
    CONNECTION_CURSOR_FIELD("cursor"),
    CONNECTION_EDGE_FIELD("edges"),
    CONNECTION_PAGE_INFO_NODE("PageInfo"),

    ORDER_BY_FIELD("orderByField"),

    NODE_TYPE("Node"),
    NODE_ID("id"),
    ERROR_TYPE("Error"),
    SCHEMA_CONNECTION_SUFFIX("Connection"),
    SCHEMA_ROOT_NODE_QUERY("Query"),
    SCHEMA_ROOT_NODE_MUTATION("Mutation");

    private final String name;

    GraphQLReservedName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
