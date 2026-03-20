package no.sikt.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import no.sikt.graphql.GraphitronContext;
import no.sikt.graphql.helpers.selection.ConnectionSelectionSet;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.dataloader.BatchLoaderEnvironment;
import org.jooq.DSLContext;
import org.jooq.Row;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Helper class for generated code that helps simplify the extraction of required data from a DataFetchingEnvironment.
 */
public class EnvironmentHandler {
    protected final DataFetchingEnvironment env;
    protected final Map<String, Object> localContext;
    protected final String executionPath;
    protected final DSLContext dslContext;
    protected final SelectionSet select, connectionSelect;
    protected final Set<String> arguments;
    protected final Map<String, Map<String, Row>> nextKeys;
    protected final String dataloaderName;

    public EnvironmentHandler(DataFetchingEnvironment env) {
        this.env = env;
        GraphitronContext graphitronContext = env.getGraphQlContext().get("graphitronContext");
        if (graphitronContext == null) {
            throw new IllegalStateException("A GraphitronContext must be registered in ExecutionInput.graphQLContext under the name \"graphitronContext\". Graphitron needs this to find the right DSLContext for queries.");
        }

        dslContext = graphitronContext.getDslContext(env);
        dataloaderName = graphitronContext.getDataLoaderName(env);

        arguments = flattenArgumentKeys(env.getArguments());
        select = new SelectionSet(getSelectionSetsFromEnvironment(env));
        select.setArgumentSet(flattenIndexedArgumentKeys(env.getArguments(), ""));
        connectionSelect = new ConnectionSelectionSet(getSelectionSetsFromEnvironment(env));
        executionPath = env.getExecutionStepInfo().getPath().toString();
        localContext = Map.of();
        nextKeys = Map.of();
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public DSLContext getCtx() {
        return dslContext;
    }

    public SelectionSet getSelect() {
        return select;
    }

    public Set<String> getArguments() {
        return arguments;
    }

    protected static List<DataFetchingFieldSelectionSet> getSelectionSetsFromEnvironment(BatchLoaderEnvironment loaderEnvironment) {
        return ((List<DataFetchingEnvironment>) (List<?>) loaderEnvironment.getKeyContextsList()).stream()
                .map(DataFetchingEnvironment::getSelectionSet)
                .toList();
    }

    private static DataFetchingFieldSelectionSet getSelectionSetsFromEnvironment(DataFetchingEnvironment env) {
        return env.getSelectionSet();
    }

    private static Set<String> flattenArgumentKeys(Map<String, Object> arguments) {
        return flattenArgumentKeys(arguments, "");
    }

    protected static Set<String> flattenArgumentKeys(Map<String, Object> arguments, String path) {
        var result = new HashSet<String>();
        for (var arg : arguments.entrySet()) {
            var key = arg.getKey();
            var value = arg.getValue();
            var nextPath = path.isEmpty() ? key : path + "/" + key;
            result.add(nextPath);

            if (value instanceof Map) {
                result.addAll(flattenArgumentKeys((Map<String, Object>) value, nextPath));
            }

            if (value instanceof List<?> listValue) {
                listValue.stream()
                        .filter(it -> it instanceof Map)
                        .forEach(it -> result.addAll(flattenArgumentKeys((Map<String, Object>) it, nextPath))
                        );
            }
        }

        return result;
    }

    /**
     * Returns the argument presence set for a specific item in a list input.
     * Uses the indexed argument set to extract per-item keys, with fallback
     * to the shared argument set for single-item inputs (wrapped in List.of()).
     *
     * @param path  The argument path prefix (e.g., "in")
     * @param index The zero-based index of the list item
     * @return A set of argument paths for the specific item, in the same format as getArguments()
     */
    public Set<String> getArgumentsForIndex(String path, int index) {
        return getArgumentsForIndex(select.getArgumentSet(), arguments, path, index);
    }

    protected static Set<String> getArgumentsForIndex(Set<String> indexedArgs, Set<String> sharedArgs, String path, int index) {
        var indexPrefix = path + "[" + index + "]";
        var pathHere = path.isEmpty() ? "" : path + "/";
        var result = indexedArgs.stream()
                .filter(arg -> arg.startsWith(indexPrefix + "/"))
                .map(arg -> pathHere + arg.substring(indexPrefix.length() + 1))
                .collect(Collectors.toSet());

        // Fallback for nested non-list inputs: when a nested mapper receives
        // an indexed path like "input[0]/address" and wraps it in List.of(),
        // there are no "input[0]/address[0]/..." entries. Match directly
        // against the indexed entries under "input[0]/address/".
        if (result.isEmpty() && path.contains("[")) {
            var directPrefix = path + "/";
            result = indexedArgs.stream()
                    .filter(arg -> arg.startsWith(directPrefix))
                    .collect(Collectors.toSet());
        }

        // Fallback for single-item case: when the input is not a list in the
        // GraphQL args (e.g., single-item overload wraps in List.of()), the
        // indexed set has no [index] entries. Fall back to the shared set.
        if (result.isEmpty()) {
            return sharedArgs;
        }
        return result;
    }

    protected static Set<String> flattenIndexedArgumentKeys(Map<String, Object> arguments, String path) {
        var result = new HashSet<String>();
        for (var arg : arguments.entrySet()) {
            var key = arg.getKey();
            var value = arg.getValue();
            var nextPath = path.isEmpty() ? key : path + "/" + key;
            result.add(nextPath);

            if (value instanceof Map) {
                result.addAll(flattenIndexedArgumentKeys((Map<String, Object>) value, nextPath));
            }

            if (value instanceof List<?> listValue) {
                var mapValues = listValue.stream().filter(it -> it instanceof Map).toList();
                IntStream
                        .range(0, mapValues.size())
                        .forEach(index -> result.addAll(flattenIndexedArgumentKeys((Map<String, Object>) mapValues.get(index), nextPath + "[" + index + "]")));
            }
        }

        return result;
    }

}
