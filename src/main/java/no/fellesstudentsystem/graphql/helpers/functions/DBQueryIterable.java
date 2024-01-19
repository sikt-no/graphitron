package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.List;
import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface DBQueryIterable<K, V> {
    Map<K, List<V>> callDBMethod(Set<K> keys, SelectionSet selectionSet);
}
