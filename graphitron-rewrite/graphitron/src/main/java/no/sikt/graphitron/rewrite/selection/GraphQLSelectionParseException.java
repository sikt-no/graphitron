package no.sikt.graphitron.rewrite.selection;

/**
 * Thrown when the input string cannot be parsed as a (possibly extended) GraphQL selection set.
 */
public class GraphQLSelectionParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GraphQLSelectionParseException(String message) {
        super(message);
    }
}
