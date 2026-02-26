package no.sikt.graphql.helpers.resolvers;

import graphql.ErrorType;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.exception.ValidationViolationGraphQLException;
import no.sikt.graphql.helpers.functions.DBCount;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.functions.DBQuery;
import no.sikt.graphql.helpers.functions.DBQueryRoot;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;

public class DataFetcherHelper extends AbstractFetcher {
    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     */
    public DataFetcherHelper(DataFetchingEnvironment env) {
        super(env);
    }

    /**
     * Load the data for a root resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<T> load(DBQueryRoot<T> dbFunction) {
        return CompletableFuture.completedFuture(dbFunction.callDBMethod(dslContext, select));
    }

    /**
     * Load the data for a root resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<ConnectionImpl<T>> loadPaginated(
            int pageSize,
            DBQueryRoot<List<Pair<String, T>>> dbFunction,
            DBCount<String> countFunction
    ) {
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        dbFunction.callDBMethod(dslContext, connectionSelect),
                        pageSize,
                        connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName()) ? (Integer) countFunction.callDBMethod(dslContext, Set.of()) : null
                )
        );
    }

    /**
     * Load the data for a resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param key Key for the queried element.
     * @return A resolver result.
     * @param <V> Type that the resolver fetches.
     */
    public <K, V> CompletableFuture<V> load(K key, DBQuery<K, V> dbFunction) {
        return getLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, set, dbFunction))
                .load(asKeyPath(key), env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @return A paginated resolver result.
     */
    public <K, V> CompletableFuture<ConnectionImpl<V>> loadPaginated(
            K key,
            int pageSize,
            DBQuery<K, List<Pair<String, V>>> dbFunction,
            DBCount<K> countFunction
    ) {
        return getConnectionLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, set, pageSize, dbFunction, countFunction))
                .load(asKeyPath(key), env);
    }

    /**
     * Load the data for a resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param key ID of the queried element.
     * @return A resolver result.
     * @param <V> Type that the resolver fetches.
     */
    public <K, V> CompletableFuture<List<V>> loadNonNullable(K key, DBQuery<K, List<V>> dbFunction) {
        return getLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, set, dbFunction))
                .load(asKeyPath(key), env)
                .thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    /**
     * Load the data for a resolver in a lookup format.
     * @param keys The lookup keys that are necessary for match rows in the result with the original resolver input.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result without pagination.
     * @param <K> The key type that this lookup uses.
     * @param <V> Type that the resolver fetches.
     */
    public <K, V> CompletableFuture<List<V>> loadLookup(List<List<K>> keys, DBQuery<String, V> dbFunction) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return loadByKeysOrdered(mergeKeys(keys, env), dbFunction);
    }

    public static <K> List<String> mergeKeys(List<List<K>> keys, DataFetchingEnvironment env) {
        if (keys.isEmpty()) {
            return List.of();
        }

        // This assumes all keys are correlated.
        var nKeys = keys.get(0).size();
        if (keys.stream().map(List::size).anyMatch(it -> it != nKeys)) {
            throw new ValidationViolationGraphQLException(
                    List.of(
                            GraphqlErrorBuilder
                                    .newError(env)
                                    .path(env.getExecutionStepInfo().getPath())
                                    .message("Keys sets have differing lengths. For this type of query, each key field is required to be an array of equal length.")
                                    .errorType(ErrorType.ValidationError)
                                    .build()
                    )
            );
        }

        var mergedKeys = new ArrayList<>(Collections.nCopies(nKeys, ""));
        for (var keyList : keys) {
            for (int i = 0; i < nKeys; i++) {
                var currentKey = mergedKeys.get(i);
                mergedKeys.set(i, (currentKey.isEmpty() ? "" : currentKey + ",") + keyList.get(i));
            }
        }

        return mergedKeys;
    }

    public <K, V> CompletableFuture<List<V>> loadByResolverKeys(List<K> keys, DBQuery<K, V> dbFunction) {
        keys = keys == null ? List.of() : keys.stream().filter(Objects::nonNull).toList();
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return loadByKeysOrdered(keys, dbFunction);
    }

    private <K, V> CompletableFuture<List<V>> loadByKeysOrdered(List<K> keys, DBQuery<K, V> dbFunction) {
        var dbResult = dbFunction.callDBMethod(dslContext, new HashSet<>(keys), select);
        var orderedResult = keys.stream().map(dbResult::get).toList();
        return CompletableFuture.completedFuture(orderedResult);
    }

    public <V> CompletableFuture<List<V>> loadLookupEntities(
            List<?> inputList,
            Map<String, DBQuery<Map<String, Object>, ? extends V>> typeNameToDBQueryMap
    ) {
        return loadLookupEntities(null, inputList, typeNameToDBQueryMap);
    }

    public <V> CompletableFuture<List<V>> loadLookupEntities(
            NodeIdStrategy nodeIdStrategy,
            List<?> inputList,
            Map<String, DBQuery<Map<String, Object>, ? extends V>> typeNameToDBQueryMap
    ) {
        List<Map<String, Object>> representations = inputList.stream().findAny().map(it -> it instanceof Map).orElse(false)
        ? (List<Map<String, Object>>) inputList : (List<Map<String, Object>>) inputList.get(0);

        return loadLookupMultipleSources(
                representations,
                it -> (String) it.get("__typename"),
                typeNameToDBQueryMap,
                (representation, entities) -> getEntityForRepresentation(representation, entities, nodeIdStrategy)
        );
    }

    static <V> V getEntityForRepresentation(Map<String, Object> representation, Map<Map<String, Object>, V> entities, NodeIdStrategy nodeIdStrategy) {
        return entities.entrySet().stream()
                .filter(entity -> representation.entrySet().stream()
                        .allMatch(r -> representationKeyMatchesEntity(r, entity, nodeIdStrategy)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static <V> boolean representationKeyMatchesEntity(Map.Entry<String, Object> representationKey, Map.Entry<Map<String, Object>, V> entity, NodeIdStrategy nodeIdStrategy) {
        Map<String, Object> representationForEntity = entity.getKey();
        if (!representationForEntity.containsKey(representationKey.getKey())) {
            return false;
        }

        var valueForEntity = representationForEntity.get(representationKey.getKey());
        if (valueForEntity instanceof String entityValString && representationKey.getValue() instanceof String repValString) {
            if (entityValString.equals(representationKey.getValue())) {
                return true;
            }
            return Optional.ofNullable(nodeIdStrategy)
                    .map(n -> n.areEqualNodeIds(entityValString, repValString))
                    .orElse(false);
        }
        return valueForEntity.toString().equals(representationKey.getValue().toString());
    }

    /**
     * Load data from multiple DB sources, returning results in input order.
     *
     * @param inputList The ordered list of input elements. Determines the order of the returned results.
     * @param sourceResolver Function that maps each input element to a source identifier used to select the DB query.
     * @param sourceToDBQueryMap Map from source identifier to the DB function that fetches elements for that source.
     * @param inputToResultLookup Function that matches an input key to a value in the results map.
     * @return A resolver result with elements ordered to match {@code inputList}. Elements not found will be {@code null}.
     */
    public <K, V> CompletableFuture<List<V>> loadLookupMultipleSources(
            List<K> inputList,
            Function<K, String> sourceResolver,
            Map<String, DBQuery<K, ? extends V>> sourceToDBQueryMap,
            BiFunction<K, Map<K, V>, V> inputToResultLookup
    ) {
        if (inputList.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        Map<K, V> allResults = new HashMap<>();
        inputList.stream()
                .collect(Collectors.groupingBy(sourceResolver, Collectors.toSet()))
                .forEach((dbQueryKey, inputSet) -> {
                    if (dbQueryKey == null) {
                        throw new IllegalArgumentException("Source resolver returned null for an input element.");
                    }
                    var query = Optional.ofNullable(sourceToDBQueryMap.getOrDefault(dbQueryKey, null))
                            .orElseThrow(() -> new IllegalArgumentException("Could not resolve query for key " + dbQueryKey));

                    allResults.putAll(query.callDBMethod(dslContext, inputSet, select));
                });
        return CompletableFuture.completedFuture(inputList.stream().map(it -> inputToResultLookup.apply(it, allResults)).toList());
    }

    /**
     * Load the data for an interface resolver.
     * @param loaderName The source from which the data should be loaded.
     * @param key The specific key that should be loaded.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result without pagination.
     * @param <V1> Type that the resolver fetches.
     */
    public <K, V1, V0 extends V1> CompletableFuture<V1> loadInterface(String loaderName, K key, DBQuery<K, V0> dbFunction) {
        return env
                .getDataLoaderRegistry()
                .<K, V1>computeIfAbsent(loaderName, name ->
                        DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<K, V0>) (keys, loaderEnvironment) ->
                                CompletableFuture.completedFuture(dbFunction.callDBMethod(dslContext, keys, new SelectionSet(getSelectionSetsFromEnvironment(loaderEnvironment))))
                        )
                )
                .load(key, env);
    }

    /**
     * Load the data for a delete resolver.
     * @param dbFunction Function to call to retrieve the IDs.
     * @param idFilteringFunction Function to call to filter out undeleted IDs.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<U> loadDelete(DBQueryRoot<T> dbFunction, Function<T, U> idFilteringFunction) {
        return CompletableFuture.completedFuture(idFilteringFunction.apply(dbFunction.callDBMethod(dslContext, select)));
    }

    /**
     * Load the data for a resolver returning wrapped data.
     * @param dbFunction Function to call to retrieve the data.
     * @param wrappingFunction Function to call to wrap the data.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<U> loadWrapped(DBQueryRoot<T> dbFunction, Function<T, U> wrappingFunction) {
        return CompletableFuture.completedFuture(wrappingFunction.apply(dbFunction.callDBMethod(dslContext, select)));
    }

    private <K, V> CompletableFuture<Map<KeyWithPath<K>, ConnectionImpl<V>>> getMappedDataLoader(
            Set<KeyWithPath<K>> keys,
            SelectionSet selectionSet,
            int pageSize,
            DBQuery<K, List<Pair<String, V>>> dbFunction,
            DBCount<K> countFunction
    ) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var idSet = keys.stream().map(KeyWithPath::key).collect(Collectors.toSet());
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        resultAsMap(keys, dbFunction.callDBMethod(dslContext, idSet, selectionSet)),
                        pageSize,
                        connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.callDBMethod(dslContext, idSet) : null
                )
        );
    }

    private <K, V> CompletableFuture<Map<KeyWithPath<K>, V>> getMappedDataLoader(Set<KeyWithPath<K>> keys, SelectionSet selectionSet, DBQuery<K, V> dbFunction) {
        keys = keys.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var idSet = keys.stream().map(KeyWithPath::key).collect(Collectors.toSet());
        return CompletableFuture.completedFuture(resultAsMap(keys, dbFunction.callDBMethod(dslContext, idSet, selectionSet)));
    }
}
