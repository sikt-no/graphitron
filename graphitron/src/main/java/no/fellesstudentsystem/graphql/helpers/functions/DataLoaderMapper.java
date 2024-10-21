package no.fellesstudentsystem.graphql.helpers.functions;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface DataLoaderMapper<T> {
    CompletableFuture<Map<String, T>> map(Set<String> keys, SelectionSet set);
}
