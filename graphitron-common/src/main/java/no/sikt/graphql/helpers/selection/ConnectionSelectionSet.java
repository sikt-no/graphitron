package no.sikt.graphql.helpers.selection;

import graphql.schema.DataFetchingFieldSelectionSet;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A helper class for generated code. Helps with checking the selection set from the {@link graphql.schema.DataFetchingEnvironment}
 * for fields used in queries that use the connection types that follow the
 * <a href="https://relay.dev/graphql/connections.htm">cursor connection specification</a>.
 */
public class ConnectionSelectionSet extends SelectionSet {
    private final static String CONNECTION_NODE_REGEX_PATTERN_PREFIX = "^(edges/node/|nodes/|)";

    public ConnectionSelectionSet(List<DataFetchingFieldSelectionSet> selectionSets) {
        super(selectionSets);
    }

    public ConnectionSelectionSet(DataFetchingFieldSelectionSet selectionSet) {
        super(selectionSet);
    }

    public boolean contains(String path) {
        var pattern = Pattern.compile(CONNECTION_NODE_REGEX_PATTERN_PREFIX + path);
        return selectionSet.stream().anyMatch(it -> pattern.matcher(it).matches());
    }
}
