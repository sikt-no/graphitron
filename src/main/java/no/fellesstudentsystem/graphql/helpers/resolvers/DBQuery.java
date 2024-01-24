package no.fellesstudentsystem.graphql.helpers.resolvers;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface DBQuery<T> {
    Map<String, T> callDBMethod(Set<String> idSet, SelectionSet selectionSet);
}
