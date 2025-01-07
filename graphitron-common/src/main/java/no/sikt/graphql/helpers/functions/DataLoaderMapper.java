package no.sikt.graphql.helpers.functions;

import no.sikt.graphql.helpers.selection.SelectionSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface DataLoaderMapper<K, V> {
    CompletableFuture<Map<K, V>> map(Set<K> keys, SelectionSet set);
}
