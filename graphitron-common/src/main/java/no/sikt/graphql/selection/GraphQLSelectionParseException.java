package no.sikt.graphql.selection;

/**
 * Thrown when the input string cannot be parsed as a (possibly extended) GraphQL selection set.
 */
public class GraphQLSelectionParseException extends RuntimeException {
    public GraphQLSelectionParseException(String message) {
        super(message);
    }
}
