package no.sikt.graphql.naming;

import static graphql.relay.Relay.NODE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Enum of various names or substrings that have special meanings in the schema.
 * These should always be used rather than specifying them as Strings in various places throughout the code.
 */
public enum GraphQLReservedName {
    TYPE_NAME("__typename"),
    PAGINATION_FIRST("first"),
    PAGINATION_AFTER("after"),
    PAGINATION_LAST("last"),
    PAGINATION_BEFORE("before"),

    CONNECTION_NODE_FIELD("node"),
    CONNECTION_NODES_FIELD("nodes"),
    CONNECTION_CURSOR_FIELD("cursor"),
    CONNECTION_EDGE_FIELD("edges"),
    CONNECTION_PAGE_INFO_NODE("PageInfo"),

    CONNECTION_TOTAL_COUNT("totalCount"),

    ORDER_BY_FIELD("orderByField"),

    NODE_TYPE(NODE),
    NODE_ID("id"),
    ERROR_TYPE("Error"),
    ERROR_FIELD("Errors"),
    SCHEMA_CONNECTION_SUFFIX("Connection"),
    SCHEMA_QUERY("Query"),
    SCHEMA_MUTATION("Mutation"),
    SCHEMA("schema"),
    OPERATION_QUERY(uncapitalize(SCHEMA_QUERY.getName())),
    OPERATION_MUTATION(uncapitalize(SCHEMA_MUTATION.getName())),

    FEDERATION_KEY("key"),
    FEDERATION_KEY_ARGUMENT("fields"),
    FEDERATION_ENTITIES_FIELD("_entities"),
    FEDERATION_REPRESENTATIONS_ARGUMENT("representations");

    private final String name;

    GraphQLReservedName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
