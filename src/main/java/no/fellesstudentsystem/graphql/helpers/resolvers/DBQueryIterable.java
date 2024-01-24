package no.fellesstudentsystem.graphql.helpers.resolvers;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.List;
import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface DBQueryIterable<T> {
    Map<String, List<T>> callDBMethod(Set<String> idSet, SelectionSet selectionSet);
}
