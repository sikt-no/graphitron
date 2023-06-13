package no.fellesstudentsystem.graphql.helpers.selection;

import graphql.schema.DataFetchingFieldSelectionSet;

import java.util.List;

public class ConnectionSelectionSets extends SelectionSets {
    private final static String CONNECTION_NODE_GLOB_PATTERN_PREFIX = "{edges/node/,nodes/,}";

    public ConnectionSelectionSets(List<DataFetchingFieldSelectionSet> selectionSets) {
        super(selectionSets);
    }

    public ConnectionSelectionSets(DataFetchingFieldSelectionSet selectionSet) {
        super(selectionSet);
    }

    public boolean contains(String path) {
        return selectionSets.stream().anyMatch(it -> it.contains(CONNECTION_NODE_GLOB_PATTERN_PREFIX + path));
    }
}
